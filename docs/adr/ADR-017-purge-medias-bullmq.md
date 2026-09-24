# ADR-017 — Purge automatique des Snaps expirés : job BullMQ, exception SnapCore+

## Statut
Proposé

## Contexte
ADR-013 (story) et le principe d'éphémérité (ADR-003) supposent un job BullMQ de purge des médias expirés, dont le fonctionnement exact — programmation, reprogrammation à l'ouverture, exception SnapCore+, résilience face à un redémarrage ou une perte de jobs — doit être précisé une fois pour toutes ici.

## Décision
**Pour un `MESSAGE` de type `TEXT` :** aucune purge, `content` est permanent (ADR-009). Aucun job n'est programmé pour ce type de message.

**Pour un `MESSAGE` de type `SNAP_PHOTO` / `SNAP_PHOTO_VIDEO` :**
- À la création, `scheduledPurgeAt` est fixé à `createdAt + 30 jours` et un job BullMQ est programmé à cette échéance.
- À l'ouverture, si `openedAt + 24h` est antérieur au `scheduledPurgeAt` déjà programmé, le job est reprogrammé à cette date plus proche — via `job.changeDelay(...)` sur le job existant (identifié de façon déterministe, voir ci-dessous), qui déplace son échéance sans le supprimer ni en recréer un autre. Le job est alors en attente (pas en cours d'exécution), ce qui rend cet appel direct possible.
- **Ce délai de 24h est un choix délibéré de simplification, pas une fidélité au vrai Snapchat** : dans l'app originale, un Snap privé ouvert disparaît presque immédiatement (quelques secondes), et ce sont les Chats qui restent disponibles plus longtemps. SnapCore retient 24h uniformément pour rester simple à implémenter et à tester, au prix de cette fidélité précise.
- **À l'exécution du job**, le backend vérifie l'abonnement du **destinataire** au moment même de l'exécution — cette vérification est l'unique point d'entrée qui décide de purger ou non, que le job vienne de son échéance normale ou d'une reprogrammation par le balayage de rattrapage (voir ci-dessous : il n'existe qu'un seul chemin de purge) :
  - Si `subscriptionTier = premium` et `premiumUntil + 24h` (marge de sécurité, voir ci-dessous) est dans le futur : la purge est annulée et **`scheduledPurgeAt` est mis à jour à `premiumUntil + 24h`**. Cette fois, le job **est en train de s'exécuter** (contrairement au cas précédent) : on ne peut ni le supprimer ni en ajouter un autre sous le même identifiant pendant qu'il tourne (BullMQ l'ignorerait silencieusement). La reprogrammation se fait donc en appelant `job.moveToDelayed(nouvelleÉchéance, token)` puis en levant une `DelayedError` pour signaler à BullMQ que ce traitement se termine ici et reprendra plus tard — pas via une suppression/réajout comme pour une reprogrammation à l'ouverture. Cette mise à jour de `scheduledPurgeAt` a lieu à **chaque** reprogrammation, sans exception : c'est ce qui empêche le balayage de rattrapage de retrouver indéfiniment ce même Snap comme "en retard".
  - Sinon : `mediaKey` est vidé et le fichier supprimé de R2 (ADR-016).
  - **Marge de sécurité sur `premiumUntil`** : une marge de 24h est ajoutée avant de considérer un abonnement comme réellement expiré, pour absorber le délai possible entre l'expiration théorique et la réception du webhook de renouvellement RevenueCat, qui peut être livré avec un léger retard (ADR-007) — sans cette marge, un renouvellement traité en retard purgerait à tort un Snap que l'utilisateur vient de payer pour garder.
- **La ligne `MESSAGE` n'est jamais supprimée**, purgée ou non.

**Pour un `STORY_ITEM` :** inchangé — purge du média R2 et de la ligne PostgreSQL à `expiresAt` (`createdAt + 24h`), sans notion d'ouverture ni d'exception d'abonnement.

**Résilience de la file de jobs :**
- Le Redis utilisé par BullMQ doit avoir la **persistance AOF activée** (`appendonly yes`), pour survivre à un redémarrage sans perdre les jobs programmés.
- **Le Redis de BullMQ doit avoir `maxmemory-policy` réglé sur `noeviction`** (exigence documentée de BullMQ elle-même). Ce Redis est partagé avec la présence temps réel (ADR-018), les vues de stories (ADR-013) et le pub/sub Socket.io (ADR-006) : sous pression mémoire, une politique d'éviction (LRU ou autre) pourrait supprimer silencieusement des jobs BullMQ programmés pour libérer de la place — l'AOF ne protège que contre un redémarrage, pas contre une éviction en mémoire pendant que Redis tourne. `noeviction` fait échouer les écritures plutôt que d'évincer des clés quand la mémoire est pleine, ce qui est le comportement voulu ici : mieux vaut une erreur visible côté application qu'une purge programmée qui disparaît sans bruit. Si le volume de présence/vues/pub-sub venait à saturer la mémoire de cette instance, une instance Redis séparée dédiée à la file de jobs serait la solution de repli.
- **Identifiant de job déterministe** : chaque job de purge utilise un identifiant BullMQ déterministe (`purge:message:<messageId>` ou `purge:story:<storyItemId>`), jamais un identifiant généré aléatoirement. Ajouter un job dont l'identifiant existe déjà dans la file est silencieusement ignoré par BullMQ (aucune erreur) — y compris si le job existant est déjà terminé ou en échec mais toujours conservé dans Redis. Deux conséquences à traiter explicitement, pas seulement "réajouter sous le même identifiant" :
  - **Reprogrammation à l'ouverture** (job en attente) : `job.changeDelay(...)` sur le job existant, comme précisé ci-dessus — pas de suppression/réajout.
  - **Reprogrammation depuis l'intérieur du job lui-même** (branche abonnement actif ci-dessus, le job est en cours d'exécution) : `job.moveToDelayed(ts, token)` puis `DelayedError`, jamais une suppression/réajout (impossible pendant l'exécution).
  - **Jobs terminés conservés par BullMQ** : la configuration active `removeOnComplete: true`, pour qu'un job de purge réussi soit retiré de Redis plutôt que conservé indéfiniment. Sans cela, le balayage de rattrapage ci-dessous tenterait de réajouter un job sous un identifiant déjà occupé par une exécution terminée, et l'ajout serait ignoré sans effet — rendant le filet de rattrapage inopérant précisément dans le cas où il devrait agir. Une politique de rétention explicite est également fixée pour les jobs en échec (`removeOnFail` avec un nombre borné, pour garder un historique de diagnostic sans polluer indéfiniment la file).
  Les noms d'API ci-dessus (`changeDelay`, `moveToDelayed`, `DelayedError`, `removeOnComplete`) sont ceux de BullMQ à la date de cette révision ; à revérifier dans la documentation de la version effectivement installée avant implémentation, le principe (jamais de suppression/réajout naïf sur un identifiant déterministe) restant valable indépendamment de la version.
- **Filet de rattrapage** : un balayage périodique (ex. toutes les heures) recherche les `Message`/`StoryItem` dont `scheduledPurgeAt`/`expiresAt` est dépassé mais dont le média n'a pas encore été purgé. **Ce balayage ne supprime jamais rien lui-même** : pour chaque ligne trouvée, il remet en file (sous l'identifiant déterministe ci-dessus, donc sans jamais créer de doublon) un job de purge identique à celui de l'échéance normale, qui repasse par l'unique logique décrite ci-dessus (vérification d'abonnement du destinataire à l'exécution). Cela garantit que le balayage de rattrapage ne peut jamais purger directement un Snap conservé par abonnement : soit `scheduledPurgeAt` a bien été tenu à jour à chaque reprogrammation (voir ci-dessus) et le balayage ne retrouve jamais ce Snap en retard, soit il le retrouve par anomalie (job perdu malgré l'AOF) et le job rejoué revérifie l'abonnement avant d'agir, comme n'importe quel autre job. C'est ce balayage qui utilise réellement l'index `@@index([scheduledPurgeAt])` sur `Message` (dans un modèle "un job programmé par message", cet index ne servirait à rien d'autre) : il couvre le cas où le job BullMQ correspondant aurait été perdu malgré la persistance AOF (fenêtre entre deux fsync, par exemple).

## Alternatives
- Se fier uniquement à la persistance AOF, sans balayage de rattrapage : plus simple, mais AOF garantit une perte bornée (quelques secondes de données au pire selon la configuration de fsync), pas une garantie absolue — le balayage referme cette fenêtre résiduelle pour un coût de requête négligeable (un scan indexé peu fréquent).
- Tâche cron périodique remplaçant entièrement BullMQ (pas de jobs programmés du tout, seulement des scans réguliers) : écartée, moins précise dans le cas nominal (délai jusqu'au prochain scan) ; le choix retenu combine la précision de BullMQ et la robustesse d'un filet de rattrapage peu coûteux.

## Conséquences
### Positives
- Une purge programmée survit à un redémarrage Redis normal (AOF) et à une pression mémoire (`noeviction`), et le balayage de rattrapage couvre le résidu de risque.
- Un renouvellement d'abonnement traité en retard ne purge plus un Snap à tort, grâce à la marge de 24h.
- Le balayage de rattrapage ne peut structurellement pas purger un Snap conservé par abonnement, puisqu'il ne fait que rejouer la même vérification qu'un job normal plutôt que de supprimer directement.
- Les identifiants de job déterministes, combinés à `changeDelay`/`moveToDelayed`/`removeOnComplete`, empêchent tout doublon entre reprogrammation normale et balayage de rattrapage, et garantissent que ce dernier peut effectivement retrouver et réajouter un job terminé si besoin.
- L'ADR ne prétend plus une fidélité à Snapchat qu'il n'a pas sur le délai de 24h.

### Négatives
- La persistance AOF a un coût d'I/O sur l'instance Redis, marginal vu le volume attendu du projet.
- `noeviction` signifie qu'un Redis saturé en mémoire refuse des écritures plutôt que d'en perdre silencieusement — préférable ici, mais cela déplace le problème vers une erreur applicative à surveiller (alerte sur la mémoire Redis) plutôt que de le masquer.
- Le balayage de rattrapage ajoute une tâche planifiée de plus à surveiller, même si elle ne doit normalement jamais rien trouver à faire.
- Les API BullMQ utilisées pour reprogrammer un job selon son état (en attente vs en cours d'exécution) sont plus subtiles qu'un simple "supprimer puis réajouter" — à documenter clairement dans le code pour l'équipe, et à revérifier à chaque montée de version de BullMQ.

## Historique
- Version initiale : job programmé à un `expiresAt` connu dès la création — incohérent avec le fonctionnement réel d'un Snap.
- Révision 1 : introduction de `scheduledPurgeAt` recalculé à l'ouverture, purge qui vide le contenu sans supprimer la ligne.
- Révision 2 : purge limitée aux Snaps (jamais le texte), exception d'abonnement SnapCore+ vérifiée à l'exécution.
- Révision 3 (revue technique) : exigence de persistance Redis (AOF) + balayage de rattrapage, marge de 24h sur `premiumUntil`, retrait de l'affirmation de fidélité à Snapchat sur le délai de 24h (celui-ci est en réalité inspiré des Chats, pas des Snaps).
- Révision 4 (revue technique) : explicite que `scheduledPurgeAt` est mis à jour à chaque reprogrammation (pas seulement mentionné) et que le balayage de rattrapage ne fait jamais que remettre en file le job normal (jamais de suppression directe), pour éliminer tout risque de purger un Snap conservé par abonnement ; ajout de l'exigence `maxmemory-policy noeviction` sur le Redis de BullMQ, partagé avec la présence et les vues de stories.
- Révision 5 (revue technique) : ajout d'identifiants de job BullMQ déterministes (`purge:message:<id>`, `purge:story:<id>`) pour que la reprogrammation et le balayage de rattrapage ne créent jamais de jobs doublons pour un même Snap ; section Contexte allégée (l'historique des revues successives est désormais uniquement dans cette section).
- Révision 6 (revue technique) : correction des mécanismes de reprogrammation — `changeDelay` pour un job en attente (ouverture), `moveToDelayed`/`DelayedError` pour un job en cours d'exécution (vérification d'abonnement), `removeOnComplete: true` pour que le balayage de rattrapage puisse effectivement réajouter un job dont l'identifiant serait sinon occupé par une exécution terminée conservée dans Redis. Un simple "supprimer puis réajouter sous le même identifiant" ne fonctionne pas dans tous les cas avec BullMQ.

## Liens
- ADR liés : ADR-003, ADR-006, ADR-007, ADR-009, ADR-013, ADR-016, ADR-018

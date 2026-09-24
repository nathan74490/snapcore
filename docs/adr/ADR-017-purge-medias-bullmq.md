# ADR-017 — Purge automatique des Snaps expirés : job BullMQ, exception SnapCore+

## Statut
Proposé

## Contexte
ADR-013 (story) et le principe d'éphémérité (ADR-003) supposaient un job BullMQ de purge sans jamais préciser son fonctionnement exact. Une révision a introduit `scheduledPurgeAt` (recalculé à l'ouverture) et l'exception SnapCore+, mais laissait deux problèmes : (1) BullMQ s'appuie sur Redis, et ADR-003 reconnaît lui-même que Redis peut perdre son contenu au redémarrage sans persistance configurée — sans filet de rattrapage, un redémarrage Redis mal configuré ferait disparaître toutes les purges programmées, et les Snaps ne seraient alors plus jamais supprimés ; (2) l'ADR présentait le délai de 24h après ouverture comme fidèle au vrai Snapchat, alors qu'en réalité un Snap privé ouvert y est supprimé quasi immédiatement — les 24h sont plutôt inspirées du fonctionnement des Chats. Cette révision corrige les deux et ajoute une marge de sécurité pour l'exception d'abonnement (point soulevé en parallèle, voir ADR-007). Une revue suivante a signalé un risque supplémentaire : le balayage de rattrapage introduit ci-dessous pourrait, s'il n'est pas conçu avec soin, purger directement des Snaps conservés par abonnement (s'il ne repasse pas par la vérification d'abonnement) ou les considérer à tort comme "en retard" (si `scheduledPurgeAt` n'est pas tenu à jour) ; le Redis partagé par BullMQ avec la présence et les vues de stories pourrait aussi évincer des jobs sous pression mémoire si sa politique n'est pas configurée pour l'en empêcher.

## Décision
**Pour un `MESSAGE` de type `TEXT` :** aucune purge, `content` est permanent (ADR-009). Aucun job n'est programmé pour ce type de message.

**Pour un `MESSAGE` de type `SNAP_PHOTO` / `SNAP_PHOTO_VIDEO` :**
- À la création, `scheduledPurgeAt` est fixé à `createdAt + 30 jours` et un job BullMQ est programmé à cette échéance.
- À l'ouverture, si `openedAt + 24h` est antérieur au `scheduledPurgeAt` déjà programmé, le job est reprogrammé à cette date plus proche.
- **Ce délai de 24h est un choix délibéré de simplification, pas une fidélité au vrai Snapchat** : dans l'app originale, un Snap privé ouvert disparaît presque immédiatement (quelques secondes), et ce sont les Chats qui restent disponibles plus longtemps. SnapCore retient 24h uniformément pour rester simple à implémenter et à tester, au prix de cette fidélité précise.
- **À l'exécution du job**, le backend vérifie l'abonnement du **destinataire** au moment même de l'exécution — cette vérification est l'unique point d'entrée qui décide de purger ou non, que le job vienne de son échéance normale ou d'une reprogrammation par le balayage de rattrapage (voir ci-dessous : il n'existe qu'un seul chemin de purge) :
  - Si `subscriptionTier = premium` et `premiumUntil + 24h` (marge de sécurité, voir ci-dessous) est dans le futur : la purge est annulée et **`scheduledPurgeAt` est mis à jour à `premiumUntil + 24h`**, et le job reprogrammé à cette nouvelle date. Cette mise à jour de `scheduledPurgeAt` a lieu à **chaque** reprogrammation, sans exception : c'est ce qui empêche le balayage de rattrapage de retrouver indéfiniment ce même Snap comme "en retard".
  - Sinon : `mediaKey` est vidé et le fichier supprimé de R2 (ADR-016).
  - **Marge de sécurité sur `premiumUntil`** : une marge de 24h est ajoutée avant de considérer un abonnement comme réellement expiré, pour absorber le délai possible entre l'expiration théorique et la réception du webhook de renouvellement RevenueCat, qui peut être livré avec un léger retard (ADR-007) — sans cette marge, un renouvellement traité en retard purgerait à tort un Snap que l'utilisateur vient de payer pour garder.
- **La ligne `MESSAGE` n'est jamais supprimée**, purgée ou non.

**Pour un `STORY_ITEM` :** inchangé — purge du média R2 et de la ligne PostgreSQL à `expiresAt` (`createdAt + 24h`), sans notion d'ouverture ni d'exception d'abonnement.

**Résilience de la file de jobs :**
- Le Redis utilisé par BullMQ doit avoir la **persistance AOF activée** (`appendonly yes`), pour survivre à un redémarrage sans perdre les jobs programmés.
- **Le Redis de BullMQ doit avoir `maxmemory-policy` réglé sur `noeviction`** (exigence documentée de BullMQ elle-même). Ce Redis est partagé avec la présence temps réel (ADR-018), les vues de stories (ADR-013) et le pub/sub Socket.io (ADR-006) : sous pression mémoire, une politique d'éviction (LRU ou autre) pourrait supprimer silencieusement des jobs BullMQ programmés pour libérer de la place — l'AOF ne protège que contre un redémarrage, pas contre une éviction en mémoire pendant que Redis tourne. `noeviction` fait échouer les écritures plutôt que d'évincer des clés quand la mémoire est pleine, ce qui est le comportement voulu ici : mieux vaut une erreur visible côté application qu'une purge programmée qui disparaît sans bruit. Si le volume de présence/vues/pub-sub venait à saturer la mémoire de cette instance, une instance Redis séparée dédiée à la file de jobs serait la solution de repli.
- **Filet de rattrapage** : un balayage périodique (ex. toutes les heures) recherche les `Message`/`StoryItem` dont `scheduledPurgeAt`/`expiresAt` est dépassé mais dont le média n'a pas encore été purgé. **Ce balayage ne supprime jamais rien lui-même** : pour chaque ligne trouvée, il remet en file un job de purge identique à celui de l'échéance normale, qui repasse par l'unique logique décrite ci-dessus (vérification d'abonnement du destinataire à l'exécution). Cela garantit que le balayage de rattrapage ne peut jamais purger directement un Snap conservé par abonnement : soit `scheduledPurgeAt` a bien été tenu à jour à chaque reprogrammation (voir ci-dessus) et le balayage ne retrouve jamais ce Snap en retard, soit il le retrouve par anomalie (job perdu malgré l'AOF) et le job rejoué revérifie l'abonnement avant d'agir, comme n'importe quel autre job. C'est ce balayage qui utilise réellement l'index `@@index([scheduledPurgeAt])` sur `Message` (dans un modèle "un job programmé par message", cet index ne servirait à rien d'autre) : il couvre le cas où le job BullMQ correspondant aurait été perdu malgré la persistance AOF (fenêtre entre deux fsync, par exemple).

## Alternatives
- Se fier uniquement à la persistance AOF, sans balayage de rattrapage : plus simple, mais AOF garantit une perte bornée (quelques secondes de données au pire selon la configuration de fsync), pas une garantie absolue — le balayage referme cette fenêtre résiduelle pour un coût de requête négligeable (un scan indexé peu fréquent).
- Tâche cron périodique remplaçant entièrement BullMQ (pas de jobs programmés du tout, seulement des scans réguliers) : écartée, moins précise dans le cas nominal (délai jusqu'au prochain scan) ; le choix retenu combine la précision de BullMQ et la robustesse d'un filet de rattrapage peu coûteux.

## Conséquences
### Positives
- Une purge programmée survit à un redémarrage Redis normal (AOF) et à une pression mémoire (`noeviction`), et le balayage de rattrapage couvre le résidu de risque.
- Un renouvellement d'abonnement traité en retard ne purge plus un Snap à tort, grâce à la marge de 24h.
- Le balayage de rattrapage ne peut structurellement pas purger un Snap conservé par abonnement, puisqu'il ne fait que rejouer la même vérification qu'un job normal plutôt que de supprimer directement.
- L'ADR ne prétend plus une fidélité à Snapchat qu'il n'a pas sur le délai de 24h.

### Négatives
- La persistance AOF a un coût d'I/O sur l'instance Redis, marginal vu le volume attendu du projet.
- `noeviction` signifie qu'un Redis saturé en mémoire refuse des écritures plutôt que d'en perdre silencieusement — préférable ici, mais cela déplace le problème vers une erreur applicative à surveiller (alerte sur la mémoire Redis) plutôt que de le masquer.
- Le balayage de rattrapage ajoute une tâche planifiée de plus à surveiller, même si elle ne doit normalement jamais rien trouver à faire.

## Historique
- Version initiale : job programmé à un `expiresAt` connu dès la création — incohérent avec le fonctionnement réel d'un Snap.
- Révision 1 : introduction de `scheduledPurgeAt` recalculé à l'ouverture, purge qui vide le contenu sans supprimer la ligne.
- Révision 2 : purge limitée aux Snaps (jamais le texte), exception d'abonnement SnapCore+ vérifiée à l'exécution.
- Révision 3 (revue technique) : exigence de persistance Redis (AOF) + balayage de rattrapage, marge de 24h sur `premiumUntil`, retrait de l'affirmation de fidélité à Snapchat sur le délai de 24h (celui-ci est en réalité inspiré des Chats, pas des Snaps).
- Révision 4 (revue technique) : explicite que `scheduledPurgeAt` est mis à jour à chaque reprogrammation (pas seulement mentionné) et que le balayage de rattrapage ne fait jamais que remettre en file le job normal (jamais de suppression directe), pour éliminer tout risque de purger un Snap conservé par abonnement ; ajout de l'exigence `maxmemory-policy noeviction` sur le Redis de BullMQ, partagé avec la présence et les vues de stories.

## Liens
- ADR liés : ADR-003, ADR-006, ADR-007, ADR-009, ADR-013, ADR-016, ADR-018

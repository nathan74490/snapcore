# ADR-017 — Purge automatique des Snaps expirés : job BullMQ, exception SnapCore+

## Statut
Proposé

## Contexte
ADR-013 (story) et le principe d'éphémérité (ADR-003) supposaient un job BullMQ de purge sans jamais préciser son fonctionnement exact. Une révision a introduit `scheduledPurgeAt` (recalculé à l'ouverture) et l'exception SnapCore+, mais laissait deux problèmes : (1) BullMQ s'appuie sur Redis, et ADR-003 reconnaît lui-même que Redis peut perdre son contenu au redémarrage sans persistance configurée — sans filet de rattrapage, un redémarrage Redis mal configuré ferait disparaître toutes les purges programmées, et les Snaps ne seraient alors plus jamais supprimés ; (2) l'ADR présentait le délai de 24h après ouverture comme fidèle au vrai Snapchat, alors qu'en réalité un Snap privé ouvert y est supprimé quasi immédiatement — les 24h sont plutôt inspirées du fonctionnement des Chats. Cette révision corrige les deux et ajoute une marge de sécurité pour l'exception d'abonnement (point soulevé en parallèle, voir ADR-007).

## Décision
**Pour un `MESSAGE` de type `TEXT` :** aucune purge, `content` est permanent (ADR-009). Aucun job n'est programmé pour ce type de message.

**Pour un `MESSAGE` de type `SNAP_PHOTO` / `SNAP_PHOTO_VIDEO` :**
- À la création, `scheduledPurgeAt` est fixé à `createdAt + 30 jours` et un job BullMQ est programmé à cette échéance.
- À l'ouverture, si `openedAt + 24h` est antérieur au `scheduledPurgeAt` déjà programmé, le job est reprogrammé à cette date plus proche.
- **Ce délai de 24h est un choix délibéré de simplification, pas une fidélité au vrai Snapchat** : dans l'app originale, un Snap privé ouvert disparaît presque immédiatement (quelques secondes), et ce sont les Chats qui restent disponibles plus longtemps. SnapCore retient 24h uniformément pour rester simple à implémenter et à tester, au prix de cette fidélité précise.
- **À l'exécution du job**, le backend vérifie l'abonnement du **destinataire** au moment même de l'exécution :
  - Si `subscriptionTier = premium` et `premiumUntil + 24h` (marge de sécurité, voir ci-dessous) est dans le futur : la purge est annulée et le job reprogrammé à `premiumUntil + 24h`.
  - Sinon : `mediaKey` est vidé et le fichier supprimé de R2 (ADR-016).
  - **Marge de sécurité sur `premiumUntil`** : une marge de 24h est ajoutée avant de considérer un abonnement comme réellement expiré, pour absorber le délai possible entre l'expiration théorique et la réception du webhook de renouvellement RevenueCat (ADR-007) — sans cette marge, un renouvellement arrivant quelques secondes après l'exécution du job purgerait à tort un Snap que l'utilisateur vient de payer pour garder.
- **La ligne `MESSAGE` n'est jamais supprimée**, purgée ou non.

**Pour un `STORY_ITEM` :** inchangé — purge du média R2 et de la ligne PostgreSQL à `expiresAt` (`createdAt + 24h`), sans notion d'ouverture ni d'exception d'abonnement.

**Résilience de la file de jobs :**
- Le Redis utilisé par BullMQ doit avoir la **persistance AOF activée** (`appendonly yes`), pour survivre à un redémarrage sans perdre les jobs programmés.
- **Filet de rattrapage** : un balayage périodique (ex. toutes les heures) recherche les `Message`/`StoryItem` dont `scheduledPurgeAt`/`expiresAt` est dépassé mais dont le média n'a pas encore été purgé, et redéclenche leur purge. C'est ce balayage qui utilise réellement l'index `@@index([scheduledPurgeAt])` sur `Message` (dans un modèle "un job programmé par message", cet index ne servirait à rien d'autre) : il couvre le cas où le job BullMQ correspondant aurait été perdu malgré la persistance AOF (fenêtre entre deux fsync, par exemple).

## Alternatives
- Se fier uniquement à la persistance AOF, sans balayage de rattrapage : plus simple, mais AOF garantit une perte bornée (quelques secondes de données au pire selon la configuration de fsync), pas une garantie absolue — le balayage referme cette fenêtre résiduelle pour un coût de requête négligeable (un scan indexé peu fréquent).
- Tâche cron périodique remplaçant entièrement BullMQ (pas de jobs programmés du tout, seulement des scans réguliers) : écartée, moins précise dans le cas nominal (délai jusqu'au prochain scan) ; le choix retenu combine la précision de BullMQ et la robustesse d'un filet de rattrapage peu coûteux.

## Conséquences
### Positives
- Une purge programmée survit à un redémarrage Redis normal (AOF), et le balayage de rattrapage couvre le résidu de risque.
- Un renouvellement d'abonnement arrivant juste après l'exécution du job ne purge plus un Snap à tort, grâce à la marge de 24h.
- L'ADR ne prétend plus une fidélité à Snapchat qu'il n'a pas sur le délai de 24h.

### Négatives
- La persistance AOF a un coût d'I/O sur l'instance Redis, marginal vu le volume attendu du projet.
- Le balayage de rattrapage ajoute une tâche planifiée de plus à surveiller, même si elle ne doit normalement jamais rien trouver à faire.

## Historique
- Version initiale : job programmé à un `expiresAt` connu dès la création — incohérent avec le fonctionnement réel d'un Snap.
- Révision 1 : introduction de `scheduledPurgeAt` recalculé à l'ouverture, purge qui vide le contenu sans supprimer la ligne.
- Révision 2 : purge limitée aux Snaps (jamais le texte), exception d'abonnement SnapCore+ vérifiée à l'exécution.
- Révision 3 (revue technique) : exigence de persistance Redis (AOF) + balayage de rattrapage, marge de 24h sur `premiumUntil`, retrait de l'affirmation de fidélité à Snapchat sur le délai de 24h (celui-ci est en réalité inspiré des Chats, pas des Snaps).

## Liens
- ADR liés : ADR-003, ADR-007, ADR-009, ADR-013, ADR-016

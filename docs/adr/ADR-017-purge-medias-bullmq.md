# ADR-017 — Purge automatique des messages et médias expirés : job BullMQ

## Statut
Proposé

## Contexte
ADR-013 (story) et le principe d'éphémérité (ADR-003) supposaient un job BullMQ de purge sans jamais préciser son fonctionnement exact pour les snaps privés (`MESSAGE`). Une première version affirmait à tort qu'un `expiresAt` connu dès la création programmait le job — or, comme dans le vrai Snapchat, un snap privé n'a pas de date d'expiration connue à la création : il expire un certain temps **après avoir été ouvert**. Un snap jamais ouvert doit malgré tout finir par être purgé (30 jours chez Snapchat), sans quoi le stockage grossirait indéfiniment.

## Décision
**Pour un `MESSAGE` (snap privé ou chat texte) :**
- À la création, `scheduledPurgeAt` est fixé à `createdAt + 30 jours` (filet de sécurité pour un message jamais ouvert) et un job BullMQ est programmé à cette échéance.
- À l'ouverture (`openedAt` renseigné), si `openedAt + 24h` est antérieur au `scheduledPurgeAt` déjà programmé, le job existant est **reprogrammé** à cette date plus proche (BullMQ permet de retirer/reprogrammer un job par son identifiant, ici l'ID du message) et `scheduledPurgeAt` est mis à jour en conséquence.
- À l'exécution du job : le champ `content` ou `mediaKey` du message est **vidé** (mis à `null`) et, si un `mediaKey` existait, le fichier correspondant est supprimé de Cloudflare R2 (ADR-016). **La ligne `MESSAGE` n'est jamais supprimée** : elle doit rester pour que la liste de conversation continue d'afficher l'icône grise "expiré" (ADR-009), qui a besoin de `contentType`, du sens et d'`openedAt` pour se calculer.

**Pour un `STORY_ITEM` :** inchangé par rapport à la version initiale — `expiresAt` est bien connu dès la création (`createdAt + 24h`, pas de notion d'ouverture différée pour une story), le job de purge supprime le média sur R2 **et** la ligne PostgreSQL (une story expirée n'a pas besoin de laisser de trace visible, contrairement à un message dans une conversation).

- BullMQ s'appuie sur Redis pour la file de jobs différés, ce qui ne nécessite aucune nouvelle brique d'infra (Redis est déjà central au projet, ADR-003).

## Alternatives
- Tâche cron périodique qui scanne les lignes dont `scheduledPurgeAt`/`expiresAt` est dépassé : plus simple à première vue, mais introduit un délai entre l'expiration réelle et la purge effective, et une charge de lecture répétée sur PostgreSQL même quand rien n'a expiré — l'index sur `scheduledPurgeAt`/`expiresAt` limiterait ce coût, mais la précision reste inférieure à un job programmé.
- Supprimer la ligne `MESSAGE` à la purge, comme pour `STORY_ITEM` : plus simple (un seul comportement pour tout le projet), mais empêche l'icône grise "expiré" de s'afficher dans la liste de conversation — contraire au comportement attendu (ADR-009).

## Conséquences
### Positives
- Comportement fidèle au vrai Snapchat : expiration liée à l'ouverture pour les snaps privés, expiration fixe pour les stories.
- Purge précise, déclenchée exactement à l'échéance calculée, sans scan périodique.
- L'icône "expiré" reste affichable indéfiniment pour un message, sans conserver le média lui-même.

### Négatives
- Reprogrammer un job BullMQ à l'ouverture ajoute une étape (retrait + recréation) par rapport à un simple job unique — complexité mineure, mais à implémenter avec soin (ne pas laisser deux jobs actifs pour le même message).
- Si le worker BullMQ est arrêté au moment où un job devait s'exécuter, le job s'exécute au redémarrage (comportement standard de BullMQ) — le contenu reste donc disponible un peu plus longtemps que prévu dans ce cas, sans risque de perte du job.

## Historique
- Version initiale : job programmé à un `expiresAt` connu dès la création de `MESSAGE`, suppression de la ligne à la purge — incohérent avec l'absence du champ `expiresAt` sur `MESSAGE` et avec le fonctionnement réel d'un snap (expiration liée à l'ouverture, pas à la création).
- Révision (revue technique) : introduction de `scheduledPurgeAt` recalculé à l'ouverture, purge qui vide le contenu sans supprimer la ligne `MESSAGE` ; comportement de `STORY_ITEM` inchangé.

## Liens
- ADR liés : ADR-003, ADR-009, ADR-013, ADR-016

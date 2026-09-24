# ADR-017 — Purge automatique des Snaps expirés : job BullMQ, exception SnapCore+

## Statut
Proposé

## Contexte
ADR-013 (story) et le principe d'éphémérité (ADR-003) supposaient un job BullMQ de purge sans jamais préciser son fonctionnement exact pour les Snaps privés. Une révision précédente programmait la purge d'un `MESSAGE` à l'ouverture (`openedAt + 24h`) ou à `createdAt + 30 jours` si jamais ouvert, en vidant le contenu sans supprimer la ligne — mais appliquait cette règle à tort à tous les types de contenu, y compris le texte (voir ADR-009, révision 2 : le texte est désormais permanent). Il manquait aussi la prise en compte de l'abonnement SnapCore+ (ADR-007), qui doit permettre de conserver un Snap indéfiniment.

## Décision
**Pour un `MESSAGE` de type `TEXT` :** aucune purge, `content` est permanent (ADR-009). Aucun job n'est programmé pour ce type de message.

**Pour un `MESSAGE` de type `SNAP_PHOTO` / `SNAP_PHOTO_VIDEO` :**
- À la création, `scheduledPurgeAt` est fixé à `createdAt + 30 jours` (filet de sécurité pour un Snap jamais ouvert) et un job BullMQ est programmé à cette échéance.
- À l'ouverture (`openedAt` renseigné), si `openedAt + 24h` est antérieur au `scheduledPurgeAt` déjà programmé, le job existant est reprogrammé à cette date plus proche et `scheduledPurgeAt` est mis à jour en conséquence.
- **À l'exécution du job**, le backend vérifie l'abonnement du **destinataire** (`recipient.subscriptionTier`) au moment même de l'exécution (pas au moment de la programmation, l'abonnement pouvant changer entre-temps) :
  - Si `subscriptionTier = premium` et `premiumUntil` est dans le futur : la purge est **annulée** et le job est **reprogrammé à `premiumUntil`**, pour être réévalué à l'expiration de l'abonnement (si l'abonnement est renouvelé avant cette date, le job sera à nouveau reporté au prochain contrôle).
  - Sinon : le média est purgé — `mediaKey` est mis à `null` et le fichier correspondant est supprimé de Cloudflare R2 (ADR-016).
- **La ligne `MESSAGE` n'est jamais supprimée**, purgée ou non : elle reste nécessaire pour que la liste de conversation affiche l'icône (colorée puis grise une fois `mediaKey` vidé, voir ADR-009).

**Pour un `STORY_ITEM` :** inchangé — `expiresAt` est connu dès la création (`createdAt + 24h`), sans notion d'ouverture différée ni d'exception d'abonnement ; le job de purge supprime le média sur R2 **et** la ligne PostgreSQL.

- BullMQ s'appuie sur Redis pour la file de jobs différés (aucune nouvelle brique, ADR-003).

## Alternatives
- Vérifier l'abonnement à la programmation du job plutôt qu'à son exécution : plus simple, mais un utilisateur qui s'abonne après la création d'un Snap (mais avant sa purge programmée) le verrait quand même supprimé, ce qui n'a pas de sens vis-à-vis de l'avantage vendu par SnapCore+.
- Appliquer l'exception d'abonnement également aux Snaps jamais ouverts après 30 jours (traiter les deux cas de façon strictement identique) : cohérent avec l'esprit "abonné = tout conserver", et c'est en réalité déjà ce que fait la décision retenue — la vérification d'abonnement à l'exécution s'applique au job de 30 jours comme à celui de 24h, sans distinction de branche.
- Tâche cron périodique scannant `scheduledPurgeAt` : écartée pour les mêmes raisons que dans la version précédente de cet ADR (délai de purge moins précis, charge de lecture répétée).

## Conséquences
### Positives
- Un Snap jamais purgé chez un abonné, y compris si l'abonnement est souscrit après l'envoi mais avant l'exécution du job — l'avantage SnapCore+ est donc fiable, pas seulement au moment de l'envoi.
- Le texte reste totalement à l'écart du mécanisme de purge : aucun risque de le voir un jour vidé par erreur.
- Comportement fidèle au vrai Snapchat pour la partie non-abonné (expiration liée à l'ouverture, filet de sécurité à 30 jours).

### Négatives
- Reprogrammer un job à chaque changement de situation (ouverture, expiration d'abonnement) ajoute de la complexité opérationnelle par rapport à un job unique et définitif — nécessaire ici pour respecter l'avantage SnapCore+.
- Si le worker BullMQ est arrêté au moment où un job devait s'exécuter, il s'exécute au redémarrage (comportement standard de BullMQ) — le média reste disponible un peu plus longtemps que prévu dans ce cas.

## Historique
- Version initiale : job programmé à un `expiresAt` connu dès la création — incohérent avec l'absence de ce champ sur `MESSAGE` et avec le fonctionnement réel d'un Snap.
- Révision 1 (revue technique) : introduction de `scheduledPurgeAt` recalculé à l'ouverture, purge qui vide le contenu sans supprimer la ligne — appliquée à tort à tous les types de contenu.
- Révision 2 (clarification du lead développeur) : la purge ne concerne plus que les Snaps (`SNAP_PHOTO`/`SNAP_PHOTO_VIDEO`), jamais le texte ; ajout de l'exception d'abonnement SnapCore+ (conservation indéfinie), vérifiée à l'exécution du job plutôt qu'à sa programmation.

## Liens
- ADR liés : ADR-003, ADR-007, ADR-009, ADR-013, ADR-016

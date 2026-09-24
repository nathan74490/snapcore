# ADR-019 — Suppression de compte : cascade sur les données liées

## Statut
Proposé

## Contexte
Aucune décision n'existait sur ce qu'il advient des données d'un utilisateur (messages, amitiés, stories, tokens d'appareil) quand son compte est supprimé, et les relations Prisma n'avaient pas de comportement `onDelete` explicite. Le projet n'a pas de contrainte de conformité (RGPD ou autre) à respecter au-delà de sa propre cohérence, puisqu'il ne sera jamais publié ni utilisé par de vrais utilisateurs.

## Décision
Suppression en cascade complète : supprimer un `User` supprime automatiquement (`onDelete: Cascade` côté Prisma) tous les `Message` où il est expéditeur ou destinataire, toutes les `Friendship` où il apparaît (`requesterId`, `addresseeId`), tous ses `StoryItem`, et tous ses `DeviceToken`. Le champ `Friendship.blockedBy` utilise `onDelete: SetNull` plutôt que `Cascade` : si l'utilisateur qui a bloqué est supprimé alors que la ligne `Friendship` elle-même ne l'est pas (cas rare, l'un des deux membres de la paire étant justement celui qui a bloqué), on perd la trace de qui a bloqué plutôt que de casser la contrainte de clé étrangère.

## Alternatives
- Conservation anonymisée des messages (l'expéditeur/destinataire supprimé devient un utilisateur fantôme "Compte supprimé") : plus proche d'une vraie app de messagerie, mais ajoute de la complexité (gestion d'un utilisateur spécial) pour un projet qui n'a pas de contrainte de conservation à respecter.
- Empêcher la suppression d'un compte tant qu'il a des données liées (comme certaines bases relationnelles le font par défaut, `onDelete: Restrict`) : plus prudent, mais rend la suppression de compte impossible en pratique sans un nettoyage manuel préalable, ce qui n'a pas d'intérêt ici.

## Conséquences
### Positives
- Comportement simple et prévisible : supprimer un compte supprime tout ce qui lui appartient, sans étape manuelle.
- Aucune donnée orpheline en base après une suppression de compte.

### Négatives
- Un utilisateur qui supprime son compte fait disparaître les messages qu'il a envoyés même du point de vue de son interlocuteur (l'historique de la conversation en est affecté) — acceptable pour un projet pédagogique sans utilisateurs réels à protéger.

## Liens
- ADR liés : ADR-004, ADR-009, ADR-012, ADR-013, ADR-018

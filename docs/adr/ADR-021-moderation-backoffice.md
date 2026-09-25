# ADR-021 — Modération : signalements, suspension de compte, suppression ciblée de contenu

## Statut
Proposé

## Contexte
Le backoffice a besoin d'actions de modération concrètes, aucune n'étant encore définie : traiter un signalement, suspendre un compte problématique sans passer par la suppression définitive (ADR-019, déclenchée par l'utilisateur lui-même et irréversible), supprimer un contenu isolé signalé sans suspendre tout le compte, et permettre à un modérateur de consulter librement un profil même sans signalement préalable (par exemple pour enquêter sur un signalement reçu par un autre canal). Il faut aussi trancher la frontière entre le backoffice (lecture directe PostgreSQL via Prisma, ADR-004) et le backend NestJS pour les actions qui ont des effets de bord (nettoyage R2, jobs BullMQ de purge, ADR-016, ADR-017).

## Décision
**Modèle `Report` (signalement) :**
- `reporterId` (l'utilisateur qui signale), `targetType` (`USER` / `MESSAGE` / `STORY_ITEM`), une seule des trois clés nullable `targetUserId` / `targetMessageId` / `targetStoryItemId` renseignée selon `targetType` — garanti par une contrainte `CHECK` en migration SQL (même famille de contrainte que celle encore ouverte pour `Message.content`/`mediaKey`, ADR-009, et que celles déjà en place sur `Friendship`, ADR-012 ; non exprimable dans le DSL Prisma).
- `reason` (texte libre), `status` (`PENDING` / `RESOLVED` / `DISMISSED`), `resolvedById` / `resolvedAt` pointant vers le `Moderator` (ADR-020) qui a traité le signalement.

**Suspension de compte (`User.moderationStatus` : `ACTIVE` / `SUSPENDED`) :**
- Réversible, à la différence de la suppression de compte (ADR-019) qui est définitive, asynchrone et déclenchée par l'utilisateur lui-même.
- Un compte `SUSPENDED` est traité comme inexistant pour les autres utilisateurs, **exactement selon la même logique qu'un compte en cours de suppression** (`deletionRequestedAt` non nul, ADR-019) : connexion bloquée, messagerie/profil/stories masqués pour les autres. **La vérification centralisée déjà recommandée pour `deletionRequestedAt` (ADR-019) doit donc couvrir les deux conditions** (`deletionRequestedAt IS NOT NULL OR moderationStatus = SUSPENDED`) au même endroit, plutôt que de dupliquer la logique à chaque point de vérification.
- Réactivation : un modérateur repasse `moderationStatus` à `ACTIVE` — suffisant pour rendre le compte de nouveau visible et fonctionnel, les données n'ayant jamais été touchées.
- `suspendedAt` / `suspendedById` / `suspensionReason` renseignés sur `User` pour la traçabilité minimale.

**Suppression ciblée d'un contenu (un `Message` ou un `StoryItem`, sans suspendre le compte) :**
- Pour un `Message` : le contenu est **rédigé**, la ligne n'est **pas** supprimée — `content` mis à `null` (`TEXT`) ou `mediaKey` vidé avec suppression immédiate de l'objet R2 (Snap). `moderatedAt` / `moderatedById` / `moderationReason` renseignés sur la ligne. La ligne est conservée pour ne pas casser la structure de la conversation pour l'autre participant, et pour garder une trace qu'une modération a eu lieu.
- Pour un `StoryItem` : la ligne est supprimée directement, avec suppression immédiate de l'objet R2 associé — pas de champ d'audit conservé sur ce modèle, cohérent avec sa nature déjà éphémère (une Story aurait de toute façon disparu au plus tard 24h après ; la modération ne fait qu'avancer cette échéance).
- **Dans les deux cas, le job BullMQ de purge programmé pour cet identifiant (`purge-message-<id>` / `purge-story-<id>`, ADR-017, voir `docs/impl/purge-bullmq.md`) doit être retiré (`job.remove()`)** au moment de la modération, pour qu'il ne s'exécute pas inutilement plus tard sur des données déjà traitées.

**Recherche libre de profil :**
- Un modérateur peut consulter n'importe quel profil et son activité (messages envoyés/reçus, stories) sans signalement préalable, pour pouvoir enquêter sur un signalement reçu par un autre canal (ex. email externe). Cet accès contourne délibérément la règle `FRIENDSHIP ACCEPTED` (ADR-009, ADR-012) : elle protège l'accès entre utilisateurs entre eux, pas l'accès d'un modérateur authentifié.

**Frontière backoffice ↔ backend :**
- Le backoffice **lit** directement PostgreSQL via Prisma (ADR-004) pour ses écrans de liste, recherche et tableau de bord — pas de round-trip HTTP nécessaire pour une simple lecture.
- Toute action qui **modifie** l'état (suspendre/réactiver un compte, rédiger ou supprimer un contenu, résoudre un signalement) appelle un endpoint admin dédié du backend NestJS (`/admin/...`), pour que la logique avec effets de bord (suppression R2, retrait de job BullMQ) reste centralisée à un seul endroit plutôt que dupliquée dans le backoffice.
- Ces endpoints sont protégés par un **secret interne partagé** (variable d'environnement, jamais exposé au navigateur du modérateur), pas par l'authentification Firebase des utilisateurs finaux (ADR-015) : l'appelant est le serveur Next.js du backoffice, déjà responsable d'avoir vérifié la session du modérateur (ADR-020) avant l'appel, pas un navigateur non authentifié. L'identifiant du modérateur (`moderatedById` / `resolvedById` / `suspendedById`) est transmis dans le corps de la requête pour l'attribution ; le backend fait confiance à cette valeur puisque l'appelant est un service interne de confiance.

## Alternatives
- Suppression complète de la ligne `Message` lors d'une modération, au lieu d'une rédaction : écartée — casserait la structure de la conversation pour l'autre participant et perdrait toute trace qu'une modération a eu lieu.
- Écriture directe en base depuis le backoffice pour toutes les actions, y compris les mutations : écartée — dupliquerait la logique de nettoyage R2/BullMQ déjà écrite côté backend (ADR-016, ADR-017, ADR-019) plutôt que de la centraliser derrière un seul point d'entrée.
- Authentifier les endpoints admin avec un jeton Firebase comme les utilisateurs finaux : écarté — un modérateur n'a pas de compte Firebase (ADR-020), il faudrait soit lui en créer un artificiellement, soit dupliquer un second mécanisme de vérification pour un usage entièrement interne.
- Implémenter la suspension comme une variante réversible du pipeline de suppression de compte (ADR-019) : écarté — ce pipeline est asynchrone et purge irréversiblement Firebase/R2/PostgreSQL, il n'a pas vocation à être annulé à mi-chemin ; la suspension doit rester une opération synchrone et triviale à inverser (changer un seul champ), incompatible avec ce pipeline.

## Conséquences
### Positives
- Les trois besoins (signalements, suspension réversible, suppression ciblée de contenu) sont couverts par un modèle de données minimal, sans nouvelle infrastructure.
- La frontière lecture directe / écriture via API évite de dupliquer la logique de nettoyage R2/BullMQ déjà décidée ailleurs (ADR-017, ADR-019), et reste cohérente avec ADR-004 qui autorisait déjà Prisma côté backoffice.
- La rédaction d'un message (plutôt que sa suppression) préserve la lisibilité de la conversation pour l'utilisateur qui n'est pas visé par la modération.

### Négatives
- La vérification centralisée de "compte actif" (ADR-019) doit désormais couvrir deux conditions (`deletionRequestedAt` et `moderationStatus`) au même endroit — un oubli de l'une des deux à un point de vérification romprait la garantie.
- Le secret interne partagé entre backoffice et backend n'a ni rotation ni expiration en V1 — acceptable pour un projet sans exposition réelle, insuffisant en production.
- Aucun rôle différencié parmi les modérateurs (ADR-020) : n'importe quel compte `Moderator` peut résoudre un signalement, suspendre un compte ou supprimer un contenu, sans distinction de gravité.
- Contrainte `CHECK` supplémentaire à ajouter en migration SQL manuelle pour `Report`, à ne pas oublier lors de la migration initiale (même catégorie que les contraintes déjà documentées pour `Friendship`, ADR-012).

## Historique
- Version initiale.

## Liens
- ADR liés : ADR-004, ADR-009, ADR-012, ADR-016, ADR-017, ADR-019, ADR-020

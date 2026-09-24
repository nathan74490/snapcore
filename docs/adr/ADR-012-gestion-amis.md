# ADR-012 — Gestion des amis : recherche par pseudo en V1, Snapcode + meilleur ami + flammes en V2

## Statut
Proposé

## Contexte
SnapCore doit permettre d'ajouter des amis, d'accepter ou refuser une demande, et de bloquer un utilisateur. Le vrai Snapchat propose plusieurs façons d'ajouter un ami (pseudo, Snapcode/QR, contacts téléphoniques) et des mécaniques sociales avancées (meilleur ami, flammes/streaks). Pour rester réaliste sans surcharger le premier lot de fonctionnalités, une partie de ces mécaniques est repoussée à une V2.

Une révision précédente a introduit la paire triée (`pairLow`/`pairHigh`) pour éliminer la condition de course sur les demandes symétriques, mais a laissé disparaître l'effet concret d'un blocage (masquer profil et contenus dans les deux sens, interdire l'envoi de messages), sans préciser qui peut débloquer, ni ce qui se passe si les deux utilisateurs se bloquent mutuellement.

## Décision
**V1 :**
- Une seule entité FRIENDSHIP par paire d'utilisateurs : `requesterId`, `addresseeId`, `status` (`PENDING`, `ACCEPTED`, `BLOCKED`, `DECLINED`), `requestedAt` (renommé depuis `createdAt`, voir Historique), `respondedAt`.
- **Unicité de la paire garantie en base** : `pairLow`/`pairHigh` (les deux ID triés) avec `@@unique([pairLow, pairHigh])`.
- **`requesterId` ≠ `addresseeId` garanti en base** : une contrainte `CHECK (requester_id <> addressee_id)` (ajoutée via migration SQL, non exprimable dans le DSL Prisma) empêche un utilisateur de s'ajouter lui-même.
- **Cohérence `pairLow`/`pairHigh` garantie en base** : une contrainte `CHECK` (migration SQL) impose que `pairLow`/`pairHigh` correspondent bien à `LEAST`/`GREATEST` de `requesterId`/`addresseeId` — sans quoi rien n'empêcherait une valeur incohérente d'être écrite malgré la contrainte d'unicité.
- **Effet d'un blocage (`status = BLOCKED`)** : le profil et les contenus (stories, statut en ligne) de chaque utilisateur sont masqués à l'autre, **dans les deux sens** ; l'envoi de messages entre les deux utilisateurs est **interdit dans les deux sens**, quel que soit celui qui a initié le blocage.
- **Qui peut débloquer** : seul `blockedByUser` (celui qui a bloqué) peut lever le blocage. L'utilisateur bloqué ne peut pas débloquer unilatéralement.
- **Blocage mutuel** : `blockedBy` ne porte qu'un seul utilisateur à la fois. Si B tente de bloquer A alors que A a déjà bloqué B, la tentative de B est un **no-op** : la ligne reste `BLOCKED` avec `blockedBy = A` (l'effet recherché par B — ne plus voir A, ne plus recevoir de message de A — est déjà obtenu par le blocage de A). Une notion de blocage réellement symétrique (les deux parties bloquantes, débloquage indépendant de chaque côté) est jugée superflue : l'effet pratique est identique.
- **Après un refus (`DECLINED`)** : une nouvelle demande **met à jour la ligne existante** (`status` repassé à `PENDING`, `requestedAt` rafraîchi) plutôt que de créer une nouvelle ligne. Aucune limite de fréquence n'est imposée en V1 (pas de protection anti-spam après un refus répété) — accepté comme limite connue, voir Conséquences.
- **Débloquer** : la ligne FRIENDSHIP est supprimée.
- **Bloquer un utilisateur qui n'est pas encore ami** : une ligne est créée directement avec `status = BLOCKED`.
- Ajout d'ami uniquement par recherche de pseudo (pas de Snapcode/QR, pas de découverte via contacts).
- Notification de demande/acceptation via Socket.io si le destinataire est connecté, sinon push FCM (ADR-018).

**V2 (plus tard) :** Snapcode/QR, meilleur ami, flammes (streak).

## Alternatives
- Deux lignes symétriques par relation d'amitié : évitée, source de doublons et d'incohérences.
- Vérification applicative avant insertion, sans contrainte base : laisse une fenêtre de course, écartée au profit de la paire triée.
- Blocage réellement symétrique (chaque partie peut bloquer/débloquer indépendamment, par exemple avec deux champs `blockedByA`/`blockedByB` ou deux lignes) : plus riche, mais complexifie le modèle pour un bénéfice pratique nul (l'effet visible — profils masqués, messages impossibles — est déjà obtenu dès qu'une des deux parties bloque).
- Limiter le nombre de nouvelles demandes après un refus (ex. un délai minimum) : écarté pour l'instant, faute d'un besoin réel identifié sur un projet sans utilisateurs malveillants.

## Conséquences
### Positives
- Modèle de données simple et suffisant pour la V1, avec des garanties d'intégrité au niveau base (paire unique, pas d'auto-ajout, cohérence `pairLow`/`pairHigh`).
- Comportement du blocage entièrement défini : effet, qui peut débloquer, cas du blocage mutuel.

### Négatives
- Aucune protection contre un utilisateur qui multiplie les demandes après un refus répété — acceptable ici, l'app n'étant jamais exposée à de vrais utilisateurs malveillants.
- Les deux contraintes `CHECK` (auto-ajout, cohérence de paire) doivent être ajoutées via une migration SQL manuelle, Prisma ne les exprimant pas nativement dans son DSL — à ne pas oublier lors de la génération de la migration initiale.

## Historique
- Version initiale : vérification applicative avant insertion, `blockedBy` sans relation, cas refus/déblocage/blocage d'un non-ami non précisés.
- Révision 1 : contrainte de paire triée au niveau base, relation `blockedByUser`, comportements refus/déblocage/blocage précisés.
- Révision 2 (revue technique) : effet concret du blocage explicité (profils/contenus masqués, messages interdits, dans les deux sens), règle de déblocage (seul le bloqueur), comportement du blocage mutuel, contraintes `CHECK` pour l'auto-ajout et la cohérence de paire, renommage `createdAt` → `requestedAt`.

## Liens
- ADR liés : ADR-018

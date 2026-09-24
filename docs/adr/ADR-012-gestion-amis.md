# ADR-012 — Gestion des amis : recherche par pseudo en V1, Snapcode + meilleur ami + flammes en V2

## Statut
Proposé

## Contexte
SnapCore doit permettre d'ajouter des amis, d'accepter ou refuser une demande, et de bloquer un utilisateur. Le vrai Snapchat propose plusieurs façons d'ajouter un ami (pseudo, Snapcode/QR, contacts téléphoniques) et des mécaniques sociales avancées (meilleur ami, flammes/streaks). Pour rester réaliste sans surcharger le premier lot de fonctionnalités, une partie de ces mécaniques est repoussée à une V2.

Le modèle initial laissait deux failles : la contrainte `@@unique([requesterId, addresseeId])` n'empêchait pas la coexistence d'une ligne A→B et d'une ligne B→A pour la même paire (deux demandes symétriques), et rien n'indiquait qui, des deux utilisateurs, était à l'origine d'un blocage. Cette révision les corrige.

## Décision
**V1 :**
- Une seule entité FRIENDSHIP par paire d'utilisateurs : `requesterId`, `addresseeId`, `status` (`PENDING`, `ACCEPTED`, `BLOCKED`, `DECLINED`), `createdAt`, `respondedAt`.
- **Unicité de la paire, indépendamment du sens** : avant toute création d'une ligne FRIENDSHIP, le backend vérifie côté application qu'aucune ligne n'existe déjà pour cette paire dans un sens comme dans l'autre (requête sur `(requesterId, addresseeId)` ET `(addresseeId, requesterId)`). Vérification applicative plutôt que contrainte base de données, pour rester simple (V1) — voir Alternatives.
- **Qui bloque qui** : champ `blockedBy` (nullable, ID utilisateur), renseigné uniquement quand `status = BLOCKED`, qui indique lequel des deux utilisateurs de la paire est à l'origine du blocage.
- Ajout d'ami uniquement par recherche de pseudo (pas de Snapcode/QR, pas de découverte via contacts).
- Notification de demande/acceptation via la passerelle Socket.io existante si le destinataire est connecté, sinon push Firebase Cloud Messaging (ADR-018).

**V2 (plus tard) :**
- Ajout d'ami par scan d'un Snapcode (QR code personnel généré à partir de l'ID utilisateur), réutilisant l'écran caméra (CameraX) déjà défini.
- Statut "meilleur ami" (mise en avant d'un ou plusieurs amis privilégiés).
- Flammes (streak) : compteur de jours consécutifs d'échange de snaps entre deux amis.

## Alternatives
- Deux lignes symétriques par relation d'amitié (une par sens) : évitée, source de doublons et d'incohérences à synchroniser.
- Contrainte au niveau base de données (paire canonique triée, ex. `userIdLow`/`userIdHigh`, avec contrainte unique dessus) pour empêcher structurellement les doublons A→B/B→A : garantie plus forte, incassable même par un bug applicatif, mais ajoute une colonne calculée et complexifie la logique d'écriture ; repoussée sauf besoin ultérieur d'une garantie plus stricte.
- Inclure Snapcode, meilleur ami et flammes dès la V1 : plus fidèle au vrai Snapchat d'emblée, mais alourdit le premier lot de fonctionnalités prioritaires alors que ces mécaniques peuvent être ajoutées plus tard sans remettre en cause le modèle FRIENDSHIP de la V1.

## Conséquences
### Positives
- Modèle de données simple et suffisant pour la V1 (une seule entité, un seul statut à faire évoluer).
- La paire A/B ne peut plus exister en double (deux sens simultanés), et on sait toujours qui a bloqué qui.
- V2 s'ajoute par extension (nouveaux champs / nouvelle entité) sans remise en cause de la V1.

### Négatives
- La vérification d'unicité de la paire repose sur la discipline du code applicatif, pas sur une contrainte base de données : un accès direct à la base contournant le backend pourrait créer un doublon — jugé acceptable vu l'échelle du projet.
- Le passage à la V2 nécessitera de recalculer un historique de streak si l'on veut le faire démarrer avant la mise en production de cette fonctionnalité — à anticiper si besoin le moment venu.

## Liens
- ADR liés : ADR-018

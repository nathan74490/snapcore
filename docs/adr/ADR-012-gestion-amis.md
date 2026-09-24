# ADR-012 — Gestion des amis : recherche par pseudo en V1, Snapcode + meilleur ami + flammes en V2

## Statut
Proposé

## Contexte
SnapCore doit permettre d'ajouter des amis, d'accepter ou refuser une demande, et de bloquer un utilisateur. Le vrai Snapchat propose plusieurs façons d'ajouter un ami (pseudo, Snapcode/QR, contacts téléphoniques) et des mécaniques sociales avancées (meilleur ami, flammes/streaks). Pour rester réaliste sans surcharger le premier lot de fonctionnalités, une partie de ces mécaniques est repoussée à une V2.

## Décision
**V1 :**
- Une seule entité FRIENDSHIP par paire d'utilisateurs : `requesterId`, `addresseeId`, `status` (`PENDING`, `ACCEPTED`, `BLOCKED`, `DECLINED`), `createdAt`, `respondedAt`.
- Ajout d'ami uniquement par recherche de pseudo (pas de Snapcode/QR, pas de découverte via contacts).
- Blocage via le statut `BLOCKED` sur la même entité (masque le profil et les contenus dans les deux sens).
- Notification de demande/acceptation via la passerelle Socket.io existante si le destinataire est connecté, sinon push Firebase Cloud Messaging (aucune nouvelle brique d'infra).

**V2 (plus tard) :**
- Ajout d'ami par scan d'un Snapcode (QR code personnel généré à partir de l'ID utilisateur), réutilisant l'écran caméra (CameraX) déjà défini.
- Statut "meilleur ami" (mise en avant d'un ou plusieurs amis privilégiés).
- Flammes (streak) : compteur de jours consécutifs d'échange de snaps entre deux amis.

## Alternatives
- Deux lignes symétriques par relation d'amitié (une par sens) : évitée, source de doublons et d'incohérences à synchroniser.
- Inclure Snapcode, meilleur ami et flammes dès la V1 : plus fidèle au vrai Snapchat d'emblée, mais alourdit le premier lot de fonctionnalités prioritaires alors que ces mécaniques peuvent être ajoutées plus tard sans remettre en cause le modèle FRIENDSHIP de la V1.

## Conséquences
### Positives
- Modèle de données simple et suffisant pour la V1 (une seule entité, un seul statut à faire évoluer).
- V2 s'ajoute par extension (nouveaux champs / nouvelle entité) sans remise en cause de la V1.
- Aucune nouvelle brique d'infra pour les notifications, tout est déjà prévu.

### Négatives
- Le passage à la V2 nécessitera de recalculer un historique de streak si l'on veut le faire démarrer avant la mise en production de cette fonctionnalité — à anticiper si besoin le moment venu.

## Liens
- ADR liés : aucun

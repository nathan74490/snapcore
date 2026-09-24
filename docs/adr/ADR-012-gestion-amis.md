# ADR-012 — Gestion des amis : recherche par pseudo en V1, Snapcode + meilleur ami + flammes en V2

## Statut
Proposé

## Contexte
SnapCore doit permettre d'ajouter des amis, d'accepter ou refuser une demande, et de bloquer un utilisateur. Le vrai Snapchat propose plusieurs façons d'ajouter un ami (pseudo, Snapcode/QR, contacts téléphoniques) et des mécaniques sociales avancées (meilleur ami, flammes/streaks). Pour rester réaliste sans surcharger le premier lot de fonctionnalités, une partie de ces mécaniques est repoussée à une V2.

Une première révision avait introduit une vérification applicative (avant insertion) pour empêcher les paires A→B et B→A symétriques, mais cette vérification reste sujette à une condition de course : si les deux demandes sont envoyées au même instant, les deux vérifications peuvent passer avant qu'aucune des deux lignes ne soit créée, produisant tout de même un doublon. Cette révision corrige le problème au niveau base plutôt qu'au niveau application, et précise plusieurs cas laissés ouverts (qui bloque qui, que faire après un refus ou un déblocage, bloquer un non-ami).

## Décision
**V1 :**
- Une seule entité FRIENDSHIP par paire d'utilisateurs : `requesterId`, `addresseeId`, `status` (`PENDING`, `ACCEPTED`, `BLOCKED`, `DECLINED`), `createdAt`, `respondedAt`.
- **Unicité de la paire garantie en base, indépendamment du sens** : deux colonnes supplémentaires `pairLow`/`pairHigh` contiennent les deux `id` utilisateur triés (peu importe qui est `requesterId`/`addresseeId`), avec une contrainte unique `@@unique([pairLow, pairHigh])`. Une tentative de création concurrente dans les deux sens échoue alors systématiquement sur l'une des deux insertions au niveau base — plus de fenêtre de course possible, contrairement à une simple vérification applicative avant insertion.
- **Qui bloque qui** : champ `blockedBy`, avec une vraie relation vers `User` (`blockedByUser`), renseigné uniquement quand `status = BLOCKED`.
- **Après un refus (`DECLINED`)** : une nouvelle demande entre les deux mêmes utilisateurs **met à jour la ligne existante** (nouveau `requesterId`/`addresseeId` selon qui relance, `status` repassé à `PENDING`, `createdAt` rafraîchi) plutôt que de créer une nouvelle ligne — la contrainte sur `(pairLow, pairHigh)` l'empêcherait de toute façon.
- **Débloquer** : la ligne FRIENDSHIP est **supprimée** — un déblocage revient à "aucune relation", pas à un statut intermédiaire à choisir.
- **Bloquer un utilisateur qui n'est pas encore ami** : une ligne est créée directement avec `status = BLOCKED`, `requesterId`/`addresseeId` = qui bloque/qui est bloqué, `blockedBy` = qui bloque.
- Ajout d'ami uniquement par recherche de pseudo (pas de Snapcode/QR, pas de découverte via contacts).
- Notification de demande/acceptation via la passerelle Socket.io existante si le destinataire est connecté, sinon push Firebase Cloud Messaging (ADR-018).

**V2 (plus tard) :**
- Ajout d'ami par scan d'un Snapcode (QR code personnel généré à partir de l'ID utilisateur), réutilisant l'écran caméra (CameraX) déjà défini.
- Statut "meilleur ami" (mise en avant d'un ou plusieurs amis privilégiés).
- Flammes (streak) : compteur de jours consécutifs d'échange de snaps entre deux amis.

## Alternatives
- Deux lignes symétriques par relation d'amitié (une par sens) : évitée, source de doublons et d'incohérences à synchroniser.
- Vérification applicative avant insertion, sans contrainte base (première révision) : plus simple à écrire, mais laisse une fenêtre de course en cas de demandes concurrentes — écartée au profit de la paire triée, qui règle le problème structurellement pour un coût de schéma minime.
- Transaction sérialisable (`SERIALIZABLE`) englobant la vérification et l'insertion : réglerait aussi la course, mais impose un niveau d'isolation plus coûteux sur toute la transaction alors que la contrainte `@@unique` sur la paire triée obtient la même garantie avec un coût bien moindre (une contrainte d'index, pas un changement de niveau d'isolation).
- Après un déblocage, repasser à un statut `DECLINED` ou conserver l'historique : plus riche, mais complexifie inutilement un V1 qui n'a pas besoin de conserver l'historique des blocages.

## Conséquences
### Positives
- Modèle de données simple et suffisant pour la V1.
- La paire A/B ne peut plus exister en double, même en cas de création concurrente — garanti par la base, pas par la discipline du code applicatif.
- Tous les cas (refus, déblocage, blocage d'un non-ami) ont un comportement défini et cohérent avec la contrainte de paire unique.

### Négatives
- La contrainte `(pairLow, pairHigh)` doit être maintenue par le code applicatif à chaque écriture (trier les deux ID avant insertion) — un oubli romprait la garantie ; à couvrir par un test.
- Le passage à la V2 nécessitera de recalculer un historique de streak si l'on veut le faire démarrer avant la mise en production de cette fonctionnalité.

## Historique
- Version initiale : vérification applicative avant insertion, `blockedBy` sans relation, cas refus/déblocage/blocage d'un non-ami non précisés.
- Révision (revue technique) : contrainte de paire triée au niveau base, relation `blockedByUser`, comportements refus/déblocage/blocage précisés.

## Liens
- ADR liés : ADR-018

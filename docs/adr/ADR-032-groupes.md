# ADR-032 : Groupes de discussion : chats et snaps à plusieurs, sans administrateur, comme Snapchat

## Statut
Accepté

## Contexte
La messagerie (ADR-009) ne relie que deux personnes : chaque `Message` a un seul destinataire, avec sa propre ouverture (`openedAt`), sa purge (ADR-017) et ses règles d'accès (amitié `ACCEPTED`, ADR-012). Snapchat permet aussi de discuter à plusieurs :
- un groupe se crée depuis « Nouveau chat », en choisissant des amis ;
- le nom du groupe est facultatif ;
- les chats et les snaps envoyés au groupe sont vus par chacun ;
- l'expéditeur voit qui a ouvert ;
- n'importe quel membre peut renommer le groupe ou y ajouter un de ses amis ;
- chacun peut le quitter.

## Décision

**Périmètre** :
- chats texte, snaps photo et vidéo, indicateur « en train d'écrire » (ADR-027), lu / ouvert par membre ;
- pas d'administrateur ;
- jusqu'à **32 membres**, comme Snapchat.

**Modèle de données** : des tables à part, sans toucher à `Message`. La messagerie à deux et ses contraintes (`CHECK` d'ADR-009, purge d'ADR-017, modération d'ADR-021) restent telles quelles, et un message de groupe n'a pas de destinataire unique.
- `Group(id, name?, createdById?, createdAt)` : `name` facultatif, 50 caractères au plus. Sans nom, l'app affiche les prénoms des autres membres.
- `GroupMember(groupId, userId, joinedAt, lastReadAt?)`, avec une clé unique `(groupId, userId)`.
- `GroupMessage(id, groupId, senderId, contentType, content?, mediaKey?, createdAt, scheduledPurgeAt?)`. Même règle de cohérence que `Message` : un texte n'a jamais de média, un snap jamais de texte.
- `GroupMessageView(messageId, userId, openedAt)` : première ouverture d'un snap par un membre.
- La suppression d'un compte (ADR-019) supprime ses appartenances et ses messages de groupe, médias R2 compris. Comme pour les messages à deux, ce que les autres ont envoyé reste.

**Règles** :
- **Création** :
  - le créateur choisit au moins 2 de ses amis `ACCEPTED`, et 31 au plus ;
  - les membres n'ont pas besoin d'être amis entre eux, comme dans Snapchat ;
  - deux groupes avec les mêmes membres peuvent coexister.
- **Ajout** :
  - n'importe quel membre ajoute un de **ses** amis `ACCEPTED`, dans la limite de 32 membres ;
  - le nouveau membre ne voit que les messages envoyés **après** son arrivée (`joinedAt`).
- **Renommer** : n'importe quel membre. Un nom vide retire le nom.
- **Quitter** :
  - le membre sort du groupe, et ses messages restent, avec son nom ;
  - quand le dernier membre part, le groupe et ses médias sont supprimés.
- **Accès** : lire, écrire et ouvrir un snap sont réservés aux membres actuels. Les règles d'amitié ne s'appliquent pas à l'intérieur du groupe : un blocage entre deux membres ne cache pas leurs messages de groupe. Limite assumée, comme dans Snapchat ; celui qui le souhaite quitte le groupe.
- **Lu** : `GroupMember.lastReadAt` est mis à jour à l'ouverture de la conversation. Un chat est lu par un membre quand `lastReadAt` est postérieur ou égal à la date du chat.
- **Snap** :
  - chaque membre, sauf l'expéditeur, l'ouvre une fois, avec la même fenêtre de grâce de 5 minutes que la vue unique d'ADR-009 ;
  - revoir un snap déjà vu reste l'avantage SnapCore+ (ADR-025).
- **Purge** (ADR-017) :
  - par défaut, 30 jours après l'envoi ;
  - dès que tous les membres présents à l'envoi l'ont ouvert (moins ceux qui sont partis depuis), la purge est rapprochée à 24 h après la dernière ouverture ;
  - job BullMQ `purge-group-message-<id>`, même file et même balayage de rattrapage ;
  - pas de conservation SnapCore+ prolongée dans un groupe.

**API** :

| Route | Rôle |
|---|---|
| `POST /groups` `{ name?, memberIds }` | Créer, réponse `GroupView` |
| `GET /groups` | Mes groupes, réponse `GroupSummary[]` (groupe, dernier message, plus ancien snap à ouvrir, non lu) |
| `GET /groups/:id` | Détail : nom, membres |
| `PATCH /groups/:id` `{ name }` | Renommer |
| `POST /groups/:id/members` `{ userIds }` | Ajouter des amis |
| `DELETE /groups/:id/members/me` | Quitter |
| `GET /groups/:id/messages?before&limit` | Historique depuis mon arrivée, du plus récent au plus ancien |
| `POST /groups/:id/messages/text` `{ content }` | Envoyer un chat |
| `POST /groups/:id/messages/snap` `{ uploadId, contentType }` | Envoyer un snap (upload ADR-016) |
| `POST /groups/:id/read` | Marquer lu |
| `POST /groups/:id/typing` | « En train d'écrire » |
| `POST /group-messages/:id/view` | URL de lecture d'un snap (ADR-009) |

`GroupMessageView` contient `{ id, groupId, senderId, contentType, content?, createdAt, mediaPurged, openedByMe, seenBy: string[] }`. `seenBy` liste les membres qui ont lu le chat ou ouvert le snap, l'expéditeur exclu.

`GroupView` contient `{ id, name?, members, formerMembers, createdAt }` : `formerMembers` sert à garder le nom des membres partis à côté de leurs messages.

Codes et messages d'erreur :
- un non-membre reçoit toujours **404 « Groupe introuvable »** ;
- un groupe plein donne **409** à l'ajout, et **400** à la création.

**Temps réel** (ADR-006, ADR-018), un événement par membre concerné :
- `group.message.received { groupId, messageId, senderId, senderName, groupName?, contentType }` : envoie un push, avec le nom du groupe et le prénom de l'expéditeur, sans relecture de l'API ;
- `group.updated { groupId }` : nom, membres ;
- `group.typing { groupId, userId }` ;
- `group.message.seen { groupId, messageId, userId }`.

**Interface** :
- **Liste Chat** : les groupes y figurent à côté des amis, triés par dernier message. Chaque ligne affiche un avatar composé de deux membres, le nom et un statut :
  - « Nouveau snap » / « Nouveau chat », avec le prénom de l'expéditeur ;
  - « Envoyé », « Ouvert », « Reçu ».
- **Création** (captures Snapchat 367 à 370) :
  - Snapchat met « New Group Chat » dans le menu « ••• » de l'écran Chat. Dans SnapCore, ce bouton ouvre le profil : la création passe par le bouton jaune « Nouveau chat ».
  - Cet écran est une feuille avec, en haut, le champ « Nom du groupe ✎ » (facultatif), puis le champ « À : » qui affiche les membres choisis en puces et sert aussi de recherche.
  - Les amis sont proposés en liste avec des cases rondes ; un ami choisi passe en bleu avec une coche.
  - Le bouton du bas reste grisé sans sélection. Avec un ami, « Discuter » ouvre la conversation à deux ; à partir de deux, « Discuter avec le groupe » crée le groupe.
- **Conversation de groupe** : chaque suite de messages commence par le prénom de son expéditeur, chacun dans sa couleur. Sous mes messages, la liste de ceux qui les ont vus ; un snap s'ouvre d'un appui ; l'indicateur d'écriture porte le prénom de celui qui écrit.
- **Réglages du groupe** (appui sur l'en-tête) :
  - liste des membres ;
  - « Renommer le groupe » ;
  - « Ajouter des membres » ;
  - « Quitter le groupe », avec confirmation.
- **« Envoyer à »** : section « Groupes » au-dessus des amis.
- **Analytics** (ADR-008) : `group_created { members }`, `group_message_sent { content_type }`, `group_renamed`, `group_members_added { count }`, `group_left`.

## Alternatives
- **Étendre `Message` avec un `groupId` nullable** : une seule table, mais `recipientId`, `openedAt`, la purge, la modération et les signalements reposent tous sur un destinataire unique. Les rendre optionnels aurait fragilisé la messagerie à deux, déjà testée. Écarté.
- **Un `Message` par destinataire (fan-out)** : réutilise tout, mais multiplie le stockage par 32, empêche de savoir que plusieurs lignes sont un même envoi, et un membre ajouté ne verrait pas les messages suivants sans réécriture. Écarté.
- **Un administrateur** (le créateur seul renomme, ajoute, retire) : plus de contrôle, mais ce n'est pas le fonctionnement de Snapchat. Écarté à la demande.

## Conséquences
### Positives
- La messagerie à deux n'est pas modifiée.
- Ouverture, purge, SnapCore+ et upload réutilisent les règles et les ports existants (ADR-009, ADR-016, ADR-017, ADR-025).

### Négatives
- Deux familles de messages : la liste Chat assemble amis et groupes côté app.
- Signaler un message de groupe n'est pas encore possible : on signale son auteur (ADR-021). Ce sera une extension du modèle `Report`.
- Le backoffice ne montre pas encore les groupes.
- Pas de retrait d'un membre par un autre (comme Snapchat), ni de groupe sans membre ami.

## Liens
- ADR liés : ADR-006, ADR-008, ADR-009, ADR-012, ADR-016, ADR-017, ADR-018, ADR-019, ADR-021, ADR-025, ADR-027

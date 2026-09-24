# ADR-009 — Modèle de message complet et statut (couleurs/icônes façon Snapchat)

## Statut
Proposé

## Contexte
SnapCore doit reproduire exactement le système d'icônes colorées de Snapchat dans la liste de discussion, qui indique en un coup d'œil le type de contenu, le sens (envoyé/reçu) et le statut (non ouvert / ouvert / expiré) du dernier message d'une conversation :
- Carré rouge plein : nouveau Snap photo reçu, non ouvert. Carré rouge contour : Snap photo envoyé, ouvert par le destinataire.
- Carré violet plein : nouveau Snap photo + vidéo/son reçu, non ouvert. Carré violet contour : Snap photo + vidéo/son envoyé, ouvert.
- Flèche bleue pleine : nouveau Chat (texte) reçu, non lu. Flèche bleue contour : Chat envoyé, ouvert/lu.
- Gris : Snap ou Chat ouvert puis expiré (plus disponible).

## Décision
- Champ `contentType` (`TEXT` | `SNAP_PHOTO` | `SNAP_PHOTO_VIDEO`) sur MESSAGE, qui détermine la couleur (bleu / rouge / violet) et la forme (flèche pour `TEXT`, carré pour les deux types de Snap).
- Contenu du message : `content` (texte, uniquement si `contentType = TEXT`) ou `mediaKey` (clé d'objet R2, uniquement si `contentType` est un des deux types de Snap — voir ADR-016) — un seul des deux est renseigné selon le type.
- Sens (envoyé/reçu) : déduit en comparant l'expéditeur à l'utilisateur connecté — aucun champ supplémentaire nécessaire.
- Statut (non ouvert / ouvert / expiré) : `openedAt` (nullable, PostgreSQL, mis à jour une seule fois à la première ouverture) est la **seule** source de vérité — `null` = jamais ouvert, une valeur = ouvert à cet instant. L'état "gris/expiré" se calcule côté client, simplement : `openedAt` renseigné ET `maintenant > openedAt + 24h`. **Redis n'intervient plus du tout dans ce calcul** : il n'y avait rien à y gagner (la donnée est déjà en base, durable) et cela évitait mal l'ambiguïté d'une clé absente. Cette règle de 24h s'applique uniformément aux Snaps et aux Chats texte, par simplification (un seul mécanisme pour tout `MESSAGE`, plutôt que des durées différentes par type de contenu).
- Le rendu (couleur, forme, plein/contour/gris) reste entièrement calculé côté application mobile ; aucun calcul côté serveur.
- Le cycle de vie complet (quand le contenu est réellement supprimé du stockage, pas seulement affiché en gris) est défini par ADR-017.

## Alternatives
- Un champ `displayColor` calculé et stocké côté serveur : évite un calcul côté client, mais duplique une information déjà déductible d'autres champs.
- Statut porté par une clé Redis avec TTL (version initiale de cet ADR) : réutilisait une infrastructure déjà en place, mais rendait indiscernables "jamais ouvert" et "expiré" (les deux se traduisant par une clé absente), et n'apportait aucun bénéfice réel une fois `openedAt` introduit en base.
- Durées de rétention différentes par type de contenu (ex. les Chats texte n'expirent jamais, seuls les Snaps expirent) : plus fidèle au vrai Snapchat, mais ajoute une branche de logique pour un gain d'authenticité jugé secondaire par rapport à la simplicité d'une règle unique.

## Conséquences
### Positives
- Reproduit fidèlement le système d'icônes de Snapchat, avec un modèle de données complet (contenu inclus).
- Statut sans ambiguïté, sans dépendance à Redis : un seul champ (`openedAt`) et une durée fixe (24h) suffisent.
- Règle unique et simple à documenter, appliquée à tous les types de contenu.

### Négatives
- Les Chats texte expirent au même rythme que les Snaps (24h après ouverture), ce qui est moins fidèle au vrai Snapchat où le texte peut être conservé plus longtemps — accepté comme simplification du V1.
- La logique d'affichage (couleur/forme selon type + sens + statut) reste répartie dans le code client, à documenter clairement.

## Historique
- Version initiale : statut basé sur une clé Redis avec TTL (ADR-003), sans champ `content`/`mediaKey`.
- Révision (revue technique) : ajout de `content`/`mediaKey`, remplacement du mécanisme Redis par `openedAt` (durable), retrait de la dépendance à ADR-003 pour le statut.

## Liens
- ADR liés : ADR-016, ADR-017

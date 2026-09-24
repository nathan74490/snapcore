# ADR-009 — Modèle de statut des messages (couleurs/icônes façon Snapchat)

## Statut
Proposé

## Contexte
SnapCore doit reproduire exactement le système d'icônes colorées de Snapchat dans la liste de discussion, qui indique en un coup d'œil le type de contenu, le sens (envoyé/reçu) et le statut (non ouvert / ouvert / expiré) du dernier message d'une conversation :
- Carré rouge plein : nouveau Snap photo reçu, non ouvert. Carré rouge contour : Snap photo envoyé, ouvert par le destinataire.
- Carré violet plein : nouveau Snap photo + vidéo/son reçu, non ouvert. Carré violet contour : Snap photo + vidéo/son envoyé, ouvert.
- Flèche bleue pleine : nouveau Chat (texte) reçu, non lu. Flèche bleue contour : Chat envoyé, ouvert/lu.
- Gris : Snap ou Chat ouvert puis expiré (plus disponible).

Le modèle initial ne comportait que `contentType`, sans contenu réel (texte ou média) ni mécanisme fiable pour distinguer "jamais ouvert" de "expiré" : les deux cas se traduisaient par une clé Redis absente (TTL écoulé ou clé jamais créée), ce qui est ambigu. Cette révision complète le modèle et lève cette ambiguïté.

## Décision
- Champ `contentType` (`TEXT` | `SNAP_PHOTO` | `SNAP_PHOTO_VIDEO`) sur MESSAGE, qui détermine la couleur (bleu / rouge / violet) et la forme (flèche pour `TEXT`, carré pour les deux types de Snap).
- Contenu du message : `content` (texte, uniquement si `contentType = TEXT`) ou `mediaUrl` (uniquement si `contentType` est un des deux types de Snap, réutilisant le pipeline média R2, ADR-016) — un seul des deux est renseigné selon le type.
- Sens (envoyé/reçu) : déduit en comparant l'expéditeur à l'utilisateur connecté — aucun champ supplémentaire nécessaire.
- Statut (non ouvert / ouvert / expiré) : le champ `openedAt` (nullable, PostgreSQL, mis à jour une seule fois à la première ouverture) est désormais la source de vérité durable de l'ouverture — `null` = jamais ouvert, une valeur = ouvert à cet instant. Redis (ADR-003) ne sert plus qu'à calculer le TTL d'expiration visuelle (gris) à partir de `openedAt` + une durée de rétention courte, jamais à représenter l'état "ouvert" lui-même : ceci évite l'ambiguïté d'une clé Redis absente qui pouvait aussi bien signifier "jamais ouvert" que "expiré".
- Le rendu (couleur, forme, plein/contour/gris) reste entièrement calculé côté application mobile à partir de ces informations ; aucun calcul supplémentaire côté serveur.

## Alternatives
- Un champ `displayColor` calculé et stocké côté serveur : évite un calcul côté client, mais duplique une information déjà déductible d'autres champs, et oblige à recalculer/republier ce champ à chaque changement de statut.
- Déduire le statut uniquement de `createdAt` (sans marqueur d'ouverture séparé) : ne nécessite aucun champ supplémentaire, mais ne résout pas l'ambiguïté — un message jamais ouvert et un message ouvert récemment sont alors indiscernables tant que la fenêtre de rétention n'est pas écoulée.

## Conséquences
### Positives
- Reproduit fidèlement le système d'icônes de Snapchat, avec un modèle de données désormais complet (contenu inclus).
- L'ambiguïté "jamais ouvert" vs "expiré" est levée par un marqueur durable et explicite (`openedAt`).
- Un seul champ ajouté au modèle pour le sens (aucun) ; contenu et statut restent chacun portés par un champ dédié et non dupliqués.

### Négatives
- Introduit une petite exception au principe strict d'ADR-003 (tout ce qui a une durée de vie limitée va dans Redis) : `openedAt` est un horodatage d'événement permanent en base, pas une donnée volatile — jugé d'une nature différente et donc justifié.
- La logique d'affichage (couleur/forme selon type + sens + statut) reste répartie dans le code client, à documenter clairement.

## Liens
- ADR liés : ADR-003, ADR-016

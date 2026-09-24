# ADR-009 — Modèle de statut des messages (couleurs/icônes façon Snapchat)

## Statut
Proposé

## Contexte
SnapCore doit reproduire exactement le système d'icônes colorées de Snapchat dans la liste de discussion, qui indique en un coup d'œil le type de contenu, le sens (envoyé/reçu) et le statut (non ouvert / ouvert / expiré) du dernier message d'une conversation :
- Carré rouge plein : nouveau Snap photo reçu, non ouvert. Carré rouge contour : Snap photo envoyé, ouvert par le destinataire.
- Carré violet plein : nouveau Snap photo + vidéo/son reçu, non ouvert. Carré violet contour : Snap photo + vidéo/son envoyé, ouvert.
- Flèche bleue pleine : nouveau Chat (texte) reçu, non lu. Flèche bleue contour : Chat envoyé, ouvert/lu.
- Gris : Snap ou Chat ouvert puis expiré (plus disponible).

## Décision
Ajouter un champ `contentType` (`TEXT` | `SNAP_PHOTO` | `SNAP_PHOTO_VIDEO`) à l'entité MESSAGE, qui détermine la couleur (bleu / rouge / violet) et la forme (flèche pour `TEXT`, carré pour les deux types de Snap). Le sens (envoyé/reçu) se déduit en comparant l'expéditeur à l'utilisateur connecté — aucun champ supplémentaire nécessaire. Le statut (non ouvert / ouvert / expiré) réutilise le mécanisme déjà prévu de suivi de vue avec expiration automatique (Redis + TTL, voir ADR-003) : plein = non ouvert, contour = ouvert, gris = expiré (TTL écoulé). Le rendu (couleur, forme, plein/contour) est entièrement calculé côté application mobile à partir de ces informations ; aucun calcul supplémentaire côté serveur.

## Alternatives
- Un champ `displayColor` calculé et stocké côté serveur : évite un calcul côté client, mais duplique une information déjà déductible d'autres champs, et oblige à recalculer/republier ce champ à chaque changement de statut.

## Conséquences
### Positives
- Reproduit fidèlement le système d'icônes de Snapchat.
- Aucune nouvelle infrastructure : réutilise le suivi de statut de vue et le TTL déjà prévus par ADR-003.
- Un seul champ ajouté au modèle (`contentType`) ; sens et statut restent déduits, pas dupliqués.

### Négatives
- La logique d'affichage (couleur/forme selon type + sens + statut) est répartie dans le code client, à documenter clairement pour rester cohérente si de nouveaux types de contenu sont ajoutés plus tard.

## Liens
- ADR liés : ADR-003

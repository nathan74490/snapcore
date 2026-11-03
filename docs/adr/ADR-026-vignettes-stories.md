# ADR-026 : Vignettes des stories : fabriquées par l'app, servies par le backend sans compter de vue

## Statut
Accepté

## Contexte
Sur la page Stories, l'anneau de chaque ami doit montrer un aperçu de sa story, comme Snapchat. Or le seul moyen d'obtenir l'image d'un élément de story est `POST /stories/:id/view`, qui renvoie l'URL du média **et enregistre une vue** (ADR-013) : l'app ne peut pas l'appeler pour afficher la page sans fausser le compteur de vues de l'auteur et marquer l'élément comme vu.

Pour une vidéo, il faut de plus une image fixe : le média lui-même (MP4 jusqu'à 60 Mo, ADR-016) n'est pas affichable dans un anneau.

## Décision
**Vignette fabriquée par l'app** au moment de la publication : la photo réduite, ou la première image de la vidéo, ~320 px de côté, en JPEG. Elle suit le flux d'upload existant (ADR-016) avec un usage dédié `STORY_THUMBNAIL` (`image/jpeg`, `image/png` ou `image/webp`, 512 Ko max).

**Publication** (`POST /stories`) : champ facultatif `thumbnailUploadId`.
- La vignette est promue **avant** le média principal, vers la clé privée `story-thumbnails/<storyItemId>` (bucket privé, jamais de CDN), et sa clé est enregistrée dans `StoryItem.thumbnailKey` (null sans vignette, y compris pour les éléments publiés avant cet ADR).
- La vignette est un confort : si sa promotion échoue (upload inconnu, d'un autre usage, trop gros, jamais envoyé...), le backend journalise un avertissement et publie l'élément **sans vignette** plutôt que de refuser la publication.
- Si la promotion du média principal échoue après la copie de la vignette, la vignette est supprimée (best-effort), sur le modèle de la suppression compensatoire d'ADR-016 : aucune ligne ni purge ne la retrouverait sinon.

**Lecture** :
- `GET /stories` : chaque entrée du fil porte `thumbnailUrl`, URL pré-signée de la vignette de l'élément que l'utilisateur verra en premier : le premier élément **non vu** qui a une vignette, sinon le plus récent qui en a une. Champ absent si aucun élément n'a de vignette (l'app affiche alors l'anneau sans image).
- `GET /stories/me` : `thumbnailUrl` par élément, absent sans vignette.
- Ces lectures **n'enregistrent aucune vue** : seule `POST /stories/:id/view` le fait.
- Durée de validité de l'URL : 15 min (`STORY_THUMBNAIL_URL_TTL_SECONDS`), le temps d'afficher la page ; l'app recharge le fil au retour sur la page. Plus longue que la minute de la lecture d'un élément (ADR-009) car l'URL est donnée pour toute une liste, pas à l'ouverture.

**Suppression** : la vignette suit le média partout où il est supprimé : retrait par l'auteur, job de purge à 24 h (ADR-013, ADR-017), suppression par un modérateur (ADR-021) et suppression de compte, où les `thumbnailKey` sont relevées avec les `mediaKey` (ADR-019).

## Alternatives
- **Génération côté serveur** (réduction de la photo avec `sharp`, extraction de la première image de la vidéo avec `ffmpeg`, dans un job après la publication) : l'app n'aurait rien à faire, mais il faudrait installer `sharp` et `ffmpeg` partout où tourne le backend (postes de développement, CI, déploiement), télécharger le média depuis R2 pour le traiter, et ajouter un job de plus avec ses reprises. Écarté : l'app a déjà l'image en main au moment de publier.
- **Réutiliser le média principal comme vignette** (URL pré-signée du média sans compter de vue) : rien à fabriquer pour une photo, mais une photo pleine taille téléchargée pour un anneau de quelques dizaines de pixels, et aucune solution pour une vidéo. Écarté.
- **Ne rien montrer dans l'anneau** (couleur seule) : aucune modification du backend, mais s'éloigne de Snapchat. Écarté.

## Conséquences
### Positives
- L'anneau affiche l'image de la story sans fausser le compteur de vues ni marquer l'élément comme vu.
- Aucune nouvelle dépendance serveur : le flux d'upload, la promotion et la purge existants sont réutilisés.
- Petite image dédiée : le fil reste léger même avec beaucoup d'amis.

### Négatives
- **La vignette dévoile la première image de l'élément sans compter de vue**, comme sur Snapchat : un ami voit un aperçu sans apparaître dans la liste des viewers. Assumé, c'est le comportement attendu.
- Un upload de plus par publication côté app, et une vignette qui dépend du client : une app modifiée peut envoyer une vignette sans rapport avec le média (même limite que le contenu du média lui-même, ADR-016).
- La vignette est promue avant le média principal : si celui-ci échoue, l'upload de la vignette est déjà consommé et l'app doit en renvoyer une pour réessayer (ou publier sans).
- Les éléments publiés avant cet ADR, ou par une version de l'app qui n'envoie pas de vignette, n'en ont pas : l'anneau reste sans image pour eux.

## Liens
- ADR liés : ADR-009, ADR-013, ADR-016, ADR-017, ADR-019, ADR-021

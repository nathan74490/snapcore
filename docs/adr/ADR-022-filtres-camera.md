# ADR-022 — Filtres : filtres couleur choisis sur l'aperçu après la capture, Lenses visage en direct en V2

## Statut
Accepté

## Contexte
Les filtres font partie de l'identité de Snapchat, et ADR-001 justifiait déjà le choix du natif par l'accès performant à la caméra « pour les filtres AR ». Aucun ADR ne décidait pourtant comment ils fonctionnent : quel type de filtre, à quel moment il est choisi, et ce qui est réellement envoyé au destinataire — le média brut avec un « filtre à appliquer » à la lecture, ou un média déjà filtré.

Snapchat distingue deux familles, qui n'interviennent pas au même moment :
- les **filtres** (couleur, ambiance) se choisissent **après** la prise, sur l'aperçu, en glissant à gauche ou à droite ;
- les **Lenses** (oreilles de chien, masques, effets qui suivent le visage) s'appliquent **en direct**, sur la caméra, **avant** la prise.

Le pipeline de capture et d'envoi existe (ADR-011 : CameraX, cache privé, upload R2 via ADR-016) et ne doit pas être remis en cause. Contrainte du projet : aucun frais réel, aucun compte tiers payant.

## Décision
**V1 : filtres couleur, choisis sur l'aperçu après la capture.**
- Un filtre est une **matrice de couleur** (transformation affine RGB : `r' = a·r + b·g + c·b + d`, et de même pour g et b), appliquée sur des couleurs **sRGB**. Catalogue fixe embarqué dans l'app : Normal, Noir & blanc, Sépia, Vintage, Vif, Froid, Chaud, Noir (N&B contrasté). Les matrices sont du code métier pur (`domain/`), testé sans Android.
- **L'écran caméra n'applique aucun filtre** : il reste réservé aux futures Lenses en direct (V2).
- **Sur l'aperçu**, glisser horizontalement passe au filtre suivant (vers la gauche) ou précédent (vers la droite), en boucle ; le nom du filtre s'affiche brièvement, avec un indicateur de position. Le rendu est immédiat : `ColorFilter` Compose sur la photo, effet `RgbMatrix` d'ExoPlayer sur la vidéo en cours de lecture.
- **Le filtre est incrusté dans le média au moment d'envoyer** (« Envoyer à »), dans un **nouveau fichier** du cache privé : la photo est redessinée avec la matrice (`ColorMatrixColorFilter`, orientation EXIF appliquée) puis réencodée en JPEG ; la vidéo est réencodée par **Media3 Transformer** avec la même matrice que celle de l'aperçu (H.264, débit plafonné à 4 Mbit/s, son conservé). Avec le filtre « Normal », une photo est envoyée telle quelle ; une vidéo est tout de même réencodée à 4 Mbit/s, pour rester sous la limite de taille du backend (voir ADR-024 et BUG-001 d'ADR-023).
- **L'original reste intact tant que l'aperçu est ouvert** : revenir de l'écran d'envoi permet de changer de filtre (l'ancien rendu est alors supprimé). Quand l'aperçu est quitté pour de bon (abandon ou Snap envoyé), l'original et le rendu sont supprimés du cache (ADR-011).
- **Ni le backend ni le modèle de données ne connaissent la notion de filtre** : aucun champ `filterId` sur `Message`/`StoryItem`, aucune modification d'API.
- La même matrice donne le même rendu sur l'aperçu et dans le fichier envoyé, pour la photo comme pour la vidéo : Android et Media3 travaillent tous deux sur les couleurs sRGB telles qu'encodées (Media3 : `WORKING_COLOR_SPACE_DEFAULT` pour du SDR).

**V2 (plus tard) : Lenses visage en direct sur l'écran caméra** (masques, oreilles, lunettes qui suivent le visage). Deux pistes à trancher dans un ADR dédié : Snap Camera Kit (rendu de qualité, mais compte développeur Snap et token d'API) ou ML Kit Face Detection (gratuit, mais tout le rendu reste à écrire). Le mécanisme d'effet CameraX en direct (`CameraEffect` ciblant aperçu, photo et vidéo, voir Historique) a été validé sur appareil et servira de point de branchement : une Lens sera ainsi incrustée dans le média dès la capture.

## Alternatives
- **Filtres couleur en direct sur la caméra, avant la capture** (version initiale de cet ADR, voir Historique) : techniquement validé (`androidx.camera.media3:media3-effect`), mais ne correspond pas à Snapchat, où la caméra en direct est réservée aux Lenses et les filtres se choisissent après coup. Écarté.
- **Envoyer le média brut + un `filterId`, appliqué à la lecture par l'app du destinataire** : aucun réencodage côté émetteur, mais oblige toutes les versions de l'app à connaître tous les filtres pour toujours (un filtre retiré casserait l'affichage des anciens Snaps), et ajoute un champ au modèle et à l'API. Écarté.
- **Incruster le filtre dès qu'il est choisi sur l'aperçu** plutôt qu'à l'envoi : réencoderait la vidéo à chaque swipe. Écarté : l'aperçu se contente d'un rendu à l'affichage, l'incrustation n'a lieu qu'une fois, au moment d'envoyer.
- **Filtres côté serveur** : contraire à ADR-016 (le backend ne fait jamais transiter les fichiers) et ajouterait un pipeline de traitement d'image. Écarté.

## Conséquences
### Positives
- Comportement fidèle à Snapchat : filtres après la capture, caméra en direct réservée aux Lenses.
- Aucune modification du backend, du schéma Prisma ni de l'API : le pipeline d'ADR-011/ADR-016 est réutilisé tel quel.
- Aucune dépendance en version alpha : uniquement des modules Media3 stables (`media3-effect`, `media3-transformer`), à la même version que `media3-exoplayer`.
- Le débit plafonné de la vidéo réencodée garde une vidéo filtrée de 60 s autour de 30 Mo, sous la limite du backend.
- Les matrices des filtres et le cycle de vie des fichiers (rendu, changement de filtre, nettoyage) sont testés unitairement.

### Négatives
- **Temps d'attente à l'envoi d'une vidéo filtrée** : le réencodage prend une fraction de la durée de la vidéo (≈ 0,7 s pour 3 s sur un Galaxy A54), affiché « Application du filtre… ». Négligeable pour une photo.
- La photo filtrée est réencodée en JPEG (qualité 90), d'où une légère perte par rapport à l'original — invisible à l'œil sur un Snap.
- Un filtre incrusté ne peut plus être retiré par le destinataire, ni modifié après l'envoi — comme dans Snapchat.
- Pas d'événement analytics « filtre utilisé » tant que PostHog (ADR-008) n'est pas intégré.

## Historique
- Version initiale : filtres couleur appliqués **en direct sur la caméra** via un `CameraEffect` CameraX (`androidx.camera.media3:media3-effect` 1.0.0-alpha04) ciblant aperçu, photo et vidéo. Validé sur Galaxy A54 (aperçu, photo et vidéo filtrés), mais remplacé à la demande du lead développeur pour suivre Snapchat : les filtres se choisissent sur l'aperçu après la capture, et le direct est réservé aux Lenses visage (V2), qui pourront réutiliser ce mécanisme.
- Révision 2 (ADR-024) : une vidéo est réencodée même avec le filtre « Normal » (matrice identité), Camera Kit enregistrant autour de 11,6 Mbit/s.
- Révision 1 (implémentation) : filtres choisis sur l'aperçu, incrustés à l'envoi (Android pour la photo, Media3 Transformer pour la vidéo) ; matrices exprimées en sRGB (et non en RGB linéaire comme supposé initialement : Media3 travaille par défaut dans l'espace de couleur d'origine pour du SDR). Vérifié sur Galaxy A54 : photo et vidéo filtrées identiques à l'aperçu, orientation et son conservés, cache vidé à la fermeture de l'aperçu.

## Liens
- ADR liés : ADR-001, ADR-008, ADR-011, ADR-016

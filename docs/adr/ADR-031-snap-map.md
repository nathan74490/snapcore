# ADR-031 : Snap Map : carte Mapbox, position des amis, mode fantôme et stories géolocalisées

## Statut
Accepté

## Contexte
La Snap Map n'était pas prévue en V1 : ADR-003 la cite comme futur usage de Redis. Dans Snapchat (captures `Snapchat ios Dec 2024`, 269 à 329), c'est le premier onglet de l'app. Elle affiche :
- les Bitmojis des amis à leur dernière position ;
- ma propre position ;
- les snaps publiés sur la carte.

Un réglage de confidentialité accompagne la carte : mode fantôme, ou partage avec « Mes amis », « Mes amis sauf… », « Seulement ces amis… ».

Snapchat y ajoute aussi des lieux, des événements, une heatmap de snaps publics, des poses, une maison et des « Footsteps ». SnapCore n'a pas de contenu public, et ces fonctions demandent des partenaires ou des données qu'il n'a pas.

## Décision
**Périmètre V1** :
- la position de mes amis, avec leur avatar (ADR-028) ;
- ma position, avec le mode fantôme et le choix de qui la voit ;
- les stories de mes amis, épinglées là où elles ont été publiées.

Pas de lieux, d'événements, de heatmap ni de snaps publics.

**Accès** : un onglet **Map**, à gauche du Chat dans la barre du bas (Map / Chat / Caméra / Stories). Sur la carte, les glissements déplacent la carte ; on en sort par la barre d'onglets. La carte n'est créée que lorsque son onglet ou le Chat est affiché.

**Carte** : SDK **Mapbox Maps pour Android** (11.32, extension Jetpack Compose), style `Standard`.
- Le jeton **public** (`pk.…`) est lu dans `local.properties` (`mapboxAccessToken`), qui n'est pas commité. Sans jeton, l'onglet affiche un message à la place de la carte.
- Le SDK se télécharge depuis le dépôt Maven de Mapbox, sans jeton secret.
- Les avatars et les stories sont des `ViewAnnotation` Compose.

**Position du téléphone** :
- Elle vient du Fused Location Provider. Permission « pendant l'utilisation » seulement (`ACCESS_FINE_LOCATION` et `ACCESS_COARSE_LOCATION`), **jamais en arrière-plan**.
- Elle est demandée depuis la carte, après une explication.
- Elle est envoyée (`PUT /me/location`) à l'ouverture de l'app, puis toutes les 30 s pendant que la carte est affichée, au plus une fois par minute.
- Elle n'est **jamais envoyée en mode fantôme** : ni par l'app, ni stockée par le backend.
- Le réglage est mémorisé par compte, pour qu'un changement de compte sur le même téléphone ne réutilise pas celui du compte précédent.

**Données** :
- **Dernière position** : donnée éphémère, en Redis seulement (ADR-003). Clé `location:<userId>`, TTL de **8 h** : sans nouvelle position, l'ami disparaît de la carte.
- **Réglage de partage** : donnée durable, en base.
  - `User.locationSharing` : `GHOST` (par défaut), `ALL_FRIENDS`, `FRIENDS_EXCEPT` ou `ONLY_FRIENDS`.
  - `LocationShareRule(ownerId, friendId)` : amis exclus ou autorisés. La liste est conservée quand on change de mode.
  - Passer en mode fantôme supprime immédiatement la position.
- **Stories** : `StoryItem.latitude/longitude`, facultatifs.
  - La position est envoyée à la publication (`POST /stories`), hors mode fantôme uniquement.
  - Elle est arrondie à 3 décimales (environ 100 m) et disparaît avec la story au bout de 24 h (ADR-013, ADR-017).

**Visibilité** : une seule règle, côté backend, pour la position et pour les stories sur la carte. `viewer` voit `owner` si :
- leur amitié est acceptée (ADR-012), sans blocage ;
- `owner` est actif (ni supprimé ni suspendu, ADR-019, ADR-021) ;
- `owner` n'est pas en mode fantôme ;
- sa liste l'autorise : absent de la liste pour « sauf », présent pour « seulement ».

La règle est appliquée à chaque lecture. Si un ami passe en mode fantôme, ses stories quittent aussi la carte.

**Routes** :
- `GET` et `PUT /me/location-sharing` : `{ mode, friendIds }`, liste limitée aux amis actuels, 2000 au plus ;
- `PUT /me/location` ;
- `GET /map/friends` : `{ user, latitude, longitude, updatedAt }` ;
- `GET /map/stories` : éléments de story avec position, vignette et `viewed`, comme `GET /stories` (ADR-026).

Toucher un pin ouvre le lecteur de stories habituel, à partir du premier élément non vu.

**Interface** (captures 269, 308 et 319 à 322) :
- en-tête : mon avatar, recherche, pastille de l'état du partage, réglages ;
- ma position marquée « Moi », grisée avec un fantôme en mode fantôme ;
- toucher un ami ouvre une fiche (fraîcheur « Il y a 12 min », bouton Chat) ;
- bouton pour recentrer la carte sur ma position ;
- premier cadrage sur ma position ; sans elle (localisation non activée), sur mes amis et leurs stories, puis sur moi si j'active la localisation ensuite ;
- un pin de story à moins de 500 m de l'avatar d'un ami ou d'un autre pin s'écarte en cercle autour de son point, pour ne pas le cacher ;
- bandeaux pour activer la localisation ou sortir du mode fantôme.

Désactiver le mode fantôme fait d'abord choisir l'audience (« Désactiver le mode fantôme ? »). Les modes « sauf » et « seulement » ouvrent la liste des amis. Les réglages s'appliquent tout de suite à l'écran et reviennent en arrière en cas d'échec.

**Analytics** (ADR-008) : écran `Map` et événement `location_sharing_changed { mode }`.

**Rafraîchissement** : par rechargement toutes les 30 s tant que l'onglet est visible, pas par websocket.

## Alternatives
- **MapLibre + OpenStreetMap** : gratuit et sans compte, mais rendu moins abouti. Écarté au profit de Mapbox, choisi pour son rendu. Le quota gratuit de Mapbox (chargements de carte mobiles mensuels) suffit au projet.
- **Google Maps SDK** : clé Google Cloud avec facturation activée. Écarté.
- **Position en arrière-plan** (comme l'option de Snapchat) : position toujours à jour, mais permission sensible, consommation de batterie, et validation Play Store plus stricte. Écarté : la position date de la dernière utilisation de l'app, ce que la fiche affiche.
- **Dernière position en base** : un historique de positions serait une donnée sensible conservée sans raison. Écarté au profit de Redis avec TTL (ADR-003).
- **Mises à jour en temps réel par websocket** (ADR-006) : plus fluide, mais une diffusion à chaque déplacement vers chaque ami autorisé. Écarté pour la V1 : un rechargement toutes les 30 s suffit pour une position mise à jour au plus chaque minute.
- **Partager la position des stories même en mode fantôme** : Snapchat publie sur la carte des snaps publics, sans lien avec la position de l'auteur. Écarté : SnapCore n'a que des stories entre amis, et une story géolocalisée révèle où l'on était.

## Conséquences
### Positives
- Confidentialité par défaut :
  - chaque compte commence en mode fantôme ;
  - rien n'est envoyé sans action de l'utilisateur ;
  - aucune position en arrière-plan ;
  - aucune position conservée au-delà de 8 h.
- Une seule règle de visibilité, testée côté backend, pour la position et les stories.
- La carte réutilise l'avatar (ADR-028), le lecteur de stories (ADR-013) et les vignettes (ADR-026).

### Négatives
- Nouvelle dépendance à un service tiers, avec quota. Le jeton public est intégré à l'APK : il faut le restreindre (URL ou application) dans le compte Mapbox avant toute diffusion.
- La position d'un ami peut dater de plusieurs heures (pas d'arrière-plan) ; la fiche affiche cette ancienneté.
- La carte pèse sur l'APK (bibliothèques natives Mapbox) et sur la batterie quand l'onglet est ouvert.
- `PUT /me/location` n'a pas de limite de débit côté backend (déjà noté dans ADR-027 et ADR-029).

## Liens
- ADR liés : ADR-003, ADR-006, ADR-008, ADR-012, ADR-013, ADR-017, ADR-019, ADR-021, ADR-026, ADR-028

# ADR-024 : Lenses visage en direct : Snap Camera Kit sur l'écran caméra

## Statut
Accepté

## Contexte
ADR-022 a réservé l'écran caméra en direct aux **Lenses** (effets qui suivent le visage : oreilles de chien, lunettes…), les filtres couleur se choisissant après la capture, sur l'aperçu. Il laissait ouvert le choix de la technologie : Snap Camera Kit, ou une détection de visage (ML Kit) avec des accessoires dessinés par l'app.

Pour la V1 des Lenses, deux Lenses sont attendues : **Chien** et **Lunettes de soleil**.

## Décision
- **Snap Camera Kit** (SDK Android `com.snap.camerakit`, version 1.50.0) fournit les Lenses : rendu 3D, suivi du visage et Lenses de même qualité que dans Snapchat.
- **Camera Kit pilote toute la caméra** : l'écran caméra n'utilise plus `LifecycleCameraController` (ADR-011), mais une `Session` Camera Kit alimentée par `CameraXImageProcessorSource` (module `support-camerax`). Aperçu, bascule avant/arrière, flash, photo et vidéo passent par cette session, avec ou sans Lens active : un seul pipeline caméra.
- **La Lens est incrustée dans le média dès la capture** : `takePhoto` renvoie l'image rendue avec la Lens, `takeVideo` la vidéo rendue. Le reste du parcours ne change pas : aperçu, filtre couleur éventuel (ADR-022), upload R2 (ADR-016). Ni le backend ni le modèle de données ne connaissent la notion de Lens.
- **Les Lenses sont gérées dans le portail Camera Kit** (Lens Scheduler), dans un groupe de Lenses ; l'app charge **tout le groupe** au démarrage de la caméra. Ajouter ou retirer une Lens ne demande donc aucune mise à jour de l'app.
- **Choix d'une Lens, comme dans Snapchat** : les Lenses forment un carrousel horizontal centré sur le bouton de capture. Au repos, il est sur sa première position (« aucune Lens », confondue avec l'anneau de capture) et les premières Lenses dépassent à droite. On le fait défiler, ou on touche une Lens : le défilement s'aimante sur une position, et **la Lens qui s'arrête au centre apparaît dans l'anneau de capture et devient la Lens active** ; ses voisines rapetissent avec la distance au centre. Un arc tourne autour de l'anneau pendant le chargement, le nom de la Lens s'affiche en haut à gauche, et la barre de navigation laisse place à une barre « ✕ | Pour vous » dont le ✕ ramène le carrousel au repos et retire la Lens.
- Une Lens qui préfère la caméra frontale (`facingPreference = FRONT`, cas des Lenses visage) bascule automatiquement sur la caméra frontale, comme dans Snapchat.
- **Identifiants hors du dépôt** : le token d'API Camera Kit et l'ID du groupe de Lenses sont lus dans `apps/mobile/local.properties` (ignoré par git), puis injectés au build (`<meta-data com.snap.camerakit.api.token>` du manifeste, `BuildConfig.CAMERA_KIT_LENS_GROUP_ID`). Le token **Staging** est utilisé : le projet n'est jamais publié (ADR-001).
- **Débit vidéo** : Camera Kit enregistre en 1080×2340 autour de 11,6 Mbit/s (mesuré sur Galaxy A54), soit ≈ 87 Mo pour 60 s, au-delà de la limite de 60 Mo du backend (ADR-016). Le débit d'enregistrement n'étant pas réglable par l'API publique de `CameraXImageProcessorSource`, toute vidéo est réencodée à 4 Mbit/s par Media3 Transformer avant l'envoi, même sans filtre couleur (ADR-022).
- Les gestes et règles de capture d'ADR-011 sont conservés : tap = photo, appui maintenu = vidéo de 60 s maximum, fichiers dans le cache privé, jamais dans la galerie.

## Alternatives
- **ML Kit Face Detection + accessoires dessinés par l'app** : gratuit, sans compte tiers et sans filigrane, la caméra CameraX existante est conservée. Mais tout le rendu est à écrire (positionnement 2D sur les points du visage, pas de vraie 3D ni d'animation), pour un résultat nettement en dessous des Lenses Snapchat. Écarté au profit de la fidélité.
- **`CameraActivity` « clé en main » de Camera Kit** (module `support-camera-activity`) : intégration en quelques lignes, mais c'est une activité séparée à l'interface imposée, hors de la navigation Compose et du style de l'app. Écarté : on garde notre écran, avec la session Camera Kit à l'intérieur.
- **Garder `LifecycleCameraController` sans Lens et ne basculer sur Camera Kit qu'avec une Lens active** : deux pipelines caméra à maintenir, et une coupure visible de l'aperçu à chaque bascule. Écarté.

## Conséquences
### Positives
- Lenses de qualité Snapchat (3D, suivi du visage, animations), sans moteur de rendu à écrire.
- Catalogue de Lenses modifiable depuis le portail, sans nouvelle version de l'app.
- Le parcours après la capture (aperçu, filtres d'ADR-022, envoi) est inchangé.

### Négatives
- **Dépendance à un service tiers et à un compte développeur Snap** : **la caméra elle-même dépend de Camera Kit**. Si le token est refusé (token invalide, ou package `com.snapcore.app` non autorisé pour ce token dans le portail), le SDK ferme sa session et l'aperçu reste noir : l'app affiche alors un message explicite, sans jamais afficher le message brut du SDK, qui contient le token. Le comportement sans réseau (Lenses non téléchargées) reste à vérifier sur appareil.
- **Filigrane** : avec le token Staging, Snap ajoute un filigrane à l'image de la caméra, qui se retrouve sur les photos et vidéos capturées. Le token Production est réservé aux apps validées par Snap.
- **Poids de l'app et coût de calcul** : le SDK et le rendu des Lenses alourdissent l'APK et consomment davantage de CPU/GPU qu'un aperçu CameraX simple ; la session doit être fermée dès que l'écran caméra disparaît.
- **CameraX en deux versions** : Camera Kit 1.50.0 est compilé contre CameraX 1.4.2, l'app utilise CameraX 1.6.2 (résolu par Gradle vers 1.6.2). À revérifier sur appareil à chaque montée de version de l'un ou de l'autre.
- Le token est embarqué dans l'APK (inévitable pour un SDK client) ; il reste hors du dépôt git.
- ADR-011 n'est plus exact sur un point : la capture ne passe plus par `LifecycleCameraController`. Ses autres décisions (gestes, durée, cache privé, nettoyage) restent valables.

## Historique
- Révision 1 : choix de la Lens refait sur le modèle de Snapchat (carrousel centré sur le bouton de capture, sélection au centre, barre « ✕ | Pour vous »), d'après un enregistrement d'écran de l'app officielle ; vérifié sur Galaxy A54 (défilement, sélection par toucher, ✕, photo avec Lens).
- Version initiale (implémentation) : vérifié sur Galaxy A54 avec le groupe de Lenses d'exemple de Snap (token Staging) : aperçu en direct, Lens incrustée dans la photo et dans la vidéo (son conservé), filigrane « Camera Kit Staging » présent. Le package `com.snapcore.app` doit être autorisé pour le token dans le portail, faute de quoi Camera Kit refuse la session (`UnauthorizedApplicationException`).

## Liens
- ADR liés : ADR-001, ADR-011, ADR-016, ADR-022

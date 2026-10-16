# ADR-008 — PostHog (cloud) comme outil d'analytics, instrumenté côté app et côté backend

## Statut
Accepté

## Contexte
SnapCore doit permettre de suivre l'usage réel de l'app (écrans visités, snaps envoyés, stories vues, adoption des fonctionnalités) dans une logique de "faire comme si l'app allait être publiée". L'objectif exprimé est de pousser les statistiques le plus loin possible, tout en gardant le principe du projet : aucun frais réel, aucune infrastructure supplémentaire à maintenir.

## Décision
Utiliser PostHog en version cloud (offre gratuite) comme outil d'analytics, instrumenté à la fois côté application mobile (SDK PostHog Android) et côté backend (SDK PostHog Node dans NestJS), pour obtenir une vision bout-en-bout : les événements clients (ouverture d'écran, snap envoyé, story vue, filtre utilisé) et les événements qui n'existent que côté serveur (abonnement SnapCore+ activé, vidéo Spotlight publiée après modération, ami accepté).

## Alternatives
- Firebase Analytics : gratuit et illimité, déjà présent dans le projet via Firebase Cloud Messaging (un seul compte Firebase), mais moins riche que PostHog sur les funnels, cohortes, heatmaps et feature flags.
- PostHog auto-hébergé : contrôle total des données, mais nécessite un serveur supplémentaire à faire tourner en continu — de la complexité et un risque de coût contraire au principe du projet.
- Mixpanel / Amplitude : très complets, mais offres gratuites plus limitées en volume ou en fonctionnalités, et un compte séparé de plus à gérer.

## Conséquences
### Positives
- Vision bout-en-bout de l'usage (client + serveur), pas seulement ce que fait le téléphone.
- Fonctionnalités avancées disponibles dès le départ : funnels, cohortes, heatmaps, feature flags — utilisables plus tard par exemple pour piloter l'activation progressive de Bitmoji, Lenses, etc.
- Aucun frais réel dans les limites de l'offre gratuite cloud ; aucune infrastructure à maintenir.

### Négatives
- Le session replay de PostHog sur Android est encore en bêta côté SDK, donc moins mature que sur web.
- Deux points d'intégration à maintenir (SDK mobile et SDK backend) plutôt qu'un seul.
- Dépendance à un service tiers en plus de la stack déjà choisie (Firebase reste utilisé uniquement pour les notifications push).

## Historique
- Révision 1 (implémentation) : instrumentation réelle, avec un port `Analytics` de chaque côté (adaptateur PostHog, aucun envoi sans clé) :
  - **identité** : l'id interne du compte (`User.id`), le même dans l'app et le backend ; jamais l'email ni le pseudo. `identify` à l'ouverture de session, `reset` à la déconnexion ;
  - **app (SDK Android)** : écrans visités (route de navigation, ou onglet Chat / Caméra / Stories de l'accueil), `snap_captured` (photo/vidéo, avec ou sans Lens), `lens_selected`, `filter_applied`, `checkout_started` ; cycle de vie de l'app ; **pas d'enregistrement de session**, pour que la caméra et les snaps ne quittent jamais le téléphone par ce canal ;
  - **backend (SDK Node)** : `user_registered`, `friend_request_accepted`, `chat_sent`, `snap_sent`, `snap_opened`, `snap_replayed` (revoir un snap déjà vu, avantage SnapCore+), `story_published`, `story_viewed`, et le cycle de vie de SnapCore+ reçu par webhook (`snapcoreplus_started`, `_renewed`, `_cancelled`, `_expired`, ADR-025) ;
  - configuration : `POSTHOG_API_KEY` / `POSTHOG_HOST` (backend), `posthogApiKey` / `posthogHost` dans `local.properties` (app), cloud UE par défaut. Les envois partent par lots en arrière-plan : une panne de PostHog ne bloque aucune requête.
  - Le « Spotlight publié après modération » cité dans la Décision n'existe pas en V1 (fonctionnalité repoussée), il n'est donc pas mesuré.

## Liens
- ADR liés : aucun

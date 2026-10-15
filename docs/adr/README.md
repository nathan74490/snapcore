# ADR — SnapCore

Liste des Architecture Decision Records du projet.

| ADR | Titre | Statut |
|-----|-------|--------|
| [ADR-001](./ADR-001-mobile-natif.md) | Application mobile native Android (Kotlin + Jetpack Compose) plutôt que cross-platform | Proposé |
| [ADR-002](./ADR-002-archi-hexagonale-backend.md) | Architecture hexagonale pour le backend NestJS | Proposé |
| [ADR-003](./ADR-003-separation-ephemere-durable.md) | Séparation des données éphémères (Redis) et durables (PostgreSQL) | Proposé |
| [ADR-004](./ADR-004-prisma-orm.md) | Prisma comme ORM du backend et du backoffice | Proposé |
| [ADR-005](./ADR-005-monorepo-turborepo.md) | Monorepo unique piloté par Turborepo, application mobile hors périmètre | Proposé |
| [ADR-006](./ADR-006-realtime-socketio-redis.md) | Passerelle temps réel Socket.io + pub/sub Redis pour le chat | Proposé |
| [ADR-007](./ADR-007-snapcoreplus-revenuecat-stripe.md) | SnapCore+ : RevenueCat + Stripe (mode test) comme moyen de paiement | Proposé |
| [ADR-008](./ADR-008-analytics-posthog.md) | PostHog (cloud) comme outil d'analytics, instrumenté côté app et côté backend | Proposé |
| [ADR-009](./ADR-009-statut-messages-couleurs.md) | Modèle de message complet et statut (couleurs/icônes façon Snapchat) | Proposé |
| [ADR-010](./ADR-010-onboarding-sans-verification.md) | Onboarding : téléphone et email collectés, email vérifié via Firebase, téléphone non vérifié | Proposé |
| [ADR-011](./ADR-011-camera-capture-cameraX.md) | Écran caméra : capture CameraX, geste tap/appui maintenu, nettoyage local après upload | Proposé |
| [ADR-012](./ADR-012-gestion-amis.md) | Gestion des amis : recherche par pseudo en V1, Snapcode + meilleur ami + flammes en V2 | Proposé |
| [ADR-013](./ADR-013-story-model.md) | Story : un STORY_ITEM par snap ajouté, expiration et vues réutilisant les mécanismes existants | Proposé |
| [ADR-014](./ADR-014-profil-modifiable.md) | Profil simple avec photo, entièrement modifiable (y compris le pseudo) | Proposé |
| [ADR-015](./ADR-015-authentification-firebase.md) | Authentification : Firebase Authentication (email/mot de passe) avec vérification d'email | Proposé |
| [ADR-016](./ADR-016-stockage-medias-r2.md) | Stockage des médias : Cloudflare R2, upload via préfixe temporaire, CDN public pour le profil uniquement | Proposé |
| [ADR-017](./ADR-017-purge-medias-bullmq.md) | Purge automatique des Snaps expirés : job BullMQ, exception SnapCore+ | Proposé |
| [ADR-018](./ADR-018-notifications-push-fcm.md) | Notifications push : Firebase Cloud Messaging, suivi des tokens et de la présence | Proposé |
| [ADR-019](./ADR-019-suppression-compte-cascade.md) | Suppression de compte : Firebase, R2 et cascade sur les données liées | Proposé |
| [ADR-020](./ADR-020-authentification-backoffice.md) | Authentification du backoffice : comptes modérateurs internes, distincts des comptes Firebase | Proposé |
| [ADR-021](./ADR-021-moderation-backoffice.md) | Modération : signalements, suspension de compte, suppression ciblée de contenu | Proposé |
| [ADR-022](./ADR-022-filtres-camera.md) | Filtres : filtres couleur choisis sur l'aperçu après la capture, Lenses visage en direct en V2 | Proposé |
| [ADR-023](./ADR-023-registre-anomalies.md) | Registre des anomalies détectées hors du périmètre en cours, à traiter plus tard | Proposé |
| [ADR-024](./ADR-024-lenses-camera-kit.md) | Lenses visage en direct : Snap Camera Kit sur l'écran caméra | Proposé |
| [ADR-025](./ADR-025-paywall-snapcoreplus.md) | Paywall SnapCore+ : Web Purchase Link RevenueCat, webhook vers le backend | Proposé |

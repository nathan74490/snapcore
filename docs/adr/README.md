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
| [ADR-009](./ADR-009-statut-messages-couleurs.md) | Modèle de statut des messages (couleurs/icônes façon Snapchat) | Proposé |
| [ADR-010](./ADR-010-onboarding-sans-verification.md) | Onboarding : téléphone et email collectés, sans système de vérification par code | Proposé |
| [ADR-011](./ADR-011-camera-capture-cameraX.md) | Écran caméra : capture CameraX, geste tap/appui maintenu, nettoyage local après upload | Proposé |

# SnapCore

Monorepo Turborepo pour le projet SnapCore (cahier des charges : voir la présentation dédiée).

## Structure

- `apps/backend` : API NestJS (architecture hexagonale)
- `apps/backoffice` : Interface de modération, Next.js (MVC)
- `apps/mobile` : Application Android native, Kotlin + Jetpack Compose (Gradle, hors du pipeline Turborepo)
- `packages/types` : Types métier partagés entre `backend` et `backoffice`
- `packages/db` : Schéma Prisma partagé
- `docs/adr` : Décisions d'architecture (ADR)

## Commandes

```bash
npm install
npm run dev      # démarre backend + backoffice en parallèle (Turborepo)
npm run build
npm run lint
npm run test
```

L'app mobile (`apps/mobile`) se build indépendamment avec Gradle (`./gradlew build`), en dehors de Turborepo.

## Crédits

- Avatars dessinés (ADR-028) : style [Avataaars](https://avataaars.com) de Pablo Stanley, licence « Free for personal and commercial use », rendu avec [DiceBear](https://www.dicebear.com) (MIT).

# ADR-005 — Monorepo unique piloté par Turborepo, application mobile hors périmètre

## Statut
Proposé

## Contexte
Les formateurs imposent un seul dépôt Git contenant l'intégralité du projet (mobile, backend, backoffice). Le backend et le backoffice partagent des types et un schéma de données en TypeScript, ce qui posait un problème de duplication et de synchronisation manuelle s'ils étaient développés dans des dépôts séparés.

## Décision
Un seul dépôt Git (`snapcore/`) organisé avec Turborepo pour orchestrer uniquement les paquets TypeScript (`apps/backend`, `apps/backoffice`, `packages/types`, `packages/db`). L'application mobile (`apps/mobile`, Kotlin/Gradle) vit dans le même dépôt, mais garde son propre système de build, non piloté par Turborepo.

## Alternatives
- Trois dépôts séparés (mobile, backend, backoffice) : ne respecte pas la contrainte des formateurs d'un seul dépôt.
- Nx à la place de Turborepo, avec un plugin communautaire Gradle pour aussi mettre en cache le build Android : techniquement possible, mais complexité de configuration plus élevée pour un gain limité, le bénéfice de mise en cache et de partage de types ne concernant de toute façon que le TypeScript.

## Conséquences
### Positives
- Un seul dépôt à cloner et à noter, conforme à la contrainte pédagogique.
- Cache de build et partage de types instantané entre backend et backoffice.

### Négatives
- `apps/mobile` n'est pas géré par les mêmes outils que le reste du dépôt (deux systèmes de build à connaître, potentiellement deux pipelines CI).

## Liens
- ADR liés : ADR-004

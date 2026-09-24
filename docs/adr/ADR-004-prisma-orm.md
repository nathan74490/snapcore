# ADR-004 — Prisma comme ORM du backend et du backoffice

## Statut
Proposé

## Contexte
Le backend NestJS et le backoffice Next.js doivent tous deux accéder à PostgreSQL, avec des types cohérents entre les deux applications, dans un contexte d'apprentissage où la simplicité de prise en main compte autant que la rigueur d'architecture.

## Décision
Utiliser Prisma comme ORM partagé (paquet `packages/db`), avec un schéma unique généré une fois et consommé par les deux applications.

## Alternatives
- Drizzle : requêtes plus proches du SQL brut, plus performant à l'exécution (pas de moteur de requête séparé), mais pas d'équivalent à Prisma Studio et exige plus d'aisance en SQL pour les requêtes complexes.
- SQL brut / query builder manuel : contrôle total mais aucune vérification de types, plus sujet aux erreurs, peu adapté à un projet dont l'objectif est d'apprendre l'architecture plutôt que l'optimisation SQL.

## Conséquences
### Positives
- Client typé généré automatiquement, partagé entre les deux applications.
- Prisma Studio pour inspecter visuellement les données pendant le développement.
- Migrations gérées automatiquement.

### Négatives
- Couche d'abstraction supplémentaire ; performances légèrement en retrait par rapport à des requêtes SQL optimisées à la main.

## Liens
- ADR liés : ADR-002

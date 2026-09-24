# ADR-002 — Architecture hexagonale pour le backend NestJS

## Statut
Proposé

## Contexte
Le backend centralise toute la logique métier de SnapCore (comptes, amis, snaps, stories, modération) et doit rester facile à faire évoluer et à tester, tout en accueillant plusieurs adaptateurs (API REST, temps réel Socket.io, jobs BullMQ).

## Décision
Organiser le backend NestJS selon une architecture hexagonale (ports et adaptateurs) : le cœur métier (use cases) au centre, ignorant tout détail technique ; des ports définissant les besoins du domaine ; des adaptateurs (Prisma, Socket.io, contrôleurs REST) branchés en périphérie.

## Alternatives
- Architecture en couches classique (MVC / service-repository direct) : plus rapide à mettre en place au départ, mais couplage plus fort entre logique métier et détails techniques (ORM, framework), tests plus difficiles à isoler.

## Conséquences
### Positives
- Logique métier testable sans dépendance à une vraie base de données.
- Un changement de détail technique (ORM, nouvelle API) ne touche pas le cœur métier.
- Ajout facilité de nouveaux adaptateurs.

### Négatives
- Plus de code de mise en place (interfaces, ports) qu'une architecture en couches directe.
- Courbe d'apprentissage pour l'équipe.

## Liens
- ADR liés : ADR-004

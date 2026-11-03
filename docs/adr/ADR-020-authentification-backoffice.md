# ADR-020 : Authentification du backoffice : comptes modérateurs internes, distincts des comptes Firebase

## Statut
Accepté

## Contexte
Le backoffice (`apps/backoffice`, Next.js) est réservé à l'équipe Alpvengers pour la modération. Ce ne sont pas des utilisateurs de l'app, mais des membres de l'équipe. Aucun mécanisme d'authentification n'existe encore pour eux : ADR-015 couvre uniquement les comptes Firebase des utilisateurs finaux, et ADR-004 se contente d'indiquer que le backoffice partage Prisma/PostgreSQL avec le backend, sans préciser comment un modérateur s'y connecte.

## Décision
- **Nouveau modèle `Moderator`** dans le schéma Prisma partagé (ADR-004) : `id`, `email` (unique), `passwordHash`, `createdAt`. Une identité entièrement séparée de `User` : un membre de l'équipe qui teste aussi l'app comme utilisateur final a un compte Firebase (`User`) et, si besoin, un compte `Moderator` distinct, sans lien entre les deux.
- **Aucune auto-inscription** : les comptes `Moderator` sont créés manuellement (script de seed ou insertion directe), cohérent avec une poignée de comptes internes plutôt qu'un volume utilisateur.
- **Mot de passe hashé** (bcrypt ou argon2id), jamais stocké en clair, jamais partagé avec un mot de passe Firebase existant.
- **Session gérée par NextAuth.js (Credentials provider)** côté `apps/backoffice` : cookie de session côté serveur Next.js, pas de jeton exposé au navigateur contenant autre chose qu'un identifiant de session.
- **Un seul rôle en V1** : tout compte `Moderator` a accès à l'ensemble des actions de modération décrites en ADR-021, sans niveau de permission différencié.
- **Pas de réinitialisation de mot de passe en V1** : un modérateur qui oublie son mot de passe dépend d'une intervention directe en base par un autre membre de l'équipe.
- **Pas de MFA en V1** : acceptable pour une poignée de comptes internes sur un projet sans exposition réelle, mais à revoir si le backoffice devait un jour porter de vraies données sensibles.

## Alternatives
- Réutiliser Firebase Authentication avec un rôle admin (custom claim ou champ `isModerator` sur `User`) : écarté, car cela mélangerait l'identité "utilisateur de l'app" et "membre de l'équipe qui modère", deux rôles réellement distincts dans la réalité du projet.
- Un compte unique partagé via variable d'environnement : écarté malgré sa simplicité, car ADR-021 s'appuie sur l'attribution d'une action de modération à un compte précis (qui a résolu ce signalement, qui a suspendu ce compte), ce qu'un compte partagé rendrait impossible.
- Un fournisseur d'identité externe (Auth0, Clerk, etc.) : écarté, coût et complexité disproportionnés pour quelques comptes internes sur un projet sans budget réel.

## Conséquences
### Positives
- Séparation nette entre identité utilisateur final (Firebase, ADR-015) et identité membre de l'équipe (PostgreSQL) : aucune confusion possible entre les deux.
- Chaque action de modération peut être attribuée à un compte précis (ADR-021), sans infrastructure supplémentaire : même PostgreSQL que le reste du projet.
- Coût nul, cohérent avec la contrainte du projet.

### Négatives
- Pas de MFA ni de politique de rotation de mot de passe en V1.
- Pas de flux de récupération de mot de passe en self-service : dépend d'un accès direct à la base.
- Un seul niveau de rôle : impossible de distinguer un modérateur junior d'un administrateur capable de créer d'autres comptes `Moderator`, repoussé à une V2 si le besoin apparaît.

## Historique
- Version initiale.

## Liens
- ADR liés : ADR-004, ADR-015, ADR-021

# apps/backoffice

Backoffice de modération SnapCore, Next.js App Router (ADR-020, ADR-021). Réservé aux modérateurs :
pas d'inscription, les comptes se créent en ligne de commande.

## Démarrer en local

```bash
cp apps/backoffice/.env.example apps/backoffice/.env.local   # puis renseigner les secrets
npm run create-moderator -w @snapcore/backoffice -- moi@exemple.com   # demande le mot de passe (12 car. min.)
npm run dev -w @snapcore/backoffice                                    # http://localhost:3001
```

Le backend doit tourner (`BACKEND_URL`) avec le même `ADMIN_API_SECRET`. Relancer `create-moderator`
avec un email existant réinitialise son mot de passe.

## Fonctionnement

- **Connexion** : NextAuth, identifiants email + mot de passe (bcrypt), session JWT de 8 h. Chaque page
  et chaque action serveur revérifie que le modérateur existe encore en base.
- **Lecture** : directement dans PostgreSQL via Prisma (`src/lib/queries.ts`) : tableau de bord, file des
  signalements, recherche et fiche d'un compte (messages, stories, statut).
- **Actions** : jamais en écriture directe. Les actions serveur (`src/app/actions.ts`) appellent l'API
  admin du backend (`src/lib/admin-api.ts`, en-tête `x-admin-secret`), qui applique les effets de bord :
  suppression R2, purge BullMQ, temps réel. Actions disponibles : suspendre ou réactiver un compte,
  retirer un message, supprimer un élément de story, clore un signalement (résolu ou rejeté).

## Tests

```bash
npm test -w @snapcore/backoffice
```

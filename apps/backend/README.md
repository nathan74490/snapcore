# apps/backend

API NestJS de SnapCore, en architecture hexagonale (ADR-002).

## Démarrer en local

```bash
docker compose up -d                        # à la racine : Postgres + Redis
cp apps/backend/.env.example apps/backend/.env
npm run migrate:deploy -w @snapcore/db      # applique les migrations (DATABASE_URL requis)
npm run dev -w @snapcore/backend
```

Firebase Admin (ADR-015) : renseigner `FIREBASE_SERVICE_ACCOUNT_PATH` (JSON du compte de
service du projet Firebase) ou `GOOGLE_APPLICATION_CREDENTIALS`.

## Tests

```bash
npm test -w @snapcore/backend                    # unitaires, sans infra
npm run test:integration -w @snapcore/backend    # contre le Postgres de docker compose (vide les tables !)
```

Les cas d'usage sont testés sans base ni Firebase, avec les dépôts en mémoire de `src/test/`
(qui reproduisent les contraintes d'unicité et `CHECK` du schéma). `app.http.spec.ts` monte
l'application Nest complète avec ces mêmes doublures.

## Organisation

```
src/
  shared/                  erreurs de domaine, horloge, Prisma, filtre HTTP
  modules/<contexte>/
    domain/                règles métier pures (aucune dépendance technique)
    application/           cas d'usage + ports (interfaces des dépendances)
    infrastructure/        adaptateurs : Prisma, Firebase…
    interface/http/        contrôleurs REST, DTO, garde d'authentification
    <contexte>.module.ts   câblage ports → adaptateurs
```

Les cas d'usage sont de simples classes TypeScript, sans décorateur Nest : le câblage est fait
dans le module (`useFactory`).

## API disponible

Toutes les routes exigent `Authorization: Bearer <ID token Firebase>` et un compte actif, sauf
mention contraire. Un compte dont la suppression est demandée est traité comme inexistant (ADR-019).

| Méthode | Route | Description | ADR |
|---|---|---|---|
| GET | `/health` | Public | |
| POST | `/auth/register` | Crée la ligne `User` (token seul, idempotent, `checkRevoked`) | 015 |
| GET | `/me` | Mon profil | 014 |
| PATCH | `/me` | Modifier pseudo / téléphone (409 si pris) | 014 |
| POST | `/me/email/sync` | Recopie l'email confirmé côté Firebase | 014 |
| POST | `/media/uploads` | URL de PUT signée vers `tmp/` `{ purpose, contentType, contentLength }` | 016 |
| PUT | `/me/photo` | Confirme un upload `PROFILE_PHOTO` `{ uploadId }` | 014, 016 |
| DELETE | `/me/photo` | Retire la photo de profil | 014, 016 |
| GET | `/users/search?username=` | Recherche par pseudo exact + relation | 012 |
| GET | `/friendships` | Mes amis | 012 |
| GET | `/friendships/requests/incoming` | Demandes reçues | 012 |
| GET | `/friendships/requests/outgoing` | Demandes envoyées | 012 |
| GET | `/friendships/blocked` | Utilisateurs que j'ai bloqués | 012 |
| POST | `/friendships/requests` | Demande d'ami `{ username }` | 012 |
| POST | `/friendships/requests/:id/accept` | Accepter | 012 |
| POST | `/friendships/requests/:id/decline` | Refuser | 012 |
| DELETE | `/friendships/:userId` | Retirer un ami | 012 |
| POST | `/blocks/:userId` | Bloquer | 012 |
| DELETE | `/blocks/:userId` | Débloquer (seul le bloqueur) | 012 |
| GET | `/conversations` | Conversations avec mes amis + dernier message | 009, 012 |
| GET | `/conversations/:friendId/messages?before=&limit=` | Historique (plus récent d'abord) | 009 |
| POST | `/conversations/:friendId/messages/text` | Chat texte permanent `{ content }` | 009 |
| POST | `/conversations/:friendId/messages/snap` | Snap `{ uploadId, contentType }` (upload `SNAP_PHOTO` / `SNAP_VIDEO`) | 009, 016 |
| POST | `/conversations/:friendId/read` | Marque les Chats reçus comme lus | 009 |
| POST | `/messages/:messageId/view` | Ouvre un Snap : URL ~1 min, vue unique, grâce 5 min, SnapCore+ | 009 |

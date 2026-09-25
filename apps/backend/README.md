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

## API admin (backoffice, ADR-021)

Routes `/admin/...` appelées **uniquement par le serveur du backoffice**, avec l'en-tête `x-admin-secret`
(valeur de `ADMIN_API_SECRET`, 32 caractères minimum ; sans elle, l'API admin est fermée). Aucun jeton
Firebase n'y donne accès. Corps : `{ moderatorId, reason }` (ou `{ moderatorId }`, `{ moderatorId, status }`).

| Méthode | Route | Effet |
|---|---|---|
| POST | `/admin/users/:userId/suspend` | Suspension réversible : invisible des autres, connexion refusée (403), temps réel coupé |
| POST | `/admin/users/:userId/reactivate` | Réactivation |
| POST | `/admin/messages/:messageId/redact` | Rédaction : texte ou média vidé (objet R2 supprimé), job de purge retiré, ligne conservée |
| DELETE | `/admin/stories/:storyItemId` | Suppression d'un élément de story (média, ligne, vues, job de purge) |
| POST | `/admin/reports/:reportId/close` | Signalement `RESOLVED` ou `DISMISSED` |

## Temps réel (Socket.io)

Connexion : `io(URL, { auth: { token: <ID token Firebase> } })`. Le serveur émet `ready` une fois
l'utilisateur authentifié (compte actif requis), puis chaque événement sous son type :
`friend_request.received`, `friend_request.accepted`, `message.received`, `snap.opened`,
`texts.read`, `snap.retained`, `snap.expired`. Contenu minimal (type + identifiants) : le client
va chercher le détail via l'API et **resynchronise tout à chaque reconnexion** (ADR-006).
Hors ligne, les événements importants partent en push FCM (message data, mêmes champs, ADR-018).
Multi-instance via l'adaptateur Redis de Socket.io ; présence partagée dans Redis.

## Jobs (BullMQ)

Le backend embarque le worker de purge des médias (module `purge`, ADR-017, `docs/impl/purge-bullmq.md`) :
jobs `purge-message-<id>` programmés à l'envoi d'un Snap (+30 j) et rapprochés à l'ouverture (+24 h),
exception SnapCore+ vérifiée à l'exécution ; jobs `purge-story-<id>` à l'expiration d'un élément de story
(média, ligne et vues supprimés) ; balayage de rattrapage toutes les heures pour les deux.
Suppression de compte (ADR-019) : file `account-deletion`, job `delete-account-<userId>` idempotent
(abonnement → Firebase → PostgreSQL sous `FOR UPDATE` → R2), réessais quasi illimités avec backoff
plafonné à 30 min, alerte (log ERROR) au-delà de 24h d'échec, rattrapage horaire.
`PURGE_WORKER_ENABLED=false` démarre une instance API sans aucun worker.

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
| DELETE | `/me` | Supprime mon compte (202 : bloqué immédiatement, suppression en arrière-plan) | 019 |
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
| GET | `/stories` | Stories de mes amis (non vues d'abord) | 013 |
| GET | `/stories/me` | Ma story + nombre de vues par élément | 013 |
| POST | `/stories` | Publie un élément `{ uploadId, contentType }` (upload `STORY_PHOTO` / `STORY_VIDEO`) | 013, 016 |
| POST | `/stories/:storyId/view` | URL de lecture ~1 min, enregistre la vue | 013 |
| GET | `/stories/:storyId/viewers` | Qui a vu mon élément (auteur uniquement) | 013 |
| DELETE | `/stories/:storyId` | Retire un élément avant expiration | 013 |
| POST | `/reports` | Signale un compte, un message reçu ou une story visible `{ targetType, targetId, reason }` | 021 |
| POST | `/devices` | Enregistre le token FCM de l'appareil `{ token }` (upsert) | 018 |
| DELETE | `/devices/:token` | Au logout : retire le token de l'appareil | 018 |

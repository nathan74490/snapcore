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

## Données de démo

```bash
npm run seed:demo -w @snapcore/backend    # backend de dev lancé à côté (ses workers traitent les purges)
```

`scripts/seed-demo.ts` peuple la base pour tester l'app avec les comptes `nathan` et `rober` (qui doivent
exister) : 8 comptes de démo (Firebase email/mot de passe en `@snapcore.test`, mot de passe commun
`SnapDemo2026!`) avec avatar dessiné (ADR-028, conservé s'il existe déjà), amitiés, 2 demandes en attente
vers nathan, stories avec vignette, conversations antidatées, snaps non ouverts et chats non lus. Tout
passe par les vrais cas d'usage (upload pré-signé vers R2 puis promotion, purges programmées). Idempotent, ne supprime jamais rien ; seuls les envois
récents sont notifiés (push / temps réel). Médias générés avec ffmpeg (`FFMPEG_PATH`, sinon le PATH).

## Tests

```bash
npm test -w @snapcore/backend                    # unitaires, sans infra
npm run test:integration -w @snapcore/backend    # base snapcore_test (créée et migrée au lancement)
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
| DELETE | `/admin/stories/:storyItemId` | Suppression d'un élément de story (média, vignette, ligne, vues, job de purge) |
| POST | `/admin/reports/:reportId/close` | Signalement `RESOLVED` ou `DISMISSED` |

## Temps réel (Socket.io)

Connexion : `io(URL, { auth: { token: <ID token Firebase> } })`. Le serveur émet `ready` une fois
l'utilisateur authentifié (compte actif requis), puis chaque événement sous son type :
`friend_request.received`, `friend_request.accepted`, `message.received`, `snap.opened`,
`texts.read`, `snap.retained`, `snap.expired`, `typing` (l'ami écrit, `userId`, ADR-027). Contenu minimal (type + identifiants) : le client
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
| POST | `/auth/register` | Crée la ligne `User` (token seul, idempotent, `checkRevoked`) `{ username, firstName, lastName?, birthday: "YYYY-MM-DD", phoneNumber? }` ; 400 si moins de 13 ans | 015, 029 |
| GET | `/users/username-availability?username=` | Public : `{ available, reason? }` (format invalide ou pseudo déjà porté par une ligne, même supprimée/suspendue) | 029 |
| GET | `/me` | Mon profil, avec `lastName?` et `birthday?` (privés, jamais dans un profil public) | 014, 029 |
| PATCH | `/me` | Modifier pseudo / prénom / nom (`""` le retire) / téléphone (409 si pris) | 014, 029 |
| POST | `/me/email/sync` | Recopie l'email confirmé côté Firebase | 014 |
| POST | `/media/uploads` | URL de PUT signée vers `tmp/` `{ purpose, contentType, contentLength }` | 016 |
| PUT | `/me/photo` | Confirme un upload `PROFILE_PHOTO` `{ uploadId }` | 014, 016 |
| DELETE | `/me/photo` | Retire la photo de profil | 014, 016 |
| PUT | `/me/avatar` | Enregistre l'avatar dessiné `{ options }` (valeurs du catalogue, 400 sinon), renvoie le profil | 028 |
| DELETE | `/me/avatar` | Retire l'avatar dessiné (204) | 028 |
| GET | `/avatars/catalog` | Avatar par défaut et valeurs autorisées par option | 028 |
| GET | `/avatars/avataaars.png?<11 options>&size=` | Public : PNG carré déterministe (64 à 512 px, 256 par défaut), cache immuable | 028 |
| DELETE | `/me` | Supprime mon compte (202 : bloqué immédiatement, suppression en arrière-plan) | 019 |
| GET | `/users/search?username=` | Recherche par pseudo exact + relation | 012 |
| POST | `/users/contacts/match` | Contacts du téléphone `{ phoneNumbers }` (1 à 2000, ≤ 32 car.) → `[{ phoneNumber, user, relation }]`, correspondance sur les 9 derniers chiffres, sans soi-même, bloqués ni comptes inactifs | 012, 029 |
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
| POST | `/conversations/:friendId/typing` | Signale à l'ami qu'on écrit (relayé en temps réel, jamais en push) | 027 |
| POST | `/messages/:messageId/view` | Ouvre un Snap : URL ~1 min, vue unique, grâce 5 min, SnapCore+ | 009 |
| GET | `/stories` | Stories de mes amis (non vues d'abord), `thumbnailUrl` de l'anneau sans compter de vue | 013, 026 |
| GET | `/stories/me` | Ma story + nombre de vues et `thumbnailUrl` par élément | 013, 026 |
| POST | `/stories` | Publie un élément `{ uploadId, contentType, thumbnailUploadId? }` (upload `STORY_PHOTO` / `STORY_VIDEO`, vignette `STORY_THUMBNAIL` facultative) | 013, 016, 026 |
| POST | `/stories/:storyId/view` | URL de lecture ~1 min, enregistre la vue | 013 |
| GET | `/stories/:storyId/viewers` | Qui a vu mon élément (auteur uniquement) | 013 |
| DELETE | `/stories/:storyId` | Retire un élément avant expiration | 013 |
| POST | `/reports` | Signale un compte, un message reçu ou une story visible `{ targetType, targetId, reason }` | 021 |
| POST | `/devices` | Enregistre le token FCM de l'appareil `{ token }` (upsert) | 018 |
| DELETE | `/devices/:token` | Au logout : retire le token de l'appareil | 018 |

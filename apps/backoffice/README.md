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
- **Lecture** : directement dans PostgreSQL via Prisma (`src/lib/queries.ts`). Pages :

  | Page | Contenu |
  |---|---|
  | `/` Tableau de bord | Chiffres de modération, communauté, activité 24 h, SnapCore+ et Snap Map ; histogrammes des 14 derniers jours ; alerte des suppressions bloquées (ADR-019) ; début de la file des signalements ; lien PostHog (`POSTHOG_DASHBOARD_URL`) |
  | `/reports` | File et historique, filtres par type, compteurs, pagination ; la fiche liste les autres signalements visant le même compte |
  | `/messages` | Fil des messages privés de l'app (chats, snaps, retirés), retrait direct |
  | `/stories` | Stories en ligne, lieu sur la Snap Map, nombre de signalements, suppression |
  | `/audit` | Journal des actions des modérateurs, reconstitué depuis les champs d'audit |
  | `/users` | Annuaire filtrable (statut, offre) et recherche par pseudo, prénom, nom, email, téléphone ou id |
  | `/users/[id]` | Fiche : profil, avatar, SnapCore+, partage de position, appareils, stories ; onglets messages (par conversation), amis et blocages, groupes, signalements reçus et envoyés |
  | `/groups` | Groupes (ADR-032), membres et historique des messages, en lecture seule |
  | `/subscriptions` | Abonnés SnapCore+ actifs, fins de période proches, abonnements expirés |
  | `/moderators` | Équipe et activité de chaque modérateur |

  Les avatars dessinés (ADR-028) passent par `/avatar`, relais authentifié vers le rendu PNG du backend.
  Les médias (snaps, stories) ne sont jamais affichés.
- **Actions** : jamais en écriture directe. Les actions serveur (`src/app/actions.ts`) appellent l'API
  admin du backend (`src/lib/admin-api.ts`, en-tête `x-admin-secret`), qui applique les effets de bord :
  suppression R2, purge BullMQ, temps réel. Actions disponibles : suspendre ou réactiver un compte,
  retirer un message, supprimer un élément de story, clore un signalement (résolu ou rejeté).

## Tests

```bash
npm test -w @snapcore/backoffice
```

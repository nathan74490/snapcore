# ADR-035 : Lives avec le SDK Live commun (version Kotlin), sous les stories

## Statut
Accepté

## Contexte
SnapCore est l'une des App membres du **Live SDK** commun (`echimine/live-sdk`) : un serveur commun (`https://live.echimine.fr`, LiveKit pour le média) et des SDK Swift et Kotlin. Chaque utilisateur d'une App membre voit les Lives en cours de toutes les autres, en rejoint un, lance le sien, discute, réagit, achète le Produit épinglé et monte à l'écran comme Co-hôte.

L'utilisateur veut ces Lives dans l'app Android, sur la page Stories, **sous « Stories récentes »**.

Le serveur commun ne connaît pas les comptes SnapCore : chaque App membre signe pour ses utilisateurs un **Jeton de Participant**. Il s'agit d'un JWT HS256 signé avec un secret propre à l'App membre, transmis en privé par echimine.

## Décision
**Backend** : nouveau module `live`, avec la route **`POST /live/participant-token → { token }`**.
- La route est authentifiée comme le reste de l'API.
- Le jeton est signé en HS256 avec `LIVE_SDK_SECRET`, sans dépendance (`node:crypto`).
- Claims :
  - `iss` : « snapcore » ;
  - `sub` : l'identifiant de l'utilisateur ;
  - `name` : le prénom, sinon le pseudo, coupé à 30 caractères ;
  - `picture` : la photo de profil, si son URL est en `https` ;
  - `iat` et `exp` : le jeton vit **5 min**.
- Le nom de famille n'est jamais transmis (ADR-029).
- Sans secret, la route répond **503** (`UnavailableError`).
- Le secret vit dans `.env`, **jamais commité**.

**App** : le SDK Kotlin, `com.github.echimine.live-sdk:ui`, servi par JitPack.
- Le SDK n'a pas encore de tag semver : la version est le **commit de `main`** (`liveSdk` dans `libs.versions.toml`). Il faudra passer au tag dès la première release.
- Le dépôt JitPack est limité à deux groupes : celui du SDK, et `com.github.davidliu`, dont vient la dépendance `audioswitch` de LiveKit.
- `AppContainer` configure `LiveSdk` au démarrage :
  - serveur `LIVE_SERVER_URL` (`liveServerUrl` dans `local.properties`, sinon `https://live.echimine.fr`) ;
  - le `tokenProvider` appelle la route du backend à chaque connexion, sans cache.

**Page Stories** : une section **« Lives »** sous les cartes « Stories récentes ».
- Les Lives en cours, en rangée de cartes : badge LIVE, titre, Hôte et App d'origine, Audience.
- Un bouton « Lancer un Live ».
- La section se recharge avec la page.
- Les écrans eux-mêmes (`LiveScreen`, `LiveLaunchScreen`) sont ceux du SDK, aux couleurs de SnapCore (`SnapLiveTheme`). Ils sont ouverts par les routes `LiveRoute(liveId)` et `LiveLaunchRoute`.

## Conséquences
- Pas de Live sans le secret de l'App membre : un backend sans `LIVE_SDK_SECRET` affiche « Les Lives n'ont pas pu être chargés. ».
- Les Lives ne passent jamais par le backend SnapCore : chat, Réactions, achats et modération vivent sur le serveur commun.
- Les permissions caméra et micro sont déjà déclarées par l'app (ADR-011). L'écran de lancement du SDK explique pourquoi il les demande.
- Mettre à jour le SDK revient à changer `liveSdk` (commit, puis tag).

# ADR-023 : Registre des anomalies détectées hors du périmètre en cours, à traiter plus tard

## Statut
Accepté

## Contexte
En développant une fonctionnalité, on tombe régulièrement sur des anomalies qui n'ont rien à voir avec elle : un bug ailleurs dans l'app, une limite mal calculée, une documentation périmée. Les corriger sur le moment mélange les sujets (une branche « filtres » qui touche à la déconnexion ou au backoffice) ; ne rien en faire, c'est les oublier.

## Décision
- Toute anomalie détectée **en dehors de la fonctionnalité en cours** est consignée dans le registre ci-dessous, puis traitée plus tard, dans sa propre branche.
- Une anomalie n'y est inscrite qu'**après vérification dans le code** (ou par une mesure). Ce qui n'a pas pu être reproduit est marqué comme tel dans le constat.
- Chaque entrée garde un identifiant stable (`BUG-NNN`, jamais réutilisé), une gravité (**Haute** : fonctionnalité cassée ou donnée perdue ; **Moyenne** : comportement incorrect visible ou gaspillage de ressources ; **Faible** : confort, configuration, documentation), sa localisation, le constat, l'impact et une piste de correction.
- Une anomalie corrigée n'est pas supprimée : son statut passe à **Corrigé** avec la référence du commit, pour garder la trace.
- Une anomalie qui touche la fonctionnalité en cours est corrigée directement dans cette fonctionnalité, sans passer par ce registre.

## Registre

| ID | Gravité | Zone | Résumé | Statut |
|----|---------|------|--------|--------|
| BUG-001 | Haute | Mobile / caméra | Une vidéo de 60 s envoyée sans filtre dépasse la limite de 60 Mo du backend | Corrigé (ADR-024, commit f222bff) |
| BUG-002 | Moyenne | Mobile / session | La déconnexion ne réinitialise pas le compte courant : le temps réel continue de tourner | Corrigé (branche feat/v1-finitions) |
| BUG-003 | Faible | Backoffice / auth | Boucle de redirection pour un modérateur supprimé dont la session est encore valide | Corrigé (branche feat/v1-finitions) |
| BUG-004 | Faible | Mobile / build | La tâche Gradle `adbReverse` échoue en silence quand plusieurs téléphones sont branchés | Corrigé (branche feat/v1-finitions) |
| BUG-005 | Faible | Backend / config | `.env.example` omet des variables lues par le code | Corrigé (branche feat/v1-finitions) |
| BUG-006 | Faible | Backend / config | `PURGE_WORKER_ENABLED` désactive aussi le worker de suppression de compte | Corrigé (branche feat/v1-finitions) |
| BUG-007 | Faible | Mobile / notifications | La permission de notification est redemandée à chaque démarrage si elle a été refusée | Corrigé (branche feat/v1-finitions) |
| BUG-008 | Faible | Documentation | `docs/impl/purge-bullmq.md` cite un fichier de test qui n'existe plus | Corrigé (branche feat/v1-finitions) |
| BUG-009 | Moyenne | Backend / suppression de compte | La suppression d'un compte n'annule pas son abonnement SnapCore+ | Corrigé (branche feat/v1-finitions) |
| BUG-010 | Faible | Mobile / chat | Le bouton flottant « Nouveau chat » masque le bouton « Ajouter » quand la liste est courte | Corrigé (branche feat/v1-finitions) |
| BUG-011 | Moyenne | Mobile / chat | Un snap ouvert reste « Nouveau snap » dans la liste Chat jusqu'à un rafraîchissement manuel | Corrigé (branche feat/v1-finitions) |
| BUG-012 | Moyenne | Mobile / chat | Une conversation qui reçoit un message reste cachée au-dessus de l'écran dans la liste Chat et dans une conversation | Corrigé (branche feat/v1-finitions) |
| BUG-013 | Faible | Mobile / chat | Le clavier reste ouvert par-dessus un snap ouvert depuis une conversation | Corrigé (branche feat/v1-finitions) |
| BUG-014 | Moyenne | Mobile / chat | Un snap non ouvert disparaît de la liste Chat quand un chat plus récent suit dans la conversation | Corrigé (branche fix/campagne-2026-11-04) |
| BUG-015 | Faible | Mobile / interface | Les icônes des barres système restent blanches sur fond blanc après la fermeture d'un snap | Corrigé (branche fix/campagne-2026-11-04) |
| BUG-016 | Faible | Mobile / amis | Nom et pseudo coupés sans points de suspension dans les demandes d'ami reçues | Corrigé (branche fix/campagne-2026-11-04) |
| BUG-017 | Faible | Mobile / notifications | Les notifications affichent le pseudo de l'expéditeur au lieu de son prénom | Corrigé (branche fix/campagne-2026-11-04) |
| BUG-018 | Faible | Mobile / stories | La barre de progression du lecteur de stories semble compter un segment de trop | Corrigé (branche fix/campagne-2026-11-04) |
| BUG-019 | Moyenne | Mobile / amis, modération | Retirer un ami, bloquer et signaler sont impossibles depuis l'app, alors que le backend les propose | Corrigé (branche fix/campagne-2026-11-04-2) |
| BUG-020 | Faible | Mobile / stories | Un élément de « Ma story » se supprime d'un seul appui, sans confirmation | Corrigé (branche fix/campagne-2026-11-04-2) |
| BUG-021 | Faible | Mobile / textes | Trois textes vouvoient l'utilisateur alors que l'app le tutoie partout ailleurs | Corrigé (branche fix/campagne-2026-11-04-2) |
| BUG-022 | Faible | Backend / tests | Le test de BUG-014 échoue environ une fois sur deux (deux chats à la même heure figée) | Ouvert |
| BUG-023 | Faible | Mobile / groupes | La notification d'un groupe sans nom affiche mon prénom et omet celui de l'expéditeur | Ouvert |
| BUG-024 | Faible | Mobile / groupes | Toucher un groupe avec un nouveau snap ouvre la conversation au lieu du snap | Ouvert |
| BUG-025 | Faible | Mobile / groupes | « Nouveau snap de Léa » est coupé dans la liste Chat : le prénom disparaît | Ouvert |
| BUG-026 | Moyenne | Mobile / Snap Map | À l'ouverture, la carte ne montre aucun ami alors que plusieurs sont à 2-3 km | Ouvert |
| BUG-027 | Faible | Mobile / Snap Map | Les pins de stories et les avatars de plusieurs amis proches se recouvrent | Corrigé (modifications locales, validation Android du 2026-11-04) |
| BUG-028 | Moyenne | Mobile / Snap Map | Mon avatar masque entièrement un ami situé au même endroit | Corrigé (modifications locales, validation Android du 2026-11-04) |
| BUG-029 | Moyenne | Mobile / groupes, réseau | Un groupe reste en chargement après l'échec de son chargement initial, sans bouton Réessayer | Corrigé (modifications locales, validation Android du 2026-11-04) |

### BUG-001 : Une vidéo de 60 s sans filtre dépasse la limite de 60 Mo
- **Où** : `apps/mobile/app/src/main/kotlin/com/snapcore/app/ui/camera/CameraScreen.kt` (`videoCaptureQualitySelector = QualitySelector.from(Quality.HD)`), limite dans `apps/backend/src/modules/media/domain/media-purpose.ts` (`SNAP_VIDEO`/`STORY_VIDEO` : 60 Mo).
- **Constat** (mesuré sur Galaxy A54) : l'enregistrement CameraX en 720p produit environ 4 Mo pour moins de 3,5 s, soit un débit de l'ordre de 10 Mbit/s. Une vidéo de 60 s pèse donc environ 75 Mo. Le commentaire du code (« une vidéo de 60 s reste nettement sous la limite ») est faux.
- **Impact** : une vidéo longue envoyée avec le filtre « Normal » (aucun réencodage, ADR-022) est refusée par le backend à la confirmation de l'upload. Les vidéos filtrées ne sont pas concernées : Media3 Transformer les réencode à 4 Mbit/s.
- **Piste** : fixer le débit de l'enregistrement (≈ 4 Mbit/s). `LifecycleCameraController` ne l'expose pas forcément : à étudier, sinon passer par un `VideoCapture`/`Recorder` configuré explicitement (`setTargetVideoEncodingBitRate`). Corriger le commentaire. Un test sur appareil d'une vidéo de 60 s doit valider la correction.
- **Correction** : avec l'intégration de Camera Kit (ADR-024), la caméra CameraX et son commentaire ont disparu, mais le problème s'est aggravé (Camera Kit enregistre en 1080×2340 à environ 11,6 Mbit/s, soit ≈ 87 Mo pour 60 s). Corrigé dans le cadre d'ADR-024 : toute vidéo est désormais réencodée à 4 Mbit/s par Media3 Transformer avant l'envoi, même sans filtre (`data/MediaFilterRenderer.kt`). Mesuré sur Galaxy A54 : 3,3 s → 2,1 Mo (4,05 Mbit/s), soit ≈ 32 Mo pour 60 s ; orientation et son conservés. Une vidéo de 60 s complète reste à tester sur appareil.

### BUG-002 : La déconnexion ne réinitialise pas le compte courant
- **Où** : `apps/mobile/app/src/main/kotlin/com/snapcore/app/ui/SnapCoreNavHost.kt`, fonction `go()` : seule la branche `Session.Ready` modifie `account`, `Session.SignedOut` le laisse à l'ancienne valeur.
- **Constat** : après une déconnexion (ou une suppression de compte), `account != null` reste vrai, donc `RealtimeWhileForeground()` reste actif. Sans utilisateur Firebase, `RealtimeClient` ne trouve pas de token et réessaie toutes les 5 s (`data/RealtimeClient.kt`, boucle `token == null` → `delay(5_000)`), indéfiniment tant que l'app est au premier plan. Une notification touchée ensuite (`launchTarget`) peut aussi naviguer avec le compte périmé.
- **Impact** : réveils inutiles (batterie, logs), état incohérent jusqu'à la prochaine connexion.
- **Piste** : `account = null` dans la branche `Session.SignedOut` de `go()`, et un test du ViewModel/de la navigation qui vérifie l'arrêt du temps réel après déconnexion.
- **Correction** : Corrigé : `go()` remet le compte à null hors session prête (`ui/SnapCoreNavHost.kt`).

### BUG-003 : Boucle de redirection pour un modérateur supprimé
- **Où** : `apps/backoffice/src/app/login/page.tsx` (redirige vers `/` dès qu'une session existe) et `apps/backoffice/src/lib/auth.ts` (`requireModerator()` redirige vers `/login` si le modérateur n'existe plus en base).
- **Constat** (lecture du code, non reproduit) : un modérateur supprimé en base alors que son JWT est encore valide (8 h) est renvoyé de `/login` vers `/`, puis de `/` vers `/login`, en boucle (erreur « trop de redirections » du navigateur).
- **Impact** : faible ; un modérateur supprimé ne peut de toute façon plus agir, mais il ne voit pas de message clair et doit effacer ses cookies.
- **Piste** : faire vérifier l'existence du modérateur par la page `/login` (même requête que `requireModerator`) avant de rediriger, ou déconnecter explicitement la session quand le modérateur n'existe plus.
- **Correction** : Corrigé : `/login` revérifie le modérateur en base (`currentModerator()`, `lib/auth.ts`) ; vérifié sur le backoffice en marche (modérateur supprimé, session encore valide : plus de boucle).

### BUG-004 : `adbReverse` échoue en silence avec plusieurs téléphones
- **Où** : `apps/mobile/app/build.gradle.kts`, tâche `adbReverse` (`adb reverse tcp:3000 tcp:3000`, `isIgnoreExitValue = true`).
- **Constat** : sans `-s <série>`, `adb reverse` refuse d'agir quand plusieurs appareils sont connectés (« more than one device/emulator ») ; l'erreur étant ignorée, aucun téléphone n'est redirigé et le build réussit quand même.
- **Impact** : avec deux téléphones branchés, l'app installée ne joint pas le backend, sans aucun message.
- **Piste** : lister les appareils (`adb devices`) et lancer `adb -s <série> reverse tcp:3000 tcp:3000` pour chacun.
- **Correction** : Corrigé : la tâche `adbReverse` liste les appareils et lance `adb -s <série> reverse` pour chacun.

### BUG-005 : `.env.example` du backend incomplet
- **Où** : `apps/backend/.env.example`.
- **Constat** : `ACCOUNT_DELETION_QUEUE_NAME`, `FCM_DRY_RUN` et `TEST_DATABASE_URL` sont lus par le code mais absents de l'exemple.
- **Impact** : configuration découverte en lisant le code ; `FCM_DRY_RUN` en particulier est utile en développement.
- **Piste** : les ajouter, commentés, avec leur valeur par défaut.
- **Correction** : Corrigé : variables ajoutées à `.env.example`.

### BUG-006 : `PURGE_WORKER_ENABLED` désactive aussi la suppression de compte
- **Où** : `apps/backend/src/modules/account-deletion/account-deletion.module.ts` (lit `PURGE_WORKER_ENABLED`) et `apps/backend/src/modules/purge/purge-worker.module.ts`.
- **Constat** : la même variable pilote le worker de purge des médias et celui de suppression de compte, alors que son nom ne parle que de la purge.
- **Impact** : désactiver la purge (par exemple sur une instance API sans worker) bloque aussi les suppressions de compte, sans que ce soit attendu.
- **Piste** : renommer en `WORKERS_ENABLED` (ou une variable par worker), et mettre à jour `.env.example`.
- **Correction** : Corrigé : `WORKERS_ENABLED` pilote les deux workers (`shared/infrastructure/workers.ts`) ; `PURGE_WORKER_ENABLED` reste lu en secours.

### BUG-007 : Permission de notification redemandée à chaque démarrage
- **Où** : `apps/mobile/app/src/main/kotlin/com/snapcore/app/ui/SnapCoreNavHost.kt`, `PushRegistration()` : la permission est demandée dès qu'elle n'est pas accordée.
- **Constat** : un utilisateur qui a refusé les notifications se la voit redemander à chaque ouverture de l'app (Android finit par bloquer la demande après deux refus, ce qui limite le problème).
- **Impact** : confort.
- **Piste** : mémoriser le refus (DataStore) et ne redemander que depuis l'écran profil, sur action de l'utilisateur.
- **Correction** : Corrigé : permission demandée une seule fois par installation ; la ligne « Notifications » des paramètres du profil ouvre les réglages Android quand elles sont désactivées.

### BUG-008 : Documentation de la purge : chemin de test périmé
- **Où** : `docs/impl/purge-bullmq.md`, ligne 6.
- **Constat** : le document cite `apps/backend/src/modules/messages/infrastructure/bullmq/purge-bullmq.int-spec.ts`, qui n'existe pas ; le test réel est `apps/backend/src/modules/purge/infrastructure/purge-bullmq.int-spec.ts`.
- **Impact** : documentation trompeuse.
- **Piste** : corriger le chemin.
- **Correction** : Corrigé : chemin du test mis à jour.

### BUG-009 : La suppression d'un compte n'annule pas son abonnement SnapCore+
- **Où** : `apps/backend/src/modules/account-deletion/infrastructure/provisional-adapters.ts` (`NoopSubscriptionCanceller`), étape (a) du job de suppression d'ADR-019.
- **Constat** : l'adaptateur d'annulation date d'avant SnapCore+ et ne fait que journaliser « aucun abonnement à annuler ». Depuis ADR-025, un compte peut être réellement abonné via RevenueCat Web Billing (Stripe, mode test).
- **Impact** : un abonné qui supprime son compte continue d'être prélevé (en mode test, sans débit réel) ; RevenueCat enverra ensuite des webhooks pour un compte disparu, acquittés sans effet.
- **Piste** : implémenter l'adaptateur avec l'API REST RevenueCat (annulation de l'abonnement Web Billing du client dont l'App User ID est l'id du compte, clé secrète côté backend), idempotent comme les autres étapes du job ; test d'intégration avec une doublure HTTP.
- **Correction** : `RevenueCatSubscriptionCanceller` (étape (a) du job de suppression) liste les abonnements du client par l'API v2 de RevenueCat et annule ceux en Web Billing qui doivent encore se renouveler ; client inconnu ou abonnement déjà annulé : rien à faire ; erreur réseau ou serveur : levée, le job réessaie. Sans clé (`REVENUECAT_SECRET_API_KEY`, `REVENUECAT_PROJECT_ID`), simple avertissement. Testé avec un faux serveur RevenueCat ; à vérifier en sandbox réelle une fois la clé fournie.

### BUG-010 : Le bouton flottant masque « Ajouter » dans la liste Chat
- **Où** : `apps/mobile/app/src/main/kotlin/com/snapcore/app/ui/chat/ChatListScreen.kt` (`FloatingActionButton` aligné en bas à droite, `contentPadding` de 90 en bas de la liste).
- **Constat** (Galaxy A54, 7 conversations) : la liste ne remplit pas l'écran, la marge basse ne sert donc à rien et le bouton rond « Nouveau chat » recouvre en grande partie le bouton jaune « Ajouter » de la ligne « Trouver mes amis ». Relevé pendant les retours du formateur (padding du Chat).
- **Impact** : bouton à moitié caché, appui ambigu entre les deux (les deux ouvrent pourtant l'ajout d'amis).
- **Piste** : retirer le bouton « Ajouter » de la ligne (toute la ligne est déjà cliquable) ou réserver la place du bouton flottant à droite de cette ligne.
- **Correction** : le bouton « Ajouter » est retiré de la ligne « Trouver mes amis », qui reste cliquable en entier. Le côté droit de la ligne est vide, le bouton flottant ne recouvre plus rien. Vérifié sur Galaxy A54.

### BUG-011 : Un snap ouvert reste « Nouveau snap » dans la liste Chat
- **Où** : `apps/mobile/app/src/main/kotlin/com/snapcore/app/ui/chat/ChatListScreen.kt` (`ChatListViewModel` ne se recharge que sur un événement temps réel), événements `snap.opened` et `texts.read` publiés à l'expéditeur seulement (`event-message.notifier.ts`).
- **Constat** (Galaxy A54, signalé par l'utilisateur) : après avoir ouvert puis fermé un snap reçu, la ligne de la conversation garde « Nouveau snap » et le compteur « Non lus » ne baisse pas tant que la liste n'est pas tirée vers le bas.
- **Impact** : l'utilisateur croit avoir un snap non vu ; la liste ne reflète pas ce qu'il vient de faire.
- **Correction** : la liste Chat se recharge à chaque retour au premier plan de l'accueil (retour d'une conversation, réouverture de l'app), comme la page Stories. Vérifié sur Galaxy A54 : le snap passe à « Reçu » et « Non lus » baisse dès la fermeture.

### BUG-012 : Une conversation qui reçoit un message reste cachée au-dessus de la liste Chat
- **Où** : `apps/mobile/app/src/main/kotlin/com/snapcore/app/ui/chat/ChatListScreen.kt` (`LazyColumn` à clés stables).
- **Constat** (Galaxy A54, signalé par l'utilisateur) : à la réception d'un message, l'événement temps réel arrive et la liste se recharge, mais la conversation remontée en tête s'insère au-dessus de la première ligne affichée : la liste garde sa ligne de référence et la nouvelle conversation reste hors de l'écran. L'utilisateur croit devoir actualiser.
- **Impact** : le message reçu semble ne pas arriver tant qu'on ne fait pas défiler la liste.
- **Correction** : quand la conversation en tête change et que la liste est déjà en haut, elle remonte pour la montrer ; plus bas dans la liste, la position est conservée. Même défaut et même correction dans une conversation ouverte (`ConversationScreen.kt`, liste inversée) : le message reçu restait sous la dernière ligne ; il s'affiche désormais si l'on était en bas, et toujours pour son propre envoi. Vérifié sur Galaxy A54 avec des chats et des snaps envoyés par l'API pendant que l'écran est affiché.

### BUG-013 : Le clavier reste ouvert par-dessus un snap
- **Où** : `apps/mobile/app/src/main/kotlin/com/snapcore/app/ui/chat/ConversationScreen.kt` (le champ de saisie garde le focus quand le lecteur de snap s'ouvre).
- **Constat** (Galaxy A54, signalé par l'utilisateur) : dans une conversation avec le clavier ouvert, ouvrir un snap reçu affiche le snap avec le clavier par-dessus.
- **Impact** : le snap, visible une seule fois, est en partie caché.
- **Correction** : à l'ouverture du lecteur, le clavier est fermé et le champ perd le focus. Vérifié sur Galaxy A54 (snap reçu en direct, clavier ouvert, puis ouvert : clavier fermé).

### BUG-014 : Un snap non ouvert disparaît de la liste Chat
- **Où** : `apps/mobile/app/src/main/kotlin/com/snapcore/app/ui/chat/ChatListScreen.kt` et `domain/Chat.kt` : la ligne d'une conversation ne regarde que `Conversation.lastMessage`, pour le statut (`statusOf`) comme pour l'ouverture directe du snap.
- **Constat** (campagne de test du 2026-11-04, Galaxy A54) :
  - Inès a envoyé à nathan un snap, puis deux chats ; nathan a lu les chats sans ouvrir le snap.
  - Dans la liste Chat, la ligne d'Inès affiche « Reçu · 18 j », sans « Nouveau snap », et toucher la ligne ouvre la conversation au lieu du snap.
  - Le snap est pourtant toujours là dans la conversation (« Appuie pour voir »), et il s'ouvre normalement.
- **Impact** : un snap reçu passe inaperçu. Snapchat met en avant un snap non ouvert tant qu'il existe, quel que soit le dernier message.
- **Correction** :
  - `GET /conversations` renvoie aussi `unopenedSnap`, le plus ancien snap reçu non ouvert dont le média existe encore (requête `DISTINCT ON`).
  - L'app résume chaque ligne par `Conversation.headline` : d'abord un envoi en cours ou en échec, puis le snap à ouvrir, sinon le dernier message.
  - Vérifié sur Galaxy A54 : Léa envoie un snap puis un chat, la ligne affiche « Nouveau snap », et la toucher ouvre le snap.

### BUG-015 : Barres système illisibles après la fermeture d'un snap
- **Où** : `apps/mobile/app/src/main/kotlin/com/snapcore/app/ui/components/SystemBars.kt` (`StatusBarIcons` applique son réglage dans un `SideEffect`, sans rien rétablir quand l'écran quitte la composition) et `ui/chat/SnapViewer.kt`, affiché par-dessus la conversation.
- **Constat** (campagne de test du 2026-11-04, Galaxy A54) :
  - Le lecteur de snap passe les icônes en blanc.
  - Une fois le snap fermé, la conversation n'est pas recomposée et ne remet pas les icônes sombres.
  - L'heure, le réseau et les boutons de navigation restent blancs sur le fond blanc, au moins plusieurs secondes après la fermeture.
- **Impact** : barre d'état et barre de navigation illisibles jusqu'au prochain changement d'écran.
- **Correction** :
  - La conversation, qui affiche le lecteur, règle les icônes selon l'état du lecteur (`StatusBarIcons(dark = state.viewer == null)`), et le lecteur ne les règle plus lui-même.
  - Le réglage est donc réappliqué à la fermeture du snap.
  - Une restauration générale à la sortie de chaque écran a été écartée : pendant une transition, l'écran qui part écraserait le réglage de celui qui arrive.
  - Vérifié sur Galaxy A54 : un snap revu dans une conversation repasse aux icônes sombres dès sa fermeture.

### BUG-016 : Demandes d'ami, nom et pseudo coupés
- **Où** : `apps/mobile/app/src/main/kotlin/com/snapcore/app/ui/friends/FriendsScreen.kt`, ligne d'une demande reçue avec les boutons « Refuser » et « Accepter ».
- **Constat** (campagne de test du 2026-11-04, Galaxy A54) :
  - Les deux boutons prennent presque toute la largeur.
  - Le prénom devient « Sar… ».
  - Les pseudos sont tronqués **sans points de suspension** : « @sara » pour `sarah.lmb`, « @tom » pour `tom_grd`. Une fois Tom accepté, la liste d'amis affiche bien « @tom_grd ».
- **Impact** : le pseudo affiché ressemble à un autre pseudo, qui peut exister. L'utilisateur risque d'accepter la mauvaise personne.
- **Correction** :
  - Comme dans Snapchat, « Refuser » devient une croix dans un rond gris, à côté de « Accepter ».
  - Le pseudo est coupé avec des points de suspension s'il reste trop long.
  - Vérifié sur Galaxy A54 : « Sarah », « @sarah.lmb » en entier.

### BUG-017 : Notifications avec le pseudo au lieu du prénom
- **Où** : `apps/mobile/app/src/main/kotlin/com/snapcore/app/push/PushServices.kt`, lignes 59 et 62 : `PushDetails(conversation?.friend?.username, …)`.
- **Constat** (campagne de test du 2026-11-04, Galaxy A54, app en arrière-plan) : un chat de Léa produit « Nouveau chat de lea.martin ». Partout ailleurs, l'app affiche le prénom (« Léa »).
- **Impact** : incohérence visible, et notification moins lisible.
- **Correction** : les trois types de notification (message, demande reçue, demande acceptée) utilisent le nom affiché de l'ami. Vérifié sur Galaxy A54 : « Nouveau chat de Léa ».

### BUG-018 : Barre de progression des stories, un segment de trop en apparence
- **Où** : `apps/mobile/app/src/main/kotlin/com/snapcore/app/ui/stories/StoryPlayerScreen.kt` : un `LinearProgressIndicator` Material 3 par élément.
- **Constat** (campagne de test du 2026-11-04, Galaxy A54) :
  - Le composant Material 3 laisse un espace (`gapSize`) entre la partie lue et la partie restante d'un même indicateur.
  - Pendant la lecture, le segment en cours paraît donc coupé en deux : la story de Léa, qui a 2 éléments, semble en avoir 3.
- **Impact** : l'utilisateur croit qu'il reste un élément de plus à voir.
- **Correction** : `gapSize = 0.dp` sur chaque indicateur. Vérifié sur Galaxy A54 : une story d'un seul élément affiche une seule barre continue.

### BUG-019 : Retirer un ami, bloquer et signaler sont impossibles depuis l'app
- **Où** : `apps/mobile` : aucun écran n'appelle `DELETE /friendships/:userId`, `POST /blocks/:userId`, `DELETE /blocks/:userId` ni `POST /reports`. `FriendsService` (`domain/Media.kt`) n'expose pas ces actions, et les lignes « Mes amis » de `FriendsScreen.kt` n'ont aucune action.
- **Constat** (seconde campagne de test du 2026-11-04, vérifié dans le code et sur Galaxy A54) :
  - ADR-012 prévoit de retirer un ami, de bloquer et de débloquer (avec la liste « mes utilisateurs bloqués »).
  - ADR-021 prévoit les signalements d'un utilisateur, d'un message ou d'une story, traités dans le backoffice.
  - Le backend implémente tout cela et le teste, mais rien ne permet de le faire depuis le téléphone.
- **Impact** :
  - Un utilisateur ne peut ni se protéger d'un ami indésirable ni signaler un contenu.
  - La modération du backoffice ne reçoit donc jamais de signalement venu de l'app.
  - C'est une exigence habituelle des stores pour une app de messagerie.
- **Correction** :
  - Nouveau `SafetyService` côté app, sur les routes existantes : `DELETE /friendships/:id`, `POST` et `DELETE /blocks/:id`, `GET /friendships/blocked`, `POST /reports`.
  - **Conversation** : menu « ⋮ » avec « Signaler », « Retirer de mes amis » et « Bloquer », chacun confirmé. Un appui long sur un message reçu le signale.
  - **Lecteur de stories** : un drapeau signale l'élément affiché, et la photo est en pause pendant le choix du motif.
  - **Paramètres** : « Utilisateurs bloqués », pour débloquer après confirmation, de façon optimiste.
  - **Motif** : choisi dans une liste (harcèlement, contenu inapproprié, spam, usurpation, autre), envoyé comme texte au backend.
- **Vérifié sur Galaxy A54** :
  - signalement de Tom, enregistré en `PENDING` avec « Spam ou arnaque » ;
  - blocage : retour à la liste, Tom disparaît, `BLOCKED` par nathan ;
  - déblocage depuis les paramètres : la ligne est supprimée, comme le prévoit ADR-012 ;
  - signalement d'une story, avec la pause puis la reprise.

### BUG-020 : Suppression d'un élément de story sans confirmation
- **Où** : `apps/mobile/app/src/main/kotlin/com/snapcore/app/ui/stories/MyStoryScreen.kt`, ligne 108 : `IconButton(onClick = { vm.delete(item) })`.
- **Constat** (seconde campagne de test du 2026-11-04, Galaxy A54) : un appui sur la corbeille supprime l'élément tout de suite et pour de bon. La corbeille est juste à côté du compteur de vues, qui ouvre la liste des spectateurs.
- **Impact** : une story publiée peut être perdue par un appui de travers, sans retour possible.
- **Correction** : fenêtre « Supprimer ce snap de ta story ? » avant la suppression. Vérifié sur Galaxy A54 : « Annuler » laisse l'élément en place.

### BUG-021 : Textes au vouvoiement
- **Où** :
  - `apps/mobile/app/src/main/kotlin/com/snapcore/app/data/api/ApiErrors.kt` ligne 25 : « Impossible de joindre le serveur. Vérifiez votre connexion. » ;
  - `ui/auth/AuthViewModels.kt` ligne 79 : « Renseignez votre email et votre mot de passe » ;
  - `ui/camera/LensCarousel.kt` ligne 144 : « Pour vous ».
- **Constat** (seconde campagne de test du 2026-11-04, Galaxy A54 sans réseau) : le message d'erreur réseau s'affiche dans « Envoyer à » au vouvoiement, alors que le reste de l'app tutoie (« Vérifie ta connexion et réessaie », « Tes amis… »).
- **Impact** : ton incohérent.
- **Correction** : « Vérifie ta connexion. », « Renseigne ton email et ton mot de passe », « Pour toi ».

### BUG-022 : Test instable sur le snap non ouvert (BUG-014)
- **Où** : `apps/backend/src/modules/messages/application/messaging-use-cases.spec.ts`, test « un snap non ouvert reste signalé même si des chats plus récents le suivent (BUG-014) ».
- **Constat** (relevé pendant les groupes, ADR-032) :
  - Les deux chats `"tu as vu ?"` et `"pas encore"` sont envoyés sans avancer l'horloge figée du test, donc à la même date.
  - Le dépôt en mémoire les départage par leur identifiant, un UUID aléatoire : le « dernier message » attendu (`"pas encore"`) n'est pas toujours celui retenu.
  - Résultat : le test échoue environ une fois sur deux, sans rapport avec le code testé.
- **Impact** : une suite de tests qui échoue par hasard fait douter des vraies régressions.
- **Piste** : `advance(MINUTE)` entre les deux chats.
- **Mesure** (troisième campagne de test du 2026-11-04) : 4 échecs sur 5 lancements de `messaging-use-cases.spec.ts`, et 2 sur 3 pour la suite complète. C'est le seul test en échec.

### BUG-023 : Notification d'un groupe sans nom, mauvais titre
- **Où** : `apps/mobile/app/src/main/kotlin/com/snapcore/app/push/PushServices.kt`, branche `group.message.received` : sans `groupName`, le titre reprend les prénoms des membres **sauf l'expéditeur** (`filter { it != senderName }`).
- **Constat** (troisième campagne de test du 2026-11-04, Galaxy A54, app en arrière-plan) :
  - Lucas écrit dans le groupe sans nom « Lucas et Hugo ».
  - La notification a pour titre « Nathan et Hugo » : elle contient mon propre prénom, et pas celui de Lucas.
  - Dans l'app, le même groupe s'appelle « Lucas et Hugo », d'après `Group.title(myId)`, qui écarte moi et non l'expéditeur.
- **Impact** : le titre ne correspond pas au nom du groupe dans la liste Chat. On ne reconnaît pas le groupe, et on lit son propre prénom.
- **Piste** : construire le titre comme `Group.title(myId)`, en écartant le compte connecté (id connu de l'app) plutôt que l'expéditeur.

### BUG-024 : Un snap de groupe ne s'ouvre pas depuis la liste Chat
- **Où** : `apps/mobile/app/src/main/kotlin/com/snapcore/app/ui/chat/ChatListScreen.kt`, `GroupRow` : l'appui ouvre toujours la conversation (`onOpenGroup`).
- **Constat** (troisième campagne de test du 2026-11-04) : la ligne de « Soirée samedi » affiche « Nouveau snap de Léa ». La toucher ouvre la conversation, et il faut encore toucher « Appuie pour voir ». Pour un ami, le même appui ouvre directement le snap (BUG-014), comme dans Snapchat.
- **Impact** : comportement différent entre un ami et un groupe ; une étape de plus pour voir un snap.
- **Piste** : passer à `GroupConversationRoute` un `openSnapId` (le `headline` à ouvrir), comme `ConversationRoute`. La conversation ouvre le lecteur dès le chargement et ramène à la liste à sa fermeture.

### BUG-025 : Statut d'un groupe coupé dans la liste Chat
- **Où** : `ChatListScreen.kt`, `GroupRow` ; libellé de `statusOf(GroupMessage, …)` dans `domain/Groups.kt`.
- **Constat** (troisième campagne de test du 2026-11-04, Galaxy A54) : « Nouveau snap de Léa · à l'instant » ne tient pas sur la ligne. Elle affiche « Nouveau snap de… », et le prénom, l'information utile, disparaît. « Nouveau chat de Lucas » tient, de justesse.
- **Impact** : on ne sait pas qui a envoyé le snap sans ouvrir le groupe.
- **Piste** : un libellé plus court, par exemple « Snap de Léa » ou « Léa · nouveau snap », ou l'heure sur une ligne à part.

### BUG-026 : Snap Map, aucun ami visible à l'ouverture
- **Où** : `apps/mobile/app/src/main/kotlin/com/snapcore/app/ui/map/MapScreen.kt`, premier cadrage : centré sur moi au zoom ville (`CityZoom = 13`), sans tenir compte des amis.
- **Constat** (troisième campagne de test du 2026-11-04, Galaxy A54) :
  - Léa, Hugo et Inès sont à 2 ou 3 km, autour du lac d'Annecy, et leurs positions sont bien en Redis.
  - À l'ouverture, la carte ne montre que « Moi ».
  - Il faut déplacer la carte vers l'est pour les voir ; l'affichage est alors correct.
  - Déjà relevé comme simple remarque lors de la première campagne.
- **Impact** : l'utilisateur croit qu'aucun ami ne partage sa position.
- **Piste** : un premier cadrage qui englobe ma position et les amis proches (par exemple ceux à moins de 20 km), avec `focusOn` déjà utilisé quand ma position est inconnue.

### BUG-027 : Snap Map, pins et avatars superposés
- **Où** : `MapScreen.kt` et `domain/SnapMap.kt` (`pinSlots`) : un pin ne s'écarte que des avatars et des pins à moins de 500 m, avec un écart fixe de 58 dp, quel que soit le zoom.
- **Constat** (troisième campagne de test du 2026-11-04, Galaxy A54, zoom ville) : dans la vieille ville d'Annecy, les stories d'Inès, de Chloé et de Léa s'empilent. L'avatar d'Inès recouvre l'une d'elles.
- **Impact** : des stories sont difficiles à voir et à toucher.
- **Piste** : regrouper les marqueurs proches au zoom courant (« 3 stories »), ou calculer l'écart à partir de la distance à l'écran plutôt qu'en mètres.
- **Correction et validation** : placement des rectangles projetés par Mapbox, libellés et mon avatar compris, puis reprojection du centre final. Recherche élargie sans retour sur une place déjà occupée, priorité aux places visibles ; les points hors champ restent à leur position géographique. Tests de 50 marqueurs très proches et contrôle sur Galaxy A54 aux cadrages ville et région. Voir la validation ajoutée à la quatrième campagne.
- **Reprise du 2026-11-05** : le placement était recalculé à chaque image du déplacement de la carte, et Mapbox renvoie des pixels faussés pour les points hors champ. Glisser la carte faisait donc sauter les stories d'une place à l'autre, et des amis lointains restaient collés au bord de l'écran. Le placement (`mapMarkerLayout`) projette désormais lui-même en Web Mercator : il ne dépend que du zoom et de l'orientation, jamais du centre. Il est recalculé quand la carte s'immobilise, et les marqueurs glissent vers leur nouvelle place. Contrôle sur Galaxy A54 : après un glissement, tous les marqueurs se déplacent du même vecteur que la carte.

### BUG-028 : Snap Map, mon avatar masque un ami au même endroit
- **Constat** (quatrième campagne du 2026-11-04, Galaxy A54, APK local installé à 14 h 36) : rober et moi avons des positions presque identiques. Son avatar est placé en `[18,570][165,717]`, le mien en `[19,570][166,717]`. Seul « Moi » est visible ; l'appui ouvre mes paramètres, pas la fiche de rober.
- **Où** : `MapScreen.kt` ajoute mon marqueur après ceux des amis, avec `allowOverlap(true)`. `mapLayout` dans `domain/SnapMap.kt` ne reçoit pas ma position et ne réserve aucun espace pour mon marqueur.
- **Impact** : un ami proche peut disparaître entièrement et devenir impossible à sélectionner à cet endroit.
- **Piste** : inclure ma position dans la résolution des collisions, ou regrouper les comptes superposés avec un choix au toucher.
- **Preuves** : [rapport et captures](../tests/campagne-2026-11-04-4.md).

- **Correction et validation de BUG-028** : ma position visible est réservée avant les amis. (Depuis la reprise du 2026-11-05, voir BUG-027, le placement ne tient plus compte des bords de l'écran : au bord, l'ami écarté peut sortir du champ, et il suffit de déplacer la carte pour le voir.) Test de régression et contrôle sur Galaxy A54 : rober est visible et son appui ouvre sa propre fiche.

### BUG-029 : Groupe en chargement persistant après un échec réseau initial
- **Constat** (quatrième campagne du 2026-11-04, Galaxy A54) : ouvrir « Lucas et Hugo » depuis la liste avec l'API arrêtée laisse un écran sans titre ni historique, avec une roue de chargement et aucun bouton Réessayer. État encore présent après plus d'une minute. Après rétablissement de l'API, sortir et rouvrir le groupe rétablit son contenu.
- **Où** : `GroupConversationScreen.kt` affiche le chargement si `group == null`, même après passage de `loading` à faux dans le ViewModel à l'échec. L'erreur est consommée par une snackbar ; aucun état d'erreur initial avec relance n'est rendu.
- **Impact** : l'utilisateur attend sans savoir comment relancer le chargement ; le champ de saisie reste présent sur cet écran incomplet.
- **Piste** : distinguer chargement initial, échec initial et contenu chargé ; proposer Réessayer avec `vm.refresh()`.
- **Preuves** : [rapport et captures](../tests/campagne-2026-11-04-4.md).

- **Correction et validation de BUG-029** : état d'erreur initial persistant avec « Réessayer », distinct des erreurs transitoires d'un groupe déjà chargé. La saisie est masquée avant chargement du groupe. Test de panne, nouvelle tentative et conservation de l'historique ; contrôle réel sur Galaxy A54 avec arrêt et rétablissement de l'API.

## Alternatives
- **Corriger chaque anomalie dès qu'elle est trouvée** : rien n'est oublié, mais la fonctionnalité en cours se retrouve mêlée à des changements sans rapport, plus difficiles à relire et à annuler. Écarté.
- **Un outil de suivi externe (issues GitHub, Notion)** : plus adapté à une équipe nombreuse, mais sépare le registre du code alors que tout le reste des décisions vit dans `docs/adr`. Écarté pour l'instant ; le registre pourra y être migré si le projet grandit.
- **Des `TODO` dans le code** : invisibles tant qu'on ne relit pas le fichier, sans gravité ni priorité. Écarté.

## Conséquences
### Positives
- Aucune anomalie repérée en chemin n'est perdue, et chaque fonctionnalité reste centrée sur son sujet.
- Les entrées sont vérifiées avant d'être inscrites : le registre ne contient pas de faux positifs.
- La gravité permet de prioriser le traitement.

### Négatives
- Le registre doit être tenu à jour (passage en « Corrigé ») : un registre qui n'est plus entretenu perd sa valeur.
- Cet ADR n'est pas une décision d'architecture au sens strict, mais un outil de suivi ; il est rangé avec les ADR pour rester à côté des décisions qu'il référence.

## Historique
- Révision 16 : BUG-027 à BUG-029 corrigés localement et vérifiés sur Galaxy A54 ; 158 tests Android passent, lint sans erreur. L'APK corrigé est installé sur l'appareil. Voir la section de validation de `docs/tests/campagne-2026-11-04-4.md`.
- Révision 15 : BUG-028 et BUG-029 constatés sur appareil pendant la quatrième campagne du 2026-11-04 ; BUG-027 reste visible à certains cadrages malgré les changements locaux. Voir `docs/tests/campagne-2026-11-04-4.md`.
- Révision 14 : BUG-023 à BUG-027 relevés pendant la troisième campagne de test du 2026-11-04, voir `docs/tests/campagne-2026-11-04-3.md` ; BUG-022 mesuré.
- Révision 13 : BUG-022 relevé pendant le développement des groupes (ADR-032).
- Révision 12 : BUG-019 à BUG-021 corrigés (branche `fix/campagne-2026-11-04-2`).
- Révision 11 : BUG-019 à BUG-021 relevés pendant la seconde campagne de test du 2026-11-04, voir `docs/tests/campagne-2026-11-04-2.md`.
- Révision 10 : BUG-014 à BUG-018 corrigés (branche `fix/campagne-2026-11-04`).
- Révision 9 : BUG-014 à BUG-018 relevés pendant la campagne de test du 2026-11-04 (Galaxy A54), voir `docs/tests/campagne-2026-11-04.md`.
- Révision 8 : BUG-012 étendu aux conversations, BUG-013 relevé et corrigé.
- Révision 7 : BUG-012 relevé et corrigé.
- Révision 6 : BUG-011 relevé et corrigé.
- Révision 5 : BUG-010 corrigé.
- Révision 4 : ajout de BUG-010 (relevé pendant les retours du formateur).
- Révision 3 : BUG-002 à BUG-009 corrigés (finitions de la V1).
- Révision 2 : ajout de BUG-009 (relevé pendant le paywall SnapCore+, ADR-025).
- Révision 1 : BUG-001 corrigé par ADR-024 (réencodage systématique des vidéos).
- Version initiale : BUG-001 (mesuré pendant le développement des filtres, ADR-022) et BUG-002 à BUG-008 (relevés pendant l'analyse du dépôt, vérifiés dans le code).

## Liens
- ADR liés : ADR-016, ADR-018, ADR-019, ADR-020, ADR-022

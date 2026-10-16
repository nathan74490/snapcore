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
| BUG-012 | Moyenne | Mobile / chat | Une conversation qui reçoit un message reste cachée au-dessus de l'écran dans la liste Chat | Corrigé (branche feat/v1-finitions) |

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
- **Correction** : quand la conversation en tête change et que la liste est déjà en haut, elle remonte pour la montrer ; plus bas dans la liste, la position est conservée. Vérifié sur Galaxy A54 avec des messages envoyés par l'API pendant que la liste est affichée ; dans une conversation ouverte, le message s'affichait déjà en direct.

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

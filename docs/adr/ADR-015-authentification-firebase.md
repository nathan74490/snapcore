# ADR-015 — Authentification : Firebase Authentication (email/mot de passe) avec vérification d'email

## Statut
Proposé

## Contexte
L'onboarding (ADR-010) collecte téléphone et email sans vérification, mais aucune décision n'avait encore été prise sur l'authentification elle-même : comment un utilisateur se connecte, comment son mot de passe est stocké, comment son identité est vérifiée à chaque requête. L'écran de référence (Mobbin) montre une étape "Set a password" dans la création de compte.

## Décision
- Utiliser **Firebase Authentication** (plan Spark, gratuit) avec le fournisseur **email/mot de passe** uniquement — jamais le fournisseur "Phone Auth", dont l'envoi de SMS de vérification n'est disponible sur aucun plan gratuit Firebase et nécessite de passer au plan payant à l'usage (Blaze).
- Le mot de passe n'est **jamais géré par notre backend** : Firebase stocke et vérifie le hash lui-même. `USER.passwordHash` est remplacé par `USER.firebaseUid` (identifiant du compte Firebase correspondant, unique), qui fait le lien entre notre `User.id` interne et le compte Firebase.
- Le SDK Firebase Auth côté Android gère l'inscription, la connexion et le rafraîchissement de session. Le backend vérifie l'ID token Firebase envoyé par l'app (via le SDK Admin Firebase, Node.js) à chaque requête authentifiée — pas de JWT signé par nous.
- **Vérification d'email activée** : Firebase envoie automatiquement un email de confirmation à l'inscription (gratuit jusqu'à 1000 envois/jour sur le plan Spark, largement suffisant ici). L'app ne bloque cependant pas l'accès tant que l'email n'est pas confirmé — le statut `emailVerified` (porté par Firebase, pas dupliqué en base) reste informatif en V1, pour ne pas réintroduire de friction à l'onboarding (cohérent avec l'esprit d'ADR-010).
- Le numéro de téléphone collecté à l'onboarding (ADR-010) reste **non vérifié** : la vérification par SMS de Firebase (Phone Auth) n'est disponible que sur le plan payant (Blaze), elle n'est donc pas utilisée.
- **La ligne `User` n'est jamais créée à la volée sur une requête authentifiée quelconque.** Un endpoint d'inscription explicite (`POST /auth/register`, appelé une seule fois par l'app juste après la création du compte Firebase) est seul responsable de créer la ligne `User` pour un `firebaseUid` donné ; toute autre requête authentifiée qui ne trouve pas de ligne `User` pour le `firebaseUid` du token échoue avec une erreur "compte introuvable", sans jamais recréer la ligne.
- **`POST /auth/register` lui-même vérifie que le compte Firebase existe encore avant de créer la ligne `User`**, en appelant `admin.auth().verifyIdToken(idToken, true)` (le second paramètre `checkRevoked` force une vérification contre l'état actuel du compte, pas seulement la signature du token) — la simple validation de signature d'`verifyIdToken(idToken)` sans ce paramètre ne suffit pas : un ID token Firebase est un JWT auto-suffisant, valide cryptographiquement jusqu'à son expiration naturelle (~1h) **même si le compte Firebase qui l'a émis a été supprimé entre-temps** (ADR-019). Sans cette vérification, l'endpoint d'inscription lui-même recréerait le compte que l'utilisateur vient de supprimer, dès que l'app relance et rejoue son ancien token : ce n'était pas la règle de recréation automatique en elle-même qui posait problème, mais l'absence de vérification de l'existence réelle du compte à la création de la ligne `User`, où qu'elle ait lieu. (À confirmer précisément dans la documentation du SDK Admin installé : si `checkRevoked` ne couvrait pas la suppression de compte, un appel explicite à `admin.auth().getUser(firebaseUid)` avant l'insertion — qui échoue avec `auth/user-not-found` si le compte a été supprimé — est le filet de sécurité de repli.)
- **L'app se déconnecte de Firebase (`FirebaseAuth.getInstance().signOut()`) dès que la suppression de compte est confirmée par le backend**, pour effacer immédiatement la session locale et éviter de rejouer l'ancien token au lancement suivant — en complément de la vérification côté backend ci-dessus, pas à sa place (l'utilisateur peut avoir plusieurs appareils connectés au même compte, tous ne recevront pas cette confirmation immédiatement).

## Alternatives
- JWT maison + bcrypt (ADR-015 initial) : contrôle total sur le format des tokens et leur durée de vie, mais réimplémente ce que Firebase Authentication fait déjà gratuitement, et n'apporte de valeur pédagogique que si l'implémentation de l'auth elle-même est un objectif d'évaluation.
- Vérification d'email désactivée comme le numéro de téléphone (cohérence stricte avec ADR-010) : plus simple, mais écarté ici puisque le coût est nul et que la vérification d'email est une fonctionnalité réelle de Snapchat qu'il est dommage de ne pas illustrer quand elle ne coûte rien.

## Conséquences
### Positives
- Aucun frais réel (email/mot de passe + vérification d'email sont gratuits sur le plan Spark).
- Beaucoup moins de code d'authentification à écrire et maintenir (hash, tokens, rafraîchissement, envoi d'email) : Firebase s'en charge.
- Reste dans l'écosystème Firebase déjà utilisé pour les notifications push (ADR-018) : un seul projet, un seul SDK Admin côté backend.
- Illustre un vrai flux de vérification d'email, sans le coût ni la complexité d'un système de code maison qu'ADR-010 avait explicitement écarté.

### Négatives
- L'identité de l'utilisateur est en partie déléguée à un service tiers (Firebase) : le backend doit vérifier les ID tokens via le SDK Admin plutôt que de les signer lui-même, une dépendance externe de plus (comme RevenueCat ou PostHog).
- Moins de démonstration d'implémentation d'authentification "from scratch" que la version JWT maison — acceptable ici, l'objectif pédagogique du projet portant sur l'architecture globale plutôt que sur la réimplémentation d'un système d'auth déjà résolu par Firebase.
- Un compte Firebase peut exister sans ligne `User` correspondante si l'inscription est interrompue entre la création côté Firebase et l'appel à `POST /auth/register` — l'app doit réessayer cet appel explicite au prochain lancement plutôt que de compter sur une recréation automatique côté backend (voir Décision et ADR-019 pour pourquoi une recréation automatique est dangereuse).

## Historique
- Version initiale (supprimée, non conservée sous ce numéro) : JWT maison (access + refresh) avec mot de passe hashé en bcrypt, sans vérification d'email.
- Révision 1 : remplacement par Firebase Authentication (email/mot de passe), avec vérification d'email activée (gratuite) mais non bloquante. À partir de cette révision, les futures évolutions de cet ADR sont documentées ici plutôt que réécrites sans trace.
- Révision 2 (revue technique) : suppression de la règle "créer la ligne User si elle n'existe pas pour un firebaseUid valide" sur une requête quelconque — elle permettait à un ID token émis avant une suppression de compte (ADR-019) de recréer le compte pendant toute sa durée de validité restante (jusqu'à ~1h). Remplacée par un endpoint d'inscription explicite, seul habilité à créer la ligne `User`.
- Révision 3 (revue technique) : le bug n'avait pas disparu, il avait changé d'endpoint — `POST /auth/register` lui-même recréait le compte en rejouant un ancien token encore valide après suppression, puisque `verifyIdToken` sans `checkRevoked` ne vérifie que la signature, pas l'existence réelle du compte. Ajout de la vérification `checkRevoked`/`getUser` avant toute création de ligne `User`, et ajout du `signOut()` côté app à la confirmation de suppression.

## Sources
- https://firebase.google.com/pricing (plan Spark : authentification email/mot de passe et vérification d'email gratuites)
- https://firebase.google.com/docs/auth/limits (quotas : vérification d'email gratuite, limite indicative d'environ 1000 envois/jour sur le plan Spark)

Ces chiffres n'ont pas pu être vérifiés autrement que par lecture de la documentation officielle Firebase citée ci-dessus ; à revalider si Firebase change sa politique de tarification.

## Liens
- ADR liés : ADR-002, ADR-010, ADR-018

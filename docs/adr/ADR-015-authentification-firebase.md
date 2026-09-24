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
- Un compte Firebase peut exister sans ligne `User` correspondante si l'inscription est interrompue entre la création côté Firebase et la création de la ligne côté backend — à gérer par une vérification/synchronisation à la connexion (créer la ligne `User` si elle n'existe pas encore pour un `firebaseUid` valide).

## Historique
- Version initiale (supprimée, non conservée sous ce numéro) : JWT maison (access + refresh) avec mot de passe hashé en bcrypt, sans vérification d'email.
- Révision : remplacement par Firebase Authentication (email/mot de passe), avec vérification d'email activée (gratuite) mais non bloquante. À partir de cette révision, les futures évolutions de cet ADR sont documentées ici plutôt que réécrites sans trace.

## Sources
- https://firebase.google.com/pricing (plan Spark : authentification email/mot de passe et vérification d'email gratuites)
- https://firebase.google.com/docs/auth/limits (quotas : vérification d'email gratuite, limite indicative d'environ 1000 envois/jour sur le plan Spark)

Ces chiffres n'ont pas pu être vérifiés autrement que par lecture de la documentation officielle Firebase citée ci-dessus ; à revalider si Firebase change sa politique de tarification.

## Liens
- ADR liés : ADR-002, ADR-010, ADR-018

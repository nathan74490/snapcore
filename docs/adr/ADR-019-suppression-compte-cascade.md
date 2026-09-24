# ADR-019 — Suppression de compte : Firebase, R2 et cascade sur les données liées

## Statut
Proposé

## Contexte
Une première version ne supprimait que la ligne `User` (et ses données liées en cascade côté PostgreSQL). Deux angles morts en résultaient, tous deux **bloquants** :
- ADR-015 prévoit que le backend recrée automatiquement une ligne `User` pour tout `firebaseUid` valide qui n'en a pas encore — si le compte Firebase n'est jamais supprimé, l'utilisateur se reconnecte et récupère un compte tout neuf juste après avoir "supprimé" le sien.
- La cascade PostgreSQL ne supprime que des lignes de base ; elle ne touche jamais aux objets R2 (médias des messages, des stories, photo de profil), qui restent orphelins indéfiniment.

Une révision suivante a corrigé ces deux points en ajoutant la suppression Firebase et R2, mais dans un ordre problématique (R2 avant Firebase) : si la suppression Firebase échoue après que les objets R2 ont déjà été supprimés, l'utilisateur conserve un compte fonctionnel mais a perdu tous ses médias — un nouvel état incohérent, différent du premier mais tout aussi réel. Il manquait également l'annulation de l'abonnement SnapCore+ (RevenueCat/Stripe) : sans elle, un abonné qui supprime son compte continuerait d'être prélevé (en mode test, mais le principe resterait faux en production).

## Décision
La suppression de compte est une **opération asynchrone, idempotente et rejouable**, pas une séquence de suppressions synchrones dans la requête HTTP :

1. **Requête de suppression (synchrone, rapide)** : le backend relève immédiatement tous les `mediaKey` non nuls des `Message` (envoyés ou reçus) et `StoryItem` de l'utilisateur, ainsi que la clé de sa photo de profil (déduite de `profilePhotoUrl`, voir ADR-016) — nécessaire puisque la suite de l'opération supprimera ces lignes et rendrait ces clés irrécupérables ensuite. Ces clés sont stockées dans la charge utile du job (voir étape 2), pas dans une table intermédiaire. Le champ `User.deletionRequestedAt` est renseigné (`now()`), marquant le compte **"en cours de suppression"** : à partir de cet instant, le backend refuse toute nouvelle connexion et toute action pour ce compte (comportement identique à un compte inexistant du point de vue de l'API), même si les étapes suivantes n'ont pas encore fini de s'exécuter.
2. **Job BullMQ idempotent, avec réessais**, exécutant dans cet ordre :
   a. **Annulation de l'abonnement SnapCore+** le cas échéant (RevenueCat/Stripe, ADR-007) — un abonné qui supprime son compte ne doit plus être prélevé, même en mode test.
   b. **Suppression du compte Firebase** via le SDK Admin (`admin.auth().deleteUser(firebaseUid)`) — empêche la recréation automatique décrite par ADR-015, puisqu'aucun `firebaseUid` valide ne subsiste pour ce compte. Idempotent : si le compte Firebase a déjà été supprimé lors d'une tentative précédente, l'erreur "utilisateur introuvable" renvoyée par le SDK est traitée comme un succès.
   c. **Suppression de la ligne `User`**, qui déclenche la cascade PostgreSQL déjà définie (`Message`, `Friendship`, `StoryItem`, `DeviceToken`). Idempotent : si la ligne a déjà été supprimée lors d'une tentative précédente, il n'y a rien à faire.
   d. **Suppression des objets R2** listés à l'étape 1, à partir de la charge utile du job (et non d'une nouvelle lecture en base, puisque la ligne `User` n'existe plus à ce stade). Idempotent : supprimer un objet R2 déjà supprimé n'est pas une erreur.

**Pourquoi cet ordre plutôt que R2 avant Firebase** : si l'étape (a) ou (b) échoue, rien d'irréversible n'a encore eu lieu (ni le compte Firebase, ni la ligne `User`, ni les objets R2 ne sont touchés) — le job réessaie sans perte de cohérence, et `deletionRequestedAt` continue de bloquer l'accès au compte entre-temps. Un ordre où R2 est supprimé en premier exposait un état incohérent bien pire : un compte qui reste vivant (connexion toujours possible via Firebase) mais qui a déjà perdu tous ses médias, sans qu'aucune nouvelle tentative ne puisse les restaurer.

Le job est déclenché immédiatement après l'étape 1, mais peut être réexécuté (manuellement ou par le mécanisme de retry de BullMQ) autant de fois que nécessaire jusqu'à son succès complet, chaque étape étant conçue pour être rejouée sans effet de bord si elle a déjà réussi.

**Jobs BullMQ en attente sur des lignes supprimées** : un job de purge (ADR-017) programmé pour un `Message`/`StoryItem` qui a depuis été supprimé par cette cascade doit gérer l'absence de la ligne comme un cas normal (la ligne n'existe plus : rien à purger, rien à faire), pas comme une erreur — le handler du job vérifie l'existence de la ligne avant d'agir et se termine silencieusement (avec un simple log) si elle n'existe plus.

Le champ `Friendship.blockedBy` reste en `onDelete: SetNull`, mais ce cas ne peut en réalité jamais se produire : l'utilisateur qui bloque fait nécessairement partie de la paire (`requesterId` ou `addresseeId`), donc sa suppression déclenche de toute façon la suppression de la ligne `Friendship` entière via la cascade sur ces deux colonnes, avant que `SetNull` ait l'occasion de s'appliquer. Le comportement reste sans danger, mais ce n'est pas un cas rare — c'est un cas impossible ; ce choix n'est là que par cohérence avec le reste du modèle, pas par nécessité.

## Alternatives
- Conservation anonymisée des messages (utilisateur fantôme "Compte supprimé") : plus proche d'une vraie app de messagerie, écartée pour la complexité qu'elle ajoute sans bénéfice pour ce projet.
- Ne pas supprimer le compte Firebase, mais le désactiver (`disabled: true`) plutôt que le supprimer : empêcherait la reconnexion sans supprimer l'historique Firebase, mais ADR-015 recrée déjà une ligne `User` pour tout `firebaseUid` valide — un compte désactivé reste un `firebaseUid` valide selon cette logique, il faudrait alors aussi modifier ADR-015 pour vérifier l'état `disabled`. La suppression complète est plus simple et suffisante ici.
- Suppression R2 avant Firebase (version précédente de cet ADR) : écartée, exposait un état incohérent pire en cas d'échec partiel (compte toujours accessible mais médias déjà perdus) que l'ordre retenu (rien d'irréversible tant que Firebase et la base n'ont pas été traités avec succès).
- Suppression synchrone dans la requête HTTP plutôt qu'un job asynchrone : plus simple à suivre, mais une requête HTTP n'est pas l'endroit pour une opération en plusieurs étapes externes (Firebase, R2, RevenueCat) avec retries ; `deletionRequestedAt` permet de répondre immédiatement à l'utilisateur tout en garantissant qu'aucune action ne reste possible sur le compte pendant que le job termine son travail.

## Conséquences
### Positives
- Un compte supprimé ne peut plus se recréer tout seul à la reconnexion suivante.
- Plus aucun objet média orphelin sur R2 après une suppression de compte.
- Les jobs de purge en attente sur des lignes disparues ne provoquent plus d'erreur.
- Un échec à n'importe quelle étape du job ne laisse jamais un état pire que "compte marqué en suppression mais encore intact" — jamais de médias perdus avec un compte qui reste vivant, ni l'inverse.
- Un abonné qui supprime son compte n'est plus prélevé pour un service qu'il ne peut plus utiliser.

### Négatives
- La suppression de compte devient une opération asynchrone en plusieurs étapes (RevenueCat/Stripe, Firebase, PostgreSQL, R2) plutôt qu'une seule suppression en base — chaque étape doit être rendue idempotente et couverte par un test d'intégration simulant un échec à chaque point de la séquence.
- Entre la requête de suppression et la fin effective du job, le compte est marqué "en suppression" mais ses lignes existent encore techniquement en base le temps que le job s'exécute — sans conséquence utilisateur puisque l'accès est déjà bloqué par `deletionRequestedAt`, mais à garder en tête pour tout code qui lirait directement la table `User` sans passer par l'API.
- Un utilisateur qui supprime son compte fait disparaître les messages qu'il a envoyés même du point de vue de son interlocuteur — acceptable pour un projet pédagogique sans utilisateurs réels à protéger.

## Historique
- Version initiale : cascade PostgreSQL uniquement (`onDelete: Cascade`/`SetNull`), sans suppression Firebase ni nettoyage R2.
- Révision 1 (revue technique) : ajout de la suppression du compte Firebase et des objets R2 avant la cascade base de données, gestion des jobs BullMQ orphelins, correction de la justification (erronée) du `SetNull` sur `blockedBy`.
- Révision 2 (revue technique) : opération transformée en job BullMQ asynchrone et idempotent, réordonnée (RevenueCat/Stripe → Firebase → PostgreSQL → R2, au lieu de R2 avant Firebase) pour qu'aucun échec partiel ne laisse un état pire que "compte marqué en suppression mais intact" ; ajout de `User.deletionRequestedAt` pour bloquer l'accès au compte pendant le traitement ; ajout de l'annulation de l'abonnement SnapCore+.

## Liens
- ADR liés : ADR-004, ADR-007, ADR-009, ADR-012, ADR-013, ADR-015, ADR-016, ADR-018

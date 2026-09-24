# ADR-019 — Suppression de compte : Firebase, R2 et cascade sur les données liées

## Statut
Proposé

## Contexte
Supprimer un compte doit couper l'accès immédiatement, ne jamais laisser le compte se recréer tout seul, ne laisser aucun média orphelin sur R2, et ne pas continuer à prélever un abonnement SnapCore+ actif — tout en restant cohérent si une étape échoue en cours de route.

## Décision
La suppression de compte est une **opération asynchrone, idempotente et rejouable** (job BullMQ), pas une séquence de suppressions synchrones dans la requête HTTP :

1. **Requête de suppression (synchrone, rapide)** : le backend renseigne `User.deletionRequestedAt` (`now()`) et met en file le job de suppression avec le seul `userId` en charge utile. À partir de cet instant :
   - le compte ne peut plus se connecter ni agir (comportement identique à un compte inexistant du point de vue de l'API) ;
   - **le compte est également traité comme inexistant du point de vue des autres utilisateurs** : l'envoi d'un message ou d'un snap vers ce compte est refusé, son profil n'apparaît plus dans une recherche ni dans une liste d'amis, et ses stories ne sont plus servies. Sans cette règle, un ami pourrait encore envoyer un Snap au compte en cours de suppression pendant que le job s'exécute ; ce média serait copié vers `messages/<messageId>` (ADR-016, sans règle de cycle de vie) puis orphelin pour toujours si le `Message` correspondant disparaît par la suite via une collecte de clés déjà effectuée.
2. **Job BullMQ, réessais quasi illimités avec backoff exponentiel plafonné** (ex. toutes les 30 min après les premières tentatives rapprochées) **et alerte si le job échoue encore après 24h** — jamais d'abandon silencieux, un compte "en suppression" qui reste bloqué indéfiniment sans que personne ne le sache serait pire qu'un léger délai. Le job exécute, dans cet ordre :
   a. **Annulation de l'abonnement SnapCore+** le cas échéant (RevenueCat/Stripe, ADR-007) — un abonné qui supprime son compte ne doit plus être prélevé, même en mode test.
   b. **Suppression du compte Firebase** via le SDK Admin (`admin.auth().deleteUser(firebaseUid)`) — idempotent : une erreur "utilisateur introuvable" (compte déjà supprimé lors d'une tentative précédente) est traitée comme un succès.
   c. **Dans une seule transaction PostgreSQL** : relève tous les `mediaKey` non nuls des `Message` (envoyés ou reçus) et `StoryItem` de l'utilisateur, ainsi que la clé de sa photo de profil (déduite de `profilePhotoUrl`, ADR-016), **puis** supprime la ligne `User` (déclenchant la cascade `Message`/`Friendship`/`StoryItem`/`DeviceToken`) — collecte et suppression sont atomiques : aucune fenêtre où une clé pourrait être créée ou modifiée entre la lecture et la suppression. C'est volontairement la dernière chose lue avant la suppression, pas la première étape de la requête HTTP, pour rester au plus près du moment réel de la cascade (voir Historique pour la version précédente qui collectait trop tôt).
   Les clés collectées à l'étape (c) sont aussitôt persistées dans la charge utile du job (`job.updateData(...)`) avant de passer à l'étape (d) — si le job est interrompu ou échoue après (c), un rejeu reprend directement à (d) avec les clés déjà connues, sans dépendre d'une ligne `User` qui n'existe plus.
   d. **Suppression des objets R2** correspondant aux clés persistées à l'étape (c) — idempotent : supprimer un objet déjà supprimé n'est pas une erreur.

**Pourquoi cet ordre (RevenueCat/Stripe → Firebase → PostgreSQL → R2)** : tant que (a) ou (b) n'ont pas réussi, rien d'irréversible n'a eu lieu et le job réessaie sans perte de cohérence, `deletionRequestedAt` continuant de bloquer le compte entre-temps. Collecter les clés dans la même transaction que la suppression de `User`, plutôt qu'à la requête initiale, ferme la fenêtre où un média pourrait être ajouté au compte entre la collecte et la suppression réelle des lignes.

**Jobs BullMQ en attente sur des lignes supprimées** : un job de purge (ADR-017) programmé pour un `Message`/`StoryItem` qui a depuis été supprimé par cette cascade doit gérer l'absence de la ligne comme un cas normal (rien à purger, rien à faire), pas comme une erreur — le handler vérifie l'existence de la ligne avant d'agir et se termine silencieusement (avec un simple log) si elle n'existe plus.

Le champ `Friendship.blockedBy` reste en `onDelete: SetNull`, mais ce cas ne peut en réalité jamais se produire : l'utilisateur qui bloque fait nécessairement partie de la paire (`requesterId` ou `addresseeId`), donc sa suppression déclenche de toute façon la suppression de la ligne `Friendship` entière via la cascade sur ces deux colonnes, avant que `SetNull` ait l'occasion de s'appliquer. Le comportement reste sans danger ; ce choix n'est là que par cohérence avec le reste du modèle, pas par nécessité.

## Alternatives
- Conservation anonymisée des messages (utilisateur fantôme "Compte supprimé") : plus proche d'une vraie app de messagerie, écartée pour la complexité qu'elle ajoute sans bénéfice pour ce projet.
- Ne pas supprimer le compte Firebase, mais le désactiver (`disabled: true`) : ADR-015 recrée déjà une ligne `User` pour tout `firebaseUid` valide, il faudrait alors aussi lui apprendre à vérifier l'état `disabled` ; la suppression complète est plus simple et suffisante ici.
- Suppression R2 avant Firebase : écartée, exposait un état incohérent pire en cas d'échec partiel (compte accessible mais médias déjà perdus) que l'ordre retenu.
- Collecte des clés dès la requête HTTP de suppression (version précédente) : écartée, laissait une fenêtre où un ami pouvait encore envoyer un média au compte après la collecte mais avant la suppression réelle des lignes, créant un objet R2 orphelin.
- Nombre de réessais borné sans alerte : écarté, un job qui abandonne silencieusement après N tentatives peut laisser un compte bloqué indéfiniment (ni supprimé, ni réactivable) sans que personne ne le remarque.

## Conséquences
### Positives
- Un compte supprimé ne peut plus se recréer tout seul à la reconnexion suivante.
- Plus aucun objet média orphelin sur R2 après une suppression de compte, y compris pour un média envoyé pendant que le job de suppression s'exécute (bloqué en amont par le traitement "compte inexistant").
- Les jobs de purge en attente sur des lignes disparues ne provoquent plus d'erreur.
- Un abonné qui supprime son compte n'est plus prélevé pour un service qu'il ne peut plus utiliser.
- Un échec à n'importe quelle étape ne fait jamais perdre les clés déjà collectées (persistées dans la charge utile du job dès qu'elles sont connues), et un job durablement bloqué déclenche une alerte plutôt que d'échouer en silence.

### Négatives
- La suppression de compte est une opération asynchrone en plusieurs étapes (RevenueCat/Stripe, Firebase, PostgreSQL, R2) — chaque étape doit être rendue idempotente et couverte par un test d'intégration simulant un échec à chaque point de la séquence.
- Traiter un compte "en suppression" comme inexistant pour les autres utilisateurs demande de vérifier `deletionRequestedAt IS NULL` à plusieurs endroits déjà existants (envoi de message, recherche de profil, liste d'amis, visibilité des stories) plutôt qu'à un seul endroit centralisé — risque d'oubli à surveiller lors de l'implémentation.
- Un utilisateur qui supprime son compte fait disparaître les messages qu'il a envoyés même du point de vue de son interlocuteur — acceptable pour un projet pédagogique sans utilisateurs réels à protéger.

## Historique
- Version initiale : cascade PostgreSQL uniquement (`onDelete: Cascade`/`SetNull`), sans suppression Firebase ni nettoyage R2.
- Révision 1 (revue technique) : ajout de la suppression du compte Firebase et des objets R2 avant la cascade base de données, gestion des jobs BullMQ orphelins, correction de la justification (erronée) du `SetNull` sur `blockedBy`.
- Révision 2 (revue technique) : opération transformée en job BullMQ asynchrone et idempotent, réordonnée (RevenueCat/Stripe → Firebase → PostgreSQL → R2, au lieu de R2 avant Firebase) pour qu'aucun échec partiel ne laisse un état pire que "compte marqué en suppression mais intact" ; ajout de `User.deletionRequestedAt` pour bloquer l'accès au compte pendant le traitement ; ajout de l'annulation de l'abonnement SnapCore+.
- Révision 3 (revue technique) : la collecte des `mediaKey` est déplacée de la requête HTTP initiale vers la même transaction que la suppression de la ligne `User`, pour fermer la fenêtre où un ami pouvait encore envoyer un média au compte entre-temps ; le compte "en suppression" est désormais traité comme inexistant pour les autres utilisateurs (envoi refusé, profil masqué, absent des listes d'amis) ; réessais du job rendus quasi illimités avec alerte au-delà de 24h, au lieu d'un nombre de tentatives borné.

## Liens
- ADR liés : ADR-004, ADR-007, ADR-009, ADR-012, ADR-013, ADR-015, ADR-016, ADR-018

# ADR-019 — Suppression de compte : Firebase, R2 et cascade sur les données liées

## Statut
Proposé

## Contexte
Une première version ne supprimait que la ligne `User` (et ses données liées en cascade côté PostgreSQL). Deux angles morts en résultaient, tous deux **bloquants** :
- ADR-015 prévoit que le backend recrée automatiquement une ligne `User` pour tout `firebaseUid` valide qui n'en a pas encore — si le compte Firebase n'est jamais supprimé, l'utilisateur se reconnecte et récupère un compte tout neuf juste après avoir "supprimé" le sien.
- La cascade PostgreSQL ne supprime que des lignes de base ; elle ne touche jamais aux objets R2 (médias des messages, des stories, photo de profil), qui restent orphelins indéfiniment.

## Décision
Supprimer un compte déclenche, dans cet ordre :
1. **Collecte des références média** : avant toute suppression, le backend relève tous les `mediaKey` non nuls des `Message` (envoyés ou reçus) et `StoryItem` de l'utilisateur, ainsi que la clé de sa photo de profil — nécessaire puisque la cascade PostgreSQL supprimera ces lignes et rendrait ces clés irrécupérables ensuite.
2. **Suppression des objets R2** correspondants (ADR-016).
3. **Suppression du compte Firebase** via le SDK Admin (`admin.auth().deleteUser(firebaseUid)`) — empêche la recréation automatique décrite par ADR-015, puisqu'aucun `firebaseUid` valide ne subsiste pour ce compte.
4. **Suppression de la ligne `User`**, qui déclenche la cascade PostgreSQL déjà définie (`Message`, `Friendship`, `StoryItem`, `DeviceToken`).

Les étapes 1 à 3 sont exécutées côté backend avant l'étape 4 ; si l'une échoue (ex. la suppression Firebase échoue), la suppression de la ligne `User` n'a pas lieu, pour éviter un état incohérent (compte Firebase vivant sans ligne `User`, qui redéclencherait la recréation automatique).

**Jobs BullMQ en attente sur des lignes supprimées** : un job de purge (ADR-017) programmé pour un `Message`/`StoryItem` qui a depuis été supprimé par cette cascade doit gérer l'absence de la ligne comme un cas normal (la ligne n'existe plus : rien à purger, rien à faire), pas comme une erreur — le handler du job vérifie l'existence de la ligne avant d'agir et se termine silencieusement (avec un simple log) si elle n'existe plus.

Le champ `Friendship.blockedBy` reste en `onDelete: SetNull`, mais ce cas ne peut en réalité jamais se produire : l'utilisateur qui bloque fait nécessairement partie de la paire (`requesterId` ou `addresseeId`), donc sa suppression déclenche de toute façon la suppression de la ligne `Friendship` entière via la cascade sur ces deux colonnes, avant que `SetNull` ait l'occasion de s'appliquer. Le comportement reste sans danger, mais ce n'est pas un cas rare — c'est un cas impossible ; ce choix n'est là que par cohérence avec le reste du modèle, pas par nécessité.

## Alternatives
- Conservation anonymisée des messages (utilisateur fantôme "Compte supprimé") : plus proche d'une vraie app de messagerie, écartée pour la complexité qu'elle ajoute sans bénéfice pour ce projet.
- Ne pas supprimer le compte Firebase, mais le désactiver (`disabled: true`) plutôt que le supprimer : empêcherait la reconnexion sans supprimer l'historique Firebase, mais ADR-015 recrée déjà une ligne `User` pour tout `firebaseUid` valide — un compte désactivé reste un `firebaseUid` valide selon cette logique, il faudrait alors aussi modifier ADR-015 pour vérifier l'état `disabled`. La suppression complète est plus simple et suffisante ici.

## Conséquences
### Positives
- Un compte supprimé ne peut plus se recréer tout seul à la reconnexion suivante.
- Plus aucun objet média orphelin sur R2 après une suppression de compte.
- Les jobs de purge en attente sur des lignes disparues ne provoquent plus d'erreur.

### Négatives
- La suppression de compte devient une opération en plusieurs étapes (R2, Firebase, PostgreSQL) plutôt qu'une seule suppression en base — nécessite une gestion d'erreur explicite si une étape échoue en cours de route (à couvrir par un test d'intégration).
- Un utilisateur qui supprime son compte fait disparaître les messages qu'il a envoyés même du point de vue de son interlocuteur — acceptable pour un projet pédagogique sans utilisateurs réels à protéger.

## Historique
- Version initiale : cascade PostgreSQL uniquement (`onDelete: Cascade`/`SetNull`), sans suppression Firebase ni nettoyage R2.
- Révision (revue technique) : ajout de la suppression du compte Firebase et des objets R2 avant la cascade base de données, gestion des jobs BullMQ orphelins, correction de la justification (erronée) du `SetNull` sur `blockedBy`.

## Liens
- ADR liés : ADR-004, ADR-009, ADR-012, ADR-013, ADR-015, ADR-016, ADR-018

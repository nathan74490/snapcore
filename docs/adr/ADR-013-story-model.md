# ADR-013 — Story : un STORY_ITEM par snap ajouté, expiration et vues réutilisant les mécanismes existants

## Statut
Accepté

## Contexte
SnapCore doit proposer les stories : une succession de snaps publiés par un utilisateur, visibles par ses amis pendant 24h, avec disparition automatique du contenu et de la liste des personnes ayant vu chaque snap. Le projet dispose déjà d'un worker BullMQ de purge des médias expirés (ADR-017).

## Décision
- Une entité `STORY_ITEM` par snap ajouté à une story (pas une entité "Story" unique) : `id`, `authorId`, `mediaKey`, `contentType` (`PHOTO` | `VIDEO`), `createdAt`, `expiresAt` (`createdAt` + 24h). La "story" affichée à l'écran est simplement l'ensemble des `STORY_ITEM` non expirés d'un même auteur.
- Expiration : chaque `STORY_ITEM` programme, à sa création, le job BullMQ de purge des médias déjà prévu dans l'architecture globale (ADR-017), exécuté à `expiresAt` (suppression de l'objet R2 + suppression de la ligne `STORY_ITEM`, contrairement au `Message` d'un snap privé dont seule la ligne est conservée à vide — voir ADR-009 : il n'y a pas de besoin d'affichage "expiré" pour un élément de story).
- Suivi des vues : un `Set` Redis par `STORY_ITEM` (`story:views:<storyItemId>`) contenant les IDs des viewers, avec un TTL aligné sur `expiresAt`, pas de table PostgreSQL dédiée. Ce choix est indépendant du suivi de statut des snaps privés, qui ne repose plus sur Redis depuis ADR-009 (le statut y est stocké durablement en Postgres via `openedAt`) : pour une story, en revanche, la liste des viewers n'a pas besoin de survivre à l'expiration du contenu, donc Redis reste adapté ici.
- Visibilité : tous les amis acceptés (statut `ACCEPTED` sur `FRIENDSHIP`, voir ADR-012) peuvent voir la story. Pas de liste personnalisée ("story privée") en V1.
- **L'API ne se fie jamais uniquement à la présence/absence de la ligne `STORY_ITEM` pour décider si un élément est encore visible** : chaque requête de liste de stories filtre explicitement sur `expiresAt > now()`, en plus de l'exclusion naturelle des lignes déjà supprimées par la purge. Cela couvre la fenêtre où le job de purge (ADR-017, y compris son balayage de rattrapage) a pris du retard : sans ce filtre, un `STORY_ITEM` expiré depuis quelques minutes mais pas encore purgé resterait visible.

## Alternatives
- Une entité "Story" unique contenant une liste de médias : modélisation plus proche de l'affichage final, mais s'éloigne du fonctionnement réel de Snapchat (chaque snap ajouté a sa propre expiration à 24h de sa publication, pas de la création de la story) et complique la purge individuelle des éléments expirés.
- Stocker les vues en PostgreSQL plutôt que Redis : permettrait de garder un historique après expiration, mais contraire au principe déjà retenu (ADR-003) de garder les données à durée de vie limitée hors de la base durable.
- Listes personnalisées ("story privée") dès la V1 : plus fidèle à Snapchat, mais alourdit le premier lot ; repoussé en V2 avec Snapcode, meilleur ami et flammes (ADR-012).

## Conséquences
### Positives
- Réutilise entièrement l'infrastructure existante (job de purge BullMQ, suivi de vue Redis avec TTL) : aucune nouvelle brique.
- Modèle fidèle au fonctionnement réel de Snapchat (expiration par snap, pas par story entière).

### Négatives
- Reconstituer "la story" d'un utilisateur nécessite de requêter tous ses `STORY_ITEM` non expirés à chaque affichage, plutôt qu'une seule ligne à lire — acceptable vu le volume attendu pour ce projet.

## Historique
- Révision 1 : remplacement de `mediaUrl` par `mediaKey` (cohérence avec ADR-016) ; suppression de la comparaison erronée avec le suivi de vue des snaps privés, qui ne repose plus sur Redis depuis ADR-009 ; clarification que la ligne `STORY_ITEM` est bien supprimée à la purge (contrairement au `Message` d'un snap privé).
- Révision 2 (revue technique) : ajout du filtre explicite `expiresAt > now()` côté API, pour ne jamais dépendre uniquement de la suppression effective de la ligne (qui peut avoir du retard, notamment via le balayage de rattrapage d'ADR-017).

## Liens
- ADR liés : ADR-003, ADR-009, ADR-012, ADR-016, ADR-017

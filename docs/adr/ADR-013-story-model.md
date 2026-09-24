# ADR-013 — Story : un STORY_ITEM par snap ajouté, expiration et vues réutilisant les mécanismes existants

## Statut
Proposé

## Contexte
SnapCore doit proposer les stories : une succession de snaps publiés par un utilisateur, visibles par ses amis pendant 24h, avec disparition automatique du contenu et de la liste des personnes ayant vu chaque snap. Le projet dispose déjà d'un worker BullMQ de purge des médias expirés et d'un mécanisme de suivi de vue éphémère dans Redis (ADR-003) pour les snaps privés.

## Décision
- Une entité `STORY_ITEM` par snap ajouté à une story (pas une entité "Story" unique) : `id`, `authorId`, `mediaUrl`, `contentType` (`PHOTO` | `VIDEO`), `createdAt`, `expiresAt` (`createdAt` + 24h). La "story" affichée à l'écran est simplement l'ensemble des `STORY_ITEM` non expirés d'un même auteur.
- Expiration : chaque `STORY_ITEM` programme, à sa création, le job BullMQ de purge des médias déjà prévu dans l'architecture globale, exécuté à `expiresAt` (suppression du média sur R2 + suppression de la ligne PostgreSQL).
- Suivi des vues : un `Set` Redis par `STORY_ITEM` (`story:views:<storyItemId>`) contenant les IDs des viewers, avec un TTL aligné sur `expiresAt` — même principe que le suivi de vue des snaps privés (ADR-003), pas de table PostgreSQL dédiée.
- Visibilité : tous les amis acceptés (statut `ACCEPTED` sur `FRIENDSHIP`, voir ADR-012) peuvent voir la story. Pas de liste personnalisée ("story privée") en V1.

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

## Liens
- ADR liés : ADR-003, ADR-012

# ADR-003 : Séparation des données éphémères (Redis) et durables (PostgreSQL)

## Statut
Accepté

## Contexte
SnapCore manipule à la fois des données qui doivent disparaître automatiquement (présence temps réel de l'utilisateur, et plus tard la position sur la carte, Snap Map n'étant pas prévu en V1) et des données qui doivent être conservées durablement (comptes, amis, messages, statut d'ouverture des snaps, contenus explicitement sauvegardés dans Memories). Le statut de vue d'un snap (`openedAt`) n'est plus un exemple de donnée éphémère Redis : il est stocké en base Postgres sur la ligne `Message` elle-même (voir ADR-009), car il doit rester consultable durablement (icône grisée) même après la purge du média.

## Décision
Stocker toute donnée à durée de vie limitée dans Redis avec une expiration (TTL) automatique, et réserver PostgreSQL aux données explicitement durables.

## Alternatives
- Tout stocker dans PostgreSQL avec des jobs de nettoyage périodiques (cron) pour purger les données expirées : plus simple en apparence (une seule base), mais risque d'oubli de purge, charge inutile sur la base relationnelle pour des données à très courte durée de vie, et latence plus élevée que Redis pour des lectures/écritures fréquentes.

## Conséquences
### Positives
- Expiration automatique et fiable (TTL natif Redis) pour les données réellement volatiles, sans dépendre d'un scan comme mécanisme principal. Ce qui est évité ici, c'est de s'appuyer *uniquement* sur un balayage périodique pour détecter ce qui a expiré (la solution "tout en PostgreSQL + cron" écartée en Alternatives) ; cela n'exclut pas qu'un contenu individuel programme sa propre action de purge à une échéance connue, avec un balayage de rattrapage en filet de sécurité secondaire (voir ADR-017 pour les médias) ; la distinction est entre "scanner comme mécanisme principal" (évité) et "planifier une action précise, avec un balayage de secours peu fréquent en complément" (complémentaire, pas contradictoire).
- Performance élevée pour les données consultées très fréquemment.

### Négatives
- Deux systèmes de stockage à maintenir et surveiller.
- Une donnée mal classée (durable stockée dans Redis par erreur) peut être perdue si Redis redémarre sans persistance configurée.

## Historique
- Révision 1 : correction de l'exemple de donnée éphémère (le statut de vue d'un snap n'est plus sur Redis mais sur la ligne Message en Postgres, voir ADR-009).
- Révision 2 : clarification de ce qui est réellement évité (le scan comme mécanisme *principal*) pour lever la contradiction apparente avec le balayage de rattrapage secondaire ajouté par ADR-017.

## Liens
- ADR liés : ADR-009

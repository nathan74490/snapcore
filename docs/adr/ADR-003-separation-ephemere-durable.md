# ADR-003 — Séparation des données éphémères (Redis) et durables (PostgreSQL)

## Statut
Proposé

## Contexte
SnapCore manipule à la fois des données qui doivent disparaître automatiquement (statut de vue d'un snap, position sur la carte, présence temps réel) et des données qui doivent être conservées durablement (comptes, amis, contenus explicitement sauvegardés dans Memories).

## Décision
Stocker toute donnée à durée de vie limitée dans Redis avec une expiration (TTL) automatique, et réserver PostgreSQL aux données explicitement durables.

## Alternatives
- Tout stocker dans PostgreSQL avec des jobs de nettoyage périodiques (cron) pour purger les données expirées : plus simple en apparence (une seule base), mais risque d'oubli de purge, charge inutile sur la base relationnelle pour des données à très courte durée de vie, et latence plus élevée que Redis pour des lectures/écritures fréquentes.

## Conséquences
### Positives
- Expiration automatique et fiable (TTL natif Redis), sans job de nettoyage à maintenir.
- Performance élevée pour les données consultées très fréquemment.

### Négatives
- Deux systèmes de stockage à maintenir et surveiller.
- Une donnée mal classée (durable stockée dans Redis par erreur) peut être perdue si Redis redémarre sans persistance configurée.

## Liens
- ADR liés : aucun

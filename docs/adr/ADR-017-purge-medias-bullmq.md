# ADR-017 — Purge automatique des médias expirés : job BullMQ

## Statut
Proposé

## Contexte
ADR-013 (story) et le principe d'éphémérité (ADR-003) supposent l'existence d'un "job BullMQ de purge des médias déjà prévu dans l'architecture globale", jamais documenté par un ADR propre. Cette décision a été prise en amont lors de la conception de l'architecture globale ; ce document la formalise a posteriori.

## Décision
- Chaque contenu à durée de vie limitée (snap privé, `STORY_ITEM`) programme, à sa création, un job **BullMQ** planifié à sa date d'expiration (`expiresAt`).
- À l'exécution du job : suppression du fichier média sur Cloudflare R2 (ADR-016) puis suppression de la ligne PostgreSQL correspondante.
- BullMQ s'appuie sur Redis pour la file de jobs différés, ce qui ne nécessite aucune nouvelle brique d'infra (Redis est déjà central au projet, ADR-003).

## Alternatives
- Tâche cron périodique qui scanne les lignes expirées : plus simple à première vue, mais introduit un délai entre l'expiration réelle et la purge effective (dépend de la fréquence du cron), et une charge de lecture répétée sur PostgreSQL même quand rien n'a expiré.

## Conséquences
### Positives
- Purge précise, déclenchée exactement à l'expiration, sans scan périodique.
- Réutilise l'infrastructure Redis déjà en place (ADR-003) : aucune nouvelle brique.

### Négatives
- Si le worker BullMQ est arrêté au moment où un job devait s'exécuter, le job s'exécute au redémarrage (comportement standard de BullMQ) — le média reste donc disponible un peu plus longtemps que prévu dans ce cas, sans risque de perte du job.

## Liens
- ADR liés : ADR-003, ADR-013, ADR-016

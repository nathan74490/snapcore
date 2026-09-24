# ADR-006 — Passerelle temps réel Socket.io + pub/sub Redis pour le chat

## Statut
Proposé

## Contexte
Le chat et la livraison des snaps nécessitent une communication en temps réel entre clients, avec la possibilité de faire tourner plusieurs instances du backend en parallèle pour la montée en charge, dans une logique de "faire comme si l'app allait être publiée".

## Décision
Utiliser Socket.io comme passerelle temps réel, avec Redis en pub/sub pour propager les messages entre plusieurs instances du serveur.

## Alternatives
- WebSocket natif (librairie `ws`) : plus léger, mais ne gère pas nativement la reconnexion automatique, les salons (rooms), ni le repli HTTP long-polling que fournit Socket.io.
- Une seule instance de serveur sans pub/sub : plus simple, mais empêche toute montée en charge horizontale (un utilisateur connecté à une instance ne peut pas recevoir un message envoyé via une autre instance).

## Conséquences
### Positives
- Reconnexion et gestion des salons prêtes à l'emploi.
- Scalabilité horizontale du serveur temps réel via Redis.

### Négatives
- Dépendance supplémentaire à Redis (déjà présent dans le projet pour d'autres usages, donc coût marginal).
- Légère complexité de configuration du pub/sub.

## Liens
- ADR liés : ADR-003

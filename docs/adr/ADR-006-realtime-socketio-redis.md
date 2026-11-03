# ADR-006 : Passerelle temps réel Socket.io + pub/sub Redis pour le chat

## Statut
Accepté

## Contexte
Le chat et la livraison des snaps nécessitent une communication en temps réel entre clients, avec la possibilité de faire tourner plusieurs instances du backend en parallèle pour la montée en charge, dans une logique de "faire comme si l'app allait être publiée".

## Décision
Utiliser Socket.io comme passerelle temps réel, avec Redis en pub/sub pour propager les messages entre plusieurs instances du serveur.

Garantie de livraison : l'API REST est la source de vérité, Socket.io ne sert qu'à notifier en temps réel qu'un événement s'est produit (nouveau message, snap ouvert, etc.), sans garantie de livraison de l'événement lui-même. Si un client est déconnecté au moment de l'émission, ou si l'événement Socket.io est perdu pour toute autre raison, il n'y a pas de re-émission automatique. En conséquence, le client doit systématiquement resynchroniser son état via l'API REST (récupérer les messages/snaps manqués) à chaque reconnexion, plutôt que de considérer que l'ensemble des événements a été reçu en continu via Socket.io.

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
- Les clients doivent implémenter une logique de resynchronisation via l'API au reconnect, puisque Socket.io ne garantit pas la livraison des événements.

## Historique
- Révision 1 : clarification que l'API REST est la source de vérité et que Socket.io n'est qu'un mécanisme de notification best-effort ; ajout de l'obligation de resynchronisation côté client à la reconnexion.

## Liens
- ADR liés : ADR-003

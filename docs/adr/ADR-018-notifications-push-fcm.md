# ADR-018 — Notifications push : Firebase Cloud Messaging

## Statut
Proposé

## Contexte
Le chat (ADR-006) et la gestion des amis (ADR-012) reposent sur la passerelle temps réel Socket.io quand l'utilisateur est connecté, mais rien ne réveille l'app quand elle est fermée ou en arrière-plan (nouveau message, nouvelle demande d'ami). Ce choix — Firebase Cloud Messaging pour réveiller l'app hors connexion temps réel — figurait déjà dans l'architecture globale (cahier des charges) mais n'avait pas d'ADR propre.

## Décision
- Utiliser **Firebase Cloud Messaging (FCM)**, gratuit, pour envoyer une notification push quand un événement (nouveau message, nouvelle demande d'ami, snap reçu) survient alors que le destinataire n'est pas connecté à la passerelle Socket.io.
- Le backend tente d'abord la livraison temps réel via Socket.io (ADR-006) ; en cas d'échec (utilisateur non connecté), il envoie un message FCM au lieu de faire retenter indéfiniment le canal temps réel.
- Le contenu du push reste minimal (type d'événement + identifiant), l'app va chercher le détail via l'API au réveil — cohérent avec le principe de ne jamais dupliquer une donnée déjà servie par ailleurs.

## Alternatives
- Pas de notification push, l'utilisateur découvre les nouveautés seulement à l'ouverture de l'app : plus simple, mais très éloigné du comportement attendu d'une app de messagerie et nuit à l'expérience utilisateur du projet pédagogique.

## Conséquences
### Positives
- Gratuit, aucune nouvelle contrainte de coût.
- Complète proprement le canal temps réel existant sans le remplacer.

### Négatives
- Ajoute une dépendance à un service Google (Firebase) côté app mobile, distincte du reste de la stack ; à isoler derrière un adapter dédié pour ne pas coupler le cœur métier à FCM (ADR-002).

## Liens
- ADR liés : ADR-002, ADR-006, ADR-012

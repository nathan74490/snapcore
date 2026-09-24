# ADR-018 — Notifications push : Firebase Cloud Messaging, suivi des tokens et de la présence

## Statut
Proposé

## Contexte
Le chat (ADR-006) et la gestion des amis (ADR-012) reposent sur la passerelle temps réel Socket.io quand l'utilisateur est connecté, mais rien ne réveille l'app quand elle est fermée ou en arrière-plan. Une première version de cet ADR ne précisait ni comment le backend obtient le token de chaque appareil, ni comment il sait si un utilisateur est connecté quand plusieurs instances du serveur tournent en parallèle.

## Décision
- Utiliser **Firebase Cloud Messaging (FCM)**, gratuit, pour envoyer une notification push quand un événement (nouveau message, nouvelle demande d'ami, snap reçu) survient alors que le destinataire n'est pas connecté à la passerelle Socket.io. Le projet utilise déjà Firebase pour l'authentification (ADR-015) : FCM n'est donc pas une dépendance isolée, mais une seconde brique du même écosystème Firebase déjà en place.
- **Tokens d'appareil** : une entité `DeviceToken` (`userId`, `token`, `updatedAt`) enregistre le dernier token FCM connu par appareil. L'app le renvoie au backend à chaque démarrage et à chaque renouvellement signalé par le SDK Firebase (les tokens FCM peuvent changer). Un utilisateur peut avoir plusieurs `DeviceToken` (plusieurs appareils) ; l'envoi push cible tous les tokens connus pour cet utilisateur.
- **Présence multi-instance** : chaque instance backend maintient une clé Redis de présence par utilisateur connecté (`presence:online:<userId>`, TTL courte), rafraîchie par un heartbeat de la connexion Socket.io. Avant d'envoyer un événement, le backend vérifie cette clé (peu importe l'instance qui a établi la connexion Socket.io) : présente → livraison temps réel, absente ou expirée → push FCM sur tous les `DeviceToken` de l'utilisateur. Ce mécanisme réutilise Redis, déjà central au projet (ADR-003), plutôt que d'introduire un système d'accusés de réception applicatifs.
- Le contenu du push reste minimal (type d'événement + identifiant), l'app va chercher le détail via l'API au réveil.

## Alternatives
- Accusés de réception (acks) applicatifs sur chaque événement Socket.io pour déterminer la présence : fonctionnellement équivalent, mais plus de code à maintenir qu'une simple clé Redis à TTL déjà dans l'esprit du reste du projet.
- Pas de notification push : plus simple, mais très éloigné du comportement attendu d'une app de messagerie.

## Conséquences
### Positives
- Gratuit, complète proprement le canal temps réel existant.
- Fonctionne correctement avec plusieurs instances backend, sans état partagé autre que Redis (déjà en place).
- Reste dans l'écosystème Firebase déjà utilisé pour l'authentification (ADR-015), pas une dépendance isolée.

### Négatives
- Un token FCM expiré ou invalide (désinstallation de l'app, par exemple) doit être détecté et nettoyé via les erreurs renvoyées par l'API FCM à l'envoi — logique à prévoir dans l'adapter FCM (ADR-002) pour ne pas accumuler des tokens morts.

## Historique
- Version initiale : mentionnait FCM sans modèle de token ni mécanisme de présence, et le décrivait comme une dépendance "distincte du reste de la stack" avant l'adoption de Firebase Authentication (ADR-015).
- Révision (revue technique) : ajout de `DeviceToken`, mécanisme de présence Redis multi-instance, mise à jour du contexte suite à ADR-015.

## Liens
- ADR liés : ADR-002, ADR-003, ADR-006, ADR-012, ADR-015

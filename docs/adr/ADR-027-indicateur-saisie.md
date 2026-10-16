# ADR-027 : Indicateur « en train d'écrire » relayé par la passerelle temps réel

## Statut
Accepté

## Contexte
Dans une conversation Snapchat, quand l'ami écrit, une petite animation l'indique en bas du fil. SnapCore n'avait rien d'équivalent : la passerelle Socket.io (ADR-006) ne sert qu'à pousser des événements du backend vers l'app, et l'app ne lui envoie rien d'autre que son jeton à la connexion.

L'information est éphémère : elle n'a de sens que si l'ami a la conversation ouverte à ce moment-là, et n'a aucune valeur une fois passée.

## Décision
**Signal par l'API REST** : `POST /conversations/:friendId/typing` (204), appelé par l'app pendant la saisie.
- Mêmes règles d'accès qu'un envoi de message : amis ACCEPTED uniquement, sinon 403 (ADR-009).
- Le backend relaie l'événement `typing` `{ userId }` à l'ami par `UserEventPublisher` : s'il est connecté, Socket.io ; sinon rien. L'événement n'est **jamais** un push FCM (absent de `PUSH_WORTHY`, ADR-018).
- Rien n'est enregistré : ni base, ni Redis.

**Rythme côté app** :
- Au plus un signal toutes les 3 s tant que le champ contient du texte ; aucun signal pour un champ vide. Après un envoi, le message suivant se signale tout de suite.
- À la réception, l'indicateur s'affiche 6 s après le dernier signal, ce qui couvre l'intervalle entre deux signaux d'une saisie continue. Il disparaît dès qu'un message arrive, ou de lui-même si l'ami s'arrête.

**Affichage** : en bas de la conversation, trois points gris qui rebondissent à tour de rôle sur la barre bleue de l'ami, sous son nom (omis si ses messages sont juste au-dessus). La conversation défile pour le montrer si l'on est en bas.

## Alternatives
- **Événement envoyé par l'app sur le socket** (`socket.emit("typing")`) : un aller simple plus léger qu'une requête HTTP, mais il faudrait que la passerelle accepte et authentifie des messages entrants, vérifie l'amitié à chaque événement et en limite le débit, alors qu'elle est aujourd'hui en lecture seule pour le client (ADR-006). Écarté pour la V1 : l'API REST a déjà l'authentification, la validation et les règles d'amitié.
- **Ne rien faire** : écarté, c'est un repère important du ressenti « temps réel » de Snapchat.

## Conséquences
### Positives
- Aucune nouvelle infrastructure : contrôleur, cas d'usage et publication d'événements existants.
- Les règles d'accès sont celles des messages, testées au même endroit.
- Aucune donnée conservée : rien à purger ni à supprimer avec le compte.

### Négatives
- Une requête HTTP toutes les 3 s pendant la saisie, par conversation : acceptable à l'échelle de la V1. Le débit n'est limité que par l'app : le backend n'a pas de limiteur de débit aujourd'hui, une app modifiée pourrait donc en envoyer davantage (sans autre effet que des événements de plus chez l'ami).
- Best-effort : un signal perdu (réseau, ami qui se reconnecte) fait simplement disparaître l'indicateur plus tôt.

## Liens
- ADR liés : ADR-006, ADR-009, ADR-018

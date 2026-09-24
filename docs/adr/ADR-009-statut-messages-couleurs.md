# ADR-009 — Modèle de message complet et statut (couleurs/icônes façon Snapchat)

## Statut
Proposé

## Contexte
SnapCore doit reproduire exactement le système d'icônes colorées de Snapchat dans la liste de discussion, qui indique en un coup d'œil le type de contenu, le sens (envoyé/reçu) et le statut (non ouvert / ouvert / expiré) du dernier message d'une conversation :
- Carré rouge plein : nouveau Snap photo reçu, non ouvert. Carré rouge contour : Snap photo envoyé, ouvert par le destinataire.
- Carré violet plein : nouveau Snap photo + vidéo/son reçu, non ouvert. Carré violet contour : Snap photo + vidéo/son envoyé, ouvert.
- Flèche bleue pleine : nouveau Chat (texte) reçu, non lu. Flèche bleue contour : Chat envoyé, ouvert/lu.
- Gris : Snap ouvert puis expiré (plus disponible).

Une révision précédente appliquait une règle unique d'expiration (24h après ouverture) à tous les types de contenu, y compris le texte. C'est incorrect pour ce projet : **le texte d'un Chat doit être conservé indéfiniment**, en base de données (jamais dans un cache), pour rester accessible même après un changement de téléphone — seul le contenu média d'un Snap (photo/vidéo) est éphémère.

## Décision
- Champ `contentType` (`TEXT` | `SNAP_PHOTO` | `SNAP_PHOTO_VIDEO`) sur MESSAGE, qui détermine la couleur (bleu / rouge / violet) et la forme (flèche pour `TEXT`, carré pour les deux types de Snap).
- Contenu du message : `content` (texte, uniquement si `contentType = TEXT`) ou `mediaKey` (clé d'objet R2, uniquement si `contentType` est un des deux types de Snap — voir ADR-016) — un seul des deux est renseigné selon le type.
- **`content` (texte) n'expire jamais et n'est jamais purgé** : stocké durablement en PostgreSQL (jamais en cache), accessible sur n'importe quel appareil tant que le compte existe. La flèche bleue reste pleine (non lu) ou en contour (lu) selon `openedAt`, mais ne devient **jamais grise** — il n'y a pas de notion d'expiration pour un Chat texte.
- **`mediaKey` (Snap photo/vidéo) reste éphémère**, selon les règles détaillées dans ADR-017 (purge à l'ouverture + 24h, sauf abonnement SnapCore+ actif qui conserve le média indéfiniment ; purge à 30 jours si jamais ouvert). Une fois le média purgé, l'icône passe au gris.
- **Conservation par abonnement (SnapCore+)** : tant que `mediaKey` n'a pas été purgé, le destinataire peut **rouvrir le Snap autant de fois qu'il le souhaite** — la conservation par abonnement lève la contrainte "vue unique" habituelle d'un Snap. Quand la purge d'un Snap est évitée grâce à un abonnement actif (ADR-017), le backend émet un événement (temps réel si l'expéditeur est connecté, sinon push, ADR-018) informant l'**expéditeur** que son Snap a été conservé — analogue à la notification de sauvegarde du vrai Snapchat, pour que l'expéditeur ne croie pas à tort que son contenu a disparu.
- Sens (envoyé/reçu) : déduit en comparant l'expéditeur à l'utilisateur connecté — aucun champ supplémentaire nécessaire.
- Statut (non ouvert / ouvert) : `openedAt` (nullable, PostgreSQL, mis à jour une seule fois à la première ouverture) est la source de vérité, pour les deux types de contenu. Pour un Snap, l'état "gris" s'ajoute lorsque le média a été purgé (`mediaKey` devenu `null` après avoir été renseigné) — pas seulement à partir d'un délai calculé côté client, puisque la purge peut être repoussée indéfiniment par un abonnement actif (ADR-017).
- Le rendu (couleur, forme, plein/contour/gris) reste entièrement calculé côté application mobile ; aucun calcul côté serveur.

## Alternatives
- Un champ `displayColor` calculé et stocké côté serveur : évite un calcul côté client, mais duplique une information déjà déductible d'autres champs.
- Statut porté par une clé Redis avec TTL (version initiale de cet ADR) : réutilisait une infrastructure déjà en place, mais rendait indiscernables "jamais ouvert" et "expiré", et n'apportait aucun bénéfice une fois `openedAt` introduit en base.
- Règle unique de 24h après ouverture appliquée à tous les types de contenu, y compris le texte (révision intermédiaire) : plus simple à documenter, mais contraire à l'exigence explicite que les Chats texte restent accessibles indéfiniment.

## Conséquences
### Positives
- Reproduit fidèlement le système d'icônes de Snapchat, avec un modèle de données complet (contenu inclus).
- Le texte d'une conversation ne disparaît jamais, même après un changement d'appareil (stocké en base, pas en cache local).
- Le statut "gris" d'un Snap reflète l'état réel du média (purgé ou non), cohérent même si la purge est repoussée par un abonnement.

### Négatives
- Deux logiques de statut différentes selon le type de contenu (texte : jamais gris ; Snap : gris une fois purgé) à documenter clairement côté client pour ne pas les confondre.
- L'icône "gris" d'un Snap dépend de l'état réel du média en base (`mediaKey` null) plutôt que d'un simple calcul de délai côté client — nécessite que le client dispose de cette information à jour (rafraîchissement de la conversation après une purge, notifiée par le canal temps réel ou au prochain chargement).
- Un Snap conservé par abonnement perd sa sémantique "vue unique" (le destinataire peut le revoir librement), ce qui diffère du comportement classique d'un Snap — assumé comme contrepartie de l'avantage payant.

## Historique
- Version initiale : statut basé sur une clé Redis avec TTL (ADR-003), sans champ `content`/`mediaKey`.
- Révision 1 (revue technique) : ajout de `content`/`mediaKey`, remplacement du mécanisme Redis par `openedAt` + règle fixe de 24h appliquée uniformément à tous les types.
- Révision 2 (clarification du lead développeur) : le texte (`TEXT`) devient permanent et n'expire jamais ; seul le Snap (photo/vidéo) reste éphémère, avec des règles propres définies par ADR-017 (y compris l'exception d'abonnement SnapCore+).
- Révision 3 (revue technique) : un Snap conservé par abonnement peut être revu librement par le destinataire, et l'expéditeur est notifié quand son Snap est ainsi conservé.

## Liens
- ADR liés : ADR-016, ADR-017

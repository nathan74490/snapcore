# ADR-009 — Modèle de message complet et statut (couleurs/icônes façon Snapchat)

## Statut
Proposé

## Contexte
SnapCore doit reproduire exactement le système d'icônes colorées de Snapchat dans la liste de discussion, qui indique en un coup d'œil le type de contenu, le sens (envoyé/reçu) et le statut (non ouvert / ouvert / expiré) du dernier message d'une conversation :
- Carré rouge plein : nouveau Snap photo reçu, non ouvert. Carré rouge contour : Snap photo envoyé, ouvert par le destinataire.
- Carré violet plein : nouveau Snap photo + vidéo/son reçu, non ouvert. Carré violet contour : Snap photo + vidéo/son envoyé, ouvert.
- Flèche bleue pleine : nouveau Chat (texte) reçu, non lu. Flèche bleue contour : Chat envoyé, ouvert/lu.
- Gris : Snap ouvert puis expiré (plus disponible).

**Le texte d'un Chat doit être conservé indéfiniment**, en base de données (jamais dans un cache), pour rester accessible même après un changement de téléphone — seul le contenu média d'un Snap (photo/vidéo) est éphémère (ADR-017). Envoyer un message suppose une relation d'amitié acceptée entre les deux comptes (ADR-012).

## Décision
- Champ `contentType` (`TEXT` | `SNAP_PHOTO` | `SNAP_PHOTO_VIDEO`) sur MESSAGE, qui détermine la couleur (bleu / rouge / violet) et la forme (flèche pour `TEXT`, carré pour les deux types de Snap).
- Contenu du message : `content` (texte, uniquement si `contentType = TEXT`) ou `mediaKey` (clé d'objet R2, uniquement si `contentType` est un des deux types de Snap — voir ADR-016) — un seul des deux est renseigné selon le type.
- **`content` (texte) n'expire jamais et n'est jamais purgé** : stocké durablement en PostgreSQL (jamais en cache), accessible sur n'importe quel appareil tant que le compte existe. La flèche bleue reste pleine (non lu) ou en contour (lu) selon `openedAt`, mais ne devient **jamais grise** — il n'y a pas de notion d'expiration pour un Chat texte.
- **`mediaKey` (Snap photo/vidéo) reste éphémère**, selon les règles détaillées dans ADR-017 (purge à l'ouverture + 24h, sauf abonnement SnapCore+ actif qui conserve le média indéfiniment ; purge à 30 jours si jamais ouvert). Une fois le média purgé, l'icône passe au gris.
- **Contrainte "vue unique" par défaut** : pour un `SNAP_PHOTO`/`SNAP_PHOTO_VIDEO`, l'API refuse de générer une nouvelle URL de lecture pré-signée (ADR-016) si `openedAt` est déjà renseigné, en dehors de la fenêtre de grâce décrite ci-dessous, **et** que le destinataire n'a pas d'abonnement SnapCore+ actif au moment de la demande — c'est cette vérification, absente jusqu'ici, qui rend la règle "vue unique" réelle plutôt que seulement déclarative (sans elle, n'importe quel destinataire pouvait revoir le média tant qu'il n'était pas encore purgé, abonné ou non). L'expéditeur, lui, n'a **jamais** accès à l'URL de lecture de son propre Snap une fois envoyé : cet endpoint est réservé au destinataire, aucun cas d'usage ne justifie qu'il serve aussi l'expéditeur. **Cette URL est également refusée si la paire est `BLOCKED`** au moment de la demande, même pour un Snap déjà reçu avant le blocage et non encore purgé (ADR-012) : le blocage masque l'accès au contenu, pas seulement l'envoi de nouveaux messages.
- **Marquage atomique de l'ouverture et fenêtre de grâce** : `openedAt` est renseigné par une mise à jour conditionnelle unique (`UPDATE "Message" SET "openedAt" = now() WHERE id = ? AND "openedAt" IS NULL`), et l'URL de lecture n'est générée que si cette mise à jour a effectivement modifié une ligne, ou si la demande tombe dans une **fenêtre de grâce de 5 minutes après `openedAt`** (dans ce cas l'URL est régénérée sans que la mise à jour ait eu lieu une seconde fois). Cette fenêtre unique couvre à la fois : deux requêtes concurrentes du même destinataire arrivant en même temps (l'une gagne la mise à jour, l'autre tombe dans la fenêtre de grâce plutôt que d'être refusée à tort comme une deuxième vue) ; et un téléchargement du média qui échoue juste après `openedAt` (l'app peut redemander une URL fraîche pendant 5 minutes sans que le Snap soit considéré comme définitivement perdu). Passé ces 5 minutes, la règle "vue unique" s'applique normalement (refus sauf abonnement actif).
- **URL de lecture à durée de vie courte, jamais mise en cache disque** : l'URL pré-signée générée à la demande a une durée de vie d'environ une minute (juste assez pour démarrer le téléchargement, pas pour être partagée ou réutilisée plus tard) ; l'app ne persiste jamais le média déchiffré sur le disque de l'appareil, uniquement en mémoire le temps de l'affichage.
- **Conservation par abonnement (SnapCore+)** : tant que `mediaKey` n'a pas été purgé et que le destinataire a un abonnement actif au moment de la demande, celui-ci peut **rouvrir le Snap autant de fois qu'il le souhaite** — la conservation par abonnement lève la contrainte "vue unique" décrite ci-dessus, uniquement pour ce destinataire. Quand la purge d'un Snap est évitée grâce à un abonnement actif (ADR-017), le backend émet un événement (temps réel si l'expéditeur est connecté, sinon push, ADR-018) informant l'**expéditeur** que son Snap a été conservé — analogue à la notification de sauvegarde du vrai Snapchat, pour que l'expéditeur ne croie pas à tort que son contenu a disparu. **Cette notification ne part qu'une seule fois par message** : un champ `retainedNotifiedAt` (nullable) sur `Message` est renseigné au premier envoi de l'événement, et le job de purge (ADR-017) ne réémet pas la notification aux exécutions suivantes du même job (à chaque cycle de renouvellement d'abonnement, la purge continue d'être évitée et le job reprogrammé, mais sans notifier à nouveau). Cette notification part **au moment où la purge est évitée**, c'est-à-dire jusqu'à `openedAt + 24h` après l'ouverture — pas au moment exact où le destinataire souscrit son abonnement.
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
- Le contrôle de la contrainte "vue unique" (statut d'abonnement du destinataire) est fait à la demande d'URL de lecture, donc à chaque tentative de (re)lecture, plutôt qu'une seule fois à l'ouverture — coût négligeable (une lecture en base) mais logique à ne pas oublier lors de l'implémentation de cet endpoint.
- La fenêtre de grâce de 5 minutes signifie qu'un destinataire non abonné peut techniquement redemander l'URL plusieurs fois dans ce court intervalle après la première ouverture — accepté comme nécessaire pour couvrir les échecs de téléchargement, la fenêtre étant trop courte pour constituer un vrai contournement de la règle "vue unique".

## Historique
- Version initiale : statut basé sur une clé Redis avec TTL (ADR-003), sans champ `content`/`mediaKey`, sans distinction entre types de contenu (une révision intermédiaire a un temps appliqué une règle unique de 24h à tous les types y compris le texte, avant d'être corrigée en Révision 2).
- Révision 1 (revue technique) : ajout de `content`/`mediaKey`, remplacement du mécanisme Redis par `openedAt` + règle fixe de 24h appliquée uniformément à tous les types.
- Révision 2 (clarification du lead développeur) : le texte (`TEXT`) devient permanent et n'expire jamais ; seul le Snap (photo/vidéo) reste éphémère, avec des règles propres définies par ADR-017 (y compris l'exception d'abonnement SnapCore+).
- Révision 3 (revue technique) : un Snap conservé par abonnement peut être revu librement par le destinataire, et l'expéditeur est notifié quand son Snap est ainsi conservé.
- Révision 4 (revue technique) : la contrainte "vue unique" devient une vérification explicite à la génération de l'URL de lecture (elle n'était appliquée nulle part jusqu'ici) ; clarification que l'expéditeur n'a jamais accès à l'URL de lecture de son propre Snap ; ajout de `retainedNotifiedAt` pour que la notification de conservation ne parte qu'une seule fois par message, et précision sur son moment d'émission (jusqu'à 24h après l'ouverture, pas au moment de la souscription).
- Révision 5 (revue technique) : ajout d'une mise à jour atomique de `openedAt` et d'une fenêtre de grâce de 5 minutes après ouverture, pour éliminer la course entre requêtes concurrentes et permettre de redemander l'URL après un échec de téléchargement ; précision de la durée de vie courte (~1 minute) de l'URL pré-signée et de l'absence de mise en cache disque côté app.
- Révision 6 (revue technique) : l'URL de lecture d'un Snap est désormais explicitement refusée si la paire est `BLOCKED`, même pour un Snap déjà reçu et non encore purgé (voir ADR-012).

## Liens
- ADR liés : ADR-012, ADR-016, ADR-017

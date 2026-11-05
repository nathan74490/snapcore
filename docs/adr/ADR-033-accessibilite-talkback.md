# ADR-033 : Accessibilité TalkBack de l'accueil jusqu'à la Snap Map

## Statut
Accepté

## Contexte
Une personne malvoyante doit pouvoir, seule et avec TalkBack (le lecteur d'écran d'Android, celui des Samsung), aller de l'écran d'accueil à la Snap Map : inscription ou connexion, premiers pas, offre SnapCore+, puis carte.

L'audit du 2026-11-05 a relevé plusieurs problèmes :
- **lectures en double ou bruyantes** : chaque onglet lu « Map Map », chaque avatar « Photo de profil », 48 initiales décoratives lues sur l'écran des contacts ;
- **boutons sans nom** : avatar qui ouvre le profil, « ✕ » lu comme un symbole, fantôme de l'accueil ;
- **champs sans libellé** une fois remplis : le libellé est un texte à part, au-dessus du champ ;
- **cases à cocher et boutons radio sans état** : de simples lignes cliquables ;
- **roue de la date de naissance** inutilisable sans la vue ;
- **marqueurs de la carte muets** : ni le nom de l'ami, ni ce que fait l'appui ;
- **aucune annonce** quand le fantôme pose une nouvelle question ou signale une erreur.

## Décision
**Règles appliquées avec la sémantique de Jetpack Compose**, sans bibliothèque supplémentaire :
- **Un élément actionnable = un nom, un rôle, un état.**
  - Les lignes à cocher utilisent `toggleable(role = Checkbox)`, et le mode fantôme `toggleable(role = Switch)`.
  - L'audience de la position utilise `selectable(role = RadioButton)`.
  - Les onglets utilisent `selectable(role = Tab)` : « Map, onglet, sélectionné ».
- **Les éléments décoratifs sont masqués** (`clearAndSetSemantics {}`) : l'avatar (le nom est lu à côté, ou par le bouton qui le contient), le fantôme animé, l'illustration des contacts, l'index alphabétique des pays.
- **Champs** : le libellé est porté par le champ lui-même, ainsi que l'erreur (`error(...)`). La ligne d'état sous le champ (« Pseudo disponible », erreur) est une région live.
- **Fantôme guide** : sa bulle est un titre (`heading`) et une région live polie. TalkBack lit la phrase entière dès son apparition, pas lettre par lettre, et la relit si elle change sur le même écran : erreur de connexion, pseudo déjà pris.
- **Roue de la date** : chaque roue (mois, jour, année) est une commande réglable (`progressBarRangeInfo` et `setProgress`). On change sa valeur en balayant vers le haut ou le bas, et TalkBack la lit (« Mois, Novembre »). Le bouton Continuer est lu après la roue (`traversalIndex`).
- **Snap Map** :
  - la carte annonce ce qu'elle contient et comment l'explorer : « Carte. 3 amis visibles, 1 story… » (`mapSummary`) ;
  - chaque ami est un bouton « Léa, à 2,4 km, il y a 12 min », avec l'action « afficher sa fiche » ;
  - chaque story est un bouton « Story d'Inès, 1 snap, pas encore vue », avec l'action « regarder la story » ;
  - ma position annonce le mode fantôme ou l'audience ;
  - la fiche d'un ami est annoncée à son ouverture, et ses boutons sont nommés (« Écrire à Léa », « Centrer la carte sur Léa », « Fermer la fiche »).
- **Disposition** : avec le clavier ouvert, les écrans de questions défilent au lieu d'écraser leur bouton Continuer.

**Navigation simplifiée dans l'onboarding** (même jour, après un premier essai jugé trop compliqué). Quand TalkBack est actif (`rememberScreenReaderOn`, d'après l'exploration au toucher) :
- **Le focus de TalkBack va sur la question du fantôme** dès qu'un écran apparaît. TalkBack lit « Étape 2 sur 11. Enchanté, Léa ! C'est quand, ton anniversaire ? ».
  - Sans cela, le focus restait sur la flèche retour, ou sur le bouton Continuer qui existe encore à l'écran suivant, et la nouvelle question n'était jamais lue.
  - Le focus clavier ne suffit pas : le focus est posé par l'action `ACTION_ACCESSIBILITY_FOCUS`, celle que TalkBack envoie lui-même (`screenReaderFocusOnAppear`).
- **Ordre de lecture** : la question, puis les champs, puis Continuer, et enfin « Passer » et « Retour » (`traversalIndex`).
- **La barre de progression ne compte plus comme un élément** : l'étape est lue avec la question.
- **Le clavier ne s'ouvre plus d'office** : on touche deux fois le champ pour écrire.
- **Pas d'animation de transition** (l'écran précédent restait lisible pendant le glissement), et la bulle s'affiche d'un coup, sans écriture lettre par lettre.
- **Le fantôme animé de l'accueil est masqué** pour TalkBack, qui arrive directement sur le titre et les boutons.
- **Listes de contacts (ajout d'amis, invitations)** : elles deviennent un seul bouton qui les résume (« Contacts à inviter : 163, dont 2 cochés »). Sans cela, il fallait passer chaque contact pour atteindre « Continuer » ou « Passer ». Un double appui ouvre la liste dans sa propre fenêtre, avec « Terminé » en haut et en bas ; sinon, le glissement passe directement à la suite de l'écran.
- **Éditeur d'avatar** (ADR-028) :
  - Avec TalkBack, les 11 onglets et la grille de vignettes (jusqu'à 35) sont remplacés par un bouton par catégorie, qui annonce le choix en cours (« Yeux : normaux. 12 choix. »).
  - Un double appui ouvre les choix de la catégorie dans leur propre fenêtre, comme des boutons radio, avec « Terminé ». Chaque choix s'applique tout de suite.
  - Les options ont un nom français (`avatarLabel`), au lieu des identifiants d'avataaars (« shortFlat », « 2c1b18 »).
  - L'aperçu décrit l'avatar en une phrase (`avatarDescription`).
- **Une phrase qui change sur le même écran** (erreur de connexion, pseudo déjà pris) est annoncée par une région live. La question d'arrivée n'en a pas, pour ne pas être lue deux fois.

**Reste de l'app** (2026-11-05, après un retour « navigation pas intuitive ») :
- **Changements d'écran** :
  - chaque destination annonce son nom à l'ouverture (`AccessibleScreen` : « Conversation avec Léa », « Mon profil », « Envoyer à »…) ;
  - le focus va au titre de l'écran (`SnapHeader`, en-tête du profil, nom dans une conversation).
- **Onglets de l'accueil** :
  - seule la page courante est lisible, alors que la caméra restait lisible derrière le Chat et les Stories ;
  - à chaque changement d'onglet, le focus va au titre (Chat, Stories), au déclencheur (Caméra) ou au résumé de la carte (Map).
- **Messagerie** :
  - une conversation est un seul élément (« Léa, nouveau snap, il y a 5 minutes »), et le bouton appareil photo devient une action personnalisée ;
  - un message est lu « Léa : …, 14:32 », avec l'icône de statut traduite en texte ;
  - les messages reçus, « Léa écrit… » et l'envoi sont annoncés une fois chacun ;
  - un snap ne disparaît pas avant d'avoir été lu, et se ferme par un bouton.
- **Caméra** :
  - le déclencheur prend une photo, et l'action « Démarrer une vidéo » filme sans maintenir l'appui ;
  - le flash est un interrupteur, et les Lens sont des choix exclusifs nommés. Avec TalkBack, une Lens n'est appliquée qu'au double appui : passer d'une Lens à l'autre fait défiler le carrousel sans rien changer. Sans TalkBack, la Lens au centre s'applique d'elle-même (ADR-024) ;
  - les filtres de l'aperçu sont un sélecteur (« Filtre, Noir et blanc, 2 sur 6 ») ;
  - les destinataires sont des cases à cocher, résumées au-delà de 6.
- **Stories** :
  - pas d'avance automatique avec TalkBack ;
  - boutons et actions Précédent, Suivant, Pause, Signaler, Fermer ;
  - chaque élément est décrit (« Photo de Léa, il y a 3 h, 2 sur 5 »).
- **Profil, amis, bloqués, SnapCore+** :
  - chaque réglage est lu en une fois (« Prénom : Nathan »), et les boutons portent le nom de la personne (« Accepter Léa », « Débloquer Léa ») ;
  - les titres des boîtes de dialogue sont lus en premier, et les motifs de signalement sont des boutons radio.
- **Avatar** : décoratif, mais un bouton posé dessus (« Mon profil ») garde son nom. Avant, il disparaissait pour TalkBack.

**Vérification** :
- `uiautomator dump` suspend TalkBack le temps de la lecture : l'app repasse alors en affichage normal. Les écrans adaptés à TalkBack se vérifient donc sur des captures d'écran, pas sur ce relevé.
- L'arbre d'accessibilité de chaque écran est relu avec `uiautomator dump` sur Galaxy A54, à la recherche de toute action sans nom.
- Le parcours est ensuite fait avec TalkBack actif, par de vrais doubles appuis. Le cadre de focus de TalkBack, visible sur les captures, est sur la question à l'ouverture de l'inscription, des premiers pas et de la connexion, et après un changement d'écran (invitations → avatar). La liste résumée s'ouvre au double appui, avec le focus sur son titre.

## Alternatives
- **Écrans spécifiques pour TalkBack**, par exemple une liste des amis à la place de la carte : plus simple à parcourir, mais un second parcours à maintenir, qui s'écarte de l'app réelle. Écarté. Les marqueurs de la carte suffisent, avec le résumé et la distance.
- **Accessibility Test Framework dans des tests instrumentés** : une vérification automatique, mais elle demande une suite de tests sur appareil, que le projet n'a pas. Reporté.

## Conséquences
### Positives
- Le parcours accueil → inscription ou connexion → premiers pas → SnapCore+ → carte se fait sans voir l'écran.
- Les règles (nom, rôle, état, décoratif masqué) s'appliquent à tout nouvel écran.

### Négatives
- La caméra, le chat et les stories n'ont pas encore été audités. Le parcours couvert s'arrête à la carte.
- Pas de test automatique : une régression d'accessibilité ne se voit qu'au relevé de l'arbre ou à l'essai avec TalkBack.

## Liens
- ADR liés : ADR-028, ADR-029, ADR-031

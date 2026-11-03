# ADR-028 : Avatars maison façon Bitmoji : style avataaars (DiceBear), options stockées par le backend, rendu PNG public déterministe

## Statut
Accepté

## Contexte
Sur Snapchat, chaque utilisateur est représenté par son Bitmoji : un personnage dessiné qu'il compose lui-même et qui apparaît dans la liste des amis, les conversations et les stories. ADR-014 avait laissé ce point de côté (« pas de Bitmoji pour l'instant ») : un profil SnapCore n'a qu'une photo, ou à défaut les initiales sur fond coloré.

Il faut un personnage personnalisable, d'un rendu soigné, utilisable librement dans un projet commercial, et affichable partout où un profil apparaît. L'app Android n'affiche que des images : elle n'embarque pas de moteur de dessin vectoriel pour ce besoin.

## Décision
**Style** : avataaars de DiceBear (`@dicebear/core` et `@dicebear/avataaars`, version 9), dessiné par Pablo Stanley, sous licence « Free for personal and commercial use ».

**Options** (`AvatarOptions`, `packages/types`) : objet de 11 chaînes, toutes obligatoires : `top`, `hairColor`, `skinColor`, `eyes`, `eyebrows`, `mouth`, `facialHair`, `accessories`, `clothing`, `clothesColor`, `backgroundColor`. Les couleurs sont des hex sans `#`.
- **Catalogue** dans le domaine (module `avatars`), dérivé au chargement du schéma du style (`avataaars.schema.properties`) : énumération pour les formes, liste par défaut pour les couleurs. Une mise à jour du style l'enrichit sans recopie.
- `"none"` est ajouté en tête pour `top`, `facialHair` et `accessories` : l'élément est alors rendu avec une probabilité de 0.
- L'expression `mouth: "vomit"` est retirée du catalogue.
- `backgroundColor` prend une palette fixe de couleurs claires : `b6e3f4, c0aede, d1d4f9, ffd5dc, ffdfbf, fffaa1, a7ffc4, ffffff`.
- Validation stricte : clé manquante ou inconnue, valeur hors catalogue → 400. À la lecture, un avatar stocké sorti du catalogue (style mis à jour) est ignoré plutôt que de casser une liste d'amis.
- Avatar proposé à l'ouverture de l'éditeur : cheveux courts bruns, sourire, sweat à capuche bleu, fond bleu clair (`DEFAULT_AVATAR`).

**Stockage** : colonne `User.avatar Json?`, null = pas d'avatar. Seules les options sont stockées, jamais d'image : rien à mettre sur R2, rien à purger.

**API** :
- `PUT /me/avatar` `{ options }` enregistre et renvoie le `User` ; `DELETE /me/avatar` le retire (204). Compte actif requis, comme les autres routes `/me`.
- `GET /avatars/catalog` (authentifié) : `{ defaults, options }`, valeurs dans l'ordre du schéma, `none` en premier quand il existe.
- `User` (GET /me) et `PublicUserProfile` (amis, demandes, recherche, conversations, stories) portent `avatar`, absent si null. Tous les profils publics passent par `toPublicProfile`.

**Rendu** : `GET /avatars/avataaars.png?<les 11 clés>&size=` (64 à 512, 256 par défaut), **public** (`@Public()`) pour être chargé directement par le client d'images de l'app.
- SVG produit par DiceBear puis rasterisé en PNG par `@resvg/resvg-js` (binaire précompilé, aucune dépendance système). Carré plein sur le fond choisi : style `default`, pas `circle`, l'app découpe le cercle elle-même.
- Déterministe : graine fixe, toutes les options du style explicites (couleur des chapeaux = vêtements, barbe = cheveux, accessoires et motif de t-shirt figés). Mêmes paramètres, mêmes octets, d'où `Cache-Control: public, max-age=31536000, immutable`.
- Paramètre invalide, manquant, inconnu ou répété → 400.
- Petit cache LRU en mémoire (300 rendus) : le rendu coûte quelques millisecondes et les mêmes avatars sont redemandés en boucle.

**Affichage côté app** : avatar en priorité, puis photo de profil, puis initiales (ADR-014). L'app construit l'URL du PNG à partir des options du profil.

## Alternatives
- **Vrai Bitmoji via Snap Kit** (Bitmoji Kit) : le personnage original, mais dépend entièrement de Snap, exige que chaque utilisateur ait un compte Snapchat et y connecte son Bitmoji, et l'offre de Snap pour les applications tierces est incertaine. Écarté.
- **Dessins maison en vectoriel dans l'app** (personnage composé de calques Compose) : aucune dépendance externe, mais long à dessiner pour un rendu nettement plus simple qu'avataaars. Écarté.
- **Rendu SVG dans l'app** (le backend ou l'app produit le SVG DiceBear, l'app le décode) : pas de rasterisation côté serveur, mais les décodeurs SVG disponibles sur Android gèrent mal les masques et découpes qu'utilise avataaars, avec des rendus différents selon les appareils. Écarté au profit d'un PNG identique partout.

## Conséquences
### Positives
- Un personnage personnalisable et soigné sans dépendre de Snap, ni d'un compte tiers.
- Aucun stockage d'image : 11 chaînes par utilisateur, qui partent avec la ligne `User` à la suppression du compte (ADR-019).
- Rendu identique sur tous les appareils, mis en cache par l'app et par tout cache HTTP intermédiaire.
- L'endpoint de rendu est public mais **ne révèle rien** : il ne lit aucune donnée utilisateur, le PNG ne dépend que de ses paramètres. Connaître l'URL de l'avatar d'un utilisateur ne donne que ce que montre déjà son profil public.

### Négatives
- **Licence du style à créditer** : avataaars (Pablo Stanley) est libre d'usage personnel et commercial ; le crédit figure dans le README du projet.
- Le domaine du module `avatars` dépend du schéma JSON du style (une donnée, pas de rendu) : une mise à jour majeure de DiceBear peut retirer des valeurs, que la lecture tolérante d'un avatar stocké absorbe (l'utilisateur retombe sur sa photo ou ses initiales).
- Deux paquets DiceBear ESM dans un backend CommonJS : chargés par le `require` natif de Node à l'exécution, et transformés par Jest dans les tests (`transformIgnorePatterns`).
- Une requête de rendu par avatar et par taille non encore en cache : quelques millisecondes, absorbées par le cache LRU et par le cache HTTP immuable.

## Liens
- ADR liés : ADR-012, ADR-013, ADR-014, ADR-016, ADR-019

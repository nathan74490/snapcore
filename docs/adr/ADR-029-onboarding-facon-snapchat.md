# ADR-029 : Onboarding façon Snapchat : inscription en 5 étapes, âge minimum, pseudo proposé, premiers pas avec les contacts

## Statut
Accepté

## Contexte
L'inscription de SnapCore tenait en 3 étapes (prénom et pseudo, email et téléphone, mot de passe), puis menait directement à la caméra. L'onboarding de Snapchat (captures du dossier `design onboarding`, iOS déc. 2024) est plus progressif. Il pose une question par écran (prénom et nom, date de naissance, pseudo proposé d'office, mot de passe, email ou téléphone). Il enchaîne ensuite des premiers pas : amis retrouvés par les contacts, invitations, création du Bitmoji, notifications.

Plusieurs de ces écrans demandent des données que SnapCore ne gérait pas : nom de famille, date de naissance, disponibilité d'un pseudo avant la création du compte, correspondance entre le carnet d'adresses et les comptes existants.

## Décision
**Inscription en 5 étapes** (« Créer un compte · Étape X sur 5 », un champ par écran, bouton grisé tant que l'étape n'est pas remplie) :
1. **Prénom** (obligatoire) et **nom** (facultatif, mêmes règles que le prénom). L'accord aux conditions d'utilisation (`/legal/cgu`) se donne en touchant Continuer.
2. **Date de naissance**, saisie sur une roue mois / jour / année. **13 ans minimum**, vérifiés par l'app et par le backend (400 « Tu dois avoir au moins 13 ans pour t'inscrire »). L'âge est calculé sur la date UTC du jour (port `Clock`).
3. **Pseudo proposé d'office**, à partir du prénom sans accents, de l'initiale du nom et de chiffres (« nathanb4821 »). Il est vérifié auprès du backend, avec jusqu'à 5 propositions ; « Changer mon pseudo » ouvre la saisie, avec la disponibilité vérifiée en direct.
4. **Mot de passe de 8 caractères minimum**, contre 6 exigés par Firebase.
5. **Email**, obligatoire : il reste l'identifiant de connexion Firebase (ADR-015). Des raccourcis de domaine (@gmail.com…) sont proposés au-dessus du clavier.

L'écran « Utiliser un numéro de téléphone à la place » de Snapchat n'est pas repris : le téléphone reste facultatif et non vérifié (ADR-010), et il est demandé juste après l'inscription. Le compte Firebase n'est toujours créé qu'à la dernière étape, puis le compte SnapCore (ADR-015). Une inscription interrompue (compte Firebase sans compte SnapCore) reprend aux étapes 1 à 3, puis « Terminer ».

**Données** : `User.lastName` (facultatif) et `User.birthday` (date, obligatoire pour toute nouvelle inscription ; null pour les comptes antérieurs). Elles sont exposées dans `GET /me` seulement, **jamais dans les profils publics**. Le nom se modifie depuis le profil.

**Disponibilité d'un pseudo** : `GET /users/username-availability?username=` est une route **publique**, appelée avant que le compte existe. Elle répond `{ available, reason? }` et ne renvoie rien d'autre.

**Premiers pas** (après une inscription seulement, chaque écran se passe) :
1. **Ton numéro** : pays (liste avec recherche et index alphabétique, pays de la carte SIM par défaut) et numéro, enregistré au format international (`+33612345678`) par `PATCH /me`.
2. **Trouve tous tes amis** : explication, puis demande de la permission Android `READ_CONTACTS`.
3. **Ajouter des amis** : comptes retrouvés par les contacts, cochés d'office (sauf amis et demandes déjà envoyées), plus une recherche par pseudo. Les demandes partent en arrière-plan.
4. **Inviter des contacts** : contacts sans compte, cochés à la main. L'app de messagerie s'ouvre avec un SMS groupé prérédigé ; c'est l'utilisateur qui l'envoie. Cet écran n'apparaît que si l'accès aux contacts a été accordé.
5. **Crée ton avatar** : ouvre l'éditeur d'avatar (ADR-028), enregistré en arrière-plan.
6. **Active les notifications** : explication avant la demande d'Android 13+. Elle n'apparaît que si la permission n'a jamais été demandée (BUG-007 d'ADR-023). Pendant les premiers pas, la demande automatique de `PushRegistration` est suspendue.

**Parcours guidé par le fantôme** (ajout du 2026-11-05) : l'inscription et les premiers pas forment un seul parcours, façon Duolingo.
- Le fantôme de SnapCore (celui du tirer-pour-actualiser) pose la question de chaque écran dans une bulle, avec un texte qui s'écrit lettre par lettre. Il est dessiné en vectoriel et animé (`GhostMascot`) : une humeur par écran (il salue, est curieux, cherche, réfléchit, est content, se cache les yeux au mot de passe, a des cœurs, fait la fête), et un air inquiet quand une réponse est refusée.
- Une barre de progression unique remplace « Étape X sur 5 » : elle avance pendant l'inscription, puis continue pendant les premiers pas. « Passer » reste en haut à droite des écrans facultatifs.
- Chaque écran arrive en glissant (depuis la gauche au retour en arrière).
- L'écran des notifications montre un aperçu de notification, avec « Activer les notifications » et « Plus tard », au lieu de la fausse boîte de dialogue iOS.
- Les textes de l'expérience onboarding-contacts-copy (ADR-030) sont conservés : le fantôme dit le titre de la variante.
- **Connexion** : même style. Le fantôme accueille (« Content de te revoir ! »), se cache les yeux pendant la saisie du mot de passe, réfléchit pendant la vérification, et annonce lui-même l'erreur (« Oups ! Email ou mot de passe incorrect. »).
- **SnapCore+ proposé à la fin** (ADR-025) : à la fin des premiers pas, le paywall s'ouvre une fois par-dessus la caméra, sauf pour un compte déjà abonné. « Plus tard » (ou la flèche) le ferme et ramène à la caméra. Mesuré par `paywall_opened { source: onboarding }`.

**Correspondance des contacts** : `POST /users/contacts/match` (authentifié, compte actif), corps `{ phoneNumbers }`.
- Au plus 2000 numéros par requête ; l'app découpe un carnet plus grand.
- La correspondance porte sur les **9 derniers chiffres**, ce qui absorbe « 06 12… » et « +33 6 12… ».
- La réponse donne `{ phoneNumber (la chaîne envoyée), user (profil public), relation }`. Elle exclut soi-même, les blocages dans les deux sens, et les comptes supprimés ou suspendus.
- Le numéro stocké de l'autre utilisateur n'est jamais renvoyé.
- Côté app, les contacts ne sont lus qu'après accord, et ne sont ni conservés ni envoyés ailleurs : seuls les numéros partent, le temps de la requête.

## Alternatives
- **Email ou téléphone au choix, avec code SMS** (Firebase Phone Auth, écran « Confirme ton numéro » à 6 cases) : fidèle à Snapchat, mais l'envoi de SMS impose le plan payant Blaze (ADR-010) et un second mode de connexion. Écarté.
- **Ne stocker ni le nom ni la date de naissance**, en gardant le prénom seul et 4 étapes : plus simple, mais sans contrôle d'âge, alors que Snapchat en fait un préalable. Écarté.
- **Vérifier le pseudo seulement à l'inscription** (409), sans route publique : aucune énumération possible, mais l'utilisateur découvre le refus après avoir saisi mot de passe et email. Écarté : Snapchat le vérifie en direct.
- **Correspondance des contacts par empreintes (hash) des numéros** : ne fait pas voyager les numéros en clair, mais un numéro de 9 chiffres se retrouve trivialement à partir de son empreinte, donc sans vraie protection. Écarté au profit de HTTPS et d'une non-conservation stricte.

## Conséquences
### Positives
- Parcours d'inscription et premiers pas fidèles à Snapchat, testés écran par écran.
- Âge minimum garanti côté backend, quelle que soit l'app cliente.
- Les amis se retrouvent sans connaître leur pseudo, et les premiers pas aboutissent à un compte déjà entouré d'amis et doté d'un avatar.

### Négatives
- **Énumération** : la route publique de disponibilité permet de tester si un pseudo existe, et la correspondance des contacts peut traiter 2000 numéros par appel. Le backend n'a pas encore de limitation de débit (déjà noté dans ADR-027). C'est acceptable pour la V1, jamais exposée à de vrais utilisateurs inconnus ; un limiteur de débit sera nécessaire avant toute ouverture.
- **Numéros non vérifiés** (ADR-010) : n'importe qui peut déclarer le numéro d'un autre et apparaître dans ses suggestions. La correspondance sur 9 chiffres peut aussi rapprocher deux numéros de pays différents. Ce ne sont que des suggestions : rien n'est ajouté sans une demande d'ami acceptée.
- Les anciennes versions de l'app ne peuvent plus s'inscrire : `birthday` est obligatoire.
- Nouvelle permission sensible (`READ_CONTACTS`), demandée une seule fois, après explication, et facultative.

## Liens
- ADR liés : ADR-010, ADR-012, ADR-014, ADR-015, ADR-018, ADR-023, ADR-027, ADR-028

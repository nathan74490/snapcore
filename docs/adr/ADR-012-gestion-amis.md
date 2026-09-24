# ADR-012 — Gestion des amis : recherche par pseudo en V1, Snapcode + meilleur ami + flammes en V2

## Statut
Proposé

## Contexte
SnapCore doit permettre d'ajouter des amis, d'accepter ou refuser une demande, et de bloquer un utilisateur. Le vrai Snapchat propose plusieurs façons d'ajouter un ami (pseudo, Snapcode/QR, contacts téléphoniques) et des mécaniques sociales avancées (meilleur ami, flammes/streaks). Pour rester réaliste sans surcharger le premier lot de fonctionnalités, une partie de ces mécaniques est repoussée à une V2.

L'effet d'une relation d'amitié (ou de son absence) sur la messagerie doit être précisé : qui peut envoyer un message à qui, ce que fait un blocage, qui peut débloquer, et ce que devient l'historique de conversation dans chaque cas.

## Décision
**V1 :**
- Une seule entité FRIENDSHIP par paire d'utilisateurs : `requesterId`, `addresseeId`, `status` (`PENDING`, `ACCEPTED`, `BLOCKED`, `DECLINED`), `requestedAt` (renommé depuis `createdAt`, voir Historique), `respondedAt`.
- **Unicité de la paire garantie en base** : `pairLow`/`pairHigh` (les deux ID triés) avec `@@unique([pairLow, pairHigh])`.
- **`requesterId` ≠ `addresseeId` garanti en base** : une contrainte `CHECK ("requesterId" <> "addresseeId")` (ajoutée via migration SQL, noms de colonnes entre guillemets doubles pour respecter la casse par défaut de Prisma, non exprimable dans le DSL Prisma) empêche un utilisateur de s'ajouter lui-même.
- **Cohérence `pairLow`/`pairHigh` garantie en base** : une contrainte `CHECK` (migration SQL) impose que `pairLow`/`pairHigh` correspondent bien à `LEAST`/`GREATEST` de `requesterId`/`addresseeId` — sans quoi rien n'empêcherait une valeur incohérente d'être écrite malgré la contrainte d'unicité.
- **Effet d'un blocage (`status = BLOCKED`)** : le profil et les contenus (stories, statut en ligne) de chaque utilisateur sont masqués à l'autre, **dans les deux sens** ; l'envoi de messages entre les deux utilisateurs est **interdit dans les deux sens**, quel que soit celui qui a initié le blocage. **L'historique de conversation existant n'est pas supprimé** : les `Message` déjà échangés restent en base (`content` reste permanent, ADR-009), mais la conversation n'apparaît plus dans la liste de discussion d'aucun des deux tant que la ligne reste `BLOCKED` (elle est traitée comme une conversation avec un profil masqué, donc invisible). Si les deux utilisateurs redeviennent amis plus tard (nouvelle demande après un déblocage), l'historique de conversation redevient visible tel quel — il n'a jamais été effacé, seulement masqué le temps du blocage.
- **Qui peut débloquer** : seul `blockedByUser` (celui qui a bloqué) peut lever le blocage. L'utilisateur bloqué ne peut pas débloquer unilatéralement. **Endpoint "mes utilisateurs bloqués"** : puisque le profil d'un utilisateur bloqué est masqué (voir ci-dessus), il serait sinon impossible pour le bloqueur de le retrouver pour le débloquer — un endpoint dédié (`GET /friendships/blocked`) liste, pour l'utilisateur courant uniquement, les paires où `blockedBy = userId courant`, indépendamment du masquage de profil qui s'applique aux autres endpoints.
- **Blocage mutuel** : `blockedBy` ne porte qu'un seul utilisateur à la fois. Si B tente de bloquer A alors que A a déjà bloqué B, la tentative de B est un **no-op apparent** : la ligne reste `BLOCKED` avec `blockedBy = A`, et l'effet immédiat recherché par B (ne plus voir A, ne plus recevoir de message de A) est bien obtenu par le blocage de A. **Ce n'est cependant pas rigoureusement équivalent à un blocage réellement symétrique** : l'intention de blocage de B n'est nulle part enregistrée. Si A débloque plus tard (seul `blockedByUser`, donc A, peut le faire), la ligne est supprimée et B se retrouve à nouveau exposé à A — sans jamais avoir été informé que son propre blocage n'avait pas été enregistré, ni consulté sur ce déblocage. Ce choix reste retenu pour la V1 par simplicité (voir Conséquences négatives pour cette limitation assumée), une notion de blocage réellement symétrique étant repoussée si le besoin se confirme.
- **Après un refus (`DECLINED`)** : une nouvelle demande **met à jour la ligne existante** (`status` repassé à `PENDING`, `requestedAt` rafraîchi) plutôt que de créer une nouvelle ligne. **`requesterId`/`addresseeId` sont recalculés selon qui envoie la nouvelle demande** : si c'est la même personne qu'avant, ils restent inchangés ; si c'est l'autre personne qui relance cette fois (le refus visait la direction opposée), `requesterId`/`addresseeId` sont inversés pour refléter le nouveau sens de la demande — seuls `pairLow`/`pairHigh` restent, par construction, indépendants de ce sens. Aucune limite de fréquence n'est imposée en V1 (pas de protection anti-spam après un refus répété) — accepté comme limite connue, voir Conséquences.
- **Débloquer** : la ligne FRIENDSHIP est supprimée — **y compris si les deux utilisateurs étaient amis (`ACCEPTED`) avant le blocage** : débloquer ne restaure pas l'amitié précédente, les deux utilisateurs redeviennent des inconnus l'un pour l'autre et devraient repasser par une nouvelle demande d'ami s'ils souhaitent redevenir amis.
- **Nouvelle demande sur une ligne `BLOCKED`** : si un utilisateur bloqué (ou celui qui a bloqué) tente d'envoyer une nouvelle demande d'ami sur une paire déjà `BLOCKED`, la demande est **refusée**, sans mettre à jour la ligne (contrairement au cas `DECLINED` ci-dessus). Autant que possible côté API, la réponse ne distingue pas ce refus d'un échec générique (par exemple le même message d'erreur que pour un pseudo introuvable), pour ne pas révéler à l'utilisateur bloqué qu'il l'est.
- **Bloquer un utilisateur qui n'est pas encore ami** : une ligne est créée directement avec `status = BLOCKED`.
- **Règle d'envoi de message/snap, jamais posée explicitement jusqu'ici** : envoyer un `Message` (texte ou Snap, ADR-009) à un utilisateur n'est possible **que si une ligne `FRIENDSHIP` `ACCEPTED` existe entre les deux comptes** au moment de l'envoi. Ce n'est pas une conséquence implicite d'un autre choix : c'est la règle qui conditionne l'endpoint d'envoi (il vérifie le statut de la paire avant d'accepter le message), la liste des conversations (qui ne propose de démarrer une conversation qu'avec un ami accepté), et le comportement après un déblocage/re-demande (tant que le statut n'est pas revenu à `ACCEPTED`, l'envoi reste refusé même si l'ancien historique de conversation, lui, redevient visible une fois `ACCEPTED` retrouvé). `PENDING`, `DECLINED` et `BLOCKED` refusent tous l'envoi — `BLOCKED` avec le comportement de non-divulgation déjà décrit ci-dessus, les deux autres avec une erreur ordinaire ("vous devez être amis pour envoyer un message").
- Ajout d'ami uniquement par recherche de pseudo (pas de Snapcode/QR, pas de découverte via contacts).
- Notification de demande/acceptation via Socket.io si le destinataire est connecté, sinon push FCM (ADR-018).

**V2 (plus tard) :** Snapcode/QR, meilleur ami, flammes (streak).

## Alternatives
- Deux lignes symétriques par relation d'amitié : évitée, source de doublons et d'incohérences.
- Vérification applicative avant insertion, sans contrainte base : laisse une fenêtre de course, écartée au profit de la paire triée.
- Blocage réellement symétrique (chaque partie peut bloquer/débloquer indépendamment, par exemple avec deux champs `blockedByA`/`blockedByB` ou deux lignes) : plus riche, mais complexifie le modèle pour un bénéfice pratique nul (l'effet visible — profils masqués, messages impossibles — est déjà obtenu dès qu'une des deux parties bloque).
- Limiter le nombre de nouvelles demandes après un refus (ex. un délai minimum) : écarté pour l'instant, faute d'un besoin réel identifié sur un projet sans utilisateurs malveillants.

## Conséquences
### Positives
- Modèle de données simple et suffisant pour la V1, avec des garanties d'intégrité au niveau base (paire unique, pas d'auto-ajout, cohérence `pairLow`/`pairHigh`).
- Comportement du blocage entièrement défini : effet, qui peut débloquer, cas du blocage mutuel.
- La règle "qui peut écrire à qui" est désormais explicite et unique (statut `ACCEPTED` requis), au lieu d'être seulement déductible d'un autre comportement documenté ailleurs (ADR-009).

### Négatives
- Aucune protection contre un utilisateur qui multiplie les demandes après un refus répété — acceptable ici, l'app n'étant jamais exposée à de vrais utilisateurs malveillants.
- Les deux contraintes `CHECK` (auto-ajout, cohérence de paire) doivent être ajoutées via une migration SQL manuelle, Prisma ne les exprimant pas nativement dans son DSL — à ne pas oublier lors de la génération de la migration initiale.
- Le blocage mutuel n'est pas rigoureusement symétrique : si B a tenté de bloquer A pendant qu'A avait déjà bloqué B, l'intention de B n'est jamais enregistrée. Un déblocage ultérieur par A expose B à A sans que B ait été consulté ni informé — limite connue et assumée pour la V1 (voir Décision).

## Historique
- Version initiale : vérification applicative avant insertion, `blockedBy` sans relation, cas refus/déblocage/blocage d'un non-ami non précisés.
- Révision 1 : contrainte de paire triée au niveau base, relation `blockedByUser`, comportements refus/déblocage/blocage précisés.
- Révision 2 (revue technique) : effet concret du blocage explicité (profils/contenus masqués, messages interdits, dans les deux sens), règle de déblocage (seul le bloqueur), comportement du blocage mutuel, contraintes `CHECK` pour l'auto-ajout et la cohérence de paire, renommage `createdAt` → `requestedAt`.
- Révision 3 (revue technique) : correction de la casse dans la contrainte `CHECK` (`"requesterId"`, pas `requester_id`, pour matcher les colonnes générées par Prisma) ; reconnaissance que le blocage mutuel n'est pas rigoureusement équivalent à un blocage symétrique (ajout en Conséquences négatives) ; règle explicite du `requesterId` lors d'une relance après refus (inversé si c'est l'autre personne qui relance) ; rejet explicite (sans révéler le blocage) d'une nouvelle demande sur une ligne `BLOCKED` ; précision que débloquer un ex-ami ne restaure pas l'amitié précédente.
- Révision 4 (revue technique) : ajout de l'endpoint "mes utilisateurs bloqués" (sans lui le bloqueur ne pourrait pas retrouver un profil qu'il a lui-même masqué pour débloquer) ; précision que l'historique de conversation n'est jamais supprimé par un blocage, seulement masqué tant que la ligne reste `BLOCKED` ; correction d'une coquille dans les conséquences négatives ("A→B" mal formulé).
- Révision 5 (revue technique) : ajout de la règle explicite "envoi de message possible seulement si `FRIENDSHIP` `ACCEPTED`" — jusqu'ici seulement déductible d'une remarque sur l'historique de conversation, sans jamais avoir été posée comme règle conditionnant l'endpoint d'envoi ; section Contexte allégée (l'historique des révisions est désormais uniquement dans cette section).

## Liens
- ADR liés : ADR-009, ADR-018

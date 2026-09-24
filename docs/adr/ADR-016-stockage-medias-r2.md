# ADR-016 — Stockage des médias : Cloudflare R2, upload via préfixe temporaire, CDN public pour le profil uniquement

## Statut
Proposé

## Contexte
Plusieurs ADR (ADR-011 caméra, ADR-013 story, ADR-014 profil) supposaient un pipeline média sans jamais le documenter. Une révision a séparé CDN public (profil) et URL pré-signées (snaps/stories), mais a introduit une **contradiction bloquante** : la règle de cycle de vie R2 (suppression automatique après 35 jours d'inactivité) s'appliquait à **tout le bucket**, y compris les objets qu'on veut justement garder — une photo de profil jamais retouchée depuis 35 jours, ou un Snap conservé indéfiniment grâce à un abonnement SnapCore+ (ADR-017). Cette révision corrige le problème en isolant la règle de cycle de vie sur un préfixe dédié aux uploads non confirmés. Une revue suivante a signalé trois angles morts sur ce flux en deux temps : rien n'empêchait un utilisateur de "réclamer" la clé temporaire d'un autre (usurpation d'upload), l'ordre entre la copie R2 et l'écriture en base n'était pas fixé (risque de ligne pointant vers un objet inexistant), et le stockage de l'ancienne clé de profil avant remplacement restait indécis entre un nouveau champ et une déduction directe.

## Décision
- Tout média utilisateur (photo/vidéo de snap, story, photo de profil) est stocké sur **Cloudflare R2** (compatible S3, sans frais de sortie).
- **Upload en deux temps** : l'app demande d'abord au backend une URL pré-signée d'upload vers une clé temporaire `tmp/<uuid>` ; **le backend génère lui-même le `<uuid>`, l'associe à l'utilisateur courant dans une clé Redis (`tmp-upload:<uuid>` → `userId`, TTL de quelques heures, largement supérieur au temps d'un upload) et renvoie l'URL pré-signée pour cette clé.** Une fois l'upload confirmé côté app (l'app informe le backend de la clé temporaire utilisée, en même temps qu'elle crée le `Message`/`StoryItem` ou met à jour son profil), **le backend vérifie d'abord que `tmp-upload:<uuid>` correspond bien à l'utilisateur courant** avant toute action — sans quoi un utilisateur A pourrait indiquer au backend la clé temporaire d'un upload en cours par un utilisateur B et se voir attribuer son média. Cette vérification passée, le backend effectue une copie serveur-à-serveur de `tmp/<uuid>` vers la clé définitive (`messages/<messageId>`, `stories/<storyItemId>`, `profiles/<userId>/<uuid>`) ; **ce n'est qu'une fois cette copie confirmée réussie que la ligne `Message`/`StoryItem`/le nouveau `profilePhotoUrl` est écrite en base**, puis l'objet temporaire est supprimé et la clé Redis `tmp-upload:<uuid>` effacée. Si la copie échoue, aucune ligne n'est créée/mise à jour et l'app peut réessayer avec la même clé temporaire tant que le TTL Redis n'a pas expiré. Cette copie ne fait jamais transiter le fichier par le backend (opération native R2/S3 de copie d'objet).
- **La règle de cycle de vie R2 (suppression automatique après 35 jours) ne s'applique qu'au préfixe `tmp/`** — filet de sécurité contre un upload commencé puis jamais confirmé. Aucune règle de cycle de vie ne s'applique aux préfixes définitifs (`messages/`, `stories/`, `profiles/`) : leur suppression est uniquement explicite, pilotée par l'application (purge ADR-017, remplacement de photo de profil).
- **Clé unique par upload** : chaque upload (y compris un remplacement de photo de profil) génère une nouvelle clé (`profiles/<userId>/<uuid>`, jamais `profiles/<userId>/photo.jpg` réutilisée), pour que le CDN devant les photos de profil ne resserve jamais l'ancienne image en cache sous la même URL.
- **Remplacement d'une photo de profil** : avant d'écrire le nouveau `profilePhotoUrl`, le backend **déduit la clé de l'ancienne image directement de l'ancien `User.profilePhotoUrl`** (l'URL CDN encode la clé R2 sous-jacente, aucun champ supplémentaire n'est nécessaire pour la retrouver), puis la supprime une fois la nouvelle confirmée — sinon l'ancienne photo resterait indéfiniment sur R2, orpheline mais jamais purgée par une règle de cycle de vie. Ce même mécanisme (dérivation de la clé à partir de l'URL stockée) est celui que réutilise ADR-019 à l'étape de collecte des références média avant suppression de compte, sans nécessiter de champ ou de table dédiés.
- **Photo de profil** (contenu public, non éphémère) : servie via un **CDN public** devant R2, l'URL complète est stockée telle quelle (`User.profilePhotoUrl`).
- **Snaps privés et stories** (contenu privé et éphémère, ADR-003/ADR-017) : jamais de CDN. Seule la **clé d'objet R2** (`mediaKey`) est stockée en base — jamais une URL. La lecture se fait via une **URL pré-signée de lecture**, générée à la demande, à durée de vie courte.

## Alternatives
- Upload direct vers la clé définitive, sans préfixe temporaire (version initiale) : plus simple, mais oblige soit à exempter tout le bucket de toute règle de cycle de vie (aucun filet de sécurité contre les uploads interrompus), soit à appliquer une règle globale qui supprime aussi ce qu'on veut garder — c'est exactement la contradiction corrigée ici.
- Nettoyage des uploads orphelins par un job applicatif périodique plutôt qu'une règle de cycle de vie R2 sur `tmp/` : fonctionnellement équivalent, mais réimplémente ce qu'une règle de cycle de vie native fait gratuitement et sans code.
- Stocker l'ancienne clé de profil dans un nouveau champ `User.previousProfilePhotoKey` ou une table d'historique dédiée : évité, la clé étant déjà déductible de l'URL CDN existante — un champ supplémentaire n'apporterait rien de plus et devrait être tenu synchronisé.
- CDN public devant tous les médias, y compris les snaps et stories : écarté (voir révision précédente), contraire au caractère privé et éphémère du contenu.

## Conséquences
### Positives
- Un objet définitif (photo de profil, snap conservé par abonnement) ne peut plus être supprimé par erreur par la règle de cycle de vie.
- Les uploads jamais confirmés (app fermée avant la fin, erreur réseau) sont nettoyés automatiquement au bout de 35 jours, sans code applicatif.
- Un remplacement de photo de profil ne laisse plus d'ancienne image orpheline sur R2, et le CDN ne resert jamais une image périmée sous une URL déjà en cache.
- Une clé temporaire ne peut plus être confirmée par un autre utilisateur que celui qui l'a effectivement demandée (vérification Redis).
- Aucune ligne en base ne peut plus pointer vers un objet R2 inexistant : l'écriture en base n'a lieu qu'après confirmation de la copie.

### Négatives
- Deux écritures R2 par upload confirmé (copie temporaire → définitive, puis suppression du temporaire) au lieu d'une seule — coût opérationnel mineur, sans transfert de données supplémentaire côté app.
- Si l'app ne confirme jamais un upload par ailleurs réussi (crash juste après), le fichier reste 35 jours sous `tmp/` avant nettoyage automatique — acceptable, c'est le rôle du filet de sécurité.
- La correspondance `tmp-upload:<uuid>` → `userId` ajoute une dépendance de plus à Redis (déjà central au projet) pour un flux qui, sans elle, n'aurait touché que R2 et Postgres — coût marginal, mais à ne pas oublier de nettoyer si un jour ce Redis change de politique d'éviction (voir ADR-017).

## Historique
- Version initiale : un seul mécanisme (CDN devant R2) pour tous les médias, `mediaUrl` stocké directement en base.
- Révision 1 (revue technique) : séparation profil (CDN public) / snaps-stories (URL pré-signées, `mediaKey` en base), ajout d'une règle de cycle de vie R2 globale à 35 jours — contradictoire avec la conservation indéfinie d'un objet définitif.
- Révision 2 (revue technique) : upload en deux temps via préfixe `tmp/`, règle de cycle de vie restreinte à ce préfixe, suppression explicite de l'ancienne photo de profil lors d'un remplacement, clé unique par upload.
- Révision 3 (revue technique) : vérification de propriété de la clé temporaire via Redis (empêche l'usurpation d'un upload en cours) ; ordre fixé entre copie R2 et écriture en base (la ligne n'est créée/mise à jour qu'après confirmation de la copie) ; l'ancienne clé de profil est déduite de l'URL existante plutôt que stockée dans un champ ou une table dédiés.

## Liens
- ADR liés : ADR-002, ADR-011, ADR-013, ADR-014, ADR-017

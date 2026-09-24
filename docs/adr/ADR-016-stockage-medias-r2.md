# ADR-016 — Stockage des médias : Cloudflare R2, upload via préfixe temporaire, CDN public pour le profil uniquement

## Statut
Proposé

## Contexte
Plusieurs ADR (ADR-011 caméra, ADR-013 story, ADR-014 profil) supposaient un pipeline média sans jamais le documenter. Une révision a séparé CDN public (profil) et URL pré-signées (snaps/stories), mais a introduit une **contradiction bloquante** : la règle de cycle de vie R2 (suppression automatique après 35 jours d'inactivité) s'appliquait à **tout le bucket**, y compris les objets qu'on veut justement garder — une photo de profil jamais retouchée depuis 35 jours, ou un Snap conservé indéfiniment grâce à un abonnement SnapCore+ (ADR-017). Cette révision corrige le problème en isolant la règle de cycle de vie sur un préfixe dédié aux uploads non confirmés.

## Décision
- Tout média utilisateur (photo/vidéo de snap, story, photo de profil) est stocké sur **Cloudflare R2** (compatible S3, sans frais de sortie).
- **Upload en deux temps** : l'app uploade toujours d'abord vers une clé temporaire sous le préfixe `tmp/<uuid>` via une URL pré-signée d'upload. Une fois l'upload confirmé côté app (l'app informe le backend de la clé temporaire utilisée, en même temps qu'elle crée le `Message`/`StoryItem` ou met à jour son profil), le **backend** effectue une copie serveur-à-serveur de `tmp/<uuid>` vers la clé définitive (`messages/<messageId>`, `stories/<storyItemId>`, `profiles/<userId>/<uuid>`), puis supprime l'objet temporaire. Cette copie ne fait jamais transiter le fichier par le backend (opération native R2/S3 de copie d'objet).
- **La règle de cycle de vie R2 (suppression automatique après 35 jours) ne s'applique qu'au préfixe `tmp/`** — filet de sécurité contre un upload commencé puis jamais confirmé. Aucune règle de cycle de vie ne s'applique aux préfixes définitifs (`messages/`, `stories/`, `profiles/`) : leur suppression est uniquement explicite, pilotée par l'application (purge ADR-017, remplacement de photo de profil).
- **Clé unique par upload** : chaque upload (y compris un remplacement de photo de profil) génère une nouvelle clé (`profiles/<userId>/<uuid>`, jamais `profiles/<userId>/photo.jpg` réutilisée), pour que le CDN devant les photos de profil ne resserve jamais l'ancienne image en cache sous la même URL.
- **Remplacement d'une photo de profil** : le backend supprime explicitement l'ancienne clé R2 (conservée le temps du remplacement dans `User` ou dans une table d'historique légère) une fois la nouvelle confirmée — sinon l'ancienne photo resterait indéfiniment sur R2, orpheline mais jamais purgée par une règle de cycle de vie.
- **Photo de profil** (contenu public, non éphémère) : servie via un **CDN public** devant R2, l'URL complète est stockée telle quelle (`User.profilePhotoUrl`).
- **Snaps privés et stories** (contenu privé et éphémère, ADR-003/ADR-017) : jamais de CDN. Seule la **clé d'objet R2** (`mediaKey`) est stockée en base — jamais une URL. La lecture se fait via une **URL pré-signée de lecture**, générée à la demande, à durée de vie courte.

## Alternatives
- Upload direct vers la clé définitive, sans préfixe temporaire (version initiale) : plus simple, mais oblige soit à exempter tout le bucket de toute règle de cycle de vie (aucun filet de sécurité contre les uploads interrompus), soit à appliquer une règle globale qui supprime aussi ce qu'on veut garder — c'est exactement la contradiction corrigée ici.
- Nettoyage des uploads orphelins par un job applicatif périodique plutôt qu'une règle de cycle de vie R2 sur `tmp/` : fonctionnellement équivalent, mais réimplémente ce qu'une règle de cycle de vie native fait gratuitement et sans code.
- CDN public devant tous les médias, y compris les snaps et stories : écarté (voir révision précédente), contraire au caractère privé et éphémère du contenu.

## Conséquences
### Positives
- Un objet définitif (photo de profil, snap conservé par abonnement) ne peut plus être supprimé par erreur par la règle de cycle de vie.
- Les uploads jamais confirmés (app fermée avant la fin, erreur réseau) sont nettoyés automatiquement au bout de 35 jours, sans code applicatif.
- Un remplacement de photo de profil ne laisse plus d'ancienne image orpheline sur R2, et le CDN ne resert jamais une image périmée sous une URL déjà en cache.

### Négatives
- Deux écritures R2 par upload confirmé (copie temporaire → définitive, puis suppression du temporaire) au lieu d'une seule — coût opérationnel mineur, sans transfert de données supplémentaire côté app.
- Si l'app ne confirme jamais un upload par ailleurs réussi (crash juste après), le fichier reste 35 jours sous `tmp/` avant nettoyage automatique — acceptable, c'est le rôle du filet de sécurité.

## Historique
- Version initiale : un seul mécanisme (CDN devant R2) pour tous les médias, `mediaUrl` stocké directement en base.
- Révision 1 (revue technique) : séparation profil (CDN public) / snaps-stories (URL pré-signées, `mediaKey` en base), ajout d'une règle de cycle de vie R2 globale à 35 jours — contradictoire avec la conservation indéfinie d'un objet définitif.
- Révision 2 (revue technique) : upload en deux temps via préfixe `tmp/`, règle de cycle de vie restreinte à ce préfixe, suppression explicite de l'ancienne photo de profil lors d'un remplacement, clé unique par upload.

## Liens
- ADR liés : ADR-002, ADR-011, ADR-013, ADR-014, ADR-017

# ADR-016 — Stockage des médias : Cloudflare R2, CDN public pour le profil uniquement, URL pré-signées pour les snaps et stories

## Statut
Proposé

## Contexte
Plusieurs ADR (ADR-011 caméra, ADR-013 story, ADR-014 profil) supposaient un pipeline média sans jamais le documenter. Une première version de cet ADR plaçait un CDN devant l'ensemble des médias, y compris les snaps privés et les stories — or un CDN public met les objets en cache : un snap purgé (ADR-017) resterait potentiellement accessible depuis le cache du CDN, et toute URL pré-signée qui y transite se met de toute façon mal en cache (elle change à chaque génération). Cette révision sépare le traitement selon la nature du média.

## Décision
- Tout média utilisateur (photo/vidéo de snap, story, photo de profil) est stocké sur **Cloudflare R2** (compatible S3, sans frais de sortie — cohérent avec la contrainte de coût nul du projet).
- L'app mobile n'upload jamais un fichier via le backend : le backend génère une **URL pré-signée d'upload** à durée limitée, que l'app utilise pour uploader directement le fichier vers R2.
- **Photo de profil** (contenu public, non éphémère) : servie via un **CDN public** devant R2, l'URL complète est stockée telle quelle (`User.profilePhotoUrl`). Le cache CDN est acceptable ici puisque rien n'a vocation à disparaître.
- **Snaps privés et stories** (contenu privé et éphémère, ADR-003/ADR-017) : **jamais de CDN**. Seule la **clé d'objet R2** (`mediaKey`) est stockée en base — jamais une URL. La lecture se fait via une **URL pré-signée de lecture**, générée à la demande par le backend, à durée de vie courte. Cela garantit qu'un média purgé (fichier supprimé de R2) devient immédiatement inaccessible, sans dépendre de l'expiration d'un cache CDN qui pourrait le conserver après suppression.
- Une **règle de cycle de vie (lifecycle) R2** supprime automatiquement tout objet non modifié depuis plus de **35 jours** — filet de sécurité contre les fichiers orphelins (upload interrompu avant la création de la ligne correspondante, ancienne photo de profil jamais nettoyée après un remplacement), sans code applicatif supplémentaire.

## Alternatives
- CDN public devant tous les médias, y compris les snaps et stories (version initiale) : plus simple (un seul mécanisme de lecture), mais contraire au caractère privé et éphémère du contenu — un objet purgé pourrait rester servi depuis le cache CDN.
- Upload du média en passant par le backend : charge inutile sur le backend pour de la simple bande passante, écarté au profit de l'upload direct.
- AWS S3 : facture les frais de sortie (egress), contraire à la contrainte de coût nul du projet.

## Conséquences
### Positives
- Aucun frais de sortie de données.
- Le contenu privé/éphémère (snaps, stories) ne peut plus survivre à sa purge via un cache CDN.
- Les fichiers orphelins sont nettoyés sans code applicatif (règle de cycle de vie du bucket).

### Négatives
- Deux chemins de lecture différents à maintenir (URL publique directe pour le profil, génération d'URL pré-signée à la demande pour les snaps/stories) plutôt qu'un seul mécanisme uniforme.
- Générer une URL pré-signée à chaque lecture d'un snap/story ajoute un aller-retour au backend par rapport à une URL CDN statique — acceptable vu le volume attendu du projet.

## Historique
- Version initiale : un seul mécanisme (CDN devant R2) pour tous les médias, `mediaUrl` stocké directement en base.
- Révision (revue technique) : séparation profil (CDN public) / snaps-stories (URL pré-signées, `mediaKey` en base), ajout d'une règle de cycle de vie R2.

## Liens
- ADR liés : ADR-002, ADR-011, ADR-013, ADR-014, ADR-017

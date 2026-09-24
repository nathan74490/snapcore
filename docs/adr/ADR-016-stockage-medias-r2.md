# ADR-016 — Stockage des médias : Cloudflare R2 + upload direct par URL pré-signée + CDN

## Statut
Proposé

## Contexte
Plusieurs ADR déjà rédigés (ADR-011 caméra, ADR-013 story, ADR-014 profil) supposent un pipeline média sans jamais le documenter : "upload direct vers R2 via URL pré-signée", "pipeline média déjà défini dans l'architecture globale". Cette décision a bien été prise en amont, lors de la conception de l'architecture globale (cahier des charges), mais n'a jamais reçu d'ADR propre. Ce document formalise a posteriori ce choix déjà en usage.

## Décision
- Tout média utilisateur (photo/vidéo de snap, story, photo de profil) est stocké sur **Cloudflare R2** (compatible S3, sans frais de sortie — cohérent avec la contrainte de coût nul du projet).
- L'app mobile n'upload jamais un fichier via le backend : le backend génère une **URL pré-signée** à durée limitée, que l'app utilise pour uploader directement le fichier vers R2. Le backend ne reçoit que l'URL du média une fois l'upload confirmé.
- La lecture des médias passe par un **CDN** devant R2 pour la performance et pour éviter d'exposer le bucket directement.
- Le backend reste seul responsable de créer les URLs pré-signées (upload et lecture si le bucket n'est pas public), ce qui lui permet de garder le contrôle d'accès (un utilisateur ne peut générer une URL que pour ses propres uploads).

## Alternatives
- Upload du média en passant par le backend (le fichier transite par le serveur avant d'être renvoyé vers R2) : plus simple à contrôler côté validation, mais charge inutile sur le backend pour de la simple bande passante, et latence supplémentaire pour l'utilisateur.
- AWS S3 : équivalent fonctionnellement, mais facture les frais de sortie (egress), contraire à la contrainte de coût nul du projet ; R2 ne facture pas l'egress.

## Conséquences
### Positives
- Aucun frais de sortie de données (contrainte du projet respectée).
- Le backend ne gère jamais le poids des fichiers médias, seulement des métadonnées (URLs) — cohérent avec l'architecture hexagonale (ADR-002).

### Négatives
- Un upload direct raté côté app (réseau coupé) laisse le fichier local orphelin tant que l'app ne relance pas l'upload — déjà anticipé côté caméra (ADR-011).

## Liens
- ADR liés : ADR-002, ADR-011, ADR-013, ADR-014

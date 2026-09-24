# ADR-014 — Profil simple avec photo, entièrement modifiable (y compris le pseudo)

## Statut
Proposé

## Contexte
SnapCore doit proposer un écran de profil simple pour ce premier lot de fonctionnalités : pseudo et photo de profil, avec possibilité pour l'utilisateur de modifier l'ensemble de ces informations, y compris son propre pseudo — contrairement au vrai Snapchat où le pseudo est figé après inscription. Il faut décider du stockage de la photo, de son traitement avant envoi, et de la façon de garantir l'unicité du pseudo lors d'une modification.

## Décision
- Champs du profil en V1 : `username`, `profilePhotoUrl`, `phoneNumber` et `email` (ces deux derniers déjà ajoutés par ADR-010). Pas de nom d'affichage distinct, pas de Snap Score, pas de Bitmoji pour l'instant.
- Photo de profil : upload direct vers R2 via URL pré-signée, comme pour les snaps et les stories — même pipeline média déjà formalisé par ADR-016.
- La photo est recadrée et redimensionnée à une taille fixe (512×512) directement sur le téléphone avant l'envoi, pour limiter le stockage R2 utilisé et éviter un pipeline de traitement d'image côté serveur.
- En l'absence de photo, un avatar par défaut est généré à l'affichage (initiales du pseudo sur fond coloré), côté app — aucune image par défaut stockée sur R2.
- Toutes les informations du profil sont modifiables depuis l'écran profil, y compris le pseudo. Une modification du pseudo revérifie son unicité (contrainte `@unique` déjà présente en base sur `User.username`) ; en cas de conflit, l'API renvoie une erreur explicite (409) sans modifier la ligne. L'unicité est **insensible à la casse** : le pseudo est normalisé en minuscules avant stockage et comparaison (`"Nathan"` et `"nathan"` sont considérés identiques), comme c'est le cas dans le vrai Snapchat.
- **Modification de l'email** : depuis l'adoption de Firebase Authentication (ADR-015), Firebase est la source de vérité pour l'identifiant de connexion. Modifier l'email depuis le profil déclenche d'abord `verifyBeforeUpdateEmail` côté Firebase (qui envoie un email de confirmation à la nouvelle adresse) ; `User.email` en base n'est mis à jour qu'une fois ce changement confirmé côté Firebase — jamais directement, pour éviter que l'utilisateur se retrouve avec une adresse de connexion différente de celle affichée sur son profil.

## Alternatives
- Pseudo figé après inscription (comme le vrai Snapchat) : plus fidèle à l'original, mais explicitement écarté ici à la demande du lead développeur, qui veut que tout le profil reste modifiable.
- Traitement/redimensionnement de la photo côté serveur : évite de dupliquer la logique de recadrage sur le client, mais nécessite un pipeline de traitement d'image supplémentaire (nouvelle brique) pour un gain limité sur ce projet.

## Conséquences
### Positives
- Réutilise entièrement le pipeline média existant pour la photo de profil.
- Modèle de profil simple, cohérent avec le reste du projet (aucune nouvelle brique d'infra).
- L'utilisateur garde un contrôle total sur ses informations, y compris son pseudo.

### Négatives
- Changer librement de pseudo peut perturber la recherche d'amis par pseudo (ADR-012) si un ami cherche l'ancien pseudo après un changement — aucune limite de fréquence de changement n'est imposée en V1 ; à revoir si cela pose problème en usage réel.

## Historique
- Version initiale : ne précisait pas comment un changement d'email se répercute sur l'identifiant de connexion.
- Révision (suite à l'adoption de Firebase Authentication, ADR-015) : le changement d'email passe désormais par Firebase avant la mise à jour de la copie en base.

## Liens
- ADR liés : ADR-010, ADR-012, ADR-015, ADR-016

# ADR-001 : Application mobile native Android (Kotlin + Jetpack Compose) plutôt que cross-platform

## Statut
Accepté

## Contexte
SnapCore reproduit les fonctionnalités clés de Snapchat (caméra, filtres AR, envoi de médias en temps réel) sur Android. Il faut choisir la technologie de développement de l'app mobile, en tenant compte du besoin d'un accès bas niveau et performant à la caméra et au matériel du téléphone. Le projet est un exercice de formation, jamais publié, mais son architecture doit être pensée comme si elle devait réellement passer à l'échelle.

## Décision
Développer l'application native en Kotlin avec Jetpack Compose pour l'UI, selon une architecture MVVM.

## Alternatives
- Flutter : code unique multiplateforme, bonne vitesse de développement, mais couche d'abstraction qui ralentit l'accès direct à la caméra et au matériel, contrainte critique pour les fonctionnalités type Snap et filtres AR en temps réel.
- React Native : même limite ; un pont (bridge) entre JavaScript et natif pénalise les opérations caméra en temps réel.

## Conséquences
### Positives
- Accès complet et performant à l'API caméra (CameraX), aux capteurs, et aux SDK AR natifs (ex. Snap Camera Kit), sans couche d'abstraction intermédiaire.
- Conforme au cahier des charges, qui vise une app Android native.

### Négatives
- Aucun portage iOS possible sans réécriture complète.
- L'équipe doit maîtriser spécifiquement Kotlin et Jetpack Compose.

## Liens
- ADR liés : aucun

# ADR-015 — Authentification : mot de passe (bcrypt) + JWT access/refresh

## Statut
Proposé

## Contexte
L'onboarding (ADR-010) collecte téléphone et email sans vérification, mais aucune décision n'a encore été prise sur l'authentification elle-même : comment un utilisateur se connecte, comment son identité est vérifiée à chaque requête, et comment le mot de passe est stocké. L'écran de référence (Mobbin) montre une étape "Set a password" dans la création de compte : SnapCore doit donc bien gérer un mot de passe, ce qui manquait jusqu'ici au modèle USER (pas de champ dédié).

## Décision
- Mot de passe hashé avec **bcrypt** avant stockage (champ `passwordHash` sur USER, jamais le mot de passe en clair).
- Authentification par **JWT** : un access token de courte durée (15 min) signé par le backend, envoyé dans l'en-tête `Authorization` de chaque requête, et un refresh token de plus longue durée (30 jours) stocké côté app de façon sécurisée (Android Keystore / EncryptedSharedPreferences), utilisé uniquement pour obtenir un nouvel access token.
- Le port `AuthPort` (architecture hexagonale, ADR-002) expose `login`, `refresh`, `logout` ; l'adapter JWT est le seul endroit qui connaît le détail de signature/expiration.
- Aucun système de session côté serveur : le backend reste sans état pour l'authentification, cohérent avec une architecture pouvant tourner sur plusieurs instances.

## Alternatives
- Session côté serveur stockée dans Redis (cookie ou token opaque) : révocation immédiate triviale (suppression de la clé), et cohérent avec l'usage déjà massif de Redis dans le projet, mais moins idiomatique pour une app mobile native et ajoute une dépendance à Redis sur le chemin critique de chaque requête authentifiée.
- Argon2 plutôt que bcrypt pour le hash du mot de passe : plus moderne et résistant, mais un choix superflu pour un projet pédagogique sans exposition réelle ; bcrypt reste un standard largement suffisant et mieux supporté par l'écosystème NestJS.

## Conséquences
### Positives
- Cohérent avec une app mobile native et une architecture hexagonale (aucune dépendance technique dans le cœur métier).
- Pas de session à synchroniser entre plusieurs instances backend si le projet devait scaler.

### Négatives
- Révocation immédiate d'un access token compromis impossible avant son expiration naturelle (15 min) — acceptable vu l'échelle du projet, mais à documenter si le sujet revient.
- Le refresh token doit être stocké de façon sécurisée côté app ; une fuite du refresh token équivaut à une session compromise jusqu'à sa révocation explicite côté backend (liste de refresh tokens révoqués, à prévoir en base).

## Liens
- ADR liés : ADR-002, ADR-010

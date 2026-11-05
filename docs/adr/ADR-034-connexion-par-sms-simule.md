# ADR-034 : Connexion par numéro de téléphone et code SMS, avec un opérateur SMS simulé

## Statut
Accepté

## Contexte
L'inscription demandait un nom de famille, un mot de passe et un email (ADR-029), et l'email était l'identifiant de connexion (ADR-015). L'utilisateur veut un onboarding plus court, sans nom de famille ni email, et une connexion par **numéro de téléphone et code SMS**, comme Snapchat.

La vérification par SMS de Firebase (Phone Auth) exige le plan payant Blaze, écarté jusqu'ici (ADR-010, ADR-015). Le projet n'a pas d'opérateur SMS, et l'utilisateur demande un **envoi de SMS fictif**.

## Décision
**Code de connexion géré par le backend**, routes publiques :
- **`POST /auth/phone/code { phoneNumber }`**
  - Numéro au format international obligatoire (« +33612345678 »).
  - Le backend génère un code à 6 chiffres et en garde l'**empreinte** dans Redis (`phone-code:<numéro>`, jamais le code en clair), avec un TTL de **5 min**.
  - Il faut attendre **30 s** avant de redemander un code.
  - Le code est envoyé par le port `SmsSender`.
- **`POST /auth/phone/verify { phoneNumber, code }`**
  - Au bout de 5 erreurs, le code est invalidé.
  - Un code ne sert qu'une fois.
  - Si le code est bon, le backend retrouve le compte Firebase lié au numéro, ou le crée (sans email ni mot de passe).
  - Il renvoie un **jeton personnalisé** Firebase (`createCustomToken`, gratuit). L'app l'échange contre une session (`signInWithCustomToken`), dont l'ID token porte `phone_number`.
- **Inscription** (`POST /auth/register`) : le numéro **vérifié** est lu dans l'ID token. Il prime sur celui du corps de la requête. L'email reste celui du token, donc null pour un compte créé par SMS.

**Opérateur SMS simulé** (`SimulatedSmsSender`) :
- Aucun SMS ne part. Le message est écrit dans les journaux du backend, avec le numéro masqué (« +33•••••5678 »).
- La réponse de `POST /auth/phone/code` contient `simulatedCode`, seulement avec cet opérateur.
- L'app l'affiche dans une bannière qui imite une notification de Messages (« SMS simulé »), avec « Utiliser le code ».
- Un vrai opérateur (Twilio, ou Firebase Phone Auth avec le plan Blaze) prendra sa place derrière le même port, sans `simulatedCode`.

**App** :
- **Inscription en 5 étapes** : prénom (plus de nom de famille), date de naissance, pseudo (proposé à partir du prénom), numéro de téléphone, code reçu par SMS.
  - Le code est vérifié dès le sixième chiffre.
  - Un numéro déjà inscrit connecte simplement au compte existant.
  - La reprise d'inscription (ADR-015) garde les étapes 1 à 3.
- **Premiers pas** : l'écran « Ton numéro » disparaît, le numéro étant déjà vérifié. Il en reste 5.
- **Connexion** : numéro, puis code. Un lien « Se connecter avec un email » garde l'accès aux comptes créés avant, dont les comptes de démo, qui n'ont pas de numéro vérifié.
- **Profil** : un compte créé par SMS n'a pas de ligne Email, et son numéro, qui sert à se connecter, n'y est pas modifiable.

## Alternatives
- **Firebase Phone Auth** : SMS réels, reCAPTCHA et Play Integrity gérés par Firebase, mais le plan Blaze est payant. Écarté pour l'instant ; c'est la cible quand le projet aura un budget SMS.
- **Pseudo et mot de passe**, avec une adresse technique cachée : aucun coût, mais pas de « mot de passe oublié », et ce n'est pas le parcours de Snapchat. Proposé, non retenu par l'utilisateur.
- **Code saisi à la main sans le montrer dans l'app** : plus réaliste, mais il faudrait lire les journaux du backend pour s'inscrire. Écarté : la bannière du SMS simulé rend le parcours utilisable sur le téléphone.

## Conséquences
### Positives
- Inscription plus courte : ni nom de famille, ni mot de passe, ni email.
- Le numéro est vérifié, ce qui fiabilise la recherche d'amis par les contacts (ADR-029).
- Aucun coût : ni plan Blaze, ni opérateur SMS.

### Négatives
- **Sécurité : avec l'opérateur simulé, le code est renvoyé à qui le demande.** N'importe qui peut donc se connecter avec le numéro de n'importe qui. Acceptable tant que l'app n'est utilisée qu'en développement et en démonstration. Il faut un vrai opérateur, et retirer `simulatedCode`, **avant toute ouverture au public**.
- Pas de limite de débit globale sur les deux routes publiques, à part les 30 s entre deux codes pour un même numéro (déjà noté pour d'autres routes dans ADR-027 et ADR-029).
- Deux modes de connexion à maintenir tant que des comptes email existent.
- Un compte sans email ne peut pas récupérer son accès par email : il dépend de son numéro.

## Liens
- ADR liés : ADR-003, ADR-010, ADR-015, ADR-029, ADR-033

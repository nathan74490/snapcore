# ADR-010 : Onboarding : téléphone et email collectés, email vérifié via Firebase, téléphone non vérifié

## Statut
Accepté

## Contexte
L'onboarding de SnapCore doit reproduire le formulaire d'inscription de Snapchat (numéro de téléphone et email demandés en plus du pseudo). Une vérification par code (SMS et/ou email) nécessiterait un service d'envoi tiers facturé au message (SMS) ou au-delà d'un petit forfait gratuit (email), ce qui va à l'encontre de la contrainte du projet de ne générer aucun frais réel.

## Décision
Collecter le numéro de téléphone et l'email pendant l'onboarding, comme dans le vrai formulaire Snapchat. Le numéro de téléphone est stocké tel quel sur USER, sans aucune vérification (pas de code, pas de SMS). L'email, lui, est vérifié gratuitement via le mécanisme natif de Firebase Authentication (ADR-015) : un email de confirmation est envoyé automatiquement à l'inscription, mais **sans bloquer l'accès à l'app** tant qu'il n'est pas confirmé : pas de nouvelle friction à l'onboarding, juste une vérification qui se fait en tâche de fond puisqu'elle ne coûte rien.

## Alternatives
- Vérification par code maison, avec envoi réel désactivé derrière un drapeau (`OTP_DELIVERY_ENABLED=false`), le code étant renvoyé directement dans la réponse de l'API pour que le testeur puisse le saisir : reproduit plus fidèlement l'expérience Snapchat (étape de saisie du code), mais réimplémente un mécanisme complet (génération de code, stockage à expiration, service d'envoi théorique) que Firebase Authentication couvre déjà gratuitement pour l'email.
- Vérifier aussi le numéro de téléphone (Firebase "Phone Auth") : écarté, car l'envoi de SMS de vérification n'est disponible sur aucun plan gratuit Firebase, il faut passer au plan payant à l'usage (Blaze) pour l'activer, contrairement à la vérification d'email qui reste gratuite sur le plan gratuit (Spark).

## Conséquences
### Positives
- Aucun frais réel : la vérification d'email est gratuite (Firebase, plan Spark), celle du téléphone est explicitement évitée car payante.
- Onboarding sans friction supplémentaire : la vérification d'email ne bloque rien, elle s'ajoute sans coûter d'étape à l'utilisateur.

### Négatives
- Le numéro de téléphone n'est jamais confirmé comme appartenant réellement à l'utilisateur (pas de protection anti-bot ni de garantie de récupération de compte par SMS), ce qui est acceptable ici puisque l'app n'est jamais exposée à de vrais utilisateurs inconnus.
- L'email peut rester non confirmé indéfiniment puisque rien ne l'impose ; le statut de vérification reste donc purement informatif en V1.

## Historique
- Version initiale : aucune vérification, ni email ni téléphone.
- Révision (adoption de Firebase Authentication, ADR-015) : l'email est désormais vérifié gratuitement via Firebase, sans bloquer l'accès ; le téléphone reste non vérifié.
- Révision (onboarding façon Snapchat, ADR-029) : le téléphone n'est plus demandé dans le formulaire d'inscription mais juste après, à l'écran facultatif « Ton numéro » ; il sert à retrouver ses amis par les contacts et reste non vérifié.
- Révision (connexion par SMS, ADR-034) : le téléphone est désormais **vérifié**, par un code SMS géré par le backend, avec un opérateur SMS simulé (pas de plan Blaze). Il sert à se connecter. L'email n'est plus demandé à l'inscription, et l'écran « Ton numéro » des premiers pas disparaît.

## Liens
- ADR liés : ADR-015, ADR-029

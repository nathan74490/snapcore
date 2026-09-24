# ADR-010 — Onboarding : téléphone et email collectés, sans système de vérification par code

## Statut
Proposé

## Contexte
L'onboarding de SnapCore doit reproduire le formulaire d'inscription de Snapchat (numéro de téléphone et email demandés en plus du pseudo). Une vérification par code (SMS et/ou email) nécessiterait un service d'envoi tiers facturé au message (SMS) ou au-delà d'un petit forfait gratuit (email), ce qui va à l'encontre de la contrainte du projet de ne générer aucun frais réel.

## Décision
Collecter le numéro de téléphone et l'email pendant l'onboarding, comme dans le vrai formulaire Snapchat, mais sans aucune étape de vérification : aucun code n'est généré, aucun SMS ni email n'est envoyé, réellement ou de façon simulée. Les deux champs sont stockés tels quels sur USER.

## Alternatives
- Vérification par code avec envoi réel désactivé derrière un drapeau (`OTP_DELIVERY_ENABLED=false`), le code étant renvoyé directement dans la réponse de l'API pour que le testeur puisse le saisir : reproduit plus fidèlement l'expérience Snapchat (étape de saisie du code), mais ajoute un mécanisme complet (génération de code, stockage à expiration, service d'envoi théorique) pour un gain d'authenticité limité sur un projet qui ne sera jamais confronté à de vrais utilisateurs malveillants.

## Conséquences
### Positives
- Aucun frais réel, aucun service d'envoi à intégrer, même en théorie.
- Onboarding plus simple à développer et à tester.

### Négatives
- Le numéro de téléphone et l'email ne sont jamais confirmés comme appartenant réellement à l'utilisateur (pas de protection anti-bot ni de garantie de récupération de compte fiable) — acceptable ici puisque l'app n'est jamais exposée à de vrais utilisateurs inconnus.

## Liens
- ADR liés : aucun

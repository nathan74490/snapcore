# ADR-011 — Écran caméra : capture CameraX, geste tap/appui maintenu, nettoyage local après upload

## Statut
Accepté

## Contexte
L'écran d'accueil de SnapCore est l'écran caméra, comme dans Snapchat : capture de photo ou de vidéo (y compris en mode selfie), avec flash et bascule caméra avant/arrière. CameraX a déjà été retenu comme techno de capture (ADR lié à la stack mobile). Il faut fixer comment le geste de capture est interprété, la limite de durée d'une vidéo, et surtout où transite le média capturé avant son envoi, en cohérence avec le pipeline média déjà défini (upload direct vers R2 via URL pré-signée) et la logique éphémère du reste du projet.

## Décision
- Geste de capture : un tap sur le bouton déclenche une photo ; un appui maintenu démarre un enregistrement vidéo qui s'arrête au relâchement du bouton (comme Snapchat), via un `GestureDetector` côté UI.
- Durée maximale d'une vidéo : 60 secondes, comme la limite classique d'un snap vidéo Snapchat.
- Flash et bascule caméra avant/arrière : gérés nativement par CameraX (`flashMode` cycle off/on/auto, `CameraSelector.DEFAULT_FRONT_CAMERA`/`BACK`), sans logique métier supplémentaire.
- Permissions caméra et micro demandées au premier accès à l'écran caméra.
- Le média capturé (photo ou vidéo) est écrit dans le cache privé de l'application, jamais dans la galerie du téléphone sauf action explicite de l'utilisateur ("enregistrer"). Il est uploadé vers R2 via URL pré-signée dès que l'utilisateur choisit un destinataire ou une story, en réutilisant le pipeline média déjà défini dans l'architecture globale. Le fichier local est supprimé immédiatement après confirmation de l'upload.

## Alternatives
- Enregistrement du média capturé dans la galerie publique du téléphone par défaut : plus simple techniquement, mais contraire à la logique éphémère/privée du reste du projet, et différent du comportement réel de Snapchat (qui ne sauvegarde dans la galerie que sur action explicite).
- Pas de limite de durée vidéo : plus simple, mais s'éloigne du comportement réel de Snapchat et complique le pipeline de transcodage/upload pour des fichiers de taille arbitraire.

## Conséquences
### Positives
- Comportement fidèle à Snapchat sur les cinq points (geste, durée, flash/bascule, permissions, non-sauvegarde par défaut).
- Réutilise entièrement le pipeline média existant (upload direct R2, aucune nouvelle brique d'infra).
- Cohérent avec la logique éphémère déjà appliquée ailleurs dans le projet (ADR-003).

### Négatives
- Une vidéo interrompue par une erreur réseau avant confirmation de l'upload doit être conservée en cache le temps d'un nouvel essai, ce qui demande une gestion d'erreur explicite côté app (à prévoir dans l'implémentation).

## Liens
- ADR liés : ADR-003, ADR-016

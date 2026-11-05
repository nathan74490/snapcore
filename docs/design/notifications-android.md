# Notifications Android — présentation du 4 novembre 2026

## Présentation

Les notifications utilisent le gabarit Android natif `BigTextStyle`, avec titre développé explicite. Les réglages de taille de texte, le thème et le regroupement restent gérés par Android. Sur le Galaxy A54, ce gabarit conserve le prénom en mode développé, contrairement au premier essai avec le style de conversation.

| Événement | Titre | Aperçu | Icône / couleur |
|---|---|---|---|
| Chat | Prénom de l'ami | Nouveau chat | Bulle bleue |
| Snap photo | Prénom de l'ami | Nouveau snap photo | Appareil photo rouge |
| Snap vidéo | Prénom de l'ami | Nouveau snap vidéo | Caméra violette |
| Groupe | Nom du groupe | Léa · Nouveau snap vidéo | Type du média reçu |
| Demande d'ami | Demande d'ami | Chloé veut t'ajouter en ami | Personne et signe + |
| Demande acceptée | Nouvel ami | Prénom et confirmation | Personne et signe + |
| Snap conservé | Snap conservé | Confirmation habituelle | Étoile dorée |

- Avatar de profil de l'expéditeur, découpé en cercle avec un liseré jaune ; initiales sur fond clair si l'image est absente ou indisponible.
- Chargement des images avec le cache Coil partagé, limité à 1,5 seconde. Aucune image de snap ni aucun texte privé de conversation n'est chargé pour le visuel.
- Action « Ouvrir les chats » ou « Voir mes amis », vers les mêmes destinations que le toucher de la notification. L'ouverture d'une conversation précise n'est pas ajoutée par ce changement.
- Notification déclarée privée, avec version publique neutre « Tu as une nouvelle notification ». L'affichage réel sur écran verrouillé dépend aussi des réglages Android de l'utilisateur ; cet état verrouillé n'a pas été contrôlé manuellement.
- Canal existant `messages` conservé : les préférences de son et de vibration ne sont pas réinitialisées.
- Pour les groupes, les données du push permettent de conserver nom, expéditeur et type vidéo même si la résolution réseau échoue.

## Validation

Test sur Galaxy A54 SM-A546B, Android 16 : aperçus photo, chat, vidéo de groupe et demande d'ami ; vérification des avatars chargés et du repli en initiales ; modes réduit et développé ; regroupement Android ; action « Ouvrir les chats » vérifiée jusqu'à l'écran Chat.

- [Vue d'ensemble](./notifications-android/01-photo.png)
- [Photo développée, prénom et avatar visibles](./notifications-android/02-photo-expanded.png)

Les notifications sont des démonstrations locales. Aucun message, snap ou demande d'ami n'a été envoyé. Ces contrôles ne valident pas à nouveau le transport FCM de bout en bout.

Compilation, 160 tests unitaires Android réussis et lint sans erreur avec :

```text
gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
```

Les tests couvrent le type photo/vidéo/chat, les libellés compacts, l'avatar, les événements ignorés et le repli des notifications de groupe sans résolution réseau.

## Rejouer les aperçus

Entrée réservée aux builds **debug**, absente des builds release :

```text
adb shell am start -n com.snapcore.app/.NotificationPreviewActivity --es sample all
adb shell cmd statusbar expand-notifications
```

Valeurs possibles de `sample` : `all`, `photo`, `chat`, `group`, `friend`, `fallback`. Chaque lancement remplace uniquement les notifications de démonstration portant le tag `snapcore-preview`.

Pour retirer seulement ces aperçus, sans effacer les notifications réelles :

```text
adb shell am start -n com.snapcore.app/.NotificationPreviewActivity --es sample clear
```

Référence : [gabarits de notifications AndroidX](https://developer.android.com/reference/androidx/core/app/NotificationCompat.BigTextStyle).

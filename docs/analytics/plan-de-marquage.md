# Plan de marquage PostHog

Référence de tous les événements envoyés à PostHog (ADR-008). Les noms côté app sont centralisés dans `apps/mobile/app/src/main/kotlin/com/snapcore/app/domain/AnalyticsEvents.kt` ; ce document et ce fichier se mettent à jour ensemble.

## Règles
- **Identité** : l'id interne du compte (`User.id`), le même dans l'app et le backend. `identify` à l'ouverture de session, `reset` à la déconnexion. Les événements envoyés avant la connexion (inscription, échec de connexion) sont anonymes, puis rattachés au compte par PostHog lors de l'`identify`.
- **Jamais de donnée personnelle** dans les propriétés : ni email, ni pseudo, ni texte, ni position, ni id d'un autre utilisateur. Une erreur est décrite par son type (`http_409`, `FirebaseAuthInvalidCredentialsException`), jamais par son message.
- **Qui envoie quoi** :
  - l'**app** envoie ce que seul le téléphone voit : gestes, parcours, échecs avant d'atteindre le serveur ;
  - le **backend** envoie ce qu'il décide ou enregistre : une action réussie côté serveur.
  
  Un même fait n'est jamais envoyé des deux côtés. Par exemple, `location_sharing_changed` n'est envoyé que par le backend, une fois le réglage enregistré.
- **Capture après succès** : côté backend, un événement n'est envoyé que si l'action a abouti.
- **Pas d'enregistrement de session** : la caméra et les snaps ne quittent jamais le téléphone par ce canal.

## Écrans (app)
`$screen` avec le nom de la route (`Preview`, `SendTo`, `Conversation`, `Profile`, `Paywall`…), ou l'onglet de l'accueil (`Map`, `Chat`, `Camera`, `Stories`).

## Événements de l'app

### Compte
| Événement | Propriétés | Quand |
|---|---|---|
| `signup_step_viewed` | `step` : name, birthday, username, password, email | Chaque étape de l'inscription affichée, y compris après un retour |
| `signup_failed` | `step`, `reason` | Création du compte refusée (Firebase ou backend) |
| `login_completed` | | Connexion réussie |
| `login_failed` | `reason` | Connexion refusée |
| `signed_out` | | Déconnexion depuis le profil |
| `email_verification_resent` | | Email de confirmation renvoyé |
| `account_deletion_prompted` | | Confirmation de suppression affichée |
| `account_deletion_cancelled` | | Suppression annulée |

### Premiers pas (ADR-029)
| Événement | Propriétés | Quand |
|---|---|---|
| `onboarding_step_viewed` | `step` : phone, find_friends, add_friends, invite, avatar, notifications | Chaque étape affichée |
| `onboarding_step_skipped` | `step` | « Passer » |
| `contacts_permission_answered` | `granted`, `$feature/onboarding-contacts-copy` | Réponse à l'accès aux contacts |
| `invites_sent` | `count` | SMS d'invitation ouvert |
| `onboarding_completed` | | Fin des premiers pas |

### Caméra et envoi
| Événement | Propriétés | Quand |
|---|---|---|
| `snap_captured` | `media` : photo ou video, `lens` (booléen) | Photo prise ou vidéo enregistrée |
| `lens_selected` | `lens` : nom de la Lens | Lens choisie |
| `camera_flipped` | `facing` : front ou back | Changement de caméra |
| `flash_toggled` | `on` | Flash activé ou coupé |
| `filter_applied` | `filter`, `media` | Filtre incrusté avant l'envoi |
| `snap_preview_confirmed` | `media`, `$feature/preview-send-label` | Bouton d'envoi de l'aperçu touché |
| `snap_discarded` | `media`, `$feature/preview-send-label` | Aperçu fermé sans envoyer |
| `snap_send_started` | `media`, `friends` (nombre), `story` (booléen) | « Envoyer » touché |
| `snap_send_failed` | `media`, `target` : friend ou story | Un envoi en échec (une cible) |
| `snap_send_retried` | `media`, `target` | Réessai d'un envoi en échec |
| `snap_send_abandoned` | `media`, `target` | Envoi en échec abandonné |

### Stories et Snap Map (ADR-031)
| Événement | Propriétés | Quand |
|---|---|---|
| `story_player_opened` | `source` : stories, map ou my_story | Lecteur de stories ouvert |
| `location_permission_answered` | `granted` | Réponse à la permission de localisation |
| `map_friend_selected` | | Fiche d'un ami ouverte sur la carte |
| `map_chat_opened` | | Bouton Chat de la fiche |
| `map_recentered` | | Bouton « Me recentrer » |

### Notifications, profil, SnapCore+
| Événement | Propriétés | Quand |
|---|---|---|
| `notification_opened` | `target` : chat ou friends | Notification push touchée |
| `avatar_randomized` | | « Au hasard » dans l'éditeur d'avatar |
| `paywall_opened` | `source` : profile_card ou settings, `$feature/profile-plus-card` depuis la carte | Paywall ouvert depuis le profil |
| `checkout_started` | `$feature/paywall-cta` si exposé (ADR-030) | Bouton du paywall |
| `checkout_returned` | `activated` | Retour du paiement : abonnement actif ou non |

## Événements du backend

### Compte et profil
| Événement | Propriétés | Quand |
|---|---|---|
| `user_registered` | `with_phone` | Compte créé |
| `profile_updated` | `fields` : champs modifiés, ex. `firstName,phoneNumber` | Profil modifié (seulement si une valeur change) |
| `profile_photo_changed` / `profile_photo_removed` | | Photo de profil |
| `avatar_saved` / `avatar_removed` | | Avatar dessiné (ADR-028) |
| `account_deletion_requested` | | Suppression demandée |
| `account_deleted` | | Suppression terminée par le job ; dernier événement du compte |

### Amis (ADR-012)
| Événement | Propriétés | Quand |
|---|---|---|
| `friend_search` | `found` | Recherche par pseudo (une paire bloquée compte comme introuvable) |
| `contacts_matched` | `contacts`, `matches` (nombres) | Contacts du téléphone comparés aux comptes |
| `friend_request_sent` | `source` : username | Demande créée, ou relancée après un refus |
| `friend_request_accepted` | | Demande acceptée, y compris par une demande croisée |
| `friend_request_declined` | | Demande refusée |
| `friend_removed` | | Ami retiré |
| `user_blocked` / `user_unblocked` | | Blocage posé ou levé |

### Messages, stories, carte
| Événement | Propriétés | Quand |
|---|---|---|
| `chat_sent` | | Message texte enregistré |
| `snap_sent` | `content_type` | Snap enregistré |
| `snap_opened` / `snap_replayed` | | Snap ouvert, ou revu (SnapCore+) |
| `story_published` | `content_type` | Story publiée |
| `story_viewed` | | Élément de story vu |
| `story_item_deleted_by_author` | `content_type` | Élément retiré de sa story par l'auteur |
| `location_sharing_changed` | `mode` | Réglage de la Snap Map enregistré |
| `content_reported` | `target` : message, story ou user | Signalement (le motif, texte libre, n'est pas envoyé) |

### SnapCore+ (ADR-025)
`snapcoreplus_started`, `_renewed`, `_cancelled`, `_expired`, `_updated`, avec `tier`, reçus par webhook RevenueCat.

### Modération (backoffice)
`user_suspended`, `user_reactivated`, `message_redacted`, `story_item_deleted`, et `report_closed` avec `status`.
- Ces événements portent la propriété `by_moderator: true`.
- Ils sont envoyés avec l'id du **modérateur**, qui est un compte du backoffice (table `Moderator`), pas un utilisateur de l'app : filtrer sur `by_moderator` pour les séparer.

## Expériences A/B (ADR-030)
`$feature_flag_called` est envoyé à l'affichage de chaque variante testée. Les conversions envoyées par l'app portent `$feature/<flag>` avec la variante vue.

| Flag | Variantes | Mesure principale |
|---|---|---|
| `paywall-cta` | control, benefit | `checkout_started` |
| `onboarding-contacts-copy` | control, social | `contacts_permission_answered` (`granted = true`) |
| `preview-send-label` | control, short | `snap_preview_confirmed` |
| `map-ghost-banner` | control, friends | `location_sharing_changed` (mode autre que GHOST) |
| `profile-plus-card` | control, price | `paywall_opened` (`source = profile_card`) |

## Ce qui n'est pas mesuré
- Le contenu : texte des messages, médias, motifs de signalement.
- La position précise : seul le mode de partage est envoyé.
- La suppression de messages ou de conversations, qui n'existe pas dans l'app.

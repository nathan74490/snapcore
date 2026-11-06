package com.snapcore.app.domain

/**
 * Plan de marquage de l'app (ADR-008), tenu à jour avec docs/analytics/plan-de-marquage.md : un seul
 * endroit pour les noms d'événements, pour qu'une faute de frappe ne crée pas un événement fantôme dans
 * PostHog. Les écrans visités sont envoyés à part ([Analytics.screen]).
 *
 * L'app n'envoie que ce que seul le téléphone voit (gestes, parcours, échecs d'envoi) ; ce que le
 * serveur décide (inscription, snap reçu par le backend, ami accepté…) est capturé par le backend.
 * Propriétés : jamais de donnée personnelle (email, pseudo, texte, position, id d'un autre utilisateur).
 */
object AnalyticsEvents {
    // --- Accueil ---
    /** Bouton de l'écran d'accueil touché { choice: signup|login }. */
    const val WELCOME_CHOICE = "welcome_choice"

    // --- Compte ---
    /** Étape de l'inscription affichée { step, step_index, resuming }. */
    const val SIGNUP_STEP_VIEWED = "signup_step_viewed"
    /** Étape de l'inscription validée, vers l'étape suivante { step, step_index, duration_ms, username_source? }. */
    const val SIGNUP_STEP_COMPLETED = "signup_step_completed"
    /** Retour à l'étape précédente { step } : l'étape quittée. */
    const val SIGNUP_STEP_BACK = "signup_step_back"
    /** Fin du parcours d'inscription sur le téléphone { outcome: registered|existing_account, resuming, duration_ms }. */
    const val SIGNUP_FINISHED = "signup_finished"
    /** Code SMS demandé (ADR-034) { flow: signup|login, resend, country } : country = code pays ISO, jamais le numéro. */
    const val SMS_CODE_REQUESTED = "sms_code_requested"
    /** « Utiliser ce code » sur le SMS simulé { flow }. */
    const val SMS_CODE_AUTOFILLED = "sms_code_autofilled"
    /** Lien « Se connecter avec un email » ou retour au numéro { to: email|phone }. */
    const val LOGIN_METHOD_SWITCHED = "login_method_switched"
    /** Inscription refusée ou en échec { step, reason } (reason : type d'erreur, jamais le message). */
    const val SIGNUP_FAILED = "signup_failed"
    const val LOGIN_COMPLETED = "login_completed"
    /** { method: phone|email, reason } : reason = type d'erreur. */
    const val LOGIN_FAILED = "login_failed"
    const val SIGNED_OUT = "signed_out"
    const val EMAIL_VERIFICATION_RESENT = "email_verification_resent"
    /** Confirmation de suppression du compte affichée ; la demande elle-même est capturée par le backend. */
    const val ACCOUNT_DELETION_PROMPTED = "account_deletion_prompted"
    const val ACCOUNT_DELETION_CANCELLED = "account_deletion_cancelled"

    // --- Premiers pas (ADR-029) ---
    /** { step } */
    const val ONBOARDING_STEP_VIEWED = "onboarding_step_viewed"
    /** { step } */
    const val ONBOARDING_STEP_SKIPPED = "onboarding_step_skipped"
    /** Étape terminée par son action principale (pas « Passer ») { step, duration_ms }. */
    const val ONBOARDING_STEP_COMPLETED = "onboarding_step_completed"
    /** « Ajouter N amis » { count, suggested } : suggested = comptes retrouvés qu'on pouvait ajouter. */
    const val ONBOARDING_FRIENDS_ADDED = "onboarding_friends_added"
    /** Réponse à la permission des notifications (Android 13+) { granted, source: onboarding }. */
    const val NOTIFICATIONS_PERMISSION_ANSWERED = "notifications_permission_answered"
    /** { granted, variante de onboarding-contacts-copy } */
    const val CONTACTS_PERMISSION_ANSWERED = "contacts_permission_answered"
    /** { count, invitable } */
    const val INVITES_SENT = "invites_sent"
    /**
     * Fin des premiers pas { ended_by: done|back, duration_ms, steps_skipped, contacts_granted, friends_added,
     * invites_sent, avatar_created, notifications_granted } ; contacts_granted et notifications_granted
     * seulement si la question a été posée.
     */
    const val ONBOARDING_COMPLETED = "onboarding_completed"

    // --- Caméra et envoi ---
    /** { media: photo|video, lens: bool } */
    const val SNAP_CAPTURED = "snap_captured"
    /** { lens } : nom de la Lens. */
    const val LENS_SELECTED = "lens_selected"
    /** { facing: front|back } */
    const val CAMERA_FLIPPED = "camera_flipped"
    /** { on } */
    const val FLASH_TOGGLED = "flash_toggled"
    /** { filter, media } */
    const val FILTER_APPLIED = "filter_applied"
    /** « Envoyer à » touché sur l'aperçu { media, variante de preview-send-label } (ADR-030). */
    const val SNAP_PREVIEW_CONFIRMED = "snap_preview_confirmed"
    /** Aperçu fermé sans envoyer { media }. */
    const val SNAP_DISCARDED = "snap_discarded"
    /** Envoi lancé depuis « Envoyer à » { media, friends: nombre, story: bool }. */
    const val SNAP_SEND_STARTED = "snap_send_started"
    /** Une cible en échec { media, target: friend|story }. */
    const val SNAP_SEND_FAILED = "snap_send_failed"
    /** { media, target } */
    const val SNAP_SEND_RETRIED = "snap_send_retried"
    /** Envoi en échec abandonné { media, target }. */
    const val SNAP_SEND_ABANDONED = "snap_send_abandoned"

    // --- Stories ---
    /** { source: stories|map|my_story } */
    const val STORY_PLAYER_OPENED = "story_player_opened"

    // --- Snap Map (ADR-031) ---
    /** { granted } */
    const val LOCATION_PERMISSION_ANSWERED = "location_permission_answered"
    const val MAP_FRIEND_SELECTED = "map_friend_selected"
    const val MAP_CHAT_OPENED = "map_chat_opened"
    const val MAP_RECENTERED = "map_recentered"

    // --- Notifications ---
    /** Notification push touchée { target: chat|friends }. */
    const val NOTIFICATION_OPENED = "notification_opened"

    // --- Profil ---
    const val AVATAR_RANDOMIZED = "avatar_randomized"

    // --- SnapCore+ (ADR-025, ADR-030) ---
    /** Paywall ouvert { source: profile_card|settings|onboarding, variante de profile-plus-card } (ADR-030). */
    const val PAYWALL_OPENED = "paywall_opened"
    /** Paywall fermé sans payer (flèche ou « Plus tard ») { source: onboarding|other }. */
    const val PAYWALL_DISMISSED = "paywall_dismissed"
    /** { variante de l'expérience paywall-cta si exposée } */
    const val CHECKOUT_STARTED = "checkout_started"
    /** Retour de la page de paiement { activated } : l'abonnement est-il actif au retour ? */
    const val CHECKOUT_RETURNED = "checkout_returned"
}

/** Nom d'un type de média dans les propriétés analytics : « photo » ou « video ». */
val MediaKind.analyticsName: String get() = name.lowercase()

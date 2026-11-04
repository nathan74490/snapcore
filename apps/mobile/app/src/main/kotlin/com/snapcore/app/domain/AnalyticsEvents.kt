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
    // --- Compte ---
    /** Étape de l'inscription affichée { step }. */
    const val SIGNUP_STEP_VIEWED = "signup_step_viewed"
    /** Inscription refusée ou en échec { step, reason } (reason : type d'erreur, jamais le message). */
    const val SIGNUP_FAILED = "signup_failed"
    const val LOGIN_COMPLETED = "login_completed"
    /** { reason } : type d'erreur. */
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
    /** { granted, variante de onboarding-contacts-copy } */
    const val CONTACTS_PERMISSION_ANSWERED = "contacts_permission_answered"
    /** { count } */
    const val INVITES_SENT = "invites_sent"
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
    /** Paywall ouvert { source: profile, variante de profile-plus-card } (ADR-030). */
    const val PAYWALL_OPENED = "paywall_opened"
    /** { variante de l'expérience paywall-cta si exposée } */
    const val CHECKOUT_STARTED = "checkout_started"
    /** Retour de la page de paiement { activated } : l'abonnement est-il actif au retour ? */
    const val CHECKOUT_RETURNED = "checkout_returned"
}

/** Nom d'un type de média dans les propriétés analytics : « photo » ou « video ». */
val MediaKind.analyticsName: String get() = name.lowercase()

/**
 * Événements d'usage côté serveur (ADR-008), complétant ceux de l'app : ce qui n'existe que sur le
 * serveur (abonnement activé par un webhook, ami accepté…). L'identifiant est l'id interne du compte,
 * le même que celui de l'app : aucune donnée personnelle (email, pseudo) n'est envoyée.
 */
export interface Analytics {
  capture(userId: string, event: string, properties?: Record<string, string | number | boolean>): void;
}

export const ANALYTICS = Symbol("Analytics");

/** Par défaut (tests, instance sans clé PostHog) : rien n'est envoyé. */
export const noAnalytics: Analytics = { capture: () => undefined };

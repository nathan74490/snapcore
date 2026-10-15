import type { SubscriptionTier } from "@snapcore/types";

/** Événement d'abonnement reçu de RevenueCat, réduit à ce qui compte pour SnapCore+ (ADR-025). */
export interface SubscriptionEvent {
  type: string;
  /** app_user_id, original_app_user_id et aliases : l'un d'eux est l'id interne du compte. */
  appUserIds: string[];
  entitlementIds: string[];
  expiresAt: Date | null;
  occurredAt: Date;
}

export interface SubscriptionChange {
  tier: SubscriptionTier;
  premiumUntil: Date | null;
}

// L'accès dure jusqu'à expiration_at_ms. Une annulation coupe seulement le renouvellement ; en cas de
// remboursement, l'expiration envoyée est déjà passée.
const GRANTING = new Set([
  "INITIAL_PURCHASE",
  "RENEWAL",
  "UNCANCELLATION",
  "PRODUCT_CHANGE",
  "SUBSCRIPTION_EXTENDED",
  "REFUND_REVERSED",
  "CANCELLATION",
]);

/**
 * Effet d'un événement sur le compte, ou null s'il ne change rien (autre entitlement, événement de
 * test ou sans effet sur l'accès : BILLING_ISSUE, SUBSCRIPTION_PAUSED…).
 */
export function subscriptionChange(event: SubscriptionEvent, entitlementId: string): SubscriptionChange | null {
  if (!event.entitlementIds.includes(entitlementId)) return null;
  if (event.type === "EXPIRATION") return { tier: "free", premiumUntil: event.expiresAt };
  if (GRANTING.has(event.type) && event.expiresAt) return { tier: "premium", premiumUntil: event.expiresAt };
  return null;
}

/** Lit le champ `event` d'un webhook RevenueCat ; null si les champs indispensables manquent. */
export function parseRevenueCatEvent(raw: Record<string, unknown>): SubscriptionEvent | null {
  const type = raw.type;
  const occurredAtMs = raw.event_timestamp_ms;
  if (typeof type !== "string" || typeof occurredAtMs !== "number") return null;

  const ids = [raw.app_user_id, raw.original_app_user_id, ...(Array.isArray(raw.aliases) ? raw.aliases : [])];
  const entitlements = Array.isArray(raw.entitlement_ids) ? raw.entitlement_ids : [];
  const expiresAtMs = raw.expiration_at_ms;
  return {
    type,
    appUserIds: [...new Set(ids.filter((id): id is string => typeof id === "string" && id.length > 0))],
    entitlementIds: entitlements.filter((id): id is string => typeof id === "string"),
    expiresAt: typeof expiresAtMs === "number" ? new Date(expiresAtMs) : null,
    occurredAt: new Date(occurredAtMs),
  };
}

/** Lien de paiement hébergé (Web Purchase Link) rattaché au compte, identifié par son id interne. */
export function checkoutUrl(purchaseLink: string, userId: string, email: string | null): string {
  const url = `${purchaseLink.replace(/\/+$/, "")}/${encodeURIComponent(userId)}`;
  return email ? `${url}?email=${encodeURIComponent(email)}` : url;
}

import type { UserAccount } from "../../identity/domain/user";
import { isSnap, type Message } from "./message";

// Marge avant de considérer un abonnement comme expiré : absorbe le retard possible du
// webhook de renouvellement RevenueCat (ADR-007, ADR-017).
export const SUBSCRIPTION_GRACE_MS = 24 * 60 * 60 * 1000;

export type PurgeDecision =
  | { kind: "nothing" } // Chat texte (jamais purgé) ou média déjà purgé
  | { kind: "not-due"; at: Date } // exécution anticipée : scheduledPurgeAt fait foi
  | { kind: "retain"; until: Date } // destinataire SnapCore+ actif : purge repoussée
  | { kind: "purge" };

// Unique point de décision de la purge, que le job vienne de son échéance normale ou du
// balayage de rattrapage (ADR-017). `recipient` est null si le compte n'est plus actif.
export function decidePurge(
  message: Pick<Message, "contentType" | "mediaKey" | "scheduledPurgeAt">,
  recipient: Pick<UserAccount, "subscriptionTier" | "premiumUntil"> | null,
  now: Date,
): PurgeDecision {
  if (!isSnap(message) || message.mediaKey === null) return { kind: "nothing" };
  if (message.scheduledPurgeAt && message.scheduledPurgeAt > now) return { kind: "not-due", at: message.scheduledPurgeAt };

  if (recipient?.subscriptionTier === "premium" && recipient.premiumUntil) {
    const until = new Date(recipient.premiumUntil.getTime() + SUBSCRIPTION_GRACE_MS);
    if (until > now) return { kind: "retain", until };
  }
  return { kind: "purge" };
}

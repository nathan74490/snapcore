// Types métier partagés entre apps/backend et apps/backoffice.
export type SubscriptionTier = "free" | "premium";

export interface User {
  id: string;
  username: string;
  subscriptionTier: SubscriptionTier;
  premiumUntil?: string;
}

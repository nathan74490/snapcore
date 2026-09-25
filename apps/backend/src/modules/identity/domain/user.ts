import type { PublicUserProfile, SubscriptionTier } from "@snapcore/types";

export interface UserAccount {
  id: string;
  username: string;
  firebaseUid: string;
  profilePhotoUrl: string | null;
  phoneNumber: string | null;
  email: string | null;
  subscriptionTier: SubscriptionTier;
  premiumUntil: Date | null;
  createdAt: Date;
  deletionRequestedAt: Date | null;
}

// Abonnement SnapCore+ actif à l'instant `now` (ADR-007). Sans marge : la marge de 24h
// d'ADR-017 ne s'applique qu'à la décision de purge, pas au droit de revoir un Snap (ADR-009).
export function hasActiveSubscription(user: Pick<UserAccount, "subscriptionTier" | "premiumUntil">, now: Date): boolean {
  return user.subscriptionTier === "premium" && user.premiumUntil !== null && user.premiumUntil > now;
}

export function toPublicProfile(user: Pick<UserAccount, "id" | "username" | "profilePhotoUrl">): PublicUserProfile {
  return {
    id: user.id,
    username: user.username,
    ...(user.profilePhotoUrl ? { profilePhotoUrl: user.profilePhotoUrl } : {}),
  };
}

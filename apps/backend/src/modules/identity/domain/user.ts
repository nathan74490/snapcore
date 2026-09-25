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

export function toPublicProfile(user: Pick<UserAccount, "id" | "username" | "profilePhotoUrl">): PublicUserProfile {
  return {
    id: user.id,
    username: user.username,
    ...(user.profilePhotoUrl ? { profilePhotoUrl: user.profilePhotoUrl } : {}),
  };
}

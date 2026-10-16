import type { User } from "@snapcore/types";
import type { UserAccount } from "../../domain/user";

// Vue de l'utilisateur sur lui-même. firebaseUid n'est jamais exposé (ADR-015).
export function presentSelf(user: UserAccount): User {
  return {
    id: user.id,
    username: user.username,
    subscriptionTier: user.subscriptionTier,
    moderationStatus: user.moderationStatus,
    ...(user.firstName ? { firstName: user.firstName } : {}),
    ...(user.profilePhotoUrl ? { profilePhotoUrl: user.profilePhotoUrl } : {}),
    ...(user.avatar ? { avatar: { ...user.avatar } } : {}),
    ...(user.phoneNumber ? { phoneNumber: user.phoneNumber } : {}),
    ...(user.email ? { email: user.email } : {}),
    ...(user.premiumUntil ? { premiumUntil: user.premiumUntil.toISOString() } : {}),
  };
}

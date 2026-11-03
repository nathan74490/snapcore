import type { AvatarOptions, ModerationStatus, PublicUserProfile, SubscriptionTier } from "@snapcore/types";

export interface UserAccount {
  id: string;
  username: string;
  firstName: string | null; // null pour les comptes créés avant son introduction
  lastName: string | null; // facultatif et privé, jamais dans un profil public (ADR-029)
  birthday: Date | null; // minuit UTC ; privée ; null pour les comptes créés avant son introduction (ADR-029)
  firebaseUid: string;
  profilePhotoUrl: string | null;
  avatar: AvatarOptions | null; // avatar dessiné (ADR-028), null = pas d'avatar
  phoneNumber: string | null;
  email: string | null;
  subscriptionTier: SubscriptionTier;
  premiumUntil: Date | null;
  createdAt: Date;
  deletionRequestedAt: Date | null;
  moderationStatus: ModerationStatus;
}

// Équivalent domaine de ACTIVE_USER_WHERE (ADR-019, ADR-021) : ni en suppression, ni suspendu.
export function isActive(user: Pick<UserAccount, "deletionRequestedAt" | "moderationStatus">): boolean {
  return user.deletionRequestedAt === null && user.moderationStatus === "ACTIVE";
}

// Abonnement SnapCore+ actif à l'instant `now` (ADR-007). Sans marge : la marge de 24h
// d'ADR-017 ne s'applique qu'à la décision de purge, pas au droit de revoir un Snap (ADR-009).
export function hasActiveSubscription(user: Pick<UserAccount, "subscriptionTier" | "premiumUntil">, now: Date): boolean {
  return user.subscriptionTier === "premium" && user.premiumUntil !== null && user.premiumUntil > now;
}

// Seul constructeur de profil public (recherche, amis, demandes, conversations, stories).
// Le nom de famille et la date de naissance n'y figurent jamais : ils restent privés (ADR-029).
export function toPublicProfile(
  user: Pick<UserAccount, "id" | "username" | "firstName" | "profilePhotoUrl" | "avatar">,
): PublicUserProfile {
  return {
    id: user.id,
    username: user.username,
    ...(user.firstName ? { firstName: user.firstName } : {}),
    ...(user.profilePhotoUrl ? { profilePhotoUrl: user.profilePhotoUrl } : {}),
    ...(user.avatar ? { avatar: { ...user.avatar } } : {}),
  };
}

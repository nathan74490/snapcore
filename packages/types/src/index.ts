// Types métier partagés entre apps/backend et apps/backoffice.
export type SubscriptionTier = "free" | "premium";

export interface User {
  id: string;
  username: string;             // modifiable depuis le profil, unicité revérifiée (voir ADR-014)
  profilePhotoUrl?: string;     // recadrée/redimensionnée côté app avant upload (voir ADR-014)
  phoneNumber?: string;         // collecté à l'onboarding, jamais vérifié (voir ADR-010)
  email?: string;               // collecté à l'onboarding, jamais vérifié (voir ADR-010)
  subscriptionTier: SubscriptionTier;
  premiumUntil?: string;
}

// Types de contenu d'un message de chat, pour le rendu des icônes/couleurs
// façon Snapchat (voir ADR-009). Le sens (envoyé/reçu) et le statut
// (non ouvert / ouvert / expiré) se déduisent côté client, à partir de
// l'expéditeur et du suivi de vue existant (Redis + TTL, ADR-003).
export type MessageContentType = "TEXT" | "SNAP_PHOTO" | "SNAP_PHOTO_VIDEO";

export interface Message {
  id: string;
  senderId: string;
  recipientId: string;
  contentType: MessageContentType;
  createdAt: string;
}

// Relation d'amitié entre deux utilisateurs (voir ADR-012).
// V1 : recherche par pseudo uniquement. V2 (plus tard) : Snapcode, meilleur ami, flammes.
export type FriendshipStatus = "PENDING" | "ACCEPTED" | "BLOCKED" | "DECLINED";

export interface Friendship {
  id: string;
  requesterId: string;
  addresseeId: string;
  status: FriendshipStatus;
  createdAt: string;
  respondedAt?: string;
}

// Un élément de story = un snap publié en story (voir ADR-013).
// Expiration à createdAt + 24h (job BullMQ de purge déjà prévu).
// Vues suivies dans Redis (Set avec TTL), pas en base — même principe qu'un snap privé (ADR-003).
export type StoryContentType = "PHOTO" | "VIDEO";

export interface StoryItem {
  id: string;
  authorId: string;
  mediaUrl: string;
  contentType: StoryContentType;
  createdAt: string;
  expiresAt: string;
}

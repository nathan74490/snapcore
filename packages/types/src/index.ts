// Types métier partagés entre apps/backend et apps/backoffice.
export type SubscriptionTier = "free" | "premium";

export interface User {
  id: string;
  username: string;             // normalisé en minuscules, modifiable depuis le profil, unicité revérifiée (voir ADR-014)
  // passwordHash n'est jamais exposé dans les types partagés app/backoffice : il ne quitte pas le backend (voir ADR-015)
  profilePhotoUrl?: string;     // recadrée/redimensionnée côté app avant upload (voir ADR-014, pipeline média ADR-016)
  phoneNumber?: string;         // collecté à l'onboarding, jamais vérifié (voir ADR-010)
  email?: string;               // collecté à l'onboarding, jamais vérifié (voir ADR-010)
  subscriptionTier: SubscriptionTier;
  premiumUntil?: string;
}

// Types de contenu d'un message de chat, pour le rendu des icônes/couleurs
// façon Snapchat (voir ADR-009). Le sens (envoyé/reçu) se déduit côté client
// à partir de l'expéditeur ; le statut (non ouvert / ouvert / expiré) se
// déduit d'`openedAt` (marqueur durable, voir ADR-009) et du TTL Redis
// d'expiration visuelle (ADR-003).
export type MessageContentType = "TEXT" | "SNAP_PHOTO" | "SNAP_PHOTO_VIDEO";

export interface Message {
  id: string;
  senderId: string;
  recipientId: string;
  contentType: MessageContentType;
  content?: string;    // texte du chat, uniquement si contentType = "TEXT" (voir ADR-009)
  mediaUrl?: string;    // média R2 (voir ADR-016), uniquement si contentType est un des deux types de Snap (voir ADR-009)
  createdAt: string;
  openedAt?: string;    // horodatage durable de la 1re ouverture ; absent = jamais ouvert (voir ADR-009)
}

// Relation d'amitié entre deux utilisateurs (voir ADR-012).
// V1 : recherche par pseudo uniquement. V2 (plus tard) : Snapcode, meilleur ami, flammes.
// L'unicité de la paire dans les deux sens est garantie côté application, pas par le type ni la base.
export type FriendshipStatus = "PENDING" | "ACCEPTED" | "BLOCKED" | "DECLINED";

export interface Friendship {
  id: string;
  requesterId: string;
  addresseeId: string;
  status: FriendshipStatus;
  blockedBy?: string;   // ID de l'utilisateur à l'origine du blocage, uniquement si status = "BLOCKED" (voir ADR-012)
  createdAt: string;
  respondedAt?: string;
}

// Un élément de story = un snap publié en story (voir ADR-013).
// Expiration à createdAt + 24h (job BullMQ de purge, voir ADR-017).
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

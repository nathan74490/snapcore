// Types métier partagés entre apps/backend et apps/backoffice.
export type SubscriptionTier = "free" | "premium";

export interface User {
  id: string;
  username: string;             // normalisé en minuscules, modifiable depuis le profil, unicité revérifiée (voir ADR-014)
  // firebaseUid (lien vers le compte Firebase Authentication) n'est jamais exposé dans les types partagés app/backoffice (voir ADR-015)
  profilePhotoUrl?: string;     // URL publique CDN, recadrée/redimensionnée côté app avant upload (voir ADR-014, ADR-016)
  phoneNumber?: string;         // collecté à l'onboarding, jamais vérifié (voir ADR-010)
  email?: string;               // copie de lecture ; Firebase reste la source de vérité pour la connexion, tout changement passe par Firebase avant mise à jour ici (voir ADR-014, ADR-015)
  subscriptionTier: SubscriptionTier;
  premiumUntil?: string;
}

// Types de contenu d'un message de chat, pour le rendu des icônes/couleurs
// façon Snapchat (voir ADR-009). Le sens (envoyé/reçu) se déduit côté client
// à partir de l'expéditeur ; le statut (non ouvert / ouvert / expiré) se
// déduit uniquement d'`openedAt` + 24h, sans dépendance à Redis (voir ADR-009).
export type MessageContentType = "TEXT" | "SNAP_PHOTO" | "SNAP_PHOTO_VIDEO";

export interface Message {
  id: string;
  senderId: string;
  recipientId: string;
  contentType: MessageContentType;
  content?: string;    // texte du chat, uniquement si contentType = "TEXT" ; vidé après purge (voir ADR-009, ADR-017)
  mediaKey?: string;    // clé d'objet R2 (voir ADR-016), jamais une URL directe ; uniquement si contentType est un type de Snap ; vidée après purge (voir ADR-009, ADR-017)
  createdAt: string;
  openedAt?: string;    // horodatage durable de la 1re ouverture ; absent = jamais ouvert (voir ADR-009)
}

// Relation d'amitié entre deux utilisateurs (voir ADR-012).
// V1 : recherche par pseudo uniquement. V2 (plus tard) : Snapcode, meilleur ami, flammes.
// L'unicité de la paire dans les deux sens est garantie côté base (paire triée), pas seulement côté application.
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
// Lu via URL pré-signée générée à la demande, jamais via CDN (voir ADR-016).
// Vues suivies dans Redis (Set avec TTL), pas en base — même principe qu'un snap privé (ADR-003).
export type StoryContentType = "PHOTO" | "VIDEO";

export interface StoryItem {
  id: string;
  authorId: string;
  mediaKey: string;
  contentType: StoryContentType;
  createdAt: string;
  expiresAt: string;
}

// Token de notification push d'un appareil (voir ADR-018).
export interface DeviceToken {
  id: string;
  userId: string;
  token: string;
  updatedAt: string;
}

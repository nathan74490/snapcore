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
  deletionRequestedAt?: string; // renseigné dès la demande de suppression de compte ; bloque connexion/actions même avant la fin du job de suppression (voir ADR-019). Champ interne : à exclure de toute réponse représentant le profil PUBLIC d'un autre utilisateur (recherche, profil consulté, liste d'amis) — pertinent uniquement pour l'utilisateur lui-même et le backend.
}

// Types de contenu d'un message de chat, pour le rendu des icônes/couleurs
// façon Snapchat (voir ADR-009). Le sens (envoyé/reçu) se déduit côté client
// à partir de l'expéditeur ; le statut se déduit d'`openedAt`.
// TEXT est permanent (jamais purgé, jamais gris). Un Snap (SNAP_PHOTO /
// SNAP_PHOTO_VIDEO) est éphémère : purgé 24h après ouverture (ou 30j si
// jamais ouvert), sauf abonnement SnapCore+ actif qui le conserve
// indéfiniment (voir ADR-009, ADR-017).
export type MessageContentType = "TEXT" | "SNAP_PHOTO" | "SNAP_PHOTO_VIDEO";

export interface Message {
  id: string;
  senderId: string;
  recipientId: string;
  contentType: MessageContentType;
  content?: string;    // texte du chat, uniquement si contentType = "TEXT" ; PERMANENT, jamais purgé (voir ADR-009)
  mediaKey?: string;    // clé d'objet R2 (voir ADR-016), jamais une URL directe ; uniquement si contentType est un type de Snap ; vidée après purge sauf abonnement actif (voir ADR-009, ADR-017)
  createdAt: string;
  openedAt?: string;    // horodatage durable de la 1re ouverture ; absent = jamais ouvert (voir ADR-009)
  retainedNotifiedAt?: string; // renseigné au premier envoi de la notification "Snap conservé" à l'expéditeur, pour qu'elle ne parte qu'une fois (voir ADR-009)
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
  // BLOCKED masque le profil/contenu et interdit la messagerie dans les deux sens ;
  // seul blockedByUser peut débloquer ; un blocage mutuel est un no-op apparent mais pas rigoureusement
  // symétrique, et débloquer un ex-ami ne restaure pas l'amitié précédente (voir ADR-012).
  blockedBy?: string;   // ID de l'utilisateur à l'origine du blocage, uniquement si status = "BLOCKED" (voir ADR-012)
  requestedAt: string;  // renommé depuis createdAt (ADR-012 v3)
  respondedAt?: string;
}

// Un élément de story = un snap publié en story (voir ADR-013).
// Expiration à createdAt + 24h (job BullMQ de purge, voir ADR-017).
// Lu via URL pré-signée générée à la demande, jamais via CDN (voir ADR-016).
// Vues suivies dans Redis (Set avec TTL aligné sur expiresAt), pas en base : la liste des viewers n'a pas besoin de survivre à l'expiration du contenu (ADR-013). Indépendant du statut des snaps privés, qui est désormais stocké durablement en Postgres (ADR-009), pas sur Redis.
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

// Profil d'un AUTRE utilisateur tel qu'exposé par l'API (recherche, liste d'amis, demandes).
// Ne contient jamais phoneNumber, email ni deletionRequestedAt (voir ADR-014, ADR-019).
export interface PublicUserProfile {
  id: string;
  username: string;
  profilePhotoUrl?: string;
}

// Relation vue depuis l'utilisateur courant, pour l'affichage d'un résultat de recherche.
// DECLINED est volontairement présenté comme "NONE" (un refus n'est pas révélé), et un
// utilisateur BLOCKED n'apparaît jamais dans une recherche (voir ADR-012).
export type FriendshipRelation = "NONE" | "OUTGOING_REQUEST" | "INCOMING_REQUEST" | "FRIENDS";

export interface UserSearchResult extends PublicUserProfile {
  relation: FriendshipRelation;
}

// Une ligne de liste (amis, demandes reçues/envoyées, utilisateurs bloqués) : la relation
// et l'autre utilisateur de la paire (voir ADR-012).
export interface FriendshipListItem {
  friendshipId: string;
  status: FriendshipStatus;
  requestedAt: string;
  respondedAt?: string;
  user: PublicUserProfile;
}

// Message tel qu'exposé par l'API (voir ADR-009). La clé R2 n'est jamais exposée :
// `mediaPurged` suffit au client pour afficher l'icône grise d'un Snap expiré.
export interface MessageView {
  id: string;
  senderId: string;
  recipientId: string;
  contentType: MessageContentType;
  content?: string;      // uniquement pour TEXT
  createdAt: string;
  openedAt?: string;
  mediaPurged: boolean;  // toujours false pour TEXT
}

// Une ligne de la liste de conversations : uniquement les amis ACCEPTED (voir ADR-012).
export interface ConversationSummary {
  friend: PublicUserProfile;
  lastMessage?: MessageView;
}

// URL de lecture d'un Snap, à durée de vie courte (~1 min), jamais mise en cache disque (voir ADR-009).
export interface SnapViewUrl {
  url: string;
  expiresAt: string;
}

// Élément de story tel qu'exposé à un ami : la clé R2 n'est jamais exposée (voir ADR-013, ADR-016).
export interface StoryItemView {
  id: string;
  contentType: StoryContentType;
  createdAt: string;
  expiresAt: string;
  viewed: boolean;          // l'utilisateur courant l'a déjà vu
}

// Story d'un ami : ses éléments encore visibles, du plus ancien au plus récent.
export interface StoryFeedEntry {
  author: PublicUserProfile;
  items: StoryItemView[];
  hasUnviewed: boolean;
}

// Élément de ma propre story, avec le nombre de vues (voir ADR-013).
export interface OwnStoryItemView {
  id: string;
  contentType: StoryContentType;
  createdAt: string;
  expiresAt: string;
  viewCount: number;
}

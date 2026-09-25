export const FRIENDSHIP_NOTIFIER = Symbol("FriendshipNotifier");

// Notification des demandes/acceptations : Socket.io si le destinataire est connecté,
// sinon push FCM (ADR-012, ADR-018). Best-effort : l'API REST reste la source de vérité (ADR-006).
export interface FriendshipNotifier {
  friendRequestReceived(addresseeId: string, friendshipId: string): Promise<void>;
  friendRequestAccepted(requesterId: string, friendshipId: string): Promise<void>;
}

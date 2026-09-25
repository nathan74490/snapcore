import type { UserEventPublisher } from "../../notifications/application/user-event-publisher";
import type { FriendshipNotifier } from "../application/ports/friendship-notifier.port";

// Socket.io si le destinataire est connecté, sinon push FCM (ADR-012, ADR-018).
export class EventFriendshipNotifier implements FriendshipNotifier {
  constructor(private readonly events: UserEventPublisher) {}

  friendRequestReceived(addresseeId: string, friendshipId: string): Promise<void> {
    return this.events.publish(addresseeId, { type: "friend_request.received", friendshipId });
  }

  friendRequestAccepted(requesterId: string, friendshipId: string): Promise<void> {
    return this.events.publish(requesterId, { type: "friend_request.accepted", friendshipId });
  }
}

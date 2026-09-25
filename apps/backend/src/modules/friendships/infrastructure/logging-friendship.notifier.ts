import { Injectable, Logger } from "@nestjs/common";
import type { FriendshipNotifier } from "../application/ports/friendship-notifier.port";

// Adaptateur provisoire en attendant la passerelle Socket.io (ADR-006) et FCM (ADR-018) :
// il se contente de journaliser l'événement.
@Injectable()
export class LoggingFriendshipNotifier implements FriendshipNotifier {
  private readonly logger = new Logger("FriendshipNotifier");

  async friendRequestReceived(addresseeId: string, friendshipId: string): Promise<void> {
    this.logger.log(`friend_request.received → ${addresseeId} (${friendshipId})`);
  }

  async friendRequestAccepted(requesterId: string, friendshipId: string): Promise<void> {
    this.logger.log(`friend_request.accepted → ${requesterId} (${friendshipId})`);
  }
}

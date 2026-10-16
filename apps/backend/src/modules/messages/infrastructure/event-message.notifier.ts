import type { UserEventPublisher } from "../../notifications/application/user-event-publisher";
import type { MessageNotifier } from "../application/ports/message-notifier.port";

// Socket.io si l'utilisateur est connecté, sinon push FCM pour les événements qui le justifient
// (ADR-006, ADR-018, voir deservesPush).
export class EventMessageNotifier implements MessageNotifier {
  constructor(private readonly events: UserEventPublisher) {}

  messageReceived(recipientId: string, messageId: string): Promise<void> {
    return this.events.publish(recipientId, { type: "message.received", messageId });
  }

  snapOpened(senderId: string, messageId: string): Promise<void> {
    return this.events.publish(senderId, { type: "snap.opened", messageId });
  }

  textsRead(senderId: string, readerId: string): Promise<void> {
    return this.events.publish(senderId, { type: "texts.read", readerId });
  }

  snapRetained(senderId: string, messageId: string): Promise<void> {
    return this.events.publish(senderId, { type: "snap.retained", messageId });
  }

  typing(recipientId: string, typistId: string): Promise<void> {
    return this.events.publish(recipientId, { type: "typing", userId: typistId });
  }

  async snapExpired(participantIds: [string, string], messageId: string): Promise<void> {
    await Promise.all(participantIds.map((id) => this.events.publish(id, { type: "snap.expired", messageId })));
  }
}

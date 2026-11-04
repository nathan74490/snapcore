import type { UserEventPublisher } from "../../notifications/application/user-event-publisher";
import type { UserEvent } from "../../notifications/domain/user-event";
import type { GroupMessageReceived, GroupNotifier } from "../application/ports";

// Un événement par membre concerné : Socket.io s'il est connecté, sinon push FCM pour le seul message
// reçu (ADR-006, ADR-018, ADR-032).
export class EventGroupNotifier implements GroupNotifier {
  constructor(private readonly events: UserEventPublisher) {}

  messageReceived(recipientIds: string[], event: GroupMessageReceived): Promise<void> {
    const { groupName, ...rest } = event;
    return this.publish(recipientIds, {
      type: "group.message.received",
      ...rest,
      ...(groupName !== null ? { groupName } : {}),
    });
  }

  updated(memberIds: string[], groupId: string): Promise<void> {
    return this.publish(memberIds, { type: "group.updated", groupId });
  }

  typing(recipientIds: string[], groupId: string, typistId: string): Promise<void> {
    return this.publish(recipientIds, { type: "group.typing", groupId, userId: typistId });
  }

  messageSeen(recipientIds: string[], groupId: string, messageId: string, viewerId: string): Promise<void> {
    return this.publish(recipientIds, { type: "group.message.seen", groupId, messageId, userId: viewerId });
  }

  private async publish(userIds: string[], event: UserEvent): Promise<void> {
    // UserEventPublisher ne lève jamais : un membre injoignable ne bloque pas les autres.
    await Promise.all(userIds.map((id) => this.events.publish(id, event)));
  }
}

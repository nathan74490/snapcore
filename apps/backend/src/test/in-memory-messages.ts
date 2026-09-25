import type { MessageNotifier } from "../modules/messages/application/ports/message-notifier.port";
import type { MessageRepository, NewMessage } from "../modules/messages/application/ports/message.repository";
import type { SnapPurgeScheduler } from "../modules/messages/application/ports/snap-purge.scheduler";
import type { Message } from "../modules/messages/domain/message";

export class InMemoryMessageRepository implements MessageRepository {
  readonly rows = new Map<string, Message>();

  async create(message: NewMessage): Promise<Message> {
    const row: Message = { ...message, openedAt: null, retainedNotifiedAt: null };
    this.rows.set(row.id, row);
    return { ...row };
  }

  async findById(id: string) {
    const row = this.rows.get(id);
    return row ? { ...row } : null;
  }

  async markOpenedIfUnopened(id: string, at: Date) {
    const row = this.rows.get(id);
    if (!row || row.openedAt) return false;
    row.openedAt = at;
    return true;
  }

  async setScheduledPurgeAt(id: string, at: Date) {
    this.rows.get(id)!.scheduledPurgeAt = at;
  }

  async markTextsRead(recipientId: string, senderId: string, at: Date) {
    let count = 0;
    for (const row of this.rows.values()) {
      if (row.recipientId === recipientId && row.senderId === senderId && row.contentType === "TEXT" && !row.openedAt) {
        row.openedAt = at;
        count++;
      }
    }
    return count;
  }

  async listBetween(userA: string, userB: string, options: { before?: Date; limit: number }) {
    return this.between(userA, userB)
      .filter((m) => !options.before || m.createdAt < options.before)
      .slice(0, options.limit);
  }

  async findLastMessagesWith(userId: string, otherUserIds: string[]) {
    const result = new Map<string, Message>();
    for (const other of otherUserIds) {
      const [last] = this.between(userId, other);
      if (last) result.set(other, last);
    }
    return result;
  }

  private between(userA: string, userB: string): Message[] {
    return [...this.rows.values()]
      .filter(
        (m) =>
          (m.senderId === userA && m.recipientId === userB) || (m.senderId === userB && m.recipientId === userA),
      )
      .sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime() || b.id.localeCompare(a.id))
      .map((m) => ({ ...m }));
  }
}

export class RecordingPurgeScheduler implements SnapPurgeScheduler {
  readonly calls: Array<{ op: "schedule" | "reschedule"; messageId: string; at: Date }> = [];

  async schedule(messageId: string, at: Date) {
    this.calls.push({ op: "schedule", messageId, at });
  }
  async reschedule(messageId: string, at: Date) {
    this.calls.push({ op: "reschedule", messageId, at });
  }
}

export class RecordingMessageNotifier implements MessageNotifier {
  readonly events: string[] = [];

  async messageReceived(recipientId: string) {
    this.events.push(`received→${recipientId}`);
  }
  async snapOpened(senderId: string) {
    this.events.push(`opened→${senderId}`);
  }
  async textsRead(senderId: string) {
    this.events.push(`read→${senderId}`);
  }
}

import type { MessageNotifier } from "../modules/messages/application/ports/message-notifier.port";
import type { MessageRepository, NewMessage } from "../modules/messages/application/ports/message.repository";
import type { SnapPurgeScheduler } from "../modules/messages/application/ports/snap-purge.scheduler";
import type { Message } from "../modules/messages/domain/message";

export class InMemoryMessageRepository implements MessageRepository {
  readonly rows = new Map<string, Message>();

  async create(message: NewMessage): Promise<Message> {
    const row: Message = { ...message, openedAt: null, retainedNotifiedAt: null, moderatedAt: null };
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

  async clearMedia(id: string) {
    this.rows.get(id)!.mediaKey = null;
  }

  async markRetainedNotified(id: string, at: Date) {
    const row = this.rows.get(id);
    if (!row || row.retainedNotifiedAt) return false;
    row.retainedNotifiedAt = at;
    return true;
  }

  async findOverdueSnapIds(now: Date, limit: number) {
    return [...this.rows.values()]
      .filter((m) => m.contentType !== "TEXT" && m.mediaKey !== null && m.scheduledPurgeAt && m.scheduledPurgeAt <= now)
      .sort((a, b) => a.scheduledPurgeAt!.getTime() - b.scheduledPurgeAt!.getTime())
      .slice(0, limit)
      .map((m) => m.id);
  }

  async redact(id: string, moderation: { moderatorId: string; reason: string; at: Date }) {
    const row = this.rows.get(id);
    if (!row || row.moderatedAt) return false;
    Object.assign(row, { content: null, mediaKey: null, moderatedAt: moderation.at });
    this.moderations.set(id, moderation);
    return true;
  }
  readonly moderations = new Map<string, { moderatorId: string; reason: string; at: Date }>();

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
  readonly recovered: string[] = [];
  recoverOutcome: "retried" | "re-added" | "untouched" = "untouched";

  async schedule(messageId: string, at: Date) {
    this.calls.push({ op: "schedule", messageId, at });
  }
  async reschedule(messageId: string, at: Date) {
    this.calls.push({ op: "reschedule", messageId, at });
  }
  async recover(messageId: string) {
    this.recovered.push(messageId);
    return this.recoverOutcome;
  }
  readonly cancelled: string[] = [];
  async cancel(messageId: string) {
    this.cancelled.push(messageId);
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
  async snapRetained(senderId: string) {
    this.events.push(`retained→${senderId}`);
  }
  async typing(recipientId: string, typistId: string) {
    this.events.push(`typing ${typistId}→${recipientId}`);
  }
  async snapExpired(participantIds: [string, string]) {
    this.events.push(`expired→${participantIds.join(",")}`);
  }
}

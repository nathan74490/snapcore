import type {
  DeviceTokenRepository,
  PresenceTracker,
  PushSender,
  RealtimeChannel,
} from "../modules/notifications/application/ports";
import type { UserEvent } from "../modules/notifications/domain/user-event";

export class InMemoryPresence implements PresenceTracker {
  readonly online = new Map<string, Set<string>>();

  async touch(userId: string, connectionId: string) {
    this.online.set(userId, (this.online.get(userId) ?? new Set()).add(connectionId));
  }
  async disconnect(userId: string, connectionId: string) {
    this.online.get(userId)?.delete(connectionId);
  }
  async isOnline(userId: string) {
    return (this.online.get(userId)?.size ?? 0) > 0;
  }
}

export class RecordingRealtime implements RealtimeChannel {
  readonly emitted: Array<{ userId: string; event: UserEvent }> = [];
  async emitToUser(userId: string, event: UserEvent) {
    this.emitted.push({ userId, event });
  }
}

export class RecordingPush implements PushSender {
  readonly sent: Array<{ tokens: string[]; data: Record<string, string> }> = [];
  deadTokens = new Set<string>();
  async send(tokens: string[], data: Record<string, string>) {
    this.sent.push({ tokens, data });
    return { invalidTokens: tokens.filter((t) => this.deadTokens.has(t)) };
  }
}

export class InMemoryDeviceTokens implements DeviceTokenRepository {
  readonly owners = new Map<string, string>(); // token → userId

  async register(userId: string, token: string) {
    this.owners.set(token, userId);
  }
  async unregister(userId: string, token: string) {
    if (this.owners.get(token) !== userId) return false;
    return this.owners.delete(token);
  }
  async listTokens(userId: string) {
    return [...this.owners].filter(([, owner]) => owner === userId).map(([token]) => token);
  }
  async deleteTokens(tokens: string[]) {
    tokens.forEach((t) => this.owners.delete(t));
  }
}

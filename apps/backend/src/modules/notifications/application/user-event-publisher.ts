import type { AppLogger } from "../../../shared/domain/logger";
import { deservesPush, toPushData, type UserEvent } from "../domain/user-event";
import type { DeviceTokenRepository, PresenceTracker, PushSender, RealtimeChannel } from "./ports";

// Point d'entrée unique des notifications (ADR-018) : connecté → Socket.io, sinon push FCM
// sur tous ses appareils. Best-effort : n'échoue jamais, l'API REST reste la source de vérité.
export class UserEventPublisher {
  constructor(
    private readonly presence: PresenceTracker,
    private readonly realtime: RealtimeChannel,
    private readonly push: PushSender,
    private readonly devices: DeviceTokenRepository,
    private readonly logger: AppLogger,
  ) {}

  async publish(userId: string, event: UserEvent): Promise<void> {
    try {
      if (await this.presence.isOnline(userId)) {
        await this.realtime.emitToUser(userId, event);
        return;
      }
      if (!deservesPush(event)) return;

      const tokens = await this.devices.listTokens(userId);
      if (tokens.length === 0) return;
      const { invalidTokens } = await this.push.send(tokens, toPushData(event));
      // Sans ce nettoyage, les tokens d'appareils désinstallés s'accumuleraient (ADR-018).
      if (invalidTokens.length > 0) await this.devices.deleteTokens(invalidTokens);
    } catch (error) {
      this.logger.warn(`Notification ${event.type} → ${userId} non délivrée : ${(error as Error).message}`);
    }
  }
}

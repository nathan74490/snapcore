import { Logger } from "@nestjs/common";
import type { MessageNotifier } from "../application/ports/message-notifier.port";
import type { SnapPurgeScheduler } from "../application/ports/snap-purge.scheduler";

// Adaptateurs provisoires : remplacés par BullMQ (ADR-017) et par Socket.io/FCM (ADR-006, ADR-018)
// dans les lots suivants. En attendant, scheduledPurgeAt en base reste la source de vérité.
export class LoggingSnapPurgeScheduler implements SnapPurgeScheduler {
  private readonly logger = new Logger("SnapPurgeScheduler");

  async schedule(messageId: string, at: Date): Promise<void> {
    this.logger.log(`purge-message-${messageId} programmée au ${at.toISOString()}`);
  }

  async reschedule(messageId: string, at: Date): Promise<void> {
    this.logger.log(`purge-message-${messageId} reprogrammée au ${at.toISOString()}`);
  }
}

export class LoggingMessageNotifier implements MessageNotifier {
  private readonly logger = new Logger("MessageNotifier");

  async messageReceived(recipientId: string, messageId: string): Promise<void> {
    this.logger.log(`message.received → ${recipientId} (${messageId})`);
  }

  async snapOpened(senderId: string, messageId: string): Promise<void> {
    this.logger.log(`snap.opened → ${senderId} (${messageId})`);
  }

  async textsRead(senderId: string, readerId: string): Promise<void> {
    this.logger.log(`texts.read → ${senderId} (lus par ${readerId})`);
  }
}

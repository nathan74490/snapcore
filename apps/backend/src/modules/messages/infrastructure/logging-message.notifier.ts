import { Logger } from "@nestjs/common";
import type { MessageNotifier } from "../application/ports/message-notifier.port";

// Adaptateur provisoire, remplacé par Socket.io/FCM (ADR-006, ADR-018) au lot suivant.
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

  async snapRetained(senderId: string, messageId: string): Promise<void> {
    this.logger.log(`snap.retained → ${senderId} (${messageId})`);
  }

  async snapExpired(participantIds: [string, string], messageId: string): Promise<void> {
    this.logger.log(`snap.expired → ${participantIds.join(", ")} (${messageId})`);
  }
}

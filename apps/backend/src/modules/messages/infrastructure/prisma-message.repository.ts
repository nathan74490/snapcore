import { Injectable } from "@nestjs/common";
import { Prisma, type Message as MessageRow } from "@snapcore/db";
import { PrismaService } from "../../../shared/infrastructure/prisma/prisma.service";
import type { MessageRepository, NewMessage } from "../application/ports/message.repository";
import type { Message } from "../domain/message";

@Injectable()
export class PrismaMessageRepository implements MessageRepository {
  constructor(private readonly prisma: PrismaService) {}

  async create(message: NewMessage): Promise<Message> {
    return toMessage(await this.prisma.message.create({ data: message }));
  }

  async findById(id: string): Promise<Message | null> {
    const row = await this.prisma.message.findUnique({ where: { id } });
    return row && toMessage(row);
  }

  async markOpenedIfUnopened(id: string, at: Date): Promise<boolean> {
    // Une seule requête conditionnelle : deux ouvertures concurrentes ne peuvent pas gagner toutes les deux.
    const { count } = await this.prisma.message.updateMany({ where: { id, openedAt: null }, data: { openedAt: at } });
    return count === 1;
  }

  async setScheduledPurgeAt(id: string, at: Date): Promise<void> {
    await this.prisma.message.update({ where: { id }, data: { scheduledPurgeAt: at } });
  }

  async markTextsRead(recipientId: string, senderId: string, at: Date): Promise<number> {
    const { count } = await this.prisma.message.updateMany({
      where: { recipientId, senderId, contentType: "TEXT", openedAt: null },
      data: { openedAt: at },
    });
    return count;
  }

  async listBetween(userA: string, userB: string, options: { before?: Date; limit: number }): Promise<Message[]> {
    const rows = await this.prisma.message.findMany({
      where: {
        OR: [
          { senderId: userA, recipientId: userB },
          { senderId: userB, recipientId: userA },
        ],
        ...(options.before ? { createdAt: { lt: options.before } } : {}),
      },
      orderBy: [{ createdAt: "desc" }, { id: "desc" }],
      take: options.limit,
    });
    return rows.map(toMessage);
  }

  async findLastMessagesWith(userId: string, otherUserIds: string[]): Promise<Map<string, Message>> {
    if (otherUserIds.length === 0) return new Map();
    const others = Prisma.join(otherUserIds);
    // DISTINCT ON : une seule requête pour le dernier message de chaque conversation.
    const rows = await this.prisma.$queryRaw<Array<MessageRow & { otherId: string }>>`
      SELECT DISTINCT ON ("otherId") m.*,
        CASE WHEN m."senderId" = ${userId} THEN m."recipientId" ELSE m."senderId" END AS "otherId"
      FROM "Message" m
      WHERE (m."senderId" = ${userId} AND m."recipientId" IN (${others}))
         OR (m."recipientId" = ${userId} AND m."senderId" IN (${others}))
      ORDER BY "otherId", m."createdAt" DESC, m.id DESC`;
    return new Map(rows.map((row) => [row.otherId, toMessage(row)]));
  }

  async clearMedia(id: string): Promise<void> {
    await this.prisma.message.update({ where: { id }, data: { mediaKey: null } });
  }

  async markRetainedNotified(id: string, at: Date): Promise<boolean> {
    const { count } = await this.prisma.message.updateMany({
      where: { id, retainedNotifiedAt: null },
      data: { retainedNotifiedAt: at },
    });
    return count === 1;
  }

  async redact(id: string, moderation: { moderatorId: string; reason: string; at: Date }): Promise<boolean> {
    const { count } = await this.prisma.message.updateMany({
      where: { id, moderatedAt: null },
      data: {
        content: null,
        mediaKey: null,
        moderatedAt: moderation.at,
        moderatedById: moderation.moderatorId,
        moderationReason: moderation.reason,
      },
    });
    return count === 1;
  }

  async findOverdueSnapIds(now: Date, limit: number): Promise<string[]> {
    // Utilise @@index([scheduledPurgeAt]) : c'est la raison d'être de cet index (ADR-017).
    const rows = await this.prisma.message.findMany({
      where: { scheduledPurgeAt: { lte: now }, mediaKey: { not: null }, contentType: { not: "TEXT" } },
      orderBy: { scheduledPurgeAt: "asc" },
      take: limit,
      select: { id: true },
    });
    return rows.map((row) => row.id);
  }
}

function toMessage(row: MessageRow): Message {
  return {
    id: row.id,
    senderId: row.senderId,
    recipientId: row.recipientId,
    contentType: row.contentType,
    content: row.content,
    mediaKey: row.mediaKey,
    createdAt: row.createdAt,
    openedAt: row.openedAt,
    scheduledPurgeAt: row.scheduledPurgeAt,
    retainedNotifiedAt: row.retainedNotifiedAt,
    moderatedAt: row.moderatedAt,
  };
}

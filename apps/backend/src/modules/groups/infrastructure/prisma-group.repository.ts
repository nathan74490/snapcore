import { Injectable } from "@nestjs/common";
import type { PublicUserProfile } from "@snapcore/types";
import { Prisma, type GroupMessage as GroupMessageRow, type User as UserRow } from "@snapcore/db";
import { ACTIVE_USER_WHERE } from "../../../shared/infrastructure/prisma/active-user";
import { PrismaService } from "../../../shared/infrastructure/prisma/prisma.service";
import { readStoredAvatar } from "../../avatars/domain/avatar-catalog";
import { toPublicProfile } from "../../identity/domain/user";
import type { ActiveGroupMember, AddMembersResult, GroupRepository } from "../application/ports";
import type { Group, GroupMember, GroupMessage, GroupMessageOpening } from "../domain/group";

@Injectable()
export class PrismaGroupRepository implements GroupRepository {
  constructor(private readonly prisma: PrismaService) {}

  // --- Groupes et membres ---

  async create(group: Group, memberIds: string[]): Promise<void> {
    await this.prisma.$transaction([
      this.prisma.group.create({ data: group }),
      this.prisma.groupMember.createMany({
        data: memberIds.map((userId) => ({ groupId: group.id, userId, joinedAt: group.createdAt })),
      }),
    ]);
  }

  async findById(groupId: string): Promise<Group | null> {
    return this.prisma.group.findUnique({ where: { id: groupId } });
  }

  async findMember(groupId: string, userId: string): Promise<GroupMember | null> {
    return this.prisma.groupMember.findUnique({ where: { groupId_userId: { groupId, userId } } });
  }

  async listMembershipsOf(userId: string): Promise<Array<{ group: Group; member: GroupMember }>> {
    const rows = await this.prisma.groupMember.findMany({ where: { userId }, include: { group: true } });
    return rows.map(({ group, ...member }) => ({ group, member }));
  }

  async listActiveMembers(groupIds: string[]): Promise<Map<string, ActiveGroupMember[]>> {
    const result = new Map<string, ActiveGroupMember[]>();
    if (groupIds.length === 0) return result;
    const rows = await this.prisma.groupMember.findMany({
      where: { groupId: { in: groupIds }, user: ACTIVE_USER_WHERE },
      include: { user: true },
      orderBy: [{ joinedAt: "asc" }, { userId: "asc" }],
    });
    for (const { user, ...member } of rows) {
      const list = result.get(member.groupId) ?? [];
      list.push({ ...member, profile: profileOf(user) });
      result.set(member.groupId, list);
    }
    return result;
  }

  async listFormerSenders(groupIds: string[]): Promise<Map<string, PublicUserProfile[]>> {
    const result = new Map<string, PublicUserProfile[]>();
    if (groupIds.length === 0) return result;
    const pairs = await this.prisma.$queryRaw<Array<{ groupId: string; senderId: string }>>`
      SELECT DISTINCT m."groupId", m."senderId"
      FROM "GroupMessage" m
      WHERE m."groupId" IN (${Prisma.join(groupIds)})
        AND NOT EXISTS (
          SELECT 1 FROM "GroupMember" gm WHERE gm."groupId" = m."groupId" AND gm."userId" = m."senderId"
        )`;
    if (pairs.length === 0) return result;
    // Filtre d'activité centralisé (ADR-019, ADR-021) plutôt que réécrit en SQL.
    const users = await this.prisma.user.findMany({
      where: { id: { in: [...new Set(pairs.map((p) => p.senderId))] }, ...ACTIVE_USER_WHERE },
      orderBy: { username: "asc" },
    });
    for (const user of users) {
      for (const pair of pairs.filter((p) => p.senderId === user.id)) {
        result.set(pair.groupId, [...(result.get(pair.groupId) ?? []), profileOf(user)]);
      }
    }
    return result;
  }

  async rename(groupId: string, name: string | null): Promise<Group | null> {
    const { count } = await this.prisma.group.updateMany({ where: { id: groupId }, data: { name } });
    return count === 1 ? this.findById(groupId) : null;
  }

  async addMembers(
    groupId: string,
    byUserId: string,
    userIds: string[],
    joinedAt: Date,
    maxMembers: number,
  ): Promise<AddMembersResult> {
    return this.prisma.$transaction(async (tx) => {
      // FOR UPDATE : deux ajouts concurrents sont sérialisés, la limite de membres ne peut pas être dépassée.
      const locked = await tx.$queryRaw<Array<{ id: string }>>`SELECT id FROM "Group" WHERE id = ${groupId} FOR UPDATE`;
      if (locked.length === 0) return { kind: "missing" } as const;
      const current = new Set(
        (await tx.groupMember.findMany({ where: { groupId }, select: { userId: true } })).map((m) => m.userId),
      );
      if (!current.has(byUserId)) return { kind: "missing" } as const;
      const added = userIds.filter((id) => !current.has(id));
      if (current.size + added.length > maxMembers) return { kind: "full" } as const;
      await tx.groupMember.createMany({ data: added.map((userId) => ({ groupId, userId, joinedAt })) });
      return { kind: "added", userIds: added } as const;
    });
  }

  async removeMember(groupId: string, userId: string): Promise<{ removed: boolean; remaining: number }> {
    return this.prisma.$transaction(async (tx) => {
      // Même verrou que l'ajout : le décompte des membres restants est exact.
      await tx.$queryRaw`SELECT id FROM "Group" WHERE id = ${groupId} FOR UPDATE`;
      const { count } = await tx.groupMember.deleteMany({ where: { groupId, userId } });
      const remaining = await tx.groupMember.count({ where: { groupId } });
      return { removed: count === 1, remaining };
    });
  }

  async deleteIfEmpty(groupId: string): Promise<boolean> {
    const { count } = await this.prisma.group.deleteMany({ where: { id: groupId, members: { none: {} } } });
    return count === 1;
  }

  async listMediaKeys(groupId: string): Promise<string[]> {
    const rows = await this.prisma.groupMessage.findMany({
      where: { groupId, mediaKey: { not: null } },
      select: { mediaKey: true },
    });
    return rows.map((row) => row.mediaKey!);
  }

  async markRead(groupId: string, userId: string, at: Date): Promise<void> {
    // Jamais en arrière : deux ouvertures concurrentes ne peuvent pas faire repasser un chat à « non lu ».
    await this.prisma.groupMember.updateMany({
      where: { groupId, userId, OR: [{ lastReadAt: null }, { lastReadAt: { lt: at } }] },
      data: { lastReadAt: at },
    });
  }

  // --- Messages ---

  async createMessage(message: GroupMessage): Promise<GroupMessage> {
    return toMessage(await this.prisma.groupMessage.create({ data: message }));
  }

  async findMessageById(id: string): Promise<GroupMessage | null> {
    const row = await this.prisma.groupMessage.findUnique({ where: { id } });
    return row && toMessage(row);
  }

  async listMessages(groupId: string, options: { since: Date; before?: Date; limit: number }): Promise<GroupMessage[]> {
    const rows = await this.prisma.groupMessage.findMany({
      where: { groupId, createdAt: { gte: options.since, ...(options.before ? { lt: options.before } : {}) } },
      orderBy: [{ createdAt: "desc" }, { id: "desc" }],
      take: options.limit,
    });
    return rows.map(toMessage);
  }

  async findLastMessages(userId: string, groupIds: string[]): Promise<Map<string, GroupMessage>> {
    if (groupIds.length === 0) return new Map();
    // DISTINCT ON : le dernier message visible de chaque groupe en une requête (index groupId, createdAt).
    const rows = await this.prisma.$queryRaw<GroupMessageRow[]>`
      SELECT DISTINCT ON (m."groupId") m.*
      FROM "GroupMessage" m
      JOIN "GroupMember" gm ON gm."groupId" = m."groupId" AND gm."userId" = ${userId}
      WHERE m."groupId" IN (${Prisma.join(groupIds)})
        AND m."createdAt" >= gm."joinedAt"
      ORDER BY m."groupId", m."createdAt" DESC, m.id DESC`;
    return new Map(rows.map((row) => [row.groupId, toMessage(row)]));
  }

  async findOldestUnopenedSnaps(userId: string, groupIds: string[]): Promise<Map<string, GroupMessage>> {
    if (groupIds.length === 0) return new Map();
    const rows = await this.prisma.$queryRaw<GroupMessageRow[]>`
      SELECT DISTINCT ON (m."groupId") m.*
      FROM "GroupMessage" m
      JOIN "GroupMember" gm ON gm."groupId" = m."groupId" AND gm."userId" = ${userId}
      WHERE m."groupId" IN (${Prisma.join(groupIds)})
        AND m."createdAt" >= gm."joinedAt"
        AND m."senderId" <> ${userId}
        AND m."contentType" <> 'TEXT'
        AND m."mediaKey" IS NOT NULL
        AND NOT EXISTS (
          SELECT 1 FROM "GroupMessageView" v WHERE v."messageId" = m.id AND v."userId" = ${userId}
        )
      ORDER BY m."groupId", m."createdAt" ASC, m.id ASC`;
    return new Map(rows.map((row) => [row.groupId, toMessage(row)]));
  }

  async findGroupsWithUnread(userId: string, groupIds: string[]): Promise<Set<string>> {
    if (groupIds.length === 0) return new Set();
    const rows = await this.prisma.$queryRaw<Array<{ groupId: string }>>`
      SELECT DISTINCT m."groupId"
      FROM "GroupMessage" m
      JOIN "GroupMember" gm ON gm."groupId" = m."groupId" AND gm."userId" = ${userId}
      WHERE m."groupId" IN (${Prisma.join(groupIds)})
        AND m."senderId" <> ${userId}
        AND m."createdAt" >= gm."joinedAt"
        AND (gm."lastReadAt" IS NULL OR m."createdAt" > gm."lastReadAt")`;
    return new Set(rows.map((row) => row.groupId));
  }

  async findLastUnreadFromOthers(groupId: string, userId: string): Promise<GroupMessage | null> {
    const [row] = await this.prisma.$queryRaw<GroupMessageRow[]>`
      SELECT m.*
      FROM "GroupMessage" m
      JOIN "GroupMember" gm ON gm."groupId" = m."groupId" AND gm."userId" = ${userId}
      WHERE m."groupId" = ${groupId}
        AND m."senderId" <> ${userId}
        AND m."createdAt" >= gm."joinedAt"
        AND (gm."lastReadAt" IS NULL OR m."createdAt" > gm."lastReadAt")
      ORDER BY m."createdAt" DESC, m.id DESC
      LIMIT 1`;
    return row ? toMessage(row) : null;
  }

  async listOpenings(messageIds: string[]): Promise<GroupMessageOpening[]> {
    if (messageIds.length === 0) return [];
    return this.prisma.groupMessageView.findMany({ where: { messageId: { in: messageIds } } });
  }

  async findOpening(messageId: string, userId: string): Promise<GroupMessageOpening | null> {
    return this.prisma.groupMessageView.findUnique({ where: { messageId_userId: { messageId, userId } } });
  }

  async recordOpening(messageId: string, userId: string, at: Date): Promise<boolean> {
    // INSERT ... ON CONFLICT DO NOTHING : deux ouvertures concurrentes ne peuvent pas gagner toutes les deux.
    const { count } = await this.prisma.groupMessageView.createMany({
      data: [{ messageId, userId, openedAt: at }],
      skipDuplicates: true,
    });
    return count === 1;
  }

  async listSnapsWithMedia(groupId: string): Promise<GroupMessage[]> {
    const rows = await this.prisma.groupMessage.findMany({
      where: { groupId, contentType: { not: "TEXT" }, mediaKey: { not: null } },
      orderBy: { createdAt: "asc" },
    });
    return rows.map(toMessage);
  }

  async setScheduledPurgeAt(id: string, at: Date): Promise<void> {
    await this.prisma.groupMessage.update({ where: { id }, data: { scheduledPurgeAt: at } });
  }

  async clearMedia(id: string): Promise<void> {
    await this.prisma.groupMessage.update({ where: { id }, data: { mediaKey: null } });
  }

  async findOverdueSnapIds(now: Date, limit: number): Promise<string[]> {
    // Utilise @@index([scheduledPurgeAt]) (ADR-017).
    const rows = await this.prisma.groupMessage.findMany({
      where: { scheduledPurgeAt: { lte: now }, mediaKey: { not: null }, contentType: { not: "TEXT" } },
      orderBy: { scheduledPurgeAt: "asc" },
      take: limit,
      select: { id: true },
    });
    return rows.map((row) => row.id);
  }
}

function profileOf(user: UserRow): PublicUserProfile {
  return toPublicProfile({ ...user, avatar: readStoredAvatar(user.avatar) });
}

function toMessage(row: GroupMessageRow): GroupMessage {
  return {
    id: row.id,
    groupId: row.groupId,
    senderId: row.senderId,
    contentType: row.contentType,
    content: row.content,
    mediaKey: row.mediaKey,
    createdAt: row.createdAt,
    scheduledPurgeAt: row.scheduledPurgeAt,
  };
}

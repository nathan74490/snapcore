import { Injectable } from "@nestjs/common";
import type { FriendshipStatus } from "@snapcore/types";
import type { Friendship as FriendshipRow, Prisma, User as UserRow } from "@snapcore/db";
import { PrismaService } from "../../../shared/infrastructure/prisma/prisma.service";
import { ACTIVE_USER_WHERE } from "../../../shared/infrastructure/prisma/active-user";
import { rethrowUniqueViolation } from "../../../shared/infrastructure/prisma/prisma-errors";
import { readStoredAvatar } from "../../avatars/domain/avatar-catalog";
import { toPublicProfile } from "../../identity/domain/user";
import type {
  FriendshipPatch,
  FriendshipRepository,
  FriendshipWithUser,
  NewFriendship,
} from "../application/ports/friendship.repository";
import { sortedPair, type Friendship } from "../domain/friendship";

const ACTIVE_USER = ACTIVE_USER_WHERE;

@Injectable()
export class PrismaFriendshipRepository implements FriendshipRepository {
  constructor(private readonly prisma: PrismaService) {}

  async findById(id: string): Promise<Friendship | null> {
    return this.prisma.friendship.findUnique({ where: { id } }).then(toFriendship);
  }

  async findByPair(userA: string, userB: string): Promise<Friendship | null> {
    return this.prisma.friendship
      .findUnique({ where: { pairLow_pairHigh: sortedPair(userA, userB) } })
      .then(toFriendship);
  }

  async findForPairs(userId: string, otherIds: string[]): Promise<Friendship[]> {
    if (otherIds.length === 0) return [];
    const rows = await this.prisma.friendship.findMany({
      where: {
        OR: [
          { requesterId: userId, addresseeId: { in: otherIds } },
          { addresseeId: userId, requesterId: { in: otherIds } },
        ],
      },
    });
    return rows.map((row) => toFriendship(row)!);
  }

  async create(friendship: NewFriendship): Promise<Friendship> {
    const row = await this.prisma.friendship
      .create({ data: { ...friendship, ...sortedPair(friendship.requesterId, friendship.addresseeId) } })
      .catch(rethrowUniqueViolation);
    return toFriendship(row)!;
  }

  async transition(id: string, expectedStatus: FriendshipStatus, patch: FriendshipPatch): Promise<Friendship | null> {
    // updateMany permet la condition sur le statut : une seule requête, atomique côté base.
    const { count } = await this.prisma.friendship.updateMany({ where: { id, status: expectedStatus }, data: patch });
    return count === 0 ? null : this.findById(id);
  }

  async deleteForPair(
    userA: string,
    userB: string,
    criteria: { status: FriendshipStatus; blockedBy?: string },
  ): Promise<boolean> {
    const { count } = await this.prisma.friendship.deleteMany({
      where: { ...sortedPair(userA, userB), ...criteria },
    });
    return count > 0;
  }

  async listFriends(userId: string): Promise<FriendshipWithUser[]> {
    return this.listWithOther(userId, {
      status: "ACCEPTED",
      OR: [
        { requesterId: userId, addressee: ACTIVE_USER },
        { addresseeId: userId, requester: ACTIVE_USER },
      ],
    });
  }

  async listIncomingRequests(userId: string): Promise<FriendshipWithUser[]> {
    return this.listWithOther(userId, { status: "PENDING", addresseeId: userId, requester: ACTIVE_USER });
  }

  async listOutgoingRequests(userId: string): Promise<FriendshipWithUser[]> {
    return this.listWithOther(userId, { status: "PENDING", requesterId: userId, addressee: ACTIVE_USER });
  }

  async listBlockedBy(userId: string): Promise<FriendshipWithUser[]> {
    return this.listWithOther(userId, {
      status: "BLOCKED",
      blockedBy: userId,
      OR: [
        { requesterId: userId, addressee: ACTIVE_USER },
        { addresseeId: userId, requester: ACTIVE_USER },
      ],
    });
  }

  private async listWithOther(userId: string, where: Prisma.FriendshipWhereInput): Promise<FriendshipWithUser[]> {
    const rows = await this.prisma.friendship.findMany({
      where,
      include: { requester: true, addressee: true },
      orderBy: { requestedAt: "desc" },
    });
    return rows.map((row) => {
      const other: UserRow = row.requesterId === userId ? row.addressee : row.requester;
      return { friendship: toFriendship(row)!, user: toPublicProfile({ ...other, avatar: readStoredAvatar(other.avatar) }) };
    });
  }
}

function toFriendship(row: FriendshipRow | null): Friendship | null {
  if (!row) return null;
  return {
    id: row.id,
    requesterId: row.requesterId,
    addresseeId: row.addresseeId,
    status: row.status,
    blockedBy: row.blockedBy,
    requestedAt: row.requestedAt,
    respondedAt: row.respondedAt,
  };
}

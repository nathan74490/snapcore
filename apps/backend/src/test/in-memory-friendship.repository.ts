import type { FriendshipStatus } from "@snapcore/types";
import { randomUUID } from "node:crypto";
import { UniqueViolationError } from "../shared/domain/errors";
import type {
  FriendshipPatch,
  FriendshipRepository,
  FriendshipWithUser,
  NewFriendship,
} from "../modules/friendships/application/ports/friendship.repository";
import { otherMemberOf, sortedPair, type Friendship } from "../modules/friendships/domain/friendship";
import { toPublicProfile } from "../modules/identity/domain/user";
import type { InMemoryUserRepository } from "./in-memory-user.repository";

// Reproduit l'unicité de la paire triée et les contraintes CHECK de la migration initiale (ADR-012).
export class InMemoryFriendshipRepository implements FriendshipRepository {
  readonly rows = new Map<string, Friendship>();

  constructor(private readonly users: InMemoryUserRepository) {}

  async findById(id: string) {
    return this.copy(this.rows.get(id));
  }

  async findByPair(userA: string, userB: string) {
    return this.copy(this.findRowByPair(userA, userB));
  }

  async findForPairs(userId: string, otherIds: string[]) {
    return [...this.rows.values()]
      .filter((f) => (f.requesterId === userId || f.addresseeId === userId) && otherIds.includes(otherMemberOf(f, userId)))
      .map((f) => ({ ...f }));
  }

  async create(friendship: NewFriendship): Promise<Friendship> {
    if (this.findRowByPair(friendship.requesterId, friendship.addresseeId)) throw new UniqueViolationError("pair");
    const row = { id: randomUUID(), ...friendship };
    this.assertChecks(row);
    this.rows.set(row.id, row);
    return { ...row };
  }

  async transition(id: string, expectedStatus: FriendshipStatus, patch: FriendshipPatch) {
    const row = this.rows.get(id);
    if (!row || row.status !== expectedStatus) return null;
    const next = { ...row, ...patch };
    this.assertChecks(next);
    this.rows.set(id, next);
    return { ...next };
  }

  async deleteForPair(userA: string, userB: string, criteria: { status: FriendshipStatus; blockedBy?: string }) {
    const row = this.findRowByPair(userA, userB);
    if (!row || row.status !== criteria.status) return false;
    if (criteria.blockedBy !== undefined && row.blockedBy !== criteria.blockedBy) return false;
    return this.rows.delete(row.id);
  }

  async listFriends(userId: string) {
    return this.list(userId, (f) => f.status === "ACCEPTED");
  }
  async listIncomingRequests(userId: string) {
    return this.list(userId, (f) => f.status === "PENDING" && f.addresseeId === userId);
  }
  async listOutgoingRequests(userId: string) {
    return this.list(userId, (f) => f.status === "PENDING" && f.requesterId === userId);
  }
  async listBlockedBy(userId: string) {
    return this.list(userId, (f) => f.status === "BLOCKED" && f.blockedBy === userId);
  }

  private async list(userId: string, predicate: (f: Friendship) => boolean): Promise<FriendshipWithUser[]> {
    const result: FriendshipWithUser[] = [];
    for (const f of this.rows.values()) {
      if (f.requesterId !== userId && f.addresseeId !== userId) continue;
      if (!predicate(f)) continue;
      const other = await this.users.findActiveById(otherMemberOf(f, userId));
      if (other) result.push({ friendship: { ...f }, user: toPublicProfile(other) });
    }
    return result;
  }

  private findRowByPair(userA: string, userB: string): Friendship | undefined {
    const pair = sortedPair(userA, userB);
    return [...this.rows.values()].find((f) => {
      const p = sortedPair(f.requesterId, f.addresseeId);
      return p.pairLow === pair.pairLow && p.pairHigh === pair.pairHigh;
    });
  }

  private assertChecks(row: Friendship): void {
    if (row.requesterId === row.addresseeId) throw new Error("CHECK Friendship_not_self_check violé");
  }

  private copy(row: Friendship | undefined): Friendship | null {
    return row ? { ...row } : null;
  }
}

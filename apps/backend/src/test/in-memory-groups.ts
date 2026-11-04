import type { PublicUserProfile } from "@snapcore/types";
import type { ActiveGroupMember, AddMembersResult, GroupMessageReceived, GroupNotifier, GroupRepository } from "../modules/groups/application/ports";
import { isVisibleTo, type Group, type GroupMember, type GroupMessage, type GroupMessageOpening } from "../modules/groups/domain/group";
import { isActive, toPublicProfile } from "../modules/identity/domain/user";
import type { InMemoryUserRepository } from "./in-memory-user.repository";

// Reproduit le schéma Prisma des groupes (ADR-032) : clé (groupId, userId), cascades à la suppression
// d'un groupe, contrainte CHECK de cohérence contenu / type, filtre des comptes actifs.
export class InMemoryGroupRepository implements GroupRepository {
  readonly groups = new Map<string, Group>();
  readonly members: GroupMember[] = [];
  readonly messages = new Map<string, GroupMessage>();
  readonly openings: GroupMessageOpening[] = [];

  constructor(private readonly users: InMemoryUserRepository) {}

  // --- Groupes et membres ---

  async create(group: Group, memberIds: string[]) {
    this.groups.set(group.id, { ...group });
    for (const userId of memberIds) this.members.push({ groupId: group.id, userId, joinedAt: group.createdAt, lastReadAt: null });
  }

  async findById(groupId: string) {
    const group = this.groups.get(groupId);
    return group ? { ...group } : null;
  }

  async findMember(groupId: string, userId: string) {
    const member = this.member(groupId, userId);
    return member ? { ...member } : null;
  }

  async listMembershipsOf(userId: string) {
    return this.members
      .filter((m) => m.userId === userId && this.groups.has(m.groupId))
      .map((m) => ({ group: { ...this.groups.get(m.groupId)! }, member: { ...m } }));
  }

  async listActiveMembers(groupIds: string[]) {
    const result = new Map<string, ActiveGroupMember[]>();
    const rows = this.members
      .filter((m) => groupIds.includes(m.groupId))
      .sort((a, b) => a.joinedAt.getTime() - b.joinedAt.getTime() || a.userId.localeCompare(b.userId));
    for (const m of rows) {
      const user = this.users.rows.get(m.userId);
      if (!user || !isActive(user)) continue;
      result.set(m.groupId, [...(result.get(m.groupId) ?? []), { ...m, profile: toPublicProfile(user) }]);
    }
    return result;
  }

  async listFormerSenders(groupIds: string[]) {
    const result = new Map<string, PublicUserProfile[]>();
    for (const groupId of groupIds) {
      const senders = new Set(
        [...this.messages.values()].filter((m) => m.groupId === groupId && !this.member(groupId, m.senderId)).map((m) => m.senderId),
      );
      const profiles = [...senders]
        .map((id) => this.users.rows.get(id))
        .filter((u) => u !== undefined && isActive(u))
        .sort((a, b) => a!.username.localeCompare(b!.username))
        .map((u) => toPublicProfile(u!));
      if (profiles.length > 0) result.set(groupId, profiles);
    }
    return result;
  }

  async rename(groupId: string, name: string | null) {
    const group = this.groups.get(groupId);
    if (!group) return null;
    group.name = name;
    return { ...group };
  }

  async addMembers(groupId: string, byUserId: string, userIds: string[], joinedAt: Date, maxMembers: number): Promise<AddMembersResult> {
    if (!this.groups.has(groupId) || !this.member(groupId, byUserId)) return { kind: "missing" };
    const current = this.members.filter((m) => m.groupId === groupId).map((m) => m.userId);
    const added = userIds.filter((id) => !current.includes(id));
    if (current.length + added.length > maxMembers) return { kind: "full" };
    for (const userId of added) this.members.push({ groupId, userId, joinedAt, lastReadAt: null });
    return { kind: "added", userIds: added };
  }

  async removeMember(groupId: string, userId: string) {
    const index = this.members.findIndex((m) => m.groupId === groupId && m.userId === userId);
    if (index >= 0) this.members.splice(index, 1);
    return { removed: index >= 0, remaining: this.members.filter((m) => m.groupId === groupId).length };
  }

  async deleteIfEmpty(groupId: string) {
    if (!this.groups.has(groupId) || this.members.some((m) => m.groupId === groupId)) return false;
    this.deleteGroup(groupId);
    return true;
  }

  async listMediaKeys(groupId: string) {
    return [...this.messages.values()].filter((m) => m.groupId === groupId && m.mediaKey).map((m) => m.mediaKey!);
  }

  async markRead(groupId: string, userId: string, at: Date) {
    const member = this.member(groupId, userId);
    if (member && (!member.lastReadAt || member.lastReadAt < at)) member.lastReadAt = at;
  }

  // --- Messages ---

  async createMessage(message: GroupMessage) {
    if (!this.groups.has(message.groupId)) throw new Error("GroupMessage_groupId_fkey");
    const isText = message.contentType === "TEXT";
    const valid = isText
      ? message.content !== null && message.mediaKey === null && message.scheduledPurgeAt === null
      : message.content === null && message.scheduledPurgeAt !== null;
    if (!valid) throw new Error("GroupMessage_content_matches_type_check");
    this.messages.set(message.id, { ...message });
    return { ...message };
  }

  async findMessageById(id: string) {
    const message = this.messages.get(id);
    return message ? { ...message } : null;
  }

  async listMessages(groupId: string, options: { since: Date; before?: Date; limit: number }) {
    return this.ofGroup(groupId)
      .filter((m) => m.createdAt >= options.since && (!options.before || m.createdAt < options.before))
      .slice(0, options.limit);
  }

  async findLastMessages(userId: string, groupIds: string[]) {
    const result = new Map<string, GroupMessage>();
    for (const groupId of groupIds) {
      const member = this.member(groupId, userId);
      const [last] = member ? this.ofGroup(groupId).filter((m) => isVisibleTo(m, member)) : [];
      if (last) result.set(groupId, last);
    }
    return result;
  }

  async findOldestUnopenedSnaps(userId: string, groupIds: string[]) {
    const result = new Map<string, GroupMessage>();
    for (const groupId of groupIds) {
      const member = this.member(groupId, userId);
      if (!member) continue;
      const oldest = this.ofGroup(groupId)
        .filter(
          (m) =>
            isVisibleTo(m, member) &&
            m.senderId !== userId &&
            m.contentType !== "TEXT" &&
            m.mediaKey !== null &&
            !this.openings.some((o) => o.messageId === m.id && o.userId === userId),
        )
        .at(-1);
      if (oldest) result.set(groupId, oldest);
    }
    return result;
  }

  async findGroupsWithUnread(userId: string, groupIds: string[]) {
    return new Set(groupIds.filter((groupId) => this.unreadFromOthers(groupId, userId).length > 0));
  }

  async findLastUnreadFromOthers(groupId: string, userId: string) {
    return this.unreadFromOthers(groupId, userId)[0] ?? null;
  }

  async listOpenings(messageIds: string[]) {
    return this.openings.filter((o) => messageIds.includes(o.messageId)).map((o) => ({ ...o }));
  }

  async findOpening(messageId: string, userId: string) {
    const opening = this.openings.find((o) => o.messageId === messageId && o.userId === userId);
    return opening ? { ...opening } : null;
  }

  async recordOpening(messageId: string, userId: string, at: Date) {
    if (this.openings.some((o) => o.messageId === messageId && o.userId === userId)) return false;
    this.openings.push({ messageId, userId, openedAt: at });
    return true;
  }

  async listSnapsWithMedia(groupId: string) {
    return this.ofGroup(groupId)
      .filter((m) => m.contentType !== "TEXT" && m.mediaKey !== null)
      .reverse();
  }

  async setScheduledPurgeAt(id: string, at: Date) {
    this.messages.get(id)!.scheduledPurgeAt = at;
  }

  async clearMedia(id: string) {
    this.messages.get(id)!.mediaKey = null;
  }

  async findOverdueSnapIds(now: Date, limit: number) {
    return [...this.messages.values()]
      .filter((m) => m.contentType !== "TEXT" && m.mediaKey !== null && m.scheduledPurgeAt && m.scheduledPurgeAt <= now)
      .sort((a, b) => a.scheduledPurgeAt!.getTime() - b.scheduledPurgeAt!.getTime())
      .slice(0, limit)
      .map((m) => m.id);
  }

  // --- Cascades (suppression de compte, ADR-019) ---

  /** Groupes dont `userId` est le seul membre restant. */
  soleMemberGroupIds(userId: string): string[] {
    return this.members
      .filter((m) => m.userId === userId && this.members.filter((o) => o.groupId === m.groupId).length === 1)
      .map((m) => m.groupId);
  }

  deleteGroup(groupId: string): void {
    this.groups.delete(groupId);
    this.removeWhere(this.members, (m) => m.groupId === groupId);
    for (const message of [...this.messages.values()].filter((m) => m.groupId === groupId)) this.deleteMessage(message.id);
  }

  deleteUser(userId: string): void {
    for (const group of this.groups.values()) if (group.createdById === userId) group.createdById = null;
    this.removeWhere(this.members, (m) => m.userId === userId);
    this.removeWhere(this.openings, (o) => o.userId === userId);
    for (const message of [...this.messages.values()].filter((m) => m.senderId === userId)) this.deleteMessage(message.id);
  }

  private deleteMessage(id: string): void {
    this.messages.delete(id);
    this.removeWhere(this.openings, (o) => o.messageId === id);
  }

  private removeWhere<T>(rows: T[], predicate: (row: T) => boolean): void {
    for (let i = rows.length - 1; i >= 0; i--) if (predicate(rows[i])) rows.splice(i, 1);
  }

  private member(groupId: string, userId: string): GroupMember | undefined {
    return this.members.find((m) => m.groupId === groupId && m.userId === userId);
  }

  private unreadFromOthers(groupId: string, userId: string): GroupMessage[] {
    const member = this.member(groupId, userId);
    if (!member) return [];
    return this.ofGroup(groupId).filter(
      (m) => m.senderId !== userId && isVisibleTo(m, member) && (!member.lastReadAt || m.createdAt > member.lastReadAt),
    );
  }

  /** Messages du groupe, du plus récent au plus ancien. */
  private ofGroup(groupId: string): GroupMessage[] {
    return [...this.messages.values()]
      .filter((m) => m.groupId === groupId)
      .sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime() || b.id.localeCompare(a.id))
      .map((m) => ({ ...m }));
  }
}

export class RecordingGroupNotifier implements GroupNotifier {
  readonly events: string[] = [];
  readonly received: Array<{ to: string } & GroupMessageReceived> = [];

  async messageReceived(recipientIds: string[], event: GroupMessageReceived) {
    for (const to of recipientIds) {
      this.events.push(`received ${event.messageId}→${to}`);
      this.received.push({ to, ...event });
    }
  }
  async updated(memberIds: string[], groupId: string) {
    for (const to of memberIds) this.events.push(`updated ${groupId}→${to}`);
  }
  async typing(recipientIds: string[], groupId: string, typistId: string) {
    for (const to of recipientIds) this.events.push(`typing ${typistId}→${to}`);
  }
  async messageSeen(recipientIds: string[], groupId: string, messageId: string, viewerId: string) {
    for (const to of recipientIds) this.events.push(`seen ${messageId} by ${viewerId}→${to}`);
  }

  /** Événements reçus par `userId`, sans le préfixe de destinataire. */
  to(userId: string): string[] {
    return this.events.filter((e) => e.endsWith(`→${userId}`)).map((e) => e.slice(0, e.lastIndexOf("→")));
  }
}

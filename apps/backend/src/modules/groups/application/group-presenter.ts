import type { GroupMessageView, GroupView, PublicUserProfile } from "@snapcore/types";
import { NotFoundError } from "../../../shared/domain/errors";
import { hasRead, isVisibleTo, type Group, type GroupMember, type GroupMessage, type GroupMessageOpening } from "../domain/group";
import type { ActiveGroupMember, GroupRepository } from "./ports";

// Même réponse que le groupe n'existe pas ou que je n'en suis pas (ou plus) membre : on ne révèle pas
// l'existence d'un groupe à un non-membre (ADR-032).
export const GROUP_NOT_FOUND = "Groupe introuvable";

export async function requireMember(groups: GroupRepository, groupId: string, userId: string): Promise<GroupMember> {
  const member = await groups.findMember(groupId, userId);
  if (!member) throw new NotFoundError(GROUP_NOT_FOUND);
  return member;
}

export function presentGroup(group: Group, members: ActiveGroupMember[], formerMembers: PublicUserProfile[]): GroupView {
  return {
    id: group.id,
    ...(group.name !== null ? { name: group.name } : {}),
    members: members.map((m) => m.profile),
    formerMembers,
    createdAt: group.createdAt.toISOString(),
  };
}

/**
 * `members` : membres actuels actifs du groupe ; `openings` : ouvertures connues de ce message (au moins).
 * seenBy ne liste que des membres actuels actifs, expéditeur exclu.
 */
export function presentGroupMessage(
  message: GroupMessage,
  me: GroupMember,
  members: GroupMember[],
  openings: GroupMessageOpening[],
): GroupMessageView {
  const isSnap = message.contentType !== "TEXT";
  const openers = new Set(openings.filter((o) => o.messageId === message.id).map((o) => o.userId));
  const seenBy = members
    .filter((m) => m.userId !== message.senderId && isVisibleTo(message, m))
    .filter((m) => (isSnap ? openers.has(m.userId) : hasRead(m, message)))
    .map((m) => m.userId);
  const mine = message.senderId === me.userId;
  return {
    id: message.id,
    groupId: message.groupId,
    senderId: message.senderId,
    contentType: message.contentType,
    ...(message.content !== null ? { content: message.content } : {}),
    createdAt: message.createdAt.toISOString(),
    // Gris = snap dont le média a été purgé ; un chat n'est jamais gris (ADR-009).
    mediaPurged: isSnap && message.mediaKey === null,
    openedByMe: !mine && (isSnap ? openers.has(me.userId) : hasRead(me, message)),
    seenBy,
  };
}

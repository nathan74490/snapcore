import type { MessageContentType } from "@snapcore/types";
import { ValidationError } from "../../../shared/domain/errors";
import { OPENED_SNAP_RETENTION_MS } from "../../messages/domain/message";

// Groupes de discussion (ADR-032) : sans administrateur, chaque membre a les mêmes droits.
export interface Group {
  id: string;
  name: string | null;
  createdById: string | null;
  createdAt: Date;
}

export interface GroupMember {
  groupId: string;
  userId: string;
  /** Le membre ne voit que les messages envoyés depuis son arrivée. */
  joinedAt: Date;
  /** Dernière ouverture de la conversation : un chat est lu si lastReadAt >= sa date. */
  lastReadAt: Date | null;
}

export interface GroupMessage {
  id: string;
  groupId: string;
  senderId: string;
  contentType: MessageContentType;
  content: string | null;
  mediaKey: string | null;
  createdAt: Date;
  scheduledPurgeAt: Date | null;
}

/** Première ouverture d'un snap de groupe par un membre. */
export interface GroupMessageOpening {
  messageId: string;
  userId: string;
  openedAt: Date;
}

export const MAX_GROUP_MEMBERS = 32;
// Le créateur choisit au moins 2 amis (sinon c'est une conversation à deux) et 31 au plus.
export const MIN_INVITED_FRIENDS = 2;
export const MAX_INVITED_FRIENDS = MAX_GROUP_MEMBERS - 1;
export const MAX_GROUP_NAME_LENGTH = 50;

/** Nom facultatif : trim, vide → sans nom (null), 50 caractères au plus. */
export function parseGroupName(raw: string | null | undefined): string | null {
  const name = (raw ?? "").trim();
  if (name.length === 0) return null;
  if (name.length > MAX_GROUP_NAME_LENGTH) {
    throw new ValidationError(`Le nom du groupe dépasse ${MAX_GROUP_NAME_LENGTH} caractères`);
  }
  return name;
}

// Préfixe R2 distinct de celui des snaps à deux (messages/<id>, ADR-016).
export function groupSnapMediaKey(messageId: string): string {
  return `group-messages/${messageId}`;
}

/** Le membre voit ce message : envoyé depuis son arrivée dans le groupe. */
export function isVisibleTo(message: Pick<GroupMessage, "createdAt">, member: Pick<GroupMember, "joinedAt">): boolean {
  return message.createdAt.getTime() >= member.joinedAt.getTime();
}

/** Chat lu par le membre : arrivé avant le chat, et conversation ouverte depuis. */
export function hasRead(member: Pick<GroupMember, "joinedAt" | "lastReadAt">, message: Pick<GroupMessage, "createdAt">): boolean {
  return isVisibleTo(message, member) && member.lastReadAt !== null && member.lastReadAt.getTime() >= message.createdAt.getTime();
}

/**
 * Purge rapprochée d'un snap de groupe (ADR-032, ADR-017) : dès que tous les membres présents à l'envoi
 * l'ont ouvert (expéditeur exclu, moins ceux qui sont partis depuis), la purge passe à 24 h après la
 * dernière ouverture, jamais repoussée. `members` = membres ACTUELS actifs du groupe.
 * Sans plus personne pour l'ouvrir, la référence est `now`.
 * @returns la nouvelle échéance, ou null si un membre concerné ne l'a pas encore ouvert.
 */
export function purgeDateWhenAllOpened(
  message: Pick<GroupMessage, "senderId" | "createdAt" | "scheduledPurgeAt">,
  members: Pick<GroupMember, "userId" | "joinedAt">[],
  openings: Pick<GroupMessageOpening, "userId" | "openedAt">[],
  now: Date,
): Date | null {
  const openedAt = new Map(openings.map((o) => [o.userId, o.openedAt]));
  const expected = members.filter((m) => m.userId !== message.senderId && isVisibleTo(message, m));
  if (expected.some((m) => !openedAt.has(m.userId))) return null;

  const last = expected.length === 0 ? now.getTime() : Math.max(...expected.map((m) => openedAt.get(m.userId)!.getTime()));
  const afterOpenings = new Date(last + OPENED_SNAP_RETENTION_MS);
  return message.scheduledPurgeAt && message.scheduledPurgeAt < afterOpenings ? message.scheduledPurgeAt : afterOpenings;
}

export type GroupPurgeDecision =
  | { kind: "nothing" } // chat texte (jamais purgé) ou média déjà purgé
  | { kind: "not-due"; at: Date } // exécution anticipée : scheduledPurgeAt fait foi
  | { kind: "purge" };

// Pas de conservation SnapCore+ dans un groupe (ADR-032) : contrairement à decidePurge des snaps à
// deux, l'abonnement d'aucun membre ne repousse la purge.
export function decideGroupPurge(
  message: Pick<GroupMessage, "contentType" | "mediaKey" | "scheduledPurgeAt">,
  now: Date,
): GroupPurgeDecision {
  if (message.contentType === "TEXT" || message.mediaKey === null) return { kind: "nothing" };
  if (message.scheduledPurgeAt && message.scheduledPurgeAt > now) return { kind: "not-due", at: message.scheduledPurgeAt };
  return { kind: "purge" };
}

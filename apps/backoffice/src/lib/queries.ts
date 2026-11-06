import "server-only";
import { Prisma } from "@snapcore/db";
import type { ReportStatus } from "@snapcore/types";
import { stuckDeletionThreshold } from "./deletion";
import { PAGE_SIZE, dailySeries } from "./format";
import { prisma } from "./prisma";

// Modèle du backoffice : lectures directes PostgreSQL via Prisma (ADR-004, ADR-021). Un modérateur
// consulte librement comptes et activité (ADR-021) ; les hash de mot de passe ne sont jamais
// sélectionnés, et une clé R2 n'est lue que pour savoir si un Snap a expiré (jamais affichée).

const DAY = 24 * 60 * 60 * 1000;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const userSummary = { id: true, username: true, firstName: true, avatar: true, profilePhotoUrl: true, moderationStatus: true, deletionRequestedAt: true } as const;

/** Page de résultats : on lit une ligne de plus pour savoir s'il en reste après. */
export interface Page<T> {
  items: T[];
  page: number;
  hasNext: boolean;
}

function paged<T>(rows: T[], page: number): Page<T> {
  return { items: rows.slice(0, PAGE_SIZE), page, hasNext: rows.length > PAGE_SIZE };
}

const pageArgs = (page: number) => ({ skip: (page - 1) * PAGE_SIZE, take: PAGE_SIZE + 1 });

// Abonnement SnapCore+ en cours (ADR-025) : premium sans date de fin, ou date de fin future.
const premiumNow = (now: Date): Prisma.UserWhereInput => ({
  subscriptionTier: "premium",
  OR: [{ premiumUntil: null }, { premiumUntil: { gt: now } }],
});

// --- Tableau de bord ---------------------------------------------------------------------------

export async function dashboardStats() {
  const now = new Date();
  const since = new Date(now.getTime() - DAY);
  const [
    users,
    suspended,
    deleting,
    newUsers,
    premium,
    messages,
    snaps,
    groupMessages,
    activeStories,
    pendingReports,
    friendships,
    groups,
    sharingLocation,
  ] = await Promise.all([
    prisma.user.count({ where: { deletionRequestedAt: null } }),
    prisma.user.count({ where: { moderationStatus: "SUSPENDED" } }),
    prisma.user.count({ where: { deletionRequestedAt: { not: null } } }),
    prisma.user.count({ where: { createdAt: { gte: new Date(now.getTime() - 7 * DAY) } } }),
    prisma.user.count({ where: { deletionRequestedAt: null, ...premiumNow(now) } }),
    prisma.message.count({ where: { createdAt: { gte: since } } }),
    prisma.message.count({ where: { createdAt: { gte: since }, contentType: { not: "TEXT" } } }),
    prisma.groupMessage.count({ where: { createdAt: { gte: since } } }),
    prisma.storyItem.count({ where: { expiresAt: { gt: now } } }),
    prisma.report.count({ where: { status: "PENDING" } }),
    prisma.friendship.count({ where: { status: "ACCEPTED" } }),
    prisma.group.count(),
    prisma.user.count({ where: { deletionRequestedAt: null, locationSharing: { not: "GHOST" } } }),
  ]);
  return { users, suspended, deleting, newUsers, premium, messages, snaps, groupMessages, activeStories, pendingReports, friendships, groups, sharingLocation };
}

// Tables de la série quotidienne : le nom est inséré tel quel dans le SQL, d'où cette liste fermée.
const ACTIVITY_TABLES = { signups: "User", messages: "Message", groupMessages: "GroupMessage", stories: "StoryItem" } as const;
export type ActivitySeries = Record<keyof typeof ACTIVITY_TABLES, ReturnType<typeof dailySeries>>;

/** Activité quotidienne des `days` derniers jours, par jour calendaire de Paris. */
export async function dailyActivity(days = 14): Promise<ActivitySeries> {
  const now = new Date();
  const since = new Date(now.getTime() - days * DAY);
  const entries = await Promise.all(
    Object.entries(ACTIVITY_TABLES).map(async ([name, table]) => {
      const rows = await prisma.$queryRaw<{ day: string; count: number }[]>(Prisma.sql`
        SELECT to_char("createdAt" AT TIME ZONE 'UTC' AT TIME ZONE 'Europe/Paris', 'YYYY-MM-DD') AS day, count(*)::int AS count
        FROM ${Prisma.raw(`"${table}"`)}
        WHERE "createdAt" >= ${since}
        GROUP BY 1`);
      return [name, dailySeries(rows, days, now)] as const;
    }),
  );
  return Object.fromEntries(entries) as ActivitySeries;
}

/** Suppressions de compte bloquées depuis plus de 24 h (ADR-019), les plus anciennes d'abord. */
export async function stuckDeletions() {
  return prisma.user.findMany({
    where: { deletionRequestedAt: { lte: stuckDeletionThreshold(new Date()) } },
    select: { id: true, username: true, deletionRequestedAt: true },
    orderBy: { deletionRequestedAt: "asc" },
    take: 50,
  });
}

// --- Signalements ------------------------------------------------------------------------------

const reportInclude = {
  reporter: { select: userSummary },
  targetUser: { select: userSummary },
  targetMessage: {
    select: {
      id: true,
      contentType: true,
      content: true,
      mediaKey: true,
      moderatedAt: true,
      createdAt: true,
      sender: { select: userSummary },
      recipient: { select: userSummary },
    },
  },
  targetStoryItem: { select: { id: true, contentType: true, createdAt: true, expiresAt: true, author: { select: userSummary } } },
  resolvedBy: { select: { email: true } },
} as const;

export type ReportTypeFilter = "USER" | "MESSAGE" | "STORY_ITEM" | null;

export async function listReports(status: ReportStatus, type: ReportTypeFilter = null, page = 1) {
  const rows = await prisma.report.findMany({
    where: { status, ...(type ? { targetType: type } : {}) },
    include: reportInclude,
    // File d'attente : les plus anciens d'abord ; historique : les plus récents d'abord.
    orderBy: { createdAt: status === "PENDING" ? "asc" : "desc" },
    ...pageArgs(page),
  });
  return paged(rows, page);
}

export async function reportCounts(): Promise<Record<ReportStatus, number>> {
  const rows = await prisma.report.groupBy({ by: ["status"], _count: { _all: true } });
  const counts: Record<ReportStatus, number> = { PENDING: 0, RESOLVED: 0, DISMISSED: 0 };
  for (const row of rows) counts[row.status] = row._count._all;
  return counts;
}

export async function getReport(id: string) {
  if (!UUID.test(id)) return null;
  return prisma.report.findUnique({ where: { id }, include: reportInclude });
}

export type ReportWithTarget = NonNullable<Awaited<ReturnType<typeof getReport>>>;

const reportsAgainstUser = (userId: string): Prisma.ReportWhereInput => ({
  OR: [{ targetUserId: userId }, { targetMessage: { senderId: userId } }, { targetStoryItem: { authorId: userId } }],
});

/** Autres signalements visant le même compte, directement ou par son contenu. */
export async function otherReportsAgainst(userId: string, excludeId: string) {
  return prisma.report.findMany({
    where: { id: { not: excludeId }, ...reportsAgainstUser(userId) },
    select: { id: true, targetType: true, reason: true, status: true, createdAt: true },
    orderBy: { createdAt: "desc" },
    take: 20,
  });
}

// --- Utilisateurs ------------------------------------------------------------------------------

export type UserStatusFilter = "all" | "active" | "suspended" | "deleting";
export type UserPlanFilter = "all" | "premium" | "free";

export interface UserFilters {
  q: string;
  status: UserStatusFilter;
  plan: UserPlanFilter;
}

function userWhere({ q, status, plan }: UserFilters, now: Date): Prisma.UserWhereInput {
  const and: Prisma.UserWhereInput[] = [];
  const query = q.trim();
  if (query) {
    const phone = query.replace(/[\s.]/g, "");
    and.push({
      OR: [
        { username: { contains: query.toLowerCase().replace(/^@/, "") } },
        { firstName: { contains: query, mode: "insensitive" } },
        { lastName: { contains: query, mode: "insensitive" } },
        { email: { contains: query, mode: "insensitive" } },
        ...(/^\+?\d{4,}$/.test(phone) ? [{ phoneNumber: { contains: phone } }] : []),
        ...(UUID.test(query) ? [{ id: query }] : []),
      ],
    });
  }
  if (status === "active") and.push({ moderationStatus: "ACTIVE", deletionRequestedAt: null });
  if (status === "suspended") and.push({ moderationStatus: "SUSPENDED" });
  if (status === "deleting") and.push({ deletionRequestedAt: { not: null } });
  if (plan === "premium") and.push(premiumNow(now));
  if (plan === "free") and.push({ NOT: premiumNow(now) });
  return { AND: and };
}

/** Liste paginée des comptes, les plus récents d'abord, avec recherche et filtres. */
export async function listUsers(filters: UserFilters, page = 1) {
  const where = userWhere(filters, new Date());
  const [rows, total] = await Promise.all([
    prisma.user.findMany({
      where,
      select: { ...userSummary, lastName: true, email: true, phoneNumber: true, avatar: true, createdAt: true, subscriptionTier: true, premiumUntil: true },
      orderBy: { createdAt: "desc" },
      ...pageArgs(page),
    }),
    prisma.user.count({ where }),
  ]);
  return { ...paged(rows, page), total };
}

export async function getUserDetail(id: string) {
  if (!UUID.test(id)) return null;
  const user = await prisma.user.findUnique({
    where: { id },
    select: {
      ...userSummary,
      lastName: true,
      birthday: true,
      email: true,
      phoneNumber: true,
      avatar: true,
      subscriptionTier: true,
      premiumUntil: true,
      subscriptionEventAt: true,
      locationSharing: true,
      createdAt: true,
      suspendedAt: true,
      suspensionReason: true,
      suspendedBy: { select: { email: true } },
    },
  });
  if (!user) return null;

  const [friends, reportsAgainst, reportsMade, messagesSent, snapsSent, groupMessagesSent, pendingRequests, stories, devices, shareRules, groups] =
    await Promise.all([
      prisma.friendship.count({ where: { status: "ACCEPTED", OR: [{ requesterId: id }, { addresseeId: id }] } }),
      prisma.report.count({ where: reportsAgainstUser(id) }),
      prisma.report.count({ where: { reporterId: id } }),
      prisma.message.count({ where: { senderId: id } }),
      prisma.message.count({ where: { senderId: id, contentType: { not: "TEXT" } } }),
      prisma.groupMessage.count({ where: { senderId: id } }),
      prisma.friendship.count({ where: { status: "PENDING", addresseeId: id } }),
      prisma.storyItem.findMany({
        where: { authorId: id, expiresAt: { gt: new Date() } },
        select: { id: true, contentType: true, createdAt: true, expiresAt: true, latitude: true, longitude: true },
        orderBy: { createdAt: "desc" },
      }),
      prisma.deviceToken.findMany({ where: { userId: id }, select: { id: true, updatedAt: true }, orderBy: { updatedAt: "desc" } }),
      prisma.locationShareRule.findMany({ where: { ownerId: id }, select: { friend: { select: { id: true, username: true } } } }),
      prisma.groupMember.findMany({
        where: { userId: id },
        select: { joinedAt: true, group: { select: { id: true, name: true, _count: { select: { members: true, messages: true } } } } },
        orderBy: { joinedAt: "desc" },
      }),
    ]);

  return {
    user,
    stats: { friends, reportsAgainst, reportsMade, messagesSent, snapsSent, groupMessagesSent, pendingRequests },
    stories,
    devices,
    shareRules: shareRules.map((rule) => rule.friend),
    groups,
  };
}

export type UserDetail = NonNullable<Awaited<ReturnType<typeof getUserDetail>>>;

const messageSelect = {
  id: true,
  contentType: true,
  content: true,
  mediaKey: true,
  moderatedAt: true,
  moderationReason: true,
  createdAt: true,
  openedAt: true,
  sender: { select: { id: true, username: true } },
  recipient: { select: { id: true, username: true } },
} as const;

/** Messages privés d'un compte, envoyés et reçus ; limités à une conversation si `withUserId`. */
export async function userMessages(id: string, page = 1, withUserId?: string) {
  const other = withUserId && UUID.test(withUserId) ? withUserId : undefined;
  const rows = await prisma.message.findMany({
    where: other
      ? { OR: [{ senderId: id, recipientId: other }, { senderId: other, recipientId: id }] }
      : { OR: [{ senderId: id }, { recipientId: id }] },
    select: messageSelect,
    orderBy: { createdAt: "desc" },
    ...pageArgs(page),
  });
  return paged(rows, page);
}

export async function userFriendships(id: string) {
  return prisma.friendship.findMany({
    where: { OR: [{ requesterId: id }, { addresseeId: id }] },
    select: {
      id: true,
      status: true,
      requestedAt: true,
      respondedAt: true,
      blockedBy: true,
      requester: { select: userSummary },
      addressee: { select: userSummary },
    },
    orderBy: { requestedAt: "desc" },
    take: 500,
  });
}

export async function userReports(id: string) {
  const [received, made] = await Promise.all([
    prisma.report.findMany({ where: reportsAgainstUser(id), include: reportInclude, orderBy: { createdAt: "desc" }, take: 100 }),
    prisma.report.findMany({ where: { reporterId: id }, include: reportInclude, orderBy: { createdAt: "desc" }, take: 100 }),
  ]);
  return { received, made };
}

// --- Contenus de l'app -------------------------------------------------------------------------

export type MessageKindFilter = "all" | "text" | "snap" | "moderated";

/** Fil des messages privés de toute l'app, les plus récents d'abord. */
export async function listMessages(kind: MessageKindFilter, page = 1) {
  const where: Prisma.MessageWhereInput =
    kind === "text"
      ? { contentType: "TEXT" }
      : kind === "snap"
        ? { contentType: { not: "TEXT" } }
        : kind === "moderated"
          ? { moderatedAt: { not: null } }
          : {};
  const rows = await prisma.message.findMany({
    where,
    select: { ...messageSelect, moderatedBy: { select: { email: true } }, _count: { select: { reports: true } } },
    orderBy: kind === "moderated" ? { moderatedAt: "desc" } : { createdAt: "desc" },
    ...pageArgs(page),
  });
  return paged(rows, page);
}

/** Stories encore visibles dans l'app, les plus récentes d'abord. */
export async function listActiveStories(page = 1) {
  const rows = await prisma.storyItem.findMany({
    where: { expiresAt: { gt: new Date() } },
    select: {
      id: true,
      contentType: true,
      createdAt: true,
      expiresAt: true,
      latitude: true,
      longitude: true,
      author: { select: userSummary },
      _count: { select: { reports: true } },
    },
    orderBy: { createdAt: "desc" },
    ...pageArgs(page),
  });
  return paged(rows, page);
}

// --- Groupes (ADR-032) -------------------------------------------------------------------------

export async function listGroups(q: string, page = 1) {
  const query = q.trim();
  const rows = await prisma.group.findMany({
    where: query
      ? {
          OR: [
            { name: { contains: query, mode: "insensitive" } },
            { members: { some: { user: { username: { contains: query.toLowerCase().replace(/^@/, "") } } } } },
            ...(UUID.test(query) ? [{ id: query }] : []),
          ],
        }
      : {},
    select: {
      id: true,
      name: true,
      createdAt: true,
      createdBy: { select: { id: true, username: true } },
      _count: { select: { members: true, messages: true } },
      members: { select: { user: { select: { username: true, firstName: true } } }, take: 4, orderBy: { joinedAt: "asc" } },
      messages: { select: { createdAt: true }, orderBy: { createdAt: "desc" }, take: 1 },
    },
    orderBy: { createdAt: "desc" },
    ...pageArgs(page),
  });
  return paged(rows, page);
}

export async function getGroup(id: string, page = 1) {
  if (!UUID.test(id)) return null;
  const group = await prisma.group.findUnique({
    where: { id },
    select: {
      id: true,
      name: true,
      createdAt: true,
      createdBy: { select: { id: true, username: true } },
      members: { select: { joinedAt: true, lastReadAt: true, user: { select: userSummary } }, orderBy: { joinedAt: "asc" } },
      _count: { select: { messages: true } },
    },
  });
  if (!group) return null;
  const rows = await prisma.groupMessage.findMany({
    where: { groupId: id },
    select: {
      id: true,
      contentType: true,
      content: true,
      mediaKey: true,
      createdAt: true,
      sender: { select: { id: true, username: true } },
      _count: { select: { views: true } },
    },
    orderBy: { createdAt: "desc" },
    ...pageArgs(page),
  });
  return { group, messages: paged(rows, page) };
}

// --- SnapCore+ (ADR-025) -----------------------------------------------------------------------

export async function subscriptionOverview() {
  const now = new Date();
  const soon = new Date(now.getTime() + 7 * DAY);
  const select = { ...userSummary, email: true, subscriptionTier: true, premiumUntil: true, subscriptionEventAt: true, createdAt: true } as const;
  const [active, expiringSoon, expired, total] = await Promise.all([
    prisma.user.findMany({ where: { deletionRequestedAt: null, ...premiumNow(now) }, select, orderBy: { premiumUntil: "asc" }, take: 500 }),
    prisma.user.count({ where: { deletionRequestedAt: null, subscriptionTier: "premium", premiumUntil: { gt: now, lte: soon } } }),
    prisma.user.findMany({ where: { deletionRequestedAt: null, premiumUntil: { lte: now } }, select, orderBy: { premiumUntil: "desc" }, take: 100 }),
    prisma.user.count({ where: { deletionRequestedAt: null } }),
  ]);
  return { active, expiringSoon, expired, total };
}

// --- Journal de modération ---------------------------------------------------------------------

export type AuditEntry =
  | { kind: "suspension"; at: Date; moderator: string | null; userId: string; username: string; detail: string | null }
  | { kind: "redaction"; at: Date; moderator: string | null; userId: string; username: string; detail: string | null }
  | { kind: "report"; at: Date; moderator: string | null; reportId: string; status: ReportStatus; targetType: "USER" | "MESSAGE" | "STORY_ITEM"; detail: string };

/**
 * Dernières actions des modérateurs, reconstituées depuis les champs d'audit en base (ADR-021).
 * Une suspension levée efface sa trace (suspendedAt remis à NULL) ; une suppression de story n'est
 * tracée que dans les logs du backend.
 */
export async function moderationLog(moderatorId?: string, limit = 150): Promise<AuditEntry[]> {
  const [suspensions, redactions, reports] = await Promise.all([
    prisma.user.findMany({
      where: { suspendedAt: { not: null }, ...(moderatorId ? { suspendedById: moderatorId } : {}) },
      select: { id: true, username: true, suspendedAt: true, suspensionReason: true, suspendedBy: { select: { email: true } } },
      orderBy: { suspendedAt: "desc" },
      take: limit,
    }),
    prisma.message.findMany({
      where: { moderatedAt: { not: null }, ...(moderatorId ? { moderatedById: moderatorId } : {}) },
      select: { moderatedAt: true, moderationReason: true, moderatedBy: { select: { email: true } }, sender: { select: { id: true, username: true } } },
      orderBy: { moderatedAt: "desc" },
      take: limit,
    }),
    prisma.report.findMany({
      where: { resolvedAt: { not: null }, ...(moderatorId ? { resolvedById: moderatorId } : {}) },
      select: { id: true, resolvedAt: true, status: true, targetType: true, reason: true, resolvedBy: { select: { email: true } } },
      orderBy: { resolvedAt: "desc" },
      take: limit,
    }),
  ]);
  const entries: AuditEntry[] = [
    ...suspensions.map((u) => ({
      kind: "suspension" as const,
      at: u.suspendedAt as Date,
      moderator: u.suspendedBy?.email ?? null,
      userId: u.id,
      username: u.username,
      detail: u.suspensionReason,
    })),
    ...redactions.map((m) => ({
      kind: "redaction" as const,
      at: m.moderatedAt as Date,
      moderator: m.moderatedBy?.email ?? null,
      userId: m.sender.id,
      username: m.sender.username,
      detail: m.moderationReason,
    })),
    ...reports.map((r) => ({
      kind: "report" as const,
      at: r.resolvedAt as Date,
      moderator: r.resolvedBy?.email ?? null,
      reportId: r.id,
      status: r.status,
      targetType: r.targetType,
      detail: r.reason,
    })),
  ];
  return entries.sort((a, b) => b.at.getTime() - a.at.getTime()).slice(0, limit);
}

export async function listModerators() {
  return prisma.moderator.findMany({
    select: {
      id: true,
      email: true,
      createdAt: true,
      _count: { select: { usersSuspended: true, messagesModerated: true, reportsResolved: true } },
    },
    orderBy: { createdAt: "asc" },
  });
}

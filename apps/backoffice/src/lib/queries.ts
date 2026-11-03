import "server-only";
import type { ReportStatus } from "@snapcore/types";
import { stuckDeletionThreshold } from "./deletion";
import { prisma } from "./prisma";

// Modèle du backoffice : lectures directes PostgreSQL via Prisma (ADR-004, ADR-021). Un modérateur
// consulte librement comptes et activité (ADR-021) ; les clés R2 et hash de mot de passe ne sont
// jamais sélectionnés.

const DAY = 24 * 60 * 60 * 1000;

const userSummary = { id: true, username: true, profilePhotoUrl: true, moderationStatus: true, deletionRequestedAt: true } as const;

export async function dashboardStats() {
  const since = new Date(Date.now() - DAY);
  const [users, suspended, newUsers, messages, activeStories, pendingReports] = await Promise.all([
    prisma.user.count({ where: { deletionRequestedAt: null } }),
    prisma.user.count({ where: { moderationStatus: "SUSPENDED" } }),
    prisma.user.count({ where: { createdAt: { gte: new Date(Date.now() - 7 * DAY) } } }),
    prisma.message.count({ where: { createdAt: { gte: since } } }),
    prisma.storyItem.count({ where: { expiresAt: { gt: new Date() } } }),
    prisma.report.count({ where: { status: "PENDING" } }),
  ]);
  return { users, suspended, newUsers, messages, activeStories, pendingReports };
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

export async function listReports(status: ReportStatus) {
  return prisma.report.findMany({
    where: { status },
    include: reportInclude,
    // File d'attente : les plus anciens d'abord ; historique : les plus récents d'abord.
    orderBy: { createdAt: status === "PENDING" ? "asc" : "desc" },
    take: 100,
  });
}

export async function getReport(id: string) {
  return prisma.report.findUnique({ where: { id }, include: reportInclude });
}

export type ReportWithTarget = NonNullable<Awaited<ReturnType<typeof getReport>>>;

export async function searchUsers(query: string) {
  const q = query.trim();
  if (!q) return [];
  return prisma.user.findMany({
    where: {
      OR: [
        { username: { contains: q.toLowerCase() } },
        { email: { contains: q, mode: "insensitive" } },
        { id: q },
      ],
    },
    select: { ...userSummary, email: true, createdAt: true },
    orderBy: { username: "asc" },
    take: 50,
  });
}

export async function getUserDetail(id: string) {
  const user = await prisma.user.findUnique({
    where: { id },
    select: {
      ...userSummary,
      firstName: true,
      lastName: true,
      birthday: true,
      email: true,
      phoneNumber: true,
      subscriptionTier: true,
      premiumUntil: true,
      createdAt: true,
      suspendedAt: true,
      suspensionReason: true,
      suspendedBy: { select: { email: true } },
    },
  });
  if (!user) return null;

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

  const [messages, stories, friends, reportsAgainst, reportsMade] = await Promise.all([
    prisma.message.findMany({
      where: { OR: [{ senderId: id }, { recipientId: id }] },
      select: messageSelect,
      orderBy: { createdAt: "desc" },
      take: 50,
    }),
    prisma.storyItem.findMany({
      where: { authorId: id, expiresAt: { gt: new Date() } },
      select: { id: true, contentType: true, createdAt: true, expiresAt: true },
      orderBy: { createdAt: "desc" },
    }),
    prisma.friendship.count({ where: { status: "ACCEPTED", OR: [{ requesterId: id }, { addresseeId: id }] } }),
    prisma.report.count({
      where: { OR: [{ targetUserId: id }, { targetMessage: { senderId: id } }, { targetStoryItem: { authorId: id } }] },
    }),
    prisma.report.count({ where: { reporterId: id } }),
  ]);

  return { user, messages, stories, friends, reportsAgainst, reportsMade };
}

export type UserDetail = NonNullable<Awaited<ReturnType<typeof getUserDetail>>>;

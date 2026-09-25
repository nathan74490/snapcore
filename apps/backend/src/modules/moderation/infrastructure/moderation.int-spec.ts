import { randomUUID } from "node:crypto";
import { PrismaService } from "../../../shared/infrastructure/prisma/prisma.service";
import { PrismaFriendshipRepository } from "../../friendships/infrastructure/prisma-friendship.repository";
import type { UserAccount } from "../../identity/domain/user";
import { PrismaUserRepository } from "../../identity/infrastructure/prisma-user.repository";
import { PrismaModerationRepository } from "./prisma-moderation.repository";

// Contre le Postgres de docker compose (vide les tables). Vérifie la contrainte
// Report_single_target_check et le filtre centralisé ACTIVE_USER_WHERE (ADR-021).
describe("Modération (Postgres réel)", () => {
  const prisma = new PrismaService();
  const users = new PrismaUserRepository(prisma);
  const moderation = new PrismaModerationRepository(prisma);
  let alice: UserAccount;
  let bob: UserAccount;
  let moderatorId: string;

  const newUser = (username: string) =>
    users.create({ username, firebaseUid: `uid-${randomUUID()}`, email: null, phoneNumber: null });

  beforeEach(async () => {
    await prisma.report.deleteMany();
    await prisma.storyItem.deleteMany();
    await prisma.message.deleteMany();
    await prisma.friendship.deleteMany();
    await prisma.user.deleteMany();
    await prisma.moderator.deleteMany();
    [alice, bob] = await Promise.all([newUser("alice"), newUser("bob")]);
    moderatorId = (await prisma.moderator.create({ data: { email: `mod-${randomUUID()}@snapcore.test`, passwordHash: "x" } })).id;
  });

  afterAll(() => prisma.$disconnect());

  const text = (from: UserAccount, to: UserAccount) =>
    prisma.message.create({ data: { id: randomUUID(), senderId: from.id, recipientId: to.id, contentType: "TEXT", content: "…" } });

  it("CHECK : refuse un signalement dont la clé ne correspond pas au type", async () => {
    const message = await text(alice, bob);
    await expect(
      prisma.report.create({ data: { reporterId: bob.id, targetType: "USER", targetMessageId: message.id, reason: "x" } }),
    ).rejects.toThrow(/Report_single_target_check/);
    await expect(
      prisma.report.create({
        data: { reporterId: bob.id, targetType: "MESSAGE", targetMessageId: message.id, targetUserId: alice.id, reason: "x" },
      }),
    ).rejects.toThrow(/Report_single_target_check/);
  });

  it("la purge d'une story signalée n'est pas bloquée : le signalement reste, cible à NULL", async () => {
    const story = await prisma.storyItem.create({
      data: { authorId: alice.id, mediaKey: "stories/x", contentType: "PHOTO", expiresAt: new Date() },
    });
    const report = await moderation.createReport({ reporterId: bob.id, target: { type: "STORY_ITEM", storyItemId: story.id }, reason: "x" });

    await prisma.storyItem.delete({ where: { id: story.id } }); // comme le job de purge à 24h (ADR-013)

    expect(await prisma.report.findUnique({ where: { id: report.id } })).toMatchObject({ targetType: "STORY_ITEM", targetStoryItemId: null });
  });

  it("la suppression du compte d'un expéditeur signalé n'est pas bloquée (cascade des messages)", async () => {
    const message = await text(alice, bob);
    const report = await moderation.createReport({ reporterId: bob.id, target: { type: "MESSAGE", messageId: message.id }, reason: "x" });

    await prisma.user.delete({ where: { id: alice.id } }); // étape (c) d'ADR-019

    expect(await prisma.report.findUnique({ where: { id: report.id } })).toMatchObject({ targetMessageId: null });
  });

  it("suspension : exclue du filtre centralisé (profil, amis) puis réactivation, traçabilité", async () => {
    const friendships = new PrismaFriendshipRepository(prisma);
    await friendships.create({ requesterId: alice.id, addresseeId: bob.id, status: "ACCEPTED", blockedBy: null, requestedAt: new Date(), respondedAt: new Date() });

    expect(await moderation.suspendUser(alice.id, { moderatorId, reason: "Spam", at: new Date() })).toBe("updated");
    expect(await moderation.suspendUser(alice.id, { moderatorId, reason: "Spam", at: new Date() })).toBe("unchanged");
    expect(await users.findActiveById(alice.id)).toBeNull();
    expect(await friendships.listFriends(bob.id)).toEqual([]);
    expect(await prisma.user.findUnique({ where: { id: alice.id } })).toMatchObject({ suspendedById: moderatorId, suspensionReason: "Spam" });

    expect(await moderation.reactivateUser(alice.id)).toBe("updated");
    expect((await friendships.listFriends(bob.id)).map((f) => f.user.id)).toEqual([alice.id]);
    expect(await prisma.user.findUnique({ where: { id: alice.id } })).toMatchObject({ suspendedById: null, suspensionReason: null });
  });

  it("un signalement n'est traité qu'une fois, attribué au modérateur", async () => {
    const report = await moderation.createReport({ reporterId: bob.id, target: { type: "USER", userId: alice.id }, reason: "x" });

    expect(await moderation.closeReport(report.id, { status: "DISMISSED", moderatorId, at: new Date() })).toBe("updated");
    expect(await moderation.closeReport(report.id, { status: "RESOLVED", moderatorId, at: new Date() })).toBe("unchanged");
    expect(await prisma.report.findUnique({ where: { id: report.id } })).toMatchObject({ status: "DISMISSED", resolvedById: moderatorId });
  });
});

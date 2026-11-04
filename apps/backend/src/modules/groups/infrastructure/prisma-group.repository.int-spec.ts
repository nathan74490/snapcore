import { randomUUID } from "node:crypto";
import { PrismaService } from "../../../shared/infrastructure/prisma/prisma.service";
import { PrismaAccountDeletionRepository } from "../../account-deletion/infrastructure/prisma-account-deletion.repository";
import type { UserAccount } from "../../identity/domain/user";
import { PrismaUserRepository } from "../../identity/infrastructure/prisma-user.repository";
import type { GroupMessage } from "../domain/group";
import { PrismaGroupRepository } from "./prisma-group.repository";

const T0 = new Date("2026-11-01T10:00:00Z");
const at = (minutes: number) => new Date(T0.getTime() + minutes * 60_000);

// Contre le Postgres de docker compose (vide les tables).
describe("PrismaGroupRepository (Postgres réel)", () => {
  const prisma = new PrismaService();
  const users = new PrismaUserRepository(prisma);
  const groups = new PrismaGroupRepository(prisma);
  let alice: UserAccount;
  let bob: UserAccount;
  let carol: UserAccount;
  let dave: UserAccount;

  const newUser = (username: string) =>
    users.create({ username, firstName: username, lastName: null, birthday: null, firebaseUid: `uid-${randomUUID()}`, email: null, phoneNumber: null });

  const newGroup = async (members: UserAccount[], createdAt = T0, name: string | null = null) => {
    const id = randomUUID();
    await groups.create({ id, name, createdById: members[0].id, createdAt }, members.map((m) => m.id));
    return id;
  };

  const text = (groupId: string, sender: UserAccount, minutes: number, content = `chat ${minutes}`): Promise<GroupMessage> =>
    groups.createMessage({
      id: randomUUID(),
      groupId,
      senderId: sender.id,
      contentType: "TEXT",
      content,
      mediaKey: null,
      createdAt: at(minutes),
      scheduledPurgeAt: null,
    });

  const snap = (groupId: string, sender: UserAccount, minutes: number): Promise<GroupMessage> => {
    const id = randomUUID();
    return groups.createMessage({
      id,
      groupId,
      senderId: sender.id,
      contentType: "SNAP_PHOTO",
      content: null,
      mediaKey: `group-messages/${id}`,
      createdAt: at(minutes),
      scheduledPurgeAt: at(minutes + 30 * 24 * 60),
    });
  };

  beforeEach(async () => {
    await prisma.group.deleteMany();
    await prisma.message.deleteMany();
    await prisma.friendship.deleteMany();
    await prisma.user.deleteMany();
    [alice, bob, carol, dave] = await Promise.all(["alice", "bob", "carol", "dave"].map(newUser));
  });

  afterAll(async () => {
    await prisma.group.deleteMany();
    await prisma.$disconnect();
  });

  it("liste : dernier message visible depuis mon arrivée, plus ancien snap à ouvrir, non lu (requêtes DISTINCT ON)", async () => {
    const g1 = await newGroup([alice, bob, carol]);
    const g2 = await newGroup([bob, carol]);
    const g3 = await newGroup([alice, bob]);
    await groups.addMembers(g2, bob.id, [alice.id], at(10), 32); // alice arrive à +10 min dans g2

    const oldestSnap = await snap(g1, bob, 1);
    await snap(g1, carol, 2);
    const opened = await snap(g1, bob, 0);
    await groups.recordOpening(opened.id, alice.id, at(3));
    const purged = await snap(g1, carol, 0.5);
    await groups.clearMedia(purged.id);
    await snap(g1, alice, 0.2); // envoyé par moi : jamais « à ouvrir »
    const lastG1 = await text(g1, carol, 5);
    await snap(g2, bob, 5); // avant l'arrivée d'alice : invisible
    const lastG2 = await text(g2, carol, 11);
    await text(g3, alice, 1); // seul message de g3 : le mien

    const ids = [g1, g2, g3];
    const last = await groups.findLastMessages(alice.id, ids);
    expect(last.get(g1)?.id).toBe(lastG1.id);
    expect(last.get(g2)?.id).toBe(lastG2.id);
    expect(last.get(g3)?.senderId).toBe(alice.id);

    const unopened = await groups.findOldestUnopenedSnaps(alice.id, ids);
    expect(unopened.get(g1)?.id).toBe(oldestSnap.id);
    expect(unopened.has(g2)).toBe(false);
    expect(unopened.has(g3)).toBe(false);

    expect([...(await groups.findGroupsWithUnread(alice.id, ids))].sort()).toEqual([g1, g2].sort());
    expect((await groups.findLastUnreadFromOthers(g1, alice.id))?.id).toBe(lastG1.id);
    await groups.markRead(g1, alice.id, at(5));
    expect(await groups.findGroupsWithUnread(alice.id, [g1])).toEqual(new Set());
    expect(await groups.findLastUnreadFromOthers(g1, alice.id)).toBeNull();
    await groups.markRead(g1, alice.id, at(1)); // jamais en arrière
    expect((await groups.findMember(g1, alice.id))?.lastReadAt).toEqual(at(5));

    expect(await groups.findLastMessages(alice.id, [])).toEqual(new Map());
    expect((await groups.listMessages(g2, { since: at(10), limit: 10 })).map((m) => m.id)).toEqual([lastG2.id]);
  });

  it("membres actifs par ordre d'arrivée, anciens membres auteurs ; suspendu traité comme inexistant", async () => {
    const g = await newGroup([alice, bob, carol]);
    await groups.addMembers(g, alice.id, [dave.id], at(5), 32);
    await text(g, carol, 1);
    await groups.removeMember(g, carol.id);
    await prisma.user.update({ where: { id: bob.id }, data: { moderationStatus: "SUSPENDED" } });

    const members = (await groups.listActiveMembers([g])).get(g)!;
    expect(members.map((m) => m.userId)).toEqual([alice.id, dave.id]);
    expect(members[0].profile).toEqual({ id: alice.id, username: "alice", firstName: "alice" });
    expect((await groups.listFormerSenders([g])).get(g)?.map((p) => p.id)).toEqual([carol.id]);

    await prisma.user.update({ where: { id: carol.id }, data: { deletionRequestedAt: new Date() } });
    expect((await groups.listFormerSenders([g])).has(g)).toBe(false);
  });

  it("ajout sous verrou : limite respectée même en concurrence, déjà membres ignorés", async () => {
    const g = await newGroup([alice, bob]);
    const results = await Promise.all([
      groups.addMembers(g, alice.id, [carol.id], at(1), 3),
      groups.addMembers(g, bob.id, [dave.id], at(1), 3),
    ]);
    expect(results.map((r) => r.kind).sort()).toEqual(["added", "full"]);
    expect(await prisma.groupMember.count({ where: { groupId: g } })).toBe(3);
    await expect(groups.addMembers(g, alice.id, [bob.id], at(2), 3)).resolves.toEqual({ kind: "added", userIds: [] });
    // Auteur de l'ajout qui n'est plus membre, ou groupe disparu.
    await expect(groups.addMembers(g, randomUUID(), [dave.id], at(2), 32)).resolves.toEqual({ kind: "missing" });
    await expect(groups.addMembers(randomUUID(), alice.id, [dave.id], at(2), 32)).resolves.toEqual({ kind: "missing" });
  });

  it("départ du dernier membre : décompte exact puis suppression en cascade (messages, ouvertures)", async () => {
    const g = await newGroup([alice, bob]);
    const s = await snap(g, alice, 1);
    await groups.recordOpening(s.id, bob.id, at(2));

    expect(await groups.removeMember(g, alice.id)).toEqual({ removed: true, remaining: 1 });
    expect(await groups.deleteIfEmpty(g)).toBe(false);
    expect(await groups.listMediaKeys(g)).toEqual([s.mediaKey]);
    expect(await groups.removeMember(g, bob.id)).toEqual({ removed: true, remaining: 0 });
    expect(await groups.removeMember(g, bob.id)).toEqual({ removed: false, remaining: 0 });
    expect(await groups.deleteIfEmpty(g)).toBe(true);

    expect(await prisma.groupMessage.count()).toBe(0);
    expect(await prisma.groupMessageView.count()).toBe(0);
  });

  it("ouverture atomique : une seule réussit par membre, même en concurrence", async () => {
    const g = await newGroup([alice, bob, carol]);
    const s = await snap(g, alice, 1);
    const results = await Promise.all(Array.from({ length: 5 }, () => groups.recordOpening(s.id, bob.id, new Date())));
    expect(results.filter(Boolean)).toHaveLength(1);
    expect(await groups.recordOpening(s.id, carol.id, new Date())).toBe(true);
    expect((await groups.listOpenings([s.id])).map((o) => o.userId).sort()).toEqual([bob.id, carol.id].sort());
  });

  it("purge : snaps échus avec média seulement (balayage), mediaKey vidé sans supprimer la ligne", async () => {
    const g = await newGroup([alice, bob]);
    const due = await snap(g, alice, 0);
    const later = await snap(g, alice, 60);
    await text(g, bob, 0);
    const now = at(30 * 24 * 60 + 1);

    expect(await groups.findOverdueSnapIds(now, 10)).toEqual([due.id]);
    await groups.clearMedia(due.id);
    expect(await groups.findOverdueSnapIds(now, 10)).toEqual([]);
    expect((await groups.listSnapsWithMedia(g)).map((m) => m.id)).toEqual([later.id]);
  });

  it("CHECK : contenu cohérent avec le type, nom de 1 à 50 caractères trimé", async () => {
    const g = await newGroup([alice, bob]);
    const base = { groupId: g, senderId: alice.id, createdAt: T0 };
    const rejected = [
      { ...base, contentType: "TEXT" as const, content: null, mediaKey: null, scheduledPurgeAt: null },
      { ...base, contentType: "TEXT" as const, content: "x", mediaKey: "group-messages/x", scheduledPurgeAt: null },
      { ...base, contentType: "TEXT" as const, content: "x", mediaKey: null, scheduledPurgeAt: T0 },
      { ...base, contentType: "SNAP_PHOTO" as const, content: "x", mediaKey: "group-messages/x", scheduledPurgeAt: T0 },
      { ...base, contentType: "SNAP_PHOTO" as const, content: null, mediaKey: "group-messages/x", scheduledPurgeAt: null },
    ];
    for (const message of rejected) {
      await expect(groups.createMessage({ ...message, id: randomUUID() })).rejects.toThrow(
        /GroupMessage_content_matches_type_check/,
      );
    }
    for (const name of ["", " Soirée", "x".repeat(51)]) {
      await expect(groups.rename(g, name)).rejects.toThrow(/Group_name_length_check/);
    }
    await expect(groups.rename(g, "x".repeat(50))).resolves.toMatchObject({ name: "x".repeat(50) });
    await expect(groups.rename(g, null)).resolves.toMatchObject({ name: null });
  });

  it("suppression de compte : ses messages et groupes où il était seul partent, médias relevés", async () => {
    const shared = await newGroup([alice, bob]);
    const alone = await newGroup([alice, bob], T0, "Vide");
    const mine = await snap(shared, alice, 1);
    const theirs = await snap(shared, bob, 2);
    const leftBehind = await snap(alone, bob, 3);
    await groups.recordOpening(theirs.id, alice.id, at(4));
    await groups.removeMember(alone, bob.id);
    let collected: string[] = [];

    await new PrismaAccountDeletionRepository(prisma).deleteUserCollectingMedia(alice.id, async (media) => {
      collected = media.privateKeys;
    });

    expect(collected.sort()).toEqual([mine.mediaKey, leftBehind.mediaKey].sort());
    expect((await prisma.group.findMany()).map((g) => g.id)).toEqual([shared]);
    expect(await prisma.group.findUnique({ where: { id: shared } })).toMatchObject({ createdById: null });
    expect((await prisma.groupMessage.findMany()).map((m) => m.id)).toEqual([theirs.id]);
    expect(await prisma.groupMessageView.count()).toBe(0);
    expect(await prisma.groupMember.findMany({ select: { userId: true } })).toEqual([{ userId: bob.id }]);
  });
});

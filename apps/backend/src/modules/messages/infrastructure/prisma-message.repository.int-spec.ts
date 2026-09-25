import { randomUUID } from "node:crypto";
import { PrismaService } from "../../../shared/infrastructure/prisma/prisma.service";
import type { UserAccount } from "../../identity/domain/user";
import { PrismaUserRepository } from "../../identity/infrastructure/prisma-user.repository";
import type { NewMessage } from "../application/ports/message.repository";
import { PrismaMessageRepository } from "./prisma-message.repository";

// Contre le Postgres de docker compose (vide les tables).
describe("PrismaMessageRepository (Postgres réel)", () => {
  const prisma = new PrismaService();
  const users = new PrismaUserRepository(prisma);
  const messages = new PrismaMessageRepository(prisma);
  let alice: UserAccount;
  let bob: UserAccount;
  let carol: UserAccount;

  const newUser = (username: string) =>
    users.create({ username, firebaseUid: `uid-${randomUUID()}`, email: null, phoneNumber: null });

  const text = (from: UserAccount, to: UserAccount, content: string, at: string): NewMessage => ({
    id: randomUUID(),
    senderId: from.id,
    recipientId: to.id,
    contentType: "TEXT",
    content,
    mediaKey: null,
    createdAt: new Date(at),
    scheduledPurgeAt: null,
  });

  beforeEach(async () => {
    await prisma.message.deleteMany();
    await prisma.friendship.deleteMany();
    await prisma.user.deleteMany();
    [alice, bob, carol] = await Promise.all([newUser("alice"), newUser("bob"), newUser("carol")]);
  });

  afterAll(() => prisma.$disconnect());

  it("dernier message par conversation, dans les deux sens (DISTINCT ON)", async () => {
    await messages.create(text(alice, bob, "a→b 1", "2026-09-25T10:00:00Z"));
    await messages.create(text(bob, alice, "b→a 2", "2026-09-25T10:05:00Z"));
    await messages.create(text(carol, alice, "c→a 1", "2026-09-25T09:00:00Z"));
    await messages.create(text(bob, carol, "hors sujet", "2026-09-25T11:00:00Z"));

    const last = await messages.findLastMessagesWith(alice.id, [bob.id, carol.id]);

    expect(last.get(bob.id)?.content).toBe("b→a 2");
    expect(last.get(carol.id)?.content).toBe("c→a 1");
    expect(last.get(bob.id)?.createdAt).toEqual(new Date("2026-09-25T10:05:00Z"));
    expect(await messages.findLastMessagesWith(alice.id, [])).toEqual(new Map());
  });

  it("l'ouverture atomique ne réussit qu'une fois, même en concurrence", async () => {
    const message = await messages.create({
      ...text(alice, bob, "", "2026-09-25T10:00:00Z"),
      contentType: "SNAP_PHOTO",
      content: null,
      mediaKey: "messages/x",
      scheduledPurgeAt: new Date("2026-10-25T10:00:00Z"),
    });

    const results = await Promise.all(
      Array.from({ length: 5 }, () => messages.markOpenedIfUnopened(message.id, new Date())),
    );

    expect(results.filter(Boolean)).toHaveLength(1);
  });

  it("marque comme lus uniquement les Chats texte reçus de l'expéditeur donné", async () => {
    await messages.create(text(alice, bob, "1", "2026-09-25T10:00:00Z"));
    await messages.create(text(alice, bob, "2", "2026-09-25T10:01:00Z"));
    await messages.create(text(carol, bob, "autre", "2026-09-25T10:02:00Z"));

    expect(await messages.markTextsRead(bob.id, alice.id, new Date())).toBe(2);
    expect(await messages.markTextsRead(bob.id, alice.id, new Date())).toBe(0);
  });

  it("pagination de l'historique dans les deux sens", async () => {
    await messages.create(text(alice, bob, "1", "2026-09-25T10:00:00Z"));
    await messages.create(text(bob, alice, "2", "2026-09-25T10:01:00Z"));
    await messages.create(text(alice, bob, "3", "2026-09-25T10:02:00Z"));

    const page = await messages.listBetween(bob.id, alice.id, { before: new Date("2026-09-25T10:02:00Z"), limit: 10 });

    expect(page.map((m) => m.content)).toEqual(["2", "1"]);
  });
});

import { randomUUID } from "node:crypto";
import { UniqueViolationError } from "../../../shared/domain/errors";
import { PrismaService } from "../../../shared/infrastructure/prisma/prisma.service";
import { FixedClock, RecordingFriendshipNotifier } from "../../../test/fakes";
import type { UserAccount } from "../../identity/domain/user";
import { PrismaUserRepository } from "../../identity/infrastructure/prisma-user.repository";
import { FriendshipUseCases } from "../application/friendship-use-cases";
import { PrismaFriendshipRepository } from "./prisma-friendship.repository";

// Tests d'intégration contre un vrai Postgres (docker compose up -d, migrations appliquées).
// Lancés par `npm run test:integration`, jamais par `npm test`.
describe("Dépôts Prisma (Postgres réel)", () => {
  const prisma = new PrismaService();
  const users = new PrismaUserRepository(prisma);
  const friendships = new PrismaFriendshipRepository(prisma);
  const useCases = new FriendshipUseCases(friendships, users, new RecordingFriendshipNotifier(), new FixedClock());

  const newUser = (username: string) =>
    users.create({ username, firstName: "Test", firebaseUid: `uid-${randomUUID()}`, email: null, phoneNumber: null });

  let alice: UserAccount;
  let bob: UserAccount;

  beforeEach(async () => {
    await prisma.friendship.deleteMany();
    await prisma.user.deleteMany();
    alice = await newUser("alice");
    bob = await newUser("bob");
  });

  afterAll(() => prisma.$disconnect());

  it("traduit une violation d'unicité du pseudo en UniqueViolationError('username')", async () => {
    await expect(newUser("alice")).rejects.toEqual(new UniqueViolationError("username"));
  });

  it("la paire triée empêche A→B et B→A de coexister", async () => {
    const base = { status: "PENDING" as const, blockedBy: null, requestedAt: new Date(), respondedAt: null };
    await friendships.create({ ...base, requesterId: alice.id, addresseeId: bob.id });

    await expect(friendships.create({ ...base, requesterId: bob.id, addresseeId: alice.id })).rejects.toEqual(
      new UniqueViolationError("pair"),
    );
  });

  it("les contraintes CHECK refusent l'auto-ajout et une paire incohérente", async () => {
    const [low, high] = [alice.id, bob.id].sort();
    await expect(
      prisma.$executeRaw`INSERT INTO "Friendship" (id, "requesterId", "addresseeId", "pairLow", "pairHigh")
        VALUES (${randomUUID()}, ${alice.id}, ${alice.id}, ${alice.id}, ${alice.id})`,
    ).rejects.toThrow(/Friendship_not_self_check/);
    await expect(
      prisma.$executeRaw`INSERT INTO "Friendship" (id, "requesterId", "addresseeId", "pairLow", "pairHigh")
        VALUES (${randomUUID()}, ${alice.id}, ${bob.id}, ${high}, ${low})`,
    ).rejects.toThrow(/Friendship_sorted_pair_check/);
  });

  it("la mise à jour conditionnelle échoue si le statut a changé entre-temps", async () => {
    const { friendshipId } = await useCases.sendRequest(alice, "bob");
    await useCases.accept(bob, friendshipId);

    await expect(friendships.transition(friendshipId, "PENDING", { status: "DECLINED" })).resolves.toBeNull();
  });

  it("parcours refus → relance inversée → acceptation → blocage → déblocage", async () => {
    const { friendshipId } = await useCases.sendRequest(alice, "bob");
    await useCases.decline(bob, friendshipId);
    await useCases.sendRequest(bob, "alice"); // relance par l'autre : sens inversé (CHECK respectée)
    await useCases.accept(alice, friendshipId);
    expect((await useCases.listFriends(alice)).map((f) => f.user.username)).toEqual(["bob"]);

    await useCases.block(bob, alice.id);
    expect((await useCases.listBlocked(bob)).map((f) => f.user.username)).toEqual(["alice"]);
    expect(await useCases.listFriends(alice)).toEqual([]);

    await useCases.unblock(bob, alice.id);
    expect(await friendships.findByPair(alice.id, bob.id)).toBeNull();
  });

  it("les listes et la recherche ignorent un compte en cours de suppression", async () => {
    const { friendshipId } = await useCases.sendRequest(alice, "bob");
    await useCases.accept(bob, friendshipId);
    await prisma.user.update({ where: { id: bob.id }, data: { deletionRequestedAt: new Date() } });

    expect(await useCases.listFriends(alice)).toEqual([]);
    expect(await users.findActiveByUsername("bob")).toBeNull();
    expect(await users.findByFirebaseUidIncludingDeleted(bob.firebaseUid)).not.toBeNull();
  });
});

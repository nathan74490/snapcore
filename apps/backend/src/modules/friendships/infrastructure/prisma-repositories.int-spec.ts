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
    users.create({ username, firstName: "Test", lastName: null, birthday: null, firebaseUid: `uid-${randomUUID()}`, email: null, phoneNumber: null });

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

  it("nom de famille et date de naissance (colonne DATE) relus à l'identique, quel que soit le fuseau", async () => {
    const created = await users.create({
      username: "carol",
      firstName: "Carol",
      lastName: "Martin",
      birthday: new Date("2004-05-12T00:00:00Z"),
      firebaseUid: `uid-${randomUUID()}`,
      email: null,
      phoneNumber: null,
    });

    const read = await users.findActiveById(created.id);
    expect(read).toMatchObject({ lastName: "Martin", birthday: new Date("2004-05-12T00:00:00Z") });
    const cleared = await users.updateProfile(created.id, { lastName: null });
    expect(cleared.lastName).toBeNull();
  });

  it("un pseudo reste pris tant que la ligne existe, même supprimée ou suspendue (ADR-029)", async () => {
    await prisma.user.update({ where: { id: bob.id }, data: { deletionRequestedAt: new Date() } });
    await prisma.user.update({ where: { id: alice.id }, data: { moderationStatus: "SUSPENDED" } });

    expect(await users.isUsernameTaken("bob")).toBe(true);
    expect(await users.isUsernameTaken("alice")).toBe(true);
    expect(await users.isUsernameTaken("carol")).toBe(false);
  });

  it("correspondance des contacts : 9 derniers chiffres, comptes actifs, blocages exclus (ADR-029)", async () => {
    const withPhone = async (username: string, phoneNumber: string) => {
      const user = await newUser(username);
      return users.updateProfile(user.id, { phoneNumber });
    };
    await users.updateProfile(alice.id, { phoneNumber: "+33700000001" });
    await users.updateProfile(bob.id, { phoneNumber: "+33612345678" });
    const carol = await withPhone("carol", "0700000003");
    const dave = await withPhone("dave", "+33700000004");
    const eve = await withPhone("eve", "+33700000005");
    const frank = await withPhone("frank", "+447700000006");
    await withPhone("court", "12345678"); // moins de 9 chiffres : jamais trouvé
    await prisma.user.update({ where: { id: dave.id }, data: { deletionRequestedAt: new Date() } });
    await prisma.user.update({ where: { id: eve.id }, data: { moderationStatus: "SUSPENDED" } });
    await useCases.block(carol, alice.id);
    await useCases.sendRequest(alice, "frank");

    const found = await users.findActiveByPhoneNumberSuffixes(["612345678", "700000003", "700000004", "700000005"]);
    expect(found.map((u) => u.username).sort()).toEqual(["bob", "carol"]);

    const matches = await useCases.matchContacts(alice, [
      "+33 7 00 00 00 01", // moi-même
      "06 12 34 56 78",
      "07 00 00 00 03", // m'a bloquée
      "07 00 00 00 04", // suppression demandée
      "07 00 00 00 05", // suspendu
      "+44 7700 000006",
      "12345678",
    ]);
    expect(matches).toEqual([
      { phoneNumber: "06 12 34 56 78", user: { id: bob.id, username: "bob", firstName: "Test" }, relation: "NONE" },
      { phoneNumber: "+44 7700 000006", user: { id: frank.id, username: "frank", firstName: "Test" }, relation: "OUTGOING_REQUEST" },
    ]);
  });
});

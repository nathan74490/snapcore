import { ConflictError, NotFoundError, UniqueViolationError, ValidationError } from "../../../shared/domain/errors";
import { FixedClock, RecordingFriendshipNotifier } from "../../../test/fakes";
import { InMemoryFriendshipRepository } from "../../../test/in-memory-friendship.repository";
import { InMemoryUserRepository } from "../../../test/in-memory-user.repository";
import type { UserAccount } from "../../identity/domain/user";
import { FriendshipUseCases } from "./friendship-use-cases";

describe("FriendshipUseCases", () => {
  let users: InMemoryUserRepository;
  let friendships: InMemoryFriendshipRepository;
  let notifier: RecordingFriendshipNotifier;
  let useCases: FriendshipUseCases;
  let alice: UserAccount;
  let bob: UserAccount;

  beforeEach(async () => {
    users = new InMemoryUserRepository();
    friendships = new InMemoryFriendshipRepository(users);
    notifier = new RecordingFriendshipNotifier();
    useCases = new FriendshipUseCases(friendships, users, notifier, new FixedClock());
    alice = await users.seed("alice");
    bob = await users.seed("bob");
  });

  const pair = () => friendships.findByPair(alice.id, bob.id);

  describe("demande d'ami", () => {
    it("crée une demande PENDING et notifie le destinataire", async () => {
      const item = await useCases.sendRequest(alice, "Bob");

      expect(item).toMatchObject({ status: "PENDING", user: { id: bob.id, username: "bob" } });
      expect(notifier.events).toEqual([{ type: "received", to: bob.id, friendshipId: item.friendshipId }]);
    });

    it("refuse de s'ajouter soi-même", async () => {
      await expect(useCases.sendRequest(alice, "alice")).rejects.toThrow(ValidationError);
    });

    it("renvoie une erreur 'introuvable' pour un compte en cours de suppression", async () => {
      users.rows.get(bob.id)!.deletionRequestedAt = new Date();
      await expect(useCases.sendRequest(alice, "bob")).rejects.toThrow(NotFoundError);
    });

    it("est idempotente si la même personne redemande", async () => {
      const first = await useCases.sendRequest(alice, "bob");
      const again = await useCases.sendRequest(alice, "bob");

      expect(again.friendshipId).toBe(first.friendshipId);
      expect(friendships.rows.size).toBe(1);
    });

    it("accepte la demande en attente si l'autre personne demande à son tour", async () => {
      await useCases.sendRequest(alice, "bob");

      const item = await useCases.sendRequest(bob, "alice");

      expect(item.status).toBe("ACCEPTED");
      expect(notifier.events.at(-1)).toMatchObject({ type: "accepted", to: alice.id });
    });

    it("refuse une demande entre amis déjà acceptés", async () => {
      const { friendshipId } = await useCases.sendRequest(alice, "bob");
      await useCases.accept(bob, friendshipId);

      await expect(useCases.sendRequest(alice, "bob")).rejects.toThrow(ConflictError);
    });

    it("après un refus, réutilise la ligne et inverse le sens si c'est l'autre qui relance", async () => {
      const { friendshipId } = await useCases.sendRequest(alice, "bob");
      await useCases.decline(bob, friendshipId);

      const item = await useCases.sendRequest(bob, "alice");

      expect(item.friendshipId).toBe(friendshipId);
      expect(await pair()).toMatchObject({ status: "PENDING", requesterId: bob.id, addresseeId: alice.id, respondedAt: null });
      expect(friendships.rows.size).toBe(1);
    });

    it("sur une paire BLOCKED, échoue comme un pseudo introuvable sans modifier la ligne", async () => {
      await useCases.block(bob, alice.id);

      await expect(useCases.sendRequest(alice, "bob")).rejects.toThrow(new NotFoundError("Utilisateur introuvable"));
      await expect(useCases.sendRequest(alice, "inconnu")).rejects.toThrow(new NotFoundError("Utilisateur introuvable"));
      expect(await pair()).toMatchObject({ status: "BLOCKED", blockedBy: bob.id });
    });

    it("rejoue les règles si l'autre a créé la paire en même temps (course)", async () => {
      const realCreate = friendships.create.bind(friendships);
      jest.spyOn(friendships, "create").mockImplementationOnce(async () => {
        // Bob crée sa demande juste avant l'insertion d'Alice.
        await realCreate({
          requesterId: bob.id,
          addresseeId: alice.id,
          status: "PENDING",
          blockedBy: null,
          requestedAt: new Date(),
          respondedAt: null,
        });
        throw new UniqueViolationError("pair");
      });

      const item = await useCases.sendRequest(alice, "bob");

      expect(item.status).toBe("ACCEPTED");
      expect(friendships.rows.size).toBe(1);
    });
  });

  describe("réponse à une demande", () => {
    it("seul le destinataire peut accepter", async () => {
      const { friendshipId } = await useCases.sendRequest(alice, "bob");

      await expect(useCases.accept(alice, friendshipId)).rejects.toThrow(NotFoundError);
      await expect(useCases.accept(bob, friendshipId)).resolves.toMatchObject({ status: "ACCEPTED" });
    });

    it("une demande déjà traitée ne peut plus être refusée", async () => {
      const { friendshipId } = await useCases.sendRequest(alice, "bob");
      await useCases.accept(bob, friendshipId);

      await expect(useCases.decline(bob, friendshipId)).rejects.toThrow(NotFoundError);
    });
  });

  describe("retrait d'ami", () => {
    it("supprime la ligne ACCEPTED : chacun peut redemander normalement", async () => {
      const { friendshipId } = await useCases.sendRequest(alice, "bob");
      await useCases.accept(bob, friendshipId);

      await useCases.removeFriend(bob, alice.id);

      expect(await pair()).toBeNull();
      await expect(useCases.sendRequest(bob, "alice")).resolves.toMatchObject({ status: "PENDING" });
    });

    it("ne supprime pas une demande en attente", async () => {
      await useCases.sendRequest(alice, "bob");
      await expect(useCases.removeFriend(alice, bob.id)).rejects.toThrow(NotFoundError);
    });
  });

  describe("blocage", () => {
    it("bloquer un inconnu crée directement une ligne BLOCKED", async () => {
      await useCases.block(alice, bob.id);
      expect(await pair()).toMatchObject({ status: "BLOCKED", blockedBy: alice.id });
    });

    it("bloquer un ami fait passer la ligne à BLOCKED", async () => {
      const { friendshipId } = await useCases.sendRequest(alice, "bob");
      await useCases.accept(bob, friendshipId);

      await useCases.block(bob, alice.id);

      expect(await pair()).toMatchObject({ status: "BLOCKED", blockedBy: bob.id });
    });

    it("le blocage mutuel est un no-op apparent : blockedBy reste le premier bloqueur", async () => {
      await useCases.block(alice, bob.id);
      await expect(useCases.block(bob, alice.id)).resolves.toBeUndefined();
      expect(await pair()).toMatchObject({ blockedBy: alice.id });
    });

    it("masque l'utilisateur bloqué dans la recherche, dans les deux sens", async () => {
      await useCases.block(alice, bob.id);

      await expect(useCases.searchByUsername(alice, "bob")).rejects.toThrow(NotFoundError);
      await expect(useCases.searchByUsername(bob, "alice")).rejects.toThrow(NotFoundError);
    });

    it("seul le bloqueur peut débloquer", async () => {
      await useCases.block(alice, bob.id);

      await expect(useCases.unblock(bob, alice.id)).rejects.toThrow(NotFoundError);
      await useCases.unblock(alice, bob.id);
      expect(await pair()).toBeNull();
    });

    it("débloquer un ex-ami ne restaure pas l'amitié", async () => {
      const { friendshipId } = await useCases.sendRequest(alice, "bob");
      await useCases.accept(bob, friendshipId);
      await useCases.block(alice, bob.id);

      await useCases.unblock(alice, bob.id);

      expect(await useCases.listFriends(alice)).toEqual([]);
      await expect(useCases.searchByUsername(alice, "bob")).resolves.toMatchObject({ relation: "NONE" });
    });

    it("liste les utilisateurs bloqués par moi uniquement", async () => {
      const carol = await users.seed("carol");
      await useCases.block(alice, bob.id);
      await useCases.block(carol, alice.id);

      const blocked = await useCases.listBlocked(alice);

      expect(blocked.map((item) => item.user.id)).toEqual([bob.id]);
    });
  });

  describe("listes et recherche", () => {
    it("n'affiche pas un ami dont le compte est en cours de suppression", async () => {
      const { friendshipId } = await useCases.sendRequest(alice, "bob");
      await useCases.accept(bob, friendshipId);
      users.rows.get(bob.id)!.deletionRequestedAt = new Date();

      expect(await useCases.listFriends(alice)).toEqual([]);
    });

    it("présente la relation vue par chacun", async () => {
      await useCases.sendRequest(alice, "bob");

      await expect(useCases.searchByUsername(alice, "BOB")).resolves.toMatchObject({ relation: "OUTGOING_REQUEST" });
      await expect(useCases.searchByUsername(bob, "alice")).resolves.toMatchObject({ relation: "INCOMING_REQUEST" });
    });

    it("ne révèle pas un refus au demandeur", async () => {
      const { friendshipId } = await useCases.sendRequest(alice, "bob");
      await useCases.decline(bob, friendshipId);

      await expect(useCases.searchByUsername(alice, "bob")).resolves.toMatchObject({ relation: "NONE" });
      expect(await useCases.listOutgoingRequests(alice)).toEqual([]);
    });
  });
});

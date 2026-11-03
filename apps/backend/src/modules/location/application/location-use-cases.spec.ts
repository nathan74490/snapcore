import { ValidationError } from "../../../shared/domain/errors";
import { FixedClock, RecordingFriendshipNotifier } from "../../../test/fakes";
import { InMemoryFriendshipRepository } from "../../../test/in-memory-friendship.repository";
import { InMemoryLocationSharingRepository, InMemoryLocationStore } from "../../../test/in-memory-location";
import { InMemoryUserRepository } from "../../../test/in-memory-user.repository";
import { FriendshipUseCases } from "../../friendships/application/friendship-use-cases";
import type { UserAccount } from "../../identity/domain/user";
import { LocationUseCases } from "./location-use-cases";
import { LocationVisibility } from "./location-visibility";

const HOUR = 60 * 60 * 1000;

describe("LocationUseCases (ADR-031)", () => {
  let clock: FixedClock;
  let users: InMemoryUserRepository;
  let friendshipRepo: InMemoryFriendshipRepository;
  let friendships: FriendshipUseCases;
  let sharing: InMemoryLocationSharingRepository;
  let store: InMemoryLocationStore;
  let events: Array<{ userId: string; event: string; properties?: Record<string, unknown> }>;
  let useCases: LocationUseCases;
  let alice: UserAccount;
  let bob: UserAccount;
  let carol: UserAccount;
  let dave: UserAccount;

  beforeEach(async () => {
    clock = new FixedClock();
    users = new InMemoryUserRepository();
    friendshipRepo = new InMemoryFriendshipRepository(users);
    friendships = new FriendshipUseCases(friendshipRepo, users, new RecordingFriendshipNotifier(), clock);
    sharing = new InMemoryLocationSharingRepository(users);
    store = new InMemoryLocationStore(() => clock.now());
    events = [];
    useCases = new LocationUseCases(sharing, store, friendshipRepo, new LocationVisibility(sharing), clock, {
      capture: (userId, event, properties) => events.push({ userId, event, properties }),
    });
    alice = await users.seed("alice");
    bob = await users.seed("bob");
    carol = await users.seed("carol");
    dave = await users.seed("dave");
    await befriend(alice, bob);
    await befriend(alice, carol);
  });

  async function befriend(a: UserAccount, b: UserAccount) {
    const { friendshipId } = await friendships.sendRequest(a, b.username);
    await friendships.accept(b, friendshipId);
  }

  const at = (latitude: number, longitude: number) => ({ latitude, longitude });
  const onMapOf = async (viewer: UserAccount) => (await useCases.friendsOnMap(viewer)).map((entry) => entry.user.username);

  describe("ma position", () => {
    it("mode fantôme par défaut : rien n'est stocké, et une position restante est effacée", async () => {
      await store.save(alice.id, { ...at(1, 1), updatedAt: clock.now() });
      await useCases.updateLocation(alice, at(45.9, 6.12));
      expect(store.entries.has(alice.id)).toBe(false);
    });

    it("hors mode fantôme : position brute et horodatage stockés", async () => {
      sharing.set(alice.id, "ALL_FRIENDS");
      await useCases.updateLocation(alice, at(45.899247, 6.129384));
      expect(store.entries.get(alice.id)).toEqual({ latitude: 45.899247, longitude: 6.129384, updatedAt: clock.now() });
    });

    it("refuse des coordonnées hors bornes", async () => {
      sharing.set(alice.id, "ALL_FRIENDS");
      await expect(useCases.updateLocation(alice, at(91, 0))).rejects.toThrow(ValidationError);
      await expect(useCases.updateLocation(alice, at(0, 181))).rejects.toThrow(ValidationError);
    });
  });

  describe("réglages de partage", () => {
    it("GHOST et liste vide par défaut", async () => {
      expect(await useCases.settings(alice)).toEqual({ mode: "GHOST", friendIds: [] });
    });

    it("liste dédupliquée, filtrée en silence aux amis ACCEPTED, et remplaçant l'ancienne", async () => {
      // dave n'est pas ami ; carol est bloquée ensuite ; un identifiant inconnu est ignoré.
      const saved = await useCases.updateSettings(alice, {
        mode: "FRIENDS_EXCEPT",
        friendIds: [bob.id, bob.id, dave.id, carol.id, "inconnu"],
      });
      expect(saved).toEqual({ mode: "FRIENDS_EXCEPT", friendIds: [bob.id, carol.id].sort() });

      const replaced = await useCases.updateSettings(alice, { mode: "ONLY_FRIENDS", friendIds: [carol.id] });
      expect(replaced).toEqual({ mode: "ONLY_FRIENDS", friendIds: [carol.id] });
      expect(await useCases.settings(alice)).toEqual(replaced);
    });

    it("la liste est conservée en passant à un autre mode", async () => {
      await useCases.updateSettings(alice, { mode: "ONLY_FRIENDS", friendIds: [bob.id] });
      expect(await useCases.updateSettings(alice, { mode: "ALL_FRIENDS", friendIds: [bob.id] })).toEqual({
        mode: "ALL_FRIENDS",
        friendIds: [bob.id],
      });
    });

    it("refuse plus de 2000 amis distincts", async () => {
      const ids = Array.from({ length: 2001 }, (_, i) => `id-${i}`);
      await expect(useCases.updateSettings(alice, { mode: "ONLY_FRIENDS", friendIds: ids })).rejects.toThrow(ValidationError);
    });

    it("passer en mode fantôme efface immédiatement la position ; événement analytics à chaque changement", async () => {
      await useCases.updateSettings(alice, { mode: "ALL_FRIENDS", friendIds: [] });
      await useCases.updateLocation(alice, at(45.9, 6.12));
      expect(store.entries.has(alice.id)).toBe(true);

      await useCases.updateSettings(alice, { mode: "GHOST", friendIds: [] });
      expect(store.entries.has(alice.id)).toBe(false);
      expect(events).toEqual([
        { userId: alice.id, event: "location_sharing_changed", properties: { mode: "ALL_FRIENDS" } },
        { userId: alice.id, event: "location_sharing_changed", properties: { mode: "GHOST" } },
      ]);
    });
  });

  describe("carte des amis", () => {
    beforeEach(async () => {
      for (const user of [alice, bob, carol, dave]) {
        await store.save(user.id, { ...at(45, 6), updatedAt: clock.now() });
      }
    });

    it("selon le mode de chaque ami, jamais les non-amis ni moi-même", async () => {
      sharing.set(alice.id, "ALL_FRIENDS");
      sharing.set(dave.id, "ALL_FRIENDS"); // pas ami de bob
      expect(await onMapOf(bob)).toEqual(["alice"]);
      expect(await onMapOf(alice)).toEqual([]); // bob et carol en mode fantôme par défaut

      sharing.set(bob.id, "FRIENDS_EXCEPT", [alice.id]);
      sharing.set(carol.id, "ONLY_FRIENDS", [alice.id]);
      expect(await onMapOf(alice)).toEqual(["carol"]);
    });

    it("renvoie le profil public, la position et son horodatage ISO", async () => {
      sharing.set(alice.id, "ALL_FRIENDS");
      const [entry] = await useCases.friendsOnMap(bob);
      expect(entry).toEqual({
        user: { id: alice.id, username: "alice", firstName: "Alice" },
        latitude: 45,
        longitude: 6,
        updatedAt: clock.now().toISOString(),
      });
    });

    it("un ami disparaît 8 h après sa dernière mise à jour", async () => {
      sharing.set(alice.id, "ALL_FRIENDS");
      clock.current = new Date(clock.current.getTime() + 8 * HOUR - 1);
      expect(await onMapOf(bob)).toEqual(["alice"]);
      clock.current = new Date(clock.current.getTime() + 1);
      expect(await onMapOf(bob)).toEqual([]);
    });

    it("blocage, retrait d'ami, suspension ou suppression demandée retirent l'ami de la carte", async () => {
      sharing.set(alice.id, "ALL_FRIENDS");
      sharing.set(bob.id, "ALL_FRIENDS");
      expect(await onMapOf(carol)).toEqual(["alice"]);

      await friendships.block(carol, alice.id);
      expect(await onMapOf(carol)).toEqual([]);
      expect(await onMapOf(alice)).toEqual(["bob"]);

      users.rows.get(bob.id)!.moderationStatus = "SUSPENDED";
      expect(await onMapOf(alice)).toEqual([]);
      users.rows.get(bob.id)!.moderationStatus = "ACTIVE";
      users.rows.get(bob.id)!.deletionRequestedAt = clock.now();
      expect(await onMapOf(alice)).toEqual([]);
    });
  });
});

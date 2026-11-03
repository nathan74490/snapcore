import Redis from "ioredis";
import { randomUUID } from "node:crypto";
import { systemClock } from "../../../shared/domain/clock";
import { PrismaService } from "../../../shared/infrastructure/prisma/prisma.service";
import { PrismaFriendshipRepository } from "../../friendships/infrastructure/prisma-friendship.repository";
import type { UserAccount } from "../../identity/domain/user";
import { PrismaUserRepository } from "../../identity/infrastructure/prisma-user.repository";
import { PrismaStoryRepository } from "../../stories/infrastructure/prisma-story.repository";
import { LocationUseCases } from "../application/location-use-cases";
import { LocationVisibility } from "../application/location-visibility";
import { LOCATION_TTL_SECONDS } from "../domain/location-sharing";
import { PrismaLocationSharingRepository } from "./prisma-location-sharing.repository";
import { locationKey, RedisLocationStore } from "./redis-location.store";

// Contre le Postgres et le Redis de docker compose (vide les tables).
describe("Snap Map (Postgres et Redis réels, ADR-031)", () => {
  const prisma = new PrismaService();
  const redis = new Redis(process.env.REDIS_URL ?? "redis://localhost:6379");
  const users = new PrismaUserRepository(prisma);
  const friendships = new PrismaFriendshipRepository(prisma);
  const sharing = new PrismaLocationSharingRepository(prisma);
  const store = new RedisLocationStore(redis);
  const useCases = new LocationUseCases(sharing, store, friendships, new LocationVisibility(sharing), systemClock);
  let alice: UserAccount;
  let bob: UserAccount;
  let carol: UserAccount;

  const newUser = (username: string) =>
    users.create({ username, firstName: "Test", lastName: null, birthday: null, firebaseUid: `uid-${randomUUID()}`, email: null, phoneNumber: null });

  const befriend = (a: UserAccount, b: UserAccount) =>
    friendships.create({ requesterId: a.id, addresseeId: b.id, status: "ACCEPTED", blockedBy: null, requestedAt: new Date(), respondedAt: new Date() });

  beforeEach(async () => {
    await prisma.storyItem.deleteMany();
    await prisma.message.deleteMany();
    await prisma.friendship.deleteMany();
    await prisma.user.deleteMany();
    alice = await newUser("alice");
    bob = await newUser("bob");
    carol = await newUser("carol");
    await redis.del(locationKey(alice.id), locationKey(bob.id), locationKey(carol.id));
  });

  // Redis est partagé avec le serveur de dev : aucune clé de test ne doit y rester (certaines n'ont pas de TTL).
  afterEach(async () => {
    await redis.del(locationKey(alice.id), locationKey(bob.id), locationKey(carol.id));
  });

  afterAll(async () => {
    await prisma.$disconnect();
    await redis.quit();
  });

  it("réglages : GHOST par défaut, remplacement de la liste en transaction, cascade à la suppression d'un ami", async () => {
    expect(await sharing.findSettings(alice.id)).toEqual({ mode: "GHOST", friendIds: [] });

    await sharing.replaceSettings(alice.id, { mode: "FRIENDS_EXCEPT", friendIds: [bob.id, carol.id] });
    const replaced = await sharing.replaceSettings(alice.id, { mode: "ONLY_FRIENDS", friendIds: [carol.id] });
    expect(replaced).toEqual({ mode: "ONLY_FRIENDS", friendIds: [carol.id] });
    expect(await sharing.findMode(alice.id)).toBe("ONLY_FRIENDS");

    const owners = await sharing.findOwners(carol.id, [alice.id, bob.id, randomUUID()]);
    expect(owners.get(alice.id)).toEqual({ deletionRequestedAt: null, moderationStatus: "ACTIVE", locationSharing: "ONLY_FRIENDS", viewerListed: true });
    expect(owners.get(bob.id)).toMatchObject({ locationSharing: "GHOST", viewerListed: false });
    expect(owners.size).toBe(2);

    await prisma.user.delete({ where: { id: carol.id } });
    expect(await sharing.findSettings(alice.id)).toEqual({ mode: "ONLY_FRIENDS", friendIds: [] });
  });

  it("position Redis : JSON { latitude, longitude, updatedAt } sous location:<id> avec un TTL de 8 h, lecture groupée", async () => {
    const updatedAt = new Date("2026-11-04T08:00:00.000Z");
    await store.save(alice.id, { latitude: 45.899247, longitude: 6.129384, updatedAt });
    await store.save(bob.id, { latitude: -33.8688, longitude: 151.2093, updatedAt });

    expect(JSON.parse((await redis.get(locationKey(alice.id)))!)).toEqual({
      latitude: 45.899247,
      longitude: 6.129384,
      updatedAt: "2026-11-04T08:00:00.000Z",
    });
    const ttl = await redis.ttl(locationKey(alice.id));
    expect(ttl).toBeGreaterThan(LOCATION_TTL_SECONDS - 10);
    expect(ttl).toBeLessThanOrEqual(LOCATION_TTL_SECONDS);

    await redis.set(locationKey(carol.id), "pas du json");
    const found = await store.findMany([alice.id, bob.id, carol.id, randomUUID()]);
    expect([...found.keys()]).toEqual([alice.id, bob.id]);
    expect(found.get(bob.id)).toEqual({ latitude: -33.8688, longitude: 151.2093, updatedAt });

    await store.remove(alice.id);
    expect(await redis.exists(locationKey(alice.id))).toBe(0);
  });

  it("de bout en bout : carte des amis selon la règle, position effacée en passant en mode fantôme", async () => {
    await befriend(alice, bob);
    await befriend(carol, alice);
    await useCases.updateSettings(alice, { mode: "FRIENDS_EXCEPT", friendIds: [carol.id] });
    await useCases.updateLocation(alice, { latitude: 45.9, longitude: 6.12 });

    expect((await useCases.friendsOnMap(bob)).map((entry) => entry.user.id)).toEqual([alice.id]);
    expect(await useCases.friendsOnMap(carol)).toEqual([]);

    await useCases.updateSettings(alice, { mode: "GHOST", friendIds: [carol.id] });
    expect(await redis.exists(locationKey(alice.id))).toBe(0);
    expect(await useCases.settings(alice)).toEqual({ mode: "GHOST", friendIds: [carol.id] }); // liste conservée
  });

  it("élément de story : latitude et longitude relues telles quelles, null sans position", async () => {
    const stories = new PrismaStoryRepository(prisma);
    const make = (latitude: number | null, longitude: number | null) => {
      const id = randomUUID();
      const now = Date.now();
      return stories.create({
        id,
        authorId: alice.id,
        mediaKey: `stories/${id}`,
        contentType: "PHOTO",
        thumbnailKey: null,
        latitude,
        longitude,
        createdAt: new Date(now),
        expiresAt: new Date(now + 60_000),
      });
    };
    const located = await make(45.899, 6.129);
    const plain = await make(null, null);

    expect(await stories.findById(located.id)).toMatchObject({ latitude: 45.899, longitude: 6.129 });
    expect(await stories.findById(plain.id)).toMatchObject({ latitude: null, longitude: null });
  });
});

import { randomUUID } from "node:crypto";
import { FakeIdentityProvider, FixedClock, RecordingAnalytics } from "../../../test/fakes";
import { InMemoryAccountDeletionRepository, RecordingDeletionQueue } from "../../../test/in-memory-account-deletion";
import { InMemoryFriendshipRepository } from "../../../test/in-memory-friendship.repository";
import { InMemoryGroupRepository } from "../../../test/in-memory-groups";
import { InMemoryLocationStore } from "../../../test/in-memory-location";
import { InMemoryObjectStorage, silentLogger } from "../../../test/in-memory-media";
import { InMemoryMessageRepository } from "../../../test/in-memory-messages";
import { RecordingRealtime } from "../../../test/in-memory-notifications";
import { InMemoryStoryRepository } from "../../../test/in-memory-stories";
import { InMemoryUserRepository } from "../../../test/in-memory-user.repository";
import type { UserAccount } from "../../identity/domain/user";
import type { StoredObject } from "../../media/application/ports/object-storage.port";
import { PublicMediaUrls } from "../../media/domain/public-media-urls";
import { AccountDeletionUseCases } from "./account-deletion-use-cases";
import type { DeletionJobContext, SubscriptionCanceller } from "./ports";

describe("AccountDeletionUseCases (ADR-019)", () => {
  const urls = new PublicMediaUrls("https://cdn.snapcore.test");
  let users: InMemoryUserRepository;
  let messages: InMemoryMessageRepository;
  let stories: InMemoryStoryRepository;
  let friendships: InMemoryFriendshipRepository;
  let groups: InMemoryGroupRepository;
  let accounts: InMemoryAccountDeletionRepository;
  let queue: RecordingDeletionQueue;
  let identity: FakeIdentityProvider;
  let storage: InMemoryObjectStorage;
  let realtime: RecordingRealtime;
  let locations: InMemoryLocationStore;
  let steps: string[];
  let analytics: RecordingAnalytics;
  let useCases: AccountDeletionUseCases;
  let alice: UserAccount;
  let bob: UserAccount;

  beforeEach(async () => {
    users = new InMemoryUserRepository();
    messages = new InMemoryMessageRepository();
    stories = new InMemoryStoryRepository();
    friendships = new InMemoryFriendshipRepository(users);
    groups = new InMemoryGroupRepository(users);
    accounts = new InMemoryAccountDeletionRepository(users, messages, stories, friendships, groups);
    queue = new RecordingDeletionQueue();
    identity = new FakeIdentityProvider();
    storage = new InMemoryObjectStorage();
    realtime = new RecordingRealtime();
    locations = new InMemoryLocationStore();
    steps = [];
    analytics = new RecordingAnalytics();
    const subscriptions: SubscriptionCanceller = { cancelFor: async () => void steps.push("abonnement") };
    const deleteAccount = identity.deleteAccount.bind(identity);
    identity.deleteAccount = async (uid) => {
      steps.push("firebase");
      await deleteAccount(uid);
    };
    useCases = new AccountDeletionUseCases(
      accounts,
      queue,
      identity,
      subscriptions,
      storage,
      urls,
      realtime,
      new FixedClock(),
      silentLogger,
      locations,
      analytics,
    );
    alice = await users.seed("alice", { profilePhotoUrl: urls.urlFor("profiles/alice/photo") });
    bob = await users.seed("bob");
  });

  // Données d'Alice : un Snap envoyé, un Snap reçu (déjà purgé), un Chat texte, une story avec
  // vignette (ADR-026), une story sans vignette, sa photo.
  async function seedAliceData() {
    const snap = async (from: UserAccount, to: UserAccount, mediaKey: string | null) => {
      const id = randomUUID();
      if (mediaKey) storage.simulateUpload(mediaKey, 10, "image/jpeg");
      await messages.create({
        id,
        senderId: from.id,
        recipientId: to.id,
        contentType: mediaKey ? "SNAP_PHOTO" : "TEXT",
        content: mediaKey ? null : "salut",
        mediaKey,
        createdAt: new Date(),
        scheduledPurgeAt: null,
      });
    };
    await snap(alice, bob, "messages/sent");
    await snap(bob, alice, "messages/received");
    await snap(alice, bob, null);
    for (const [id, thumbnailKey] of [["s1", "story-thumbnails/s1"], ["s2", null]] as const) {
      storage.simulateUpload(`stories/${id}`, 10, "image/jpeg");
      if (thumbnailKey) storage.simulateUpload(thumbnailKey, 10, "image/jpeg");
      await stories.create({
        id,
        authorId: alice.id,
        mediaKey: `stories/${id}`,
        contentType: "PHOTO",
        thumbnailKey,
        latitude: null,
        longitude: null,
        createdAt: new Date(),
        expiresAt: new Date(Date.now() + 1000),
      });
    }
  }

  function jobContext() {
    const context: DeletionJobContext & { persisted: StoredObject[][] } = {
      data: { userId: alice.id, firebaseUid: alice.firebaseUid },
      persisted: [],
      persistCollected: async (objects) => {
        steps.push("clés persistées");
        context.persisted.push(objects);
        context.data = { ...context.data, collected: objects };
      },
    };
    return context;
  }

  it("la demande bloque le compte immédiatement, met le job en file et coupe le temps réel", async () => {
    await useCases.request(alice);

    expect(users.rows.get(alice.id)!.deletionRequestedAt).not.toBeNull();
    expect(await users.findActiveById(alice.id)).toBeNull(); // inexistant pour tous
    expect(queue.enqueued).toEqual([{ userId: alice.id, firebaseUid: alice.firebaseUid }]);
    expect(realtime.disconnected).toEqual([alice.id]);
  });

  it("la demande efface aussi la position de la Snap Map, sans bloquer si Redis échoue (ADR-031)", async () => {
    await locations.save(alice.id, { latitude: 45.9, longitude: 6.12, updatedAt: new Date() });
    await useCases.request(alice);
    expect(locations.entries.has(alice.id)).toBe(false);

    locations.remove = async () => {
      throw new Error("Redis indisponible");
    };
    await expect(useCases.request(bob)).resolves.toBeUndefined();
    expect(queue.enqueued.map((job) => job.userId)).toContain(bob.id);
  });

  it("exécute les étapes dans l'ordre : abonnement → Firebase → base (clés avant suppression) → R2", async () => {
    await seedAliceData();
    await useCases.request(alice);
    const originalDelete = storage.delete.bind(storage);
    storage.delete = async (object) => {
      steps.push(`r2:${object.key}`);
      await originalDelete(object);
    };

    await expect(useCases.runJob(jobContext())).resolves.toEqual({ kind: "deleted", removedObjects: 6 });

    expect(steps).toEqual([
      "abonnement",
      "firebase",
      "clés persistées",
      "r2:messages/sent",
      "r2:messages/received",
      "r2:stories/s1",
      "r2:story-thumbnails/s1",
      "r2:stories/s2",
      "r2:profiles/alice/photo",
    ]);
    expect(users.rows.has(alice.id)).toBe(false);
    expect(messages.rows.size).toBe(0); // cascade, y compris les messages reçus
    expect(identity.deletedAccounts.has(alice.firebaseUid)).toBe(true);
  });

  it("relève aussi la photo de profil dans le bucket public", async () => {
    await useCases.request(alice);
    const context = jobContext();

    await useCases.runJob(context);

    expect(context.persisted[0]).toContainEqual({ area: "public", key: "profiles/alice/photo" });
  });

  it("refuse de supprimer un compte dont la suppression n'a pas été demandée", async () => {
    await expect(useCases.runJob(jobContext())).resolves.toEqual({ kind: "aborted", reason: "not-requested" });
    expect(users.rows.has(alice.id)).toBe(true);
    expect(steps).toEqual([]);
  });

  it("échec de Firebase : rien d'irréversible, le rejeu reprend depuis le début", async () => {
    await seedAliceData();
    await useCases.request(alice);
    identity.deleteAccount = async () => {
      throw new Error("Firebase indisponible");
    };

    await expect(useCases.runJob(jobContext())).rejects.toThrow("Firebase indisponible");
    expect(users.rows.has(alice.id)).toBe(true);
    expect(storage.has({ area: "private", key: "messages/sent" })).toBe(true);
  });

  it("transaction annulée après la persistance des clés : le rejeu relit la base, rien n'est perdu", async () => {
    await seedAliceData();
    await useCases.request(alice);
    accounts.failNextDelete = true;
    const context = jobContext();

    await expect(useCases.runJob(context)).rejects.toThrow("transaction annulée");
    expect(users.rows.has(alice.id)).toBe(true);

    await useCases.runJob(context);
    expect(users.rows.has(alice.id)).toBe(false);
    expect(storage.has({ area: "private", key: "messages/sent" })).toBe(false);
  });

  it("échec de R2 après la suppression en base : le rejeu supprime les objets grâce aux clés du job", async () => {
    await seedAliceData();
    await useCases.request(alice);
    const context = jobContext();
    storage.failNextDelete = true;

    await expect(useCases.runJob(context)).rejects.toThrow("R2 indisponible");
    expect(users.rows.has(alice.id)).toBe(false); // étape (c) déjà faite

    steps = [];
    await expect(useCases.runJob(context)).resolves.toEqual({ kind: "deleted", removedObjects: 6 });
    expect(steps).toEqual([]); // ni abonnement ni Firebase rejoués : la ligne n'existe plus
    expect([...storage.objects.keys()]).toEqual([]);
  });

  it("groupes (ADR-032) : ses messages de groupe et les groupes dont il était le dernier membre partent, médias compris", async () => {
    const carol = await users.seed("carol");
    const at = new Date("2026-11-01T10:00:00Z");
    await groups.create({ id: "shared", name: null, createdById: alice.id, createdAt: at }, [alice.id, bob.id, carol.id]);
    await groups.create({ id: "alone", name: "Vide", createdById: bob.id, createdAt: at }, [alice.id, bob.id]);
    const snap = async (id: string, groupId: string, sender: UserAccount) => {
      storage.simulateUpload(`group-messages/${id}`, 10, "image/jpeg");
      await groups.createMessage({
        id,
        groupId,
        senderId: sender.id,
        contentType: "SNAP_PHOTO",
        content: null,
        mediaKey: `group-messages/${id}`,
        createdAt: at,
        scheduledPurgeAt: new Date(at.getTime() + 1000),
      });
    };
    await snap("a1", "shared", alice);
    await snap("b1", "shared", bob);
    await snap("b2", "alone", bob);
    await groups.recordOpening("b1", alice.id, at);
    await groups.removeMember("alone", bob.id); // alice reste seule dans « alone »
    await useCases.request(alice);
    const context = jobContext();

    await useCases.runJob(context);

    expect(context.persisted[0]).toEqual(
      expect.arrayContaining([
        { area: "private", key: "group-messages/a1" },
        { area: "private", key: "group-messages/b2" },
      ]),
    );
    expect(context.persisted[0]).not.toContainEqual({ area: "private", key: "group-messages/b1" });
    expect(storage.has({ area: "private", key: "group-messages/b1" })).toBe(true);
    expect(storage.has({ area: "private", key: "group-messages/b2" })).toBe(false);
    expect([...groups.groups.keys()]).toEqual(["shared"]);
    expect(groups.groups.get("shared")!.createdById).toBeNull();
    expect(groups.members.map((m) => m.userId).sort()).toEqual([bob.id, carol.id].sort());
    expect([...groups.messages.keys()]).toEqual(["b1"]);
    expect(groups.openings).toEqual([]);
  });

  it("le rattrapage remet en file les comptes marqués dont la ligne existe encore", async () => {
    await accounts.markDeletionRequested(bob.id, new Date());

    await expect(useCases.sweepPending()).resolves.toEqual({ requeued: 1 });
    expect(queue.enqueued).toEqual([{ userId: bob.id, firebaseUid: bob.firebaseUid }]);
  });

  describe("analytics (ADR-008)", () => {
    it("account_deletion_requested à la demande, account_deleted une seule fois quand le job aboutit", async () => {
      await seedAliceData();
      await useCases.request(alice);
      const context = jobContext();
      storage.failNextDelete = true;

      await expect(useCases.runJob(context)).rejects.toThrow("R2 indisponible");
      expect(analytics.events).toEqual([{ userId: alice.id, event: "account_deletion_requested" }]);

      await useCases.runJob(context);
      expect(analytics.events).toEqual([
        { userId: alice.id, event: "account_deletion_requested" },
        { userId: alice.id, event: "account_deleted" },
      ]);
    });

    it("aucun account_deleted si le job est refusé (suppression non demandée)", async () => {
      await useCases.runJob(jobContext());
      expect(analytics.events).toEqual([]);
    });
  });
});

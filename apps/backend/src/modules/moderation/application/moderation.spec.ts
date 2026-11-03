import { randomUUID } from "node:crypto";
import { ConflictError, ForbiddenError, NotFoundError, ValidationError } from "../../../shared/domain/errors";
import { FixedClock, RecordingFriendshipNotifier } from "../../../test/fakes";
import { InMemoryFriendshipRepository } from "../../../test/in-memory-friendship.repository";
import { InMemoryLocationStore } from "../../../test/in-memory-location";
import { InMemoryObjectStorage, silentLogger } from "../../../test/in-memory-media";
import { InMemoryMessageRepository, RecordingPurgeScheduler } from "../../../test/in-memory-messages";
import { InMemoryModerationRepository } from "../../../test/in-memory-moderation";
import {
  InMemoryDeviceTokens,
  InMemoryPresence,
  RecordingPush,
  RecordingRealtime,
} from "../../../test/in-memory-notifications";
import { InMemoryStoryRepository, InMemoryStoryViews, RecordingStoryPurgeScheduler } from "../../../test/in-memory-stories";
import { InMemoryUserRepository } from "../../../test/in-memory-user.repository";
import { FriendshipUseCases } from "../../friendships/application/friendship-use-cases";
import { ResolveCurrentUser } from "../../identity/application/use-cases/resolve-current-user";
import type { UserAccount } from "../../identity/domain/user";
import { UserEventPublisher } from "../../notifications/application/user-event-publisher";
import { ModerationUseCases } from "./moderation-use-cases";
import { ReportUseCases } from "./report-use-cases";

const MOD = "mod-1";
const HOUR = 60 * 60 * 1000;

describe("Modération (ADR-021)", () => {
  let clock: FixedClock;
  let users: InMemoryUserRepository;
  let friendshipRepo: InMemoryFriendshipRepository;
  let friendships: FriendshipUseCases;
  let messages: InMemoryMessageRepository;
  let stories: InMemoryStoryRepository;
  let views: InMemoryStoryViews;
  let storage: InMemoryObjectStorage;
  let snapPurge: RecordingPurgeScheduler;
  let storyPurge: RecordingStoryPurgeScheduler;
  let realtime: RecordingRealtime;
  let locations: InMemoryLocationStore;
  let presence: InMemoryPresence;
  let repo: InMemoryModerationRepository;
  let moderation: ModerationUseCases;
  let reports: ReportUseCases;
  let alice: UserAccount;
  let bob: UserAccount;

  beforeEach(async () => {
    clock = new FixedClock();
    users = new InMemoryUserRepository();
    friendshipRepo = new InMemoryFriendshipRepository(users);
    friendships = new FriendshipUseCases(friendshipRepo, users, new RecordingFriendshipNotifier(), clock);
    messages = new InMemoryMessageRepository();
    stories = new InMemoryStoryRepository();
    views = new InMemoryStoryViews();
    storage = new InMemoryObjectStorage();
    snapPurge = new RecordingPurgeScheduler();
    storyPurge = new RecordingStoryPurgeScheduler();
    realtime = new RecordingRealtime();
    locations = new InMemoryLocationStore();
    presence = new InMemoryPresence();
    repo = new InMemoryModerationRepository(users);
    const events = new UserEventPublisher(presence, realtime, new RecordingPush(), new InMemoryDeviceTokens(), silentLogger);
    moderation = new ModerationUseCases(repo, messages, stories, views, storage, snapPurge, storyPurge, realtime, events, clock, silentLogger, locations);
    reports = new ReportUseCases(repo, users, messages, stories, friendshipRepo, clock);
    alice = await users.seed("alice");
    bob = await users.seed("bob");
    const { friendshipId } = await friendships.sendRequest(alice, "bob");
    await friendships.accept(bob, friendshipId);
  });

  async function message(from: UserAccount, to: UserAccount, kind: "TEXT" | "SNAP_PHOTO") {
    const id = randomUUID();
    const mediaKey = kind === "TEXT" ? null : `messages/${id}`;
    if (mediaKey) storage.simulateUpload(mediaKey, 10, "image/jpeg");
    await messages.create({
      id,
      senderId: from.id,
      recipientId: to.id,
      contentType: kind,
      content: kind === "TEXT" ? "contenu offensant" : null,
      mediaKey,
      createdAt: clock.now(),
      scheduledPurgeAt: null,
    });
    return id;
  }

  async function story(author: UserAccount, withThumbnail = false) {
    const id = randomUUID();
    storage.simulateUpload(`stories/${id}`, 10, "image/jpeg");
    if (withThumbnail) storage.simulateUpload(`story-thumbnails/${id}`, 10, "image/jpeg");
    await stories.create({
      id,
      authorId: author.id,
      mediaKey: `stories/${id}`,
      contentType: "PHOTO",
      thumbnailKey: withThumbnail ? `story-thumbnails/${id}` : null,
      latitude: null,
      longitude: null,
      createdAt: clock.now(),
      expiresAt: new Date(clock.now().getTime() + 24 * HOUR),
    });
    return id;
  }

  describe("suspension", () => {
    it("suspend : compte invisible pour les autres, connexion refusée (403), temps réel coupé", async () => {
      await locations.save(alice.id, { latitude: 45.9, longitude: 6.12, updatedAt: clock.now() });
      await moderation.suspendUser(alice.id, MOD, "Harcèlement");

      expect(locations.entries.has(alice.id)).toBe(false); // position de la Snap Map effacée (ADR-031)
      expect(await users.findActiveById(alice.id)).toBeNull();
      await expect(friendships.searchByUsername(bob, "alice")).rejects.toThrow(NotFoundError);
      await expect(new ResolveCurrentUser(users).execute(alice.firebaseUid)).rejects.toThrow(new ForbiddenError("Ce compte est suspendu"));
      expect(realtime.disconnected).toEqual([alice.id]);
      expect(repo.suspensions.get(alice.id)).toEqual({ moderatorId: MOD, reason: "Harcèlement", at: clock.now() });
    });

    it("réactivation : le compte redevient visible, amitié et données intactes", async () => {
      await moderation.suspendUser(alice.id, MOD, "Erreur");
      await moderation.reactivateUser(alice.id, MOD);

      expect(await users.findActiveById(alice.id)).not.toBeNull();
      expect(await friendships.listFriends(bob)).toHaveLength(1);
    });

    it("refuse de suspendre deux fois, un compte inconnu, ou sans raison", async () => {
      await moderation.suspendUser(alice.id, MOD, "x");
      await expect(moderation.suspendUser(alice.id, MOD, "x")).rejects.toThrow(ConflictError);
      await expect(moderation.suspendUser(randomUUID(), MOD, "x")).rejects.toThrow(NotFoundError);
      await expect(moderation.suspendUser(bob.id, MOD, "   ")).rejects.toThrow(ValidationError);
    });

    it("refuse un identifiant de modérateur inconnu", async () => {
      await expect(moderation.suspendUser(alice.id, "inconnu", "x")).rejects.toThrow(ValidationError);
    });
  });

  describe("rédaction d'un message", () => {
    it("texte : contenu vidé, ligne conservée, participants prévenus", async () => {
      const id = await message(alice, bob, "TEXT");
      await presence.touch(bob.id, "socket");

      await moderation.redactMessage(id, MOD, "Insulte");

      expect(messages.rows.get(id)).toMatchObject({ content: null, moderatedAt: clock.now() });
      expect(messages.moderations.get(id)).toMatchObject({ moderatorId: MOD, reason: "Insulte" });
      expect(realtime.emitted).toContainEqual({ userId: bob.id, event: { type: "message.moderated", messageId: id } });
    });

    it("snap : objet R2 supprimé immédiatement et job de purge retiré", async () => {
      const id = await message(alice, bob, "SNAP_PHOTO");

      await moderation.redactMessage(id, MOD, "Contenu choquant");

      expect(storage.has({ area: "private", key: `messages/${id}` })).toBe(false);
      expect(messages.rows.get(id)!.mediaKey).toBeNull();
      expect(snapPurge.cancelled).toEqual([id]);
    });

    it("refuse de rédiger deux fois", async () => {
      const id = await message(alice, bob, "TEXT");
      await moderation.redactMessage(id, MOD, "x");
      await expect(moderation.redactMessage(id, MOD, "x")).rejects.toThrow(ConflictError);
    });
  });

  it("suppression d'un élément de story : média, vignette, ligne et vues supprimés, job retiré", async () => {
    const id = await story(alice, true);
    await views.record(id, bob.id);

    await moderation.deleteStoryItem(id, MOD, "Violence");

    expect(stories.rows.has(id)).toBe(false);
    expect(storage.has({ area: "private", key: `stories/${id}` })).toBe(false);
    expect(storage.has({ area: "private", key: `story-thumbnails/${id}` })).toBe(false);
    expect(views.sets.has(id)).toBe(false);
    expect(storyPurge.cancelled).toEqual([id]);
  });

  describe("signalements", () => {
    it("un utilisateur signale un message reçu, un modérateur le résout une seule fois", async () => {
      const id = await message(alice, bob, "TEXT");
      const report = await reports.report(bob, { type: "MESSAGE", messageId: id }, "Il m'insulte");

      await moderation.closeReport(report.id, "RESOLVED", MOD);

      expect(repo.reports.get(report.id)).toMatchObject({ status: "RESOLVED", resolvedById: MOD, reason: "Il m'insulte" });
      await expect(moderation.closeReport(report.id, "DISMISSED", MOD)).rejects.toThrow(ConflictError);
    });

    it("on ne peut signaler que ce que l'on voit", async () => {
      const carol = await users.seed("carol");
      const privateMessage = await message(alice, bob, "TEXT");
      const aliceStory = await story(alice);

      // Carol n'est ni dans la conversation, ni amie d'Alice : même réponse que pour un contenu inexistant.
      await expect(reports.report(carol, { type: "MESSAGE", messageId: privateMessage }, "x")).rejects.toThrow(NotFoundError);
      await expect(reports.report(carol, { type: "STORY_ITEM", storyItemId: aliceStory }, "x")).rejects.toThrow(NotFoundError);
      await expect(reports.report(bob, { type: "STORY_ITEM", storyItemId: aliceStory }, "x")).resolves.toBeDefined();
      await expect(reports.report(alice, { type: "MESSAGE", messageId: privateMessage }, "x")).rejects.toThrow(ValidationError);
      await expect(reports.report(alice, { type: "USER", userId: alice.id }, "x")).rejects.toThrow(ValidationError);
    });
  });
});

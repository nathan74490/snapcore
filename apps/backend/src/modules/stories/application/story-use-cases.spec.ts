import { NotFoundError, ValidationError } from "../../../shared/domain/errors";
import { FixedClock, RecordingFriendshipNotifier } from "../../../test/fakes";
import { InMemoryFriendshipRepository } from "../../../test/in-memory-friendship.repository";
import { InMemoryObjectStorage, InMemoryUploadTicketStore, silentLogger } from "../../../test/in-memory-media";
import { InMemoryStoryRepository, InMemoryStoryViews, RecordingStoryPurgeScheduler } from "../../../test/in-memory-stories";
import { InMemoryUserRepository } from "../../../test/in-memory-user.repository";
import { FriendshipUseCases } from "../../friendships/application/friendship-use-cases";
import type { UserAccount } from "../../identity/domain/user";
import { RequestUpload } from "../../media/application/request-upload";
import { UploadPromoter } from "../../media/application/upload-promoter";
import { StoryUseCases } from "./story-use-cases";

const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;

describe("StoryUseCases (ADR-013)", () => {
  let clock: FixedClock;
  let users: InMemoryUserRepository;
  let friendships: FriendshipUseCases;
  let stories: InMemoryStoryRepository;
  let views: InMemoryStoryViews;
  let storage: InMemoryObjectStorage;
  let requestUpload: RequestUpload;
  let scheduler: RecordingStoryPurgeScheduler;
  let useCases: StoryUseCases;
  let alice: UserAccount;
  let bob: UserAccount;
  let carol: UserAccount;

  beforeEach(async () => {
    clock = new FixedClock();
    users = new InMemoryUserRepository();
    const friendshipRepo = new InMemoryFriendshipRepository(users);
    friendships = new FriendshipUseCases(friendshipRepo, users, new RecordingFriendshipNotifier(), clock);
    stories = new InMemoryStoryRepository();
    views = new InMemoryStoryViews();
    storage = new InMemoryObjectStorage();
    const tickets = new InMemoryUploadTicketStore();
    requestUpload = new RequestUpload(storage, tickets, clock);
    scheduler = new RecordingStoryPurgeScheduler();
    useCases = new StoryUseCases(
      stories,
      views,
      friendshipRepo,
      users,
      new UploadPromoter(storage, tickets, silentLogger),
      storage,
      scheduler,
      clock,
      silentLogger,
    );
    alice = await users.seed("alice");
    bob = await users.seed("bob");
    carol = await users.seed("carol");
    await befriend(alice, bob);
  });

  async function befriend(a: UserAccount, b: UserAccount) {
    const { friendshipId } = await friendships.sendRequest(a, b.username);
    await friendships.accept(b, friendshipId);
  }

  async function publish(author = alice, contentType: "PHOTO" | "VIDEO" = "PHOTO") {
    const purpose = contentType === "PHOTO" ? "STORY_PHOTO" : "STORY_VIDEO";
    const type = contentType === "PHOTO" ? "image/jpeg" : "video/mp4";
    const { uploadId } = await requestUpload.execute(author, { purpose, contentType: type, contentLength: 1000 });
    storage.simulateUpload(`tmp/${uploadId}`, 1000, type);
    return useCases.publish(author, { uploadId, contentType });
  }

  const advance = (ms: number) => {
    clock.current = new Date(clock.current.getTime() + ms);
  };

  it("publie un élément : média en stories/<id>, expiration à +24h, purge programmée", async () => {
    const item = await publish();

    expect(item.expiresAt).toBe(new Date(clock.current.getTime() + 24 * HOUR).toISOString());
    expect(storage.has({ area: "private", key: `stories/${item.id}` })).toBe(true);
    expect(scheduler.scheduled).toEqual([{ storyId: item.id, at: new Date(item.expiresAt) }]);
  });

  it("refuse un upload de snap privé pour une story", async () => {
    const { uploadId } = await requestUpload.execute(alice, { purpose: "SNAP_PHOTO", contentType: "image/jpeg", contentLength: 10 });
    storage.simulateUpload(`tmp/${uploadId}`, 10, "image/jpeg");
    await expect(useCases.publish(alice, { uploadId, contentType: "PHOTO" })).rejects.toThrow(ValidationError);
  });

  it("chaque élément expire 24h après SA publication, pas celle de la story", async () => {
    const first = await publish();
    advance(20 * HOUR);
    const second = await publish();
    advance(5 * HOUR); // le 1er a 25h, le 2e 5h

    const [entry] = await useCases.feed(bob);
    expect(entry.items.map((i) => i.id)).toEqual([second.id]);
    expect(first.id).not.toBe(second.id);
  });

  it("le fil ne montre que les amis ACCEPTED", async () => {
    await publish(alice);
    await publish(carol); // carol n'est amie avec personne

    const feed = await useCases.feed(bob);

    expect(feed.map((e) => e.author.id)).toEqual([alice.id]);
  });

  it("un élément expiré mais pas encore purgé n'est plus visible ni lisible", async () => {
    const item = await publish();
    advance(24 * HOUR + 1);

    expect(await useCases.feed(bob)).toEqual([]);
    await expect(useCases.view(bob, item.id)).rejects.toThrow(NotFoundError);
    expect(stories.rows.has(item.id)).toBe(true); // la purge n'est pas encore passée
  });

  it("voir un élément enregistre la vue ; il passe ensuite à 'vu' dans le fil", async () => {
    const item = await publish();

    const url = await useCases.view(bob, item.id);

    expect(url.url).toContain(`stories/${item.id}`);
    const [entry] = await useCases.feed(bob);
    expect(entry).toMatchObject({ hasUnviewed: false, items: [{ id: item.id, viewed: true }] });
  });

  it("les stories non vues passent en premier dans le fil", async () => {
    await befriend(bob, carol);
    const fromAlice = await publish(alice);
    advance(MINUTE);
    await publish(carol);
    await useCases.view(bob, fromAlice.id);

    const feed = await useCases.feed(bob);

    expect(feed.map((e) => e.author.username)).toEqual(["carol", "alice"]);
  });

  it("l'auteur voit le nombre de vues et la liste des viewers ; les autres non", async () => {
    const item = await publish();
    await useCases.view(bob, item.id);
    await useCases.view(alice, item.id); // l'auteur qui revoit sa story n'est pas compté

    expect(await useCases.mine(alice)).toMatchObject([{ id: item.id, viewCount: 1 }]);
    expect((await useCases.viewers(alice, item.id)).map((u) => u.username)).toEqual(["bob"]);
    await expect(useCases.viewers(bob, item.id)).rejects.toThrow(NotFoundError);
  });

  it.each([
    ["un inconnu", async () => undefined, () => carol],
    ["après un blocage", async () => friendships.block(alice, bob.id), () => bob],
    ["après un retrait d'ami", async () => friendships.removeFriend(bob, alice.id), () => bob],
  ])("refuse la lecture par %s, comme une story inexistante", async (_label, change, viewer) => {
    const item = await publish();
    await change();
    await expect(useCases.view(viewer(), item.id)).rejects.toThrow(new NotFoundError("Story introuvable"));
  });

  it("masque la story d'un auteur en cours de suppression de compte", async () => {
    const item = await publish();
    users.rows.get(alice.id)!.deletionRequestedAt = new Date();

    expect(await useCases.feed(bob)).toEqual([]);
    await expect(useCases.view(bob, item.id)).rejects.toThrow(NotFoundError);
  });

  it("l'auteur peut retirer un élément avant son expiration", async () => {
    const item = await publish();
    await useCases.view(bob, item.id);

    await useCases.remove(alice, item.id);

    expect(stories.rows.has(item.id)).toBe(false);
    expect(storage.has({ area: "private", key: `stories/${item.id}` })).toBe(false);
    expect(views.sets.has(item.id)).toBe(false);
    await expect(useCases.remove(bob, item.id)).rejects.toThrow(NotFoundError);
  });

  describe("purge", () => {
    it("à expiration : média R2, ligne et vues supprimés", async () => {
      const item = await publish();
      await useCases.view(bob, item.id);
      advance(24 * HOUR);

      await expect(useCases.runPurgeJob(item.id)).resolves.toEqual({ kind: "done" });

      expect(stories.rows.has(item.id)).toBe(false);
      expect(storage.has({ area: "private", key: `stories/${item.id}` })).toBe(false);
      expect(views.sets.has(item.id)).toBe(false);
    });

    it("un job exécuté en avance est re-différé à expiresAt", async () => {
      const item = await publish();
      await expect(useCases.runPurgeJob(item.id)).resolves.toEqual({ kind: "delay", until: new Date(item.expiresAt) });
    });

    it("un élément déjà retiré n'est pas une erreur", async () => {
      const item = await publish();
      await useCases.remove(alice, item.id);
      await expect(useCases.runPurgeJob(item.id)).resolves.toEqual({ kind: "done" });
    });

    it("le balayage relance les jobs des éléments expirés non purgés", async () => {
      const expired = await publish();
      advance(23 * HOUR);
      await publish();
      advance(2 * HOUR);

      await useCases.sweepOverdue();

      expect(scheduler.recovered).toEqual([expired.id]);
    });
  });
});

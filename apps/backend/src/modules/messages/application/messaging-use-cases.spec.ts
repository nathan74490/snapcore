import { ForbiddenError, GoneError, NotFoundError, ValidationError } from "../../../shared/domain/errors";
import { FixedClock, RecordingFriendshipNotifier } from "../../../test/fakes";
import { InMemoryFriendshipRepository } from "../../../test/in-memory-friendship.repository";
import { InMemoryObjectStorage, InMemoryUploadTicketStore, silentLogger } from "../../../test/in-memory-media";
import {
  InMemoryMessageRepository,
  RecordingMessageNotifier,
  RecordingPurgeScheduler,
} from "../../../test/in-memory-messages";
import { InMemoryUserRepository } from "../../../test/in-memory-user.repository";
import { FriendshipUseCases } from "../../friendships/application/friendship-use-cases";
import type { UserAccount } from "../../identity/domain/user";
import { RequestUpload } from "../../media/application/request-upload";
import { UploadPromoter } from "../../media/application/upload-promoter";
import { MessagingUseCases } from "./messaging-use-cases";

const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

describe("MessagingUseCases (ADR-009)", () => {
  let clock: FixedClock;
  let users: InMemoryUserRepository;
  let friendshipRepo: InMemoryFriendshipRepository;
  let friendships: FriendshipUseCases;
  let messages: InMemoryMessageRepository;
  let storage: InMemoryObjectStorage;
  let requestUpload: RequestUpload;
  let scheduler: RecordingPurgeScheduler;
  let notifier: RecordingMessageNotifier;
  let messaging: MessagingUseCases;
  let alice: UserAccount;
  let bob: UserAccount;

  beforeEach(async () => {
    clock = new FixedClock();
    users = new InMemoryUserRepository();
    friendshipRepo = new InMemoryFriendshipRepository(users);
    friendships = new FriendshipUseCases(friendshipRepo, users, new RecordingFriendshipNotifier(), clock);
    messages = new InMemoryMessageRepository();
    storage = new InMemoryObjectStorage();
    const tickets = new InMemoryUploadTicketStore();
    requestUpload = new RequestUpload(storage, tickets, clock);
    scheduler = new RecordingPurgeScheduler();
    notifier = new RecordingMessageNotifier();
    messaging = new MessagingUseCases(
      messages,
      friendshipRepo,
      users,
      new UploadPromoter(storage, tickets, silentLogger),
      storage,
      scheduler,
      notifier,
      clock,
      silentLogger,
    );
    alice = await users.seed("alice");
    bob = await users.seed("bob");
    await befriend(alice, bob);
  });

  async function befriend(a: UserAccount, b: UserAccount) {
    const { friendshipId } = await friendships.sendRequest(a, b.username);
    await friendships.accept(b, friendshipId);
  }

  async function sendSnap(from = alice, to = bob) {
    const { uploadId } = await requestUpload.execute(from, { purpose: "SNAP_PHOTO", contentType: "image/jpeg", contentLength: 5000 });
    storage.simulateUpload(`tmp/${uploadId}`, 5000, "image/jpeg");
    return messaging.sendSnap(from, to.id, { uploadId, contentType: "SNAP_PHOTO" });
  }

  const advance = (ms: number) => {
    clock.current = new Date(clock.current.getTime() + ms);
  };

  describe("envoi", () => {
    it("envoie un Chat texte permanent : ni média ni purge programmée", async () => {
      const view = await messaging.sendText(alice, bob.id, "  salut  ");

      expect(view).toMatchObject({ contentType: "TEXT", content: "salut", mediaPurged: false });
      expect(messages.rows.get(view.id)).toMatchObject({ mediaKey: null, scheduledPurgeAt: null });
      expect(scheduler.calls).toEqual([]);
      expect(notifier.events).toEqual([`received→${bob.id}`]);
    });

    it("refuse un message vide", async () => {
      await expect(messaging.sendText(alice, bob.id, "   ")).rejects.toThrow(ValidationError);
    });

    it("envoie un Snap : média copié en messages/<id>, purge programmée à +30 jours", async () => {
      const view = await sendSnap();

      const row = messages.rows.get(view.id)!;
      expect(row.mediaKey).toBe(`messages/${view.id}`);
      expect(storage.has({ area: "private", key: row.mediaKey! })).toBe(true);
      expect(row.scheduledPurgeAt).toEqual(new Date(clock.current.getTime() + 30 * DAY));
      expect(scheduler.calls).toEqual([{ op: "schedule", messageId: view.id, at: row.scheduledPurgeAt }]);
    });

    it("refuse un Snap vidéo construit à partir d'un upload photo", async () => {
      const { uploadId } = await requestUpload.execute(alice, { purpose: "SNAP_PHOTO", contentType: "image/jpeg", contentLength: 10 });
      storage.simulateUpload(`tmp/${uploadId}`, 10, "image/jpeg");

      await expect(messaging.sendSnap(alice, bob.id, { uploadId, contentType: "SNAP_PHOTO_VIDEO" })).rejects.toThrow(
        ValidationError,
      );
    });

    it.each([
      ["après un retrait d'ami", async () => friendships.removeFriend(alice, bob.id)],
      ["après un blocage par le destinataire", async () => friendships.block(bob, alice.id)],
      ["après un blocage par l'expéditeur", async () => friendships.block(alice, bob.id)],
    ])("refuse l'envoi %s, avec le même message dans tous les cas", async (_label, change) => {
      await change();
      await expect(messaging.sendText(alice, bob.id, "hello")).rejects.toThrow(
        new ForbiddenError("Vous devez être amis pour échanger des messages"),
      );
    });

    it("traite un destinataire en cours de suppression comme inexistant", async () => {
      users.rows.get(bob.id)!.deletionRequestedAt = new Date();
      await expect(messaging.sendText(alice, bob.id, "hello")).rejects.toThrow(NotFoundError);
    });
  });

  describe("vue unique d'un Snap", () => {
    it("1re ouverture : URL de ~1 min, openedAt renseigné, purge rapprochée à +24h", async () => {
      const { id } = await sendSnap();

      const view = await messaging.requestSnapView(bob, id);

      expect(view.url).toContain(`messages/${id}`);
      expect(view.expiresAt).toBe(new Date(clock.current.getTime() + MINUTE).toISOString());
      expect(messages.rows.get(id)!.openedAt).toEqual(clock.current);
      const purgeAt = new Date(clock.current.getTime() + DAY);
      expect(messages.rows.get(id)!.scheduledPurgeAt).toEqual(purgeAt);
      expect(scheduler.calls.at(-1)).toEqual({ op: "reschedule", messageId: id, at: purgeAt });
      expect(notifier.events).toContain(`opened→${alice.id}`);
    });

    it("l'expéditeur n'a jamais accès à la lecture de son propre Snap", async () => {
      const { id } = await sendSnap();
      await expect(messaging.requestSnapView(alice, id)).rejects.toThrow(NotFoundError);
    });

    it("peut redemander l'URL pendant la fenêtre de grâce de 5 min, sans ré-ouvrir", async () => {
      const { id } = await sendSnap();
      await messaging.requestSnapView(bob, id);
      const openedAt = messages.rows.get(id)!.openedAt;
      advance(4 * MINUTE);

      await expect(messaging.requestSnapView(bob, id)).resolves.toHaveProperty("url");
      expect(messages.rows.get(id)!.openedAt).toEqual(openedAt);
    });

    it("refuse une nouvelle lecture après la fenêtre de grâce sans abonnement", async () => {
      const { id } = await sendSnap();
      await messaging.requestSnapView(bob, id);
      advance(6 * MINUTE);

      await expect(messaging.requestSnapView(bob, id)).rejects.toThrow(new ForbiddenError("Ce Snap a déjà été vu"));
    });

    it("un destinataire SnapCore+ actif peut revoir le Snap autant qu'il veut", async () => {
      const { id } = await sendSnap();
      await messaging.requestSnapView(bob, id);
      advance(3 * HOUR);
      const premiumBob = { ...bob, subscriptionTier: "premium" as const, premiumUntil: new Date(clock.current.getTime() + DAY) };

      await expect(messaging.requestSnapView(premiumBob, id)).resolves.toHaveProperty("url");
    });

    it("un abonnement expiré ne lève plus la vue unique", async () => {
      const { id } = await sendSnap();
      await messaging.requestSnapView(bob, id);
      advance(HOUR);
      const expiredBob = { ...bob, subscriptionTier: "premium" as const, premiumUntil: new Date(clock.current.getTime() - 1) };

      await expect(messaging.requestSnapView(expiredBob, id)).rejects.toThrow(ForbiddenError);
    });

    it("refuse la lecture d'un Snap déjà reçu après un retrait d'ami ou un blocage", async () => {
      const { id } = await sendSnap();
      await friendships.removeFriend(bob, alice.id);

      await expect(messaging.requestSnapView(bob, id)).rejects.toThrow(ForbiddenError);
      expect(messages.rows.get(id)!.openedAt).toBeNull();
    });

    it("renvoie 410 pour un Snap purgé, qui s'affiche alors en gris", async () => {
      const { id } = await sendSnap();
      messages.rows.get(id)!.mediaKey = null; // effet de la purge (ADR-017)

      await expect(messaging.requestSnapView(bob, id)).rejects.toThrow(GoneError);
      const [conversation] = await messaging.listConversations(alice);
      expect(conversation.lastMessage).toMatchObject({ id, mediaPurged: true });
    });

    it("n'éloigne jamais une purge déjà plus proche que +24h", async () => {
      const { id } = await sendSnap();
      const soon = new Date(clock.current.getTime() + HOUR);
      messages.rows.get(id)!.scheduledPurgeAt = soon;

      await messaging.requestSnapView(bob, id);

      expect(messages.rows.get(id)!.scheduledPurgeAt).toEqual(soon);
      expect(scheduler.calls.filter((c) => c.op === "reschedule")).toEqual([]);
    });
  });

  describe("conversations et historique", () => {
    it("liste uniquement les amis ACCEPTED, la plus récente en premier", async () => {
      const carol = await users.seed("carol");
      const dave = await users.seed("dave");
      await befriend(alice, carol);
      await friendships.sendRequest(alice, "dave"); // demande en attente : pas de conversation
      await messaging.sendText(alice, bob.id, "1");
      advance(MINUTE);
      await messaging.sendText(carol, alice.id, "2");

      const conversations = await messaging.listConversations(alice);

      expect(conversations.map((c) => c.friend.username)).toEqual(["carol", "bob"]);
      expect(conversations[0].lastMessage).toMatchObject({ content: "2", senderId: carol.id });
      expect(conversations.some((c) => c.friend.id === dave.id)).toBe(false);
    });

    it("un snap non ouvert reste signalé même si des chats plus récents le suivent (BUG-014)", async () => {
      const first = await sendSnap(alice, bob);
      advance(MINUTE);
      const second = await sendSnap(alice, bob);
      advance(MINUTE);
      await messaging.sendText(alice, bob.id, "tu as vu ?");
      await messaging.sendText(bob, alice.id, "pas encore");

      const [forBob] = await messaging.listConversations(bob);
      expect(forBob.lastMessage).toMatchObject({ content: "pas encore" });
      expect(forBob.unopenedSnap).toMatchObject({ id: first.id }); // le plus ancien d'abord

      await messaging.requestSnapView(bob, first.id);
      expect((await messaging.listConversations(bob))[0].unopenedSnap).toMatchObject({ id: second.id });

      messages.rows.get(second.id)!.mediaKey = null; // purgé : plus rien à ouvrir
      expect((await messaging.listConversations(bob))[0].unopenedSnap).toBeUndefined();
      // Côté expéditeur, ses propres snaps ne sont jamais « à ouvrir ».
      expect((await messaging.listConversations(alice))[0].unopenedSnap).toBeUndefined();
    });

    it("l'historique est conservé après un blocage et réapparaît quand la paire redevient ACCEPTED", async () => {
      await messaging.sendText(alice, bob.id, "avant");
      await friendships.block(alice, bob.id);
      await expect(messaging.listMessages(alice, bob.id, {})).rejects.toThrow(ForbiddenError);
      expect(await messaging.listConversations(alice)).toEqual([]);

      await friendships.unblock(alice, bob.id);
      await befriend(bob, alice);

      const history = await messaging.listMessages(alice, bob.id, {});
      expect(history.map((m) => m.content)).toEqual(["avant"]);
    });

    it("pagine l'historique vers le passé", async () => {
      for (const content of ["1", "2", "3"]) {
        await messaging.sendText(alice, bob.id, content);
        advance(MINUTE);
      }
      const firstPage = await messaging.listMessages(bob, alice.id, { limit: 2 });
      const secondPage = await messaging.listMessages(bob, alice.id, { before: new Date(firstPage[1].createdAt), limit: 2 });

      expect(firstPage.map((m) => m.content)).toEqual(["3", "2"]);
      expect(secondPage.map((m) => m.content)).toEqual(["1"]);
    });

    it("marque les Chats reçus comme lus (flèche pleine → contour), pas les Snaps", async () => {
      const text = await messaging.sendText(alice, bob.id, "coucou");
      const snap = await sendSnap();

      await messaging.markConversationRead(bob, alice.id);

      expect(messages.rows.get(text.id)!.openedAt).toEqual(clock.current);
      expect(messages.rows.get(snap.id)!.openedAt).toBeNull();
      expect(notifier.events).toContain(`read→${alice.id}`);
    });
  });

  describe("indicateur « en train d'écrire » (ADR-027)", () => {
    it("est relayé à l'ami, sans rien enregistrer", async () => {
      await messaging.notifyTyping(alice, bob.id);

      expect(notifier.events).toEqual([`typing ${alice.id}→${bob.id}`]);
      expect(messages.rows.size).toBe(0);
    });

    it("est refusé hors amitié ACCEPTED", async () => {
      await friendships.block(alice, bob.id);
      await expect(messaging.notifyTyping(alice, bob.id)).rejects.toThrow(ForbiddenError);
      expect(notifier.events).toEqual([]);
    });
  });
});

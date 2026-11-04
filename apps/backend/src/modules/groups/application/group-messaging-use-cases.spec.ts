import { ForbiddenError, GoneError, NotFoundError, ValidationError } from "../../../shared/domain/errors";
import { DAY, GroupWorld, HOUR, MINUTE } from "../../../test/group-world";
import type { UserAccount } from "../../identity/domain/user";

describe("GroupMessagingUseCases (ADR-032)", () => {
  let w: GroupWorld;
  let alice: UserAccount;
  let bob: UserAccount;
  let carol: UserAccount;
  let dave: UserAccount;
  let groupId: string;

  beforeEach(async () => {
    w = new GroupWorld();
    [alice, bob, carol, dave] = await Promise.all(["alice", "bob", "carol", "dave"].map((u) => w.user(u)));
    await w.befriend(alice, bob, carol, dave);
    ({ id: groupId } = await w.groups.create(alice, { name: "Soirée", memberIds: [bob.id, carol.id] }));
    w.notifier.events.length = 0;
    w.advance(MINUTE);
  });

  describe("envoi", () => {
    it("chat texte permanent : notifié aux autres membres (pas à l'expéditeur), avec prénom et nom du groupe", async () => {
      const view = await w.messaging.sendText(bob, groupId, "  salut  ");

      expect(view).toEqual({
        id: view.id,
        groupId,
        senderId: bob.id,
        contentType: "TEXT",
        content: "salut",
        createdAt: w.clock.current.toISOString(),
        mediaPurged: false,
        openedByMe: false,
        seenBy: [],
      });
      expect(w.groupRepo.messages.get(view.id)).toMatchObject({ mediaKey: null, scheduledPurgeAt: null });
      expect(w.scheduler.calls).toEqual([]);
      expect(w.notifier.received.map((r) => r.to).sort()).toEqual([alice.id, carol.id].sort());
      expect(w.notifier.received[0]).toMatchObject({
        groupId,
        messageId: view.id,
        senderId: bob.id,
        senderName: "Bob",
        groupName: "Soirée",
        contentType: "TEXT",
      });
      expect(w.analytics.named("group_message_sent")).toEqual([
        { userId: bob.id, event: "group_message_sent", properties: { content_type: "TEXT" } },
      ]);
    });

    it("refuse un chat vide", async () => {
      await expect(w.messaging.sendText(bob, groupId, "  ")).rejects.toThrow(ValidationError);
    });

    it("snap : média copié en group-messages/<id>, purge programmée à +30 jours", async () => {
      const view = await w.sendSnap(bob, groupId, "SNAP_PHOTO_VIDEO");

      const row = w.groupRepo.messages.get(view.id)!;
      expect(row).toMatchObject({ contentType: "SNAP_PHOTO_VIDEO", content: null, mediaKey: `group-messages/${view.id}` });
      expect(w.storage.has({ area: "private", key: row.mediaKey! })).toBe(true);
      expect(row.scheduledPurgeAt).toEqual(new Date(w.clock.current.getTime() + 30 * DAY));
      expect(w.scheduler.calls).toEqual([{ op: "schedule", messageId: view.id, at: row.scheduledPurgeAt }]);
      expect(view).not.toHaveProperty("content");
      expect(w.analytics.named("group_message_sent")[0].properties).toEqual({ content_type: "SNAP_PHOTO_VIDEO" });
    });

    it("un blocage entre deux membres ne les empêche pas d'écrire dans le groupe", async () => {
      await w.friendships.block(carol, bob.id);
      await expect(w.messaging.sendText(bob, groupId, "toujours là")).resolves.toBeDefined();
      expect((await w.messaging.listMessages(carol, groupId, {})).map((m) => m.content)).toEqual(["toujours là"]);
    });

    it("un membre parti ne peut plus écrire", async () => {
      await w.groups.leave(carol, groupId);
      await expect(w.messaging.sendText(carol, groupId, "re")).rejects.toThrow(new NotFoundError("Groupe introuvable"));
    });
  });

  describe("historique, lu et seenBy", () => {
    it("du plus récent au plus ancien, paginé avec before", async () => {
      for (const content of ["1", "2", "3"]) {
        await w.messaging.sendText(bob, groupId, content);
        w.advance(MINUTE);
      }
      const page = await w.messaging.listMessages(alice, groupId, { limit: 2 });
      expect(page.map((m) => m.content)).toEqual(["3", "2"]);
      const next = await w.messaging.listMessages(alice, groupId, { before: new Date(page[1].createdAt), limit: 2 });
      expect(next.map((m) => m.content)).toEqual(["1"]);
    });

    it("lecture : lastReadAt mis à jour, seenBy et openedByMe, notification group.message.seen aux autres", async () => {
      const chat = await w.messaging.sendText(alice, groupId, "on y va ?");
      w.advance(MINUTE);

      await w.messaging.markRead(bob, groupId);

      expect(w.groupRepo.members.find((m) => m.userId === bob.id)!.lastReadAt).toEqual(w.clock.current);
      const [forAlice] = await w.messaging.listMessages(alice, groupId, {});
      expect(forAlice).toMatchObject({ seenBy: [bob.id], openedByMe: false });
      const [forBob] = await w.messaging.listMessages(bob, groupId, {});
      expect(forBob.openedByMe).toBe(true);
      const [forCarol] = await w.messaging.listMessages(carol, groupId, {});
      expect(forCarol.openedByMe).toBe(false);
      expect(w.notifier.to(alice.id)).toContain(`seen ${chat.id} by ${bob.id}`);
      expect(w.notifier.to(bob.id)).not.toContain(`seen ${chat.id} by ${bob.id}`);
    });

    it("relire sans nouveau message ne notifie personne", async () => {
      await w.messaging.sendText(alice, groupId, "hello");
      await w.messaging.markRead(bob, groupId);
      w.notifier.events.length = 0;
      w.advance(MINUTE);
      await w.messaging.markRead(bob, groupId);
      expect(w.notifier.events).toEqual([]);
    });

    it("un membre ajouté après un chat ne figure pas dans son seenBy, même après lecture", async () => {
      const chat = await w.messaging.sendText(alice, groupId, "avant");
      w.advance(MINUTE);
      await w.groups.addMembers(alice, groupId, [dave.id]);
      w.advance(MINUTE);
      await w.messaging.markRead(dave, groupId);

      const [view] = await w.messaging.listMessages(alice, groupId, {});
      expect(view.id).toBe(chat.id);
      expect(view.seenBy).toEqual([]);
    });

    it("typing : relayé aux autres membres seulement", async () => {
      await w.messaging.notifyTyping(bob, groupId);
      expect(w.notifier.events.sort()).toEqual([`typing ${bob.id}→${alice.id}`, `typing ${bob.id}→${carol.id}`].sort());
    });
  });

  describe("vue unique d'un snap", () => {
    it("1re ouverture : URL de ~1 min, ouverture enregistrée, expéditeur et autres prévenus", async () => {
      const snap = await w.sendSnap(alice, groupId);

      const view = await w.messaging.requestSnapView(bob, snap.id);

      expect(view.url).toContain(`group-messages/${snap.id}`);
      expect(view.expiresAt).toBe(new Date(w.clock.current.getTime() + MINUTE).toISOString());
      expect(w.groupRepo.openings).toEqual([{ messageId: snap.id, userId: bob.id, openedAt: w.clock.current }]);
      expect(w.notifier.to(alice.id)).toContain(`seen ${snap.id} by ${bob.id}`);
      expect(w.notifier.to(carol.id)).toContain(`seen ${snap.id} by ${bob.id}`);
      const [forAlice] = await w.messaging.listMessages(alice, groupId, {});
      expect(forAlice).toMatchObject({ seenBy: [bob.id], openedByMe: false });
    });

    it("chaque membre l'ouvre une fois : grâce de 5 min, puis refus sauf SnapCore+", async () => {
      const snap = await w.sendSnap(alice, groupId);
      await w.messaging.requestSnapView(bob, snap.id);

      w.advance(5 * MINUTE);
      await expect(w.messaging.requestSnapView(bob, snap.id)).resolves.toBeDefined();
      w.advance(1);
      await expect(w.messaging.requestSnapView(bob, snap.id)).rejects.toThrow(new ForbiddenError("Ce Snap a déjà été vu"));
      // L'ouverture de bob ne consomme pas celle de carol.
      await expect(w.messaging.requestSnapView(carol, snap.id)).resolves.toBeDefined();

      const premiumBob = { ...bob, subscriptionTier: "premium" as const, premiumUntil: new Date(w.clock.current.getTime() + DAY) };
      await expect(w.messaging.requestSnapView(premiumBob, snap.id)).resolves.toBeDefined();
      expect(w.groupRepo.openings.filter((o) => o.userId === bob.id)).toHaveLength(1);
    });

    it("réservé aux membres actuels arrivés avant l'envoi, jamais à l'expéditeur ; 404 identique", async () => {
      const snap = await w.sendSnap(alice, groupId);
      w.advance(MINUTE);
      await w.groups.addMembers(alice, groupId, [dave.id]);
      const notFound = new NotFoundError("Snap introuvable");

      await expect(w.messaging.requestSnapView(alice, snap.id)).rejects.toThrow(notFound);
      await expect(w.messaging.requestSnapView(dave, snap.id)).rejects.toThrow(notFound);
      await w.groups.leave(carol, groupId);
      await expect(w.messaging.requestSnapView(carol, snap.id)).rejects.toThrow(notFound);
      const chat = await w.messaging.sendText(alice, groupId, "texte");
      await expect(w.messaging.requestSnapView(bob, chat.id)).rejects.toThrow(notFound);
    });

    it("snap purgé : 410", async () => {
      const snap = await w.sendSnap(alice, groupId);
      await w.groupRepo.clearMedia(snap.id);
      await expect(w.messaging.requestSnapView(bob, snap.id)).rejects.toThrow(new GoneError("Ce Snap a expiré"));
      expect((await w.messaging.listMessages(bob, groupId, {}))[0].mediaPurged).toBe(true);
    });

    it("purge rapprochée à dernière ouverture + 24 h quand tous les membres présents à l'envoi l'ont ouvert", async () => {
      const snap = await w.sendSnap(alice, groupId);
      const initial = w.groupRepo.messages.get(snap.id)!.scheduledPurgeAt;
      w.advance(MINUTE);
      await w.groups.addMembers(alice, groupId, [dave.id]); // arrivé après : pas attendu

      await w.messaging.requestSnapView(bob, snap.id);
      expect(w.groupRepo.messages.get(snap.id)!.scheduledPurgeAt).toEqual(initial);
      expect(w.scheduler.calls.filter((c) => c.op === "reschedule")).toEqual([]);

      w.advance(HOUR);
      await w.messaging.requestSnapView(carol, snap.id);

      const purgeAt = new Date(w.clock.current.getTime() + DAY);
      expect(w.groupRepo.messages.get(snap.id)!.scheduledPurgeAt).toEqual(purgeAt);
      expect(w.scheduler.calls.at(-1)).toEqual({ op: "reschedule", messageId: snap.id, at: purgeAt });
    });

    it("un membre suspendu n'empêche pas la purge rapprochée (traité comme inexistant)", async () => {
      const snap = await w.sendSnap(alice, groupId);
      w.users.rows.get(carol.id)!.moderationStatus = "SUSPENDED";

      await w.messaging.requestSnapView(bob, snap.id);

      expect(w.groupRepo.messages.get(snap.id)!.scheduledPurgeAt).toEqual(new Date(w.clock.current.getTime() + DAY));
    });
  });
});

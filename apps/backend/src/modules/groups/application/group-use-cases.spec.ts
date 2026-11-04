import { ConflictError, ForbiddenError, NotFoundError, ValidationError } from "../../../shared/domain/errors";
import { GroupWorld, MINUTE } from "../../../test/group-world";
import type { UserAccount } from "../../identity/domain/user";

describe("GroupUseCases (ADR-032)", () => {
  let w: GroupWorld;
  let alice: UserAccount;
  let bob: UserAccount;
  let carol: UserAccount;
  let dave: UserAccount;

  beforeEach(async () => {
    w = new GroupWorld();
    [alice, bob, carol, dave] = await Promise.all(["alice", "bob", "carol", "dave"].map((u) => w.user(u)));
    await w.befriend(alice, bob, carol, dave);
  });

  const createGroup = (name?: string | null, by = alice, members = [bob, carol]) =>
    w.groups.create(by, { name, memberIds: members.map((m) => m.id) });

  describe("création", () => {
    it("crée un groupe avec le créateur et ses amis, tous arrivés à la création", async () => {
      const view = await createGroup("  Soirée  ");

      expect(view).toMatchObject({ name: "Soirée", formerMembers: [], createdAt: w.clock.current.toISOString() });
      expect(view.members.map((m) => m.username).sort()).toEqual(["alice", "bob", "carol"]);
      expect(view.members[0]).not.toHaveProperty("phoneNumber");
      expect(w.groupRepo.members.every((m) => m.joinedAt.getTime() === w.clock.current.getTime())).toBe(true);
      expect(w.groupRepo.groups.get(view.id)!.createdById).toBe(alice.id);
      expect(w.notifier.to(bob.id)).toEqual([`updated ${view.id}`]);
      expect(w.analytics.named("group_created")).toEqual([{ userId: alice.id, event: "group_created", properties: { members: 3 } }]);
    });

    it.each([[undefined], [null], [""], ["   "]])("sans nom (%p) : name absent de la réponse", async (name) => {
      const view = await createGroup(name);
      expect(view).not.toHaveProperty("name");
      expect(w.groupRepo.groups.get(view.id)!.name).toBeNull();
    });

    it("refuse un nom de plus de 50 caractères (après trim)", async () => {
      await expect(createGroup(` ${"a".repeat(50)} `)).resolves.toBeDefined();
      await expect(createGroup("a".repeat(51))).rejects.toThrow(new ValidationError("Le nom du groupe dépasse 50 caractères"));
    });

    it("exige au moins 2 amis, doublons et moi-même ignorés", async () => {
      await expect(w.groups.create(alice, { memberIds: [bob.id, bob.id, alice.id] })).rejects.toThrow(
        new ValidationError("Choisis au moins 2 amis pour créer un groupe"),
      );
      expect(w.groupRepo.groups.size).toBe(0);
    });

    it("accepte 31 amis, refuse 32 (32 membres au plus)", async () => {
      const friends: UserAccount[] = [];
      for (let i = 0; i < 32; i++) friends.push(await w.user(`ami${i}`));
      await w.befriend(alice, ...friends);

      await expect(createGroup(null, alice, friends.slice(0, 31))).resolves.toMatchObject({ members: expect.any(Array) });
      await expect(createGroup(null, alice, friends)).rejects.toThrow(new ValidationError("Un groupe compte 32 membres au plus"));
    });

    it("les membres n'ont pas besoin d'être amis entre eux ; deux groupes identiques coexistent", async () => {
      const first = await createGroup();
      const second = await createGroup();
      expect(first.id).not.toBe(second.id);
    });

    it.each([
      ["un non-ami", async (u: UserAccount) => u],
      ["une demande en attente", async (u: UserAccount) => (await w.friendships.sendRequest(alice, u.username), u)],
      ["un ami qui m'a bloqué", async (u: UserAccount) => (await w.befriend(alice, u), await w.friendships.block(u, alice.id), u)],
      ["un ami suspendu", async (u: UserAccount) => (await w.befriend(alice, u), (w.users.rows.get(u.id)!.moderationStatus = "SUSPENDED"), u)],
      ["un ami en cours de suppression", async (u: UserAccount) => (await w.befriend(alice, u), (w.users.rows.get(u.id)!.deletionRequestedAt = new Date()), u)],
    ])("refuse %s, avec le même message", async (_label, prepare) => {
      const other = await prepare(await w.user("eve"));
      await expect(createGroup(null, alice, [bob, other])).rejects.toThrow(
        new ForbiddenError("Tu ne peux ajouter que tes amis à un groupe"),
      );
      expect(w.analytics.named("group_created")).toEqual([]);
    });
  });

  describe("accès", () => {
    it("un non-membre reçoit 404 « Groupe introuvable » partout, comme pour un groupe inexistant", async () => {
      const { id } = await createGroup();
      const notFound = new NotFoundError("Groupe introuvable");

      await expect(w.groups.get(dave, id)).rejects.toThrow(notFound);
      await expect(w.groups.rename(dave, id, "x")).rejects.toThrow(notFound);
      await expect(w.groups.addMembers(dave, id, [alice.id])).rejects.toThrow(notFound);
      await expect(w.groups.leave(dave, id)).rejects.toThrow(notFound);
      await expect(w.messaging.listMessages(dave, id, {})).rejects.toThrow(notFound);
      await expect(w.messaging.sendText(dave, id, "coucou")).rejects.toThrow(notFound);
      await expect(w.messaging.markRead(dave, id)).rejects.toThrow(notFound);
      await expect(w.messaging.notifyTyping(dave, id)).rejects.toThrow(notFound);
      await expect(w.groups.get(alice, "00000000-0000-0000-0000-000000000000")).rejects.toThrow(notFound);
    });

    it("le détail donne nom et membres actifs ; un membre suspendu est traité comme inexistant", async () => {
      const { id } = await createGroup("Projet");
      w.users.rows.get(carol.id)!.moderationStatus = "SUSPENDED";

      const view = await w.groups.get(bob, id);
      expect(view.name).toBe("Projet");
      expect(view.members.map((m) => m.id).sort()).toEqual([alice.id, bob.id].sort());
    });
  });

  describe("renommage", () => {
    it("n'importe quel membre renomme ; un nom vide retire le nom", async () => {
      const { id } = await createGroup("Avant");

      await expect(w.groups.rename(carol, id, " Après ")).resolves.toMatchObject({ name: "Après" });
      const unnamed = await w.groups.rename(bob, id, "");
      expect(unnamed).not.toHaveProperty("name");
      expect(w.notifier.to(alice.id)).toContain(`updated ${id}`);
      expect(w.analytics.named("group_renamed")).toHaveLength(2);
    });

    it("refuse un nom trop long sans rien modifier", async () => {
      const { id } = await createGroup("Avant");
      await expect(w.groups.rename(bob, id, "x".repeat(51))).rejects.toThrow(ValidationError);
      expect(w.groupRepo.groups.get(id)!.name).toBe("Avant");
    });
  });

  describe("ajout de membres", () => {
    it("n'importe quel membre ajoute SES amis ; déjà membres ignorés ; le nouveau voit les messages suivants seulement", async () => {
      const { id } = await createGroup();
      await w.befriend(bob, dave); // dave est ami de bob, pas besoin d'être ami d'alice
      await w.messaging.sendText(alice, id, "avant l'arrivée de dave");
      w.advance(MINUTE);

      const view = await w.groups.addMembers(bob, id, [dave.id, carol.id]);

      expect(view.members.map((m) => m.id)).toContain(dave.id);
      expect(w.groupRepo.members.find((m) => m.userId === dave.id)!.joinedAt).toEqual(w.clock.current);
      expect(w.analytics.named("group_members_added")).toEqual([
        { userId: bob.id, event: "group_members_added", properties: { count: 1 } },
      ]);
      expect(w.notifier.to(dave.id)).toEqual([`updated ${id}`]);

      w.advance(MINUTE);
      await w.messaging.sendText(alice, id, "après");
      const history = await w.messaging.listMessages(dave, id, {});
      expect(history.map((m) => m.content)).toEqual(["après"]);
    });

    it("refuse d'ajouter quelqu'un qui n'est pas mon ami (même s'il est ami d'un autre membre)", async () => {
      const { id } = await createGroup();
      const eve = await w.user("eve");
      await w.befriend(alice, eve);
      await expect(w.groups.addMembers(bob, id, [eve.id])).rejects.toThrow(ForbiddenError);
    });

    it("ajouter uniquement des membres existants : réponse inchangée, aucun événement", async () => {
      const { id } = await createGroup();
      w.notifier.events.length = 0;
      await w.groups.addMembers(alice, id, [bob.id]);
      expect(w.notifier.events).toEqual([]);
      expect(w.analytics.named("group_members_added")).toEqual([]);
      await expect(w.groups.addMembers(alice, id, [alice.id])).rejects.toThrow(ValidationError);
    });

    it("refuse de dépasser 32 membres (409), sans rien ajouter", async () => {
      const friends: UserAccount[] = [];
      for (let i = 0; i < 31; i++) friends.push(await w.user(`ami${i}`));
      await w.befriend(alice, ...friends);
      const { id } = await createGroup(null, alice, friends.slice(0, 30)); // 31 membres

      await expect(w.groups.addMembers(alice, id, [friends[30].id, dave.id])).rejects.toThrow(
        new ConflictError("Un groupe compte 32 membres au plus"),
      );
      await expect(w.groups.addMembers(alice, id, [dave.id])).resolves.toBeDefined();
      expect(w.groupRepo.members.filter((m) => m.groupId === id)).toHaveLength(32);
    });
  });

  describe("départ", () => {
    it("le membre sort ; ses messages restent, avec son nom (formerMembers) ; les autres sont prévenus", async () => {
      const { id } = await createGroup();
      await w.messaging.sendText(carol, id, "bye");
      w.notifier.events.length = 0;

      await w.groups.leave(carol, id);

      await expect(w.groups.get(carol, id)).rejects.toThrow(NotFoundError);
      const view = await w.groups.get(alice, id);
      expect(view.members.map((m) => m.id)).not.toContain(carol.id);
      expect(view.formerMembers.map((m) => m.id)).toEqual([carol.id]);
      expect((await w.messaging.listMessages(alice, id, {})).map((m) => m.senderId)).toEqual([carol.id]);
      expect(w.notifier.to(alice.id)).toEqual([`updated ${id}`]);
      expect(w.notifier.to(carol.id)).toEqual([]);
      expect(w.analytics.named("group_left")).toEqual([{ userId: carol.id, event: "group_left" }]);
    });

    it("le dernier membre qui part supprime le groupe et ses médias R2", async () => {
      const { id } = await createGroup();
      const snap = await w.sendSnap(bob, id);
      await w.groups.leave(bob, id);
      await w.groups.leave(carol, id);
      expect(w.storage.has({ area: "private", key: `group-messages/${snap.id}` })).toBe(true);

      await w.groups.leave(alice, id);

      expect(w.groupRepo.groups.has(id)).toBe(false);
      expect(w.groupRepo.messages.size).toBe(0);
      expect(w.storage.has({ area: "private", key: `group-messages/${snap.id}` })).toBe(false);
    });

    it("échec R2 au dernier départ : le groupe vide est conservé, ses médias restent aux jobs de purge", async () => {
      const { id } = await createGroup();
      await w.sendSnap(bob, id);
      await w.groups.leave(bob, id);
      await w.groups.leave(carol, id);
      w.storage.failNextDelete = true;

      await w.groups.leave(alice, id);

      expect(w.groupRepo.groups.has(id)).toBe(true);
      expect(w.groupRepo.members.filter((m) => m.groupId === id)).toEqual([]);
      expect(await w.groups.list(alice)).toEqual([]);
    });

    it("mon départ rapproche la purge d'un snap que j'étais seul à ne pas avoir ouvert", async () => {
      const { id } = await createGroup();
      const snap = await w.sendSnap(alice, id);
      await w.messaging.requestSnapView(bob, snap.id);
      w.advance(MINUTE);

      await w.groups.leave(carol, id);

      const openedAt = w.groupRepo.openings[0].openedAt;
      const purgeAt = new Date(openedAt.getTime() + 24 * 60 * MINUTE);
      expect(w.groupRepo.messages.get(snap.id)!.scheduledPurgeAt).toEqual(purgeAt);
      expect(w.scheduler.calls.at(-1)).toEqual({ op: "reschedule", messageId: snap.id, at: purgeAt });
    });
  });

  describe("liste de mes groupes", () => {
    it("dernier message, plus ancien snap à ouvrir, non lu ; triée par dernier message", async () => {
      const older = await createGroup("Ancien");
      w.advance(MINUTE);
      const newer = await createGroup("Récent", alice, [bob, dave]);
      w.advance(MINUTE);
      const snap1 = await w.sendSnap(bob, older.id);
      w.advance(MINUTE);
      await w.sendSnap(carol, older.id);
      w.advance(MINUTE);
      await w.messaging.sendText(bob, older.id, "dernier");

      const list = await w.groups.list(alice);

      expect(list.map((s) => s.group.name)).toEqual(["Ancien", "Récent"]);
      expect(list[0]).toMatchObject({
        lastMessage: { content: "dernier", senderId: bob.id, openedByMe: false },
        unopenedSnap: { id: snap1.id, contentType: "SNAP_PHOTO", openedByMe: false, mediaPurged: false },
        unread: true,
      });
      expect(list[1]).toEqual({ group: expect.objectContaining({ id: newer.id }), unread: false });
      // Sans message, un groupe est classé à mon arrivée.
      expect(await w.groups.list(carol)).toHaveLength(1);
    });

    it("unread passe à false à la lecture ; unopenedSnap reste tant que le snap n'est pas ouvert", async () => {
      const { id } = await createGroup();
      const snap = await w.sendSnap(bob, id);
      w.advance(MINUTE);

      await w.messaging.markRead(alice, id);
      let [summary] = await w.groups.list(alice);
      expect(summary.unread).toBe(false);
      expect(summary.unopenedSnap?.id).toBe(snap.id);

      await w.messaging.requestSnapView(alice, snap.id);
      [summary] = await w.groups.list(alice);
      expect(summary.unopenedSnap).toBeUndefined();
      expect(summary.lastMessage).toMatchObject({ id: snap.id, openedByMe: true, seenBy: [alice.id] });
    });

    it("mes propres messages ne rendent pas le groupe non lu ; un snap purgé n'est plus à ouvrir", async () => {
      const { id } = await createGroup();
      await w.messaging.sendText(alice, id, "moi");
      w.advance(MINUTE);
      const snap = await w.sendSnap(bob, id);
      await w.groupRepo.clearMedia(snap.id);

      const [summary] = await w.groups.list(alice);
      expect(summary.unopenedSnap).toBeUndefined();
      expect(summary.lastMessage).toMatchObject({ id: snap.id, mediaPurged: true });
      expect((await w.groups.list(carol))[0].unread).toBe(true);
    });

    it("un membre ajouté ne voit ni dernier message ni snap antérieurs à son arrivée", async () => {
      const { id } = await createGroup();
      await w.sendSnap(bob, id);
      w.advance(MINUTE);
      await w.groups.addMembers(alice, id, [dave.id]);

      const [summary] = await w.groups.list(dave);
      expect(summary).toEqual({ group: expect.objectContaining({ id }), unread: false });
    });
  });
});

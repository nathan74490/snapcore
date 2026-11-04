import { DAY, GroupWorld, MINUTE } from "../../../test/group-world";
import type { UserAccount } from "../../identity/domain/user";

describe("GroupSnapPurge (ADR-032, ADR-017)", () => {
  let w: GroupWorld;
  let alice: UserAccount;
  let bob: UserAccount;
  let carol: UserAccount;
  let groupId: string;

  beforeEach(async () => {
    w = new GroupWorld();
    [alice, bob, carol] = await Promise.all(["alice", "bob", "carol"].map((u) => w.user(u)));
    await w.befriend(alice, bob, carol);
    ({ id: groupId } = await w.groups.create(alice, { memberIds: [bob.id, carol.id] }));
  });

  it("purge un snap échu : R2 d'abord, puis mediaKey vidé (ligne conservée)", async () => {
    const snap = await w.sendSnap(alice, groupId);
    w.advance(30 * DAY);

    await expect(w.purge.runPurgeJob(snap.id)).resolves.toEqual({ kind: "done", detail: "purged" });

    expect(w.storage.has({ area: "private", key: `group-messages/${snap.id}` })).toBe(false);
    expect(w.groupRepo.messages.get(snap.id)).toMatchObject({ mediaKey: null });
  });

  it("pas de conservation SnapCore+ dans un groupe : un destinataire abonné ne repousse pas la purge", async () => {
    Object.assign(w.users.rows.get(bob.id)!, { subscriptionTier: "premium", premiumUntil: new Date(w.clock.current.getTime() + 365 * DAY) });
    const snap = await w.sendSnap(alice, groupId);
    w.advance(30 * DAY);

    await expect(w.purge.runPurgeJob(snap.id)).resolves.toEqual({ kind: "done", detail: "purged" });
  });

  it("exécution anticipée : re-différée à scheduledPurgeAt", async () => {
    const snap = await w.sendSnap(alice, groupId);
    const at = w.groupRepo.messages.get(snap.id)!.scheduledPurgeAt!;
    await expect(w.purge.runPurgeJob(snap.id)).resolves.toEqual({ kind: "delay", until: at, detail: "not-due" });
  });

  it("chat texte, média déjà purgé, ligne disparue : rien à faire", async () => {
    const chat = await w.messaging.sendText(alice, groupId, "permanent");
    await expect(w.purge.runPurgeJob(chat.id)).resolves.toEqual({ kind: "done", detail: "nothing-to-purge" });
    await expect(w.purge.runPurgeJob("absent")).resolves.toEqual({ kind: "done", detail: "missing" });
  });

  it("une erreur R2 fait échouer le job sans vider mediaKey (rejeu idempotent)", async () => {
    const snap = await w.sendSnap(alice, groupId);
    w.advance(30 * DAY);
    w.storage.failNextDelete = true;

    await expect(w.purge.runPurgeJob(snap.id)).rejects.toThrow("R2 indisponible");
    expect(w.groupRepo.messages.get(snap.id)!.mediaKey).not.toBeNull();
  });

  it("balayage : relance uniquement les snaps échus dont le média existe encore", async () => {
    const due = await w.sendSnap(alice, groupId);
    w.advance(MINUTE);
    const later = await w.sendSnap(alice, groupId);
    const purged = await w.sendSnap(bob, groupId);
    await w.groupRepo.clearMedia(purged.id);
    w.advance(30 * DAY - MINUTE);
    w.scheduler.recoverOutcome = "re-added";

    await expect(w.purge.sweepOverdue()).resolves.toEqual({ overdue: 1, retried: 0, reAdded: 1 });
    expect(w.scheduler.recovered).toEqual([due.id]);
    expect(later.id).not.toBe(due.id);
  });
});

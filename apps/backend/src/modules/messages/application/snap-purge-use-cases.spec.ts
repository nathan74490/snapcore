import { randomUUID } from "node:crypto";
import { FixedClock } from "../../../test/fakes";
import { InMemoryObjectStorage, silentLogger } from "../../../test/in-memory-media";
import {
  InMemoryMessageRepository,
  RecordingMessageNotifier,
  RecordingPurgeScheduler,
} from "../../../test/in-memory-messages";
import { InMemoryUserRepository } from "../../../test/in-memory-user.repository";
import type { UserAccount } from "../../identity/domain/user";
import { SnapPurgeUseCases } from "./snap-purge-use-cases";

const HOUR = 60 * 60 * 1000;
const DAY = 24 * HOUR;

describe("SnapPurgeUseCases (ADR-017)", () => {
  let clock: FixedClock;
  let users: InMemoryUserRepository;
  let messages: InMemoryMessageRepository;
  let storage: InMemoryObjectStorage;
  let scheduler: RecordingPurgeScheduler;
  let notifier: RecordingMessageNotifier;
  let purge: SnapPurgeUseCases;
  let alice: UserAccount;
  let bob: UserAccount;

  beforeEach(async () => {
    clock = new FixedClock();
    users = new InMemoryUserRepository();
    messages = new InMemoryMessageRepository();
    storage = new InMemoryObjectStorage();
    scheduler = new RecordingPurgeScheduler();
    notifier = new RecordingMessageNotifier();
    purge = new SnapPurgeUseCases(messages, users, storage, scheduler, notifier, clock, silentLogger);
    alice = await users.seed("alice");
    bob = await users.seed("bob");
  });

  const at = (offsetMs: number) => new Date(clock.current.getTime() + offsetMs);

  async function snapDueAt(scheduledPurgeAt: Date, to = bob) {
    const id = randomUUID();
    const mediaKey = `messages/${id}`;
    storage.simulateUpload(mediaKey, 100, "image/jpeg");
    await messages.create({
      id,
      senderId: alice.id,
      recipientId: to.id,
      contentType: "SNAP_PHOTO",
      content: null,
      mediaKey,
      createdAt: at(-DAY),
      scheduledPurgeAt,
    });
    return { id, mediaKey };
  }

  const subscribe = (user: UserAccount, premiumUntil: Date) => {
    Object.assign(users.rows.get(user.id)!, { subscriptionTier: "premium", premiumUntil });
  };

  it("purge un Snap échu : média supprimé de R2, mediaKey vidé, ligne conservée", async () => {
    const { id, mediaKey } = await snapDueAt(at(-1));

    await expect(purge.runPurgeJob(id)).resolves.toEqual({ kind: "done", detail: "purged" });

    expect(storage.has({ area: "private", key: mediaKey })).toBe(false);
    expect(messages.rows.get(id)).toMatchObject({ mediaKey: null });
    expect(notifier.events).toEqual([`expired→${alice.id},${bob.id}`]);
  });

  it("ne purge jamais un Chat texte", async () => {
    const id = randomUUID();
    await messages.create({
      id,
      senderId: alice.id,
      recipientId: bob.id,
      contentType: "TEXT",
      content: "permanent",
      mediaKey: null,
      createdAt: at(-40 * DAY),
      scheduledPurgeAt: null,
    });

    await expect(purge.runPurgeJob(id)).resolves.toEqual({ kind: "done", detail: "nothing-to-purge" });
    expect(messages.rows.get(id)!.content).toBe("permanent");
  });

  it("une ligne supprimée entre-temps (suppression de compte) n'est pas une erreur", async () => {
    await expect(purge.runPurgeJob(randomUUID())).resolves.toEqual({ kind: "done", detail: "missing" });
  });

  it("un job exécuté avant scheduledPurgeAt est re-différé sans rien purger", async () => {
    const due = at(HOUR);
    const { id, mediaKey } = await snapDueAt(due);

    await expect(purge.runPurgeJob(id)).resolves.toEqual({ kind: "delay", until: due, detail: "not-due" });
    expect(storage.has({ area: "private", key: mediaKey })).toBe(true);
  });

  describe("exception SnapCore+ du destinataire", () => {
    it("conserve le Snap et repousse la purge à premiumUntil + 24h", async () => {
      const { id, mediaKey } = await snapDueAt(at(-1));
      const premiumUntil = at(10 * DAY);
      subscribe(bob, premiumUntil);

      const outcome = await purge.runPurgeJob(id);

      const until = new Date(premiumUntil.getTime() + DAY);
      expect(outcome).toEqual({ kind: "delay", until, detail: "retained" });
      expect(messages.rows.get(id)!.scheduledPurgeAt).toEqual(until);
      expect(storage.has({ area: "private", key: mediaKey })).toBe(true);
    });

    it("notifie l'expéditeur une seule fois, même après plusieurs renouvellements", async () => {
      const { id } = await snapDueAt(at(-1));
      subscribe(bob, at(DAY));
      await purge.runPurgeJob(id);

      clock.current = at(3 * DAY); // renouvelé entre-temps, le job se réexécute
      subscribe(bob, at(30 * DAY));
      await purge.runPurgeJob(id);

      expect(notifier.events.filter((e) => e.startsWith("retained"))).toEqual([`retained→${alice.id}`]);
      expect(messages.rows.get(id)!.retainedNotifiedAt).not.toBeNull();
    });

    it("marge de 24h : un abonnement expiré depuis moins de 24h conserve encore le Snap", async () => {
      const { id } = await snapDueAt(at(-1));
      subscribe(bob, at(-HOUR));

      await expect(purge.runPurgeJob(id)).resolves.toMatchObject({ kind: "delay", detail: "retained" });
    });

    it("au-delà de la marge, le Snap est purgé", async () => {
      const { id } = await snapDueAt(at(-1));
      subscribe(bob, at(-25 * HOUR));

      await expect(purge.runPurgeJob(id)).resolves.toEqual({ kind: "done", detail: "purged" });
    });

    it("seul l'abonnement du DESTINATAIRE compte, pas celui de l'expéditeur", async () => {
      const { id } = await snapDueAt(at(-1));
      subscribe(alice, at(30 * DAY));

      await expect(purge.runPurgeJob(id)).resolves.toEqual({ kind: "done", detail: "purged" });
    });
  });

  it("si R2 échoue, le job échoue et mediaKey reste en base pour le rejeu", async () => {
    const { id, mediaKey } = await snapDueAt(at(-1));
    storage.failNextDelete = true;

    await expect(purge.runPurgeJob(id)).rejects.toThrow("R2 indisponible");
    expect(messages.rows.get(id)!.mediaKey).toBe(mediaKey);
  });

  describe("balayage de rattrapage", () => {
    it("relance le job de chaque Snap en retard, sans jamais purger lui-même", async () => {
      const late = await snapDueAt(at(-HOUR));
      await snapDueAt(at(DAY)); // pas encore échu
      scheduler.recoverOutcome = "retried";

      const result = await purge.sweepOverdue();

      expect(scheduler.recovered).toEqual([late.id]);
      expect(result).toEqual({ overdue: 1, retried: 1, reAdded: 0 });
      expect(storage.has({ area: "private", key: late.mediaKey })).toBe(true);
    });

    it("ne retrouve plus un Snap conservé par abonnement (scheduledPurgeAt repoussé)", async () => {
      const { id } = await snapDueAt(at(-1));
      subscribe(bob, at(10 * DAY));
      await purge.runPurgeJob(id);

      await purge.sweepOverdue();

      expect(scheduler.recovered).toEqual([]);
    });
  });
});

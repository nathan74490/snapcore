import { Queue, Worker } from "bullmq";
import { randomUUID } from "node:crypto";
import { systemClock } from "../../../shared/domain/clock";
import { InMemoryGroupRepository } from "../../../test/in-memory-groups";
import { InMemoryObjectStorage, silentLogger } from "../../../test/in-memory-media";
import { InMemoryMessageRepository, RecordingMessageNotifier } from "../../../test/in-memory-messages";
import { InMemoryUserRepository } from "../../../test/in-memory-user.repository";
import type { UserAccount } from "../../identity/domain/user";
import { GroupSnapPurge } from "../../groups/application/group-snap-purge";
import { SnapPurgeUseCases } from "../../messages/application/snap-purge-use-cases";
import { BullMqPurgeScheduler } from "./bullmq-purge.scheduler";
import { PurgeWorker } from "./purge.worker";
import { bullmqConnection, PURGE_JOB_NAME, purgeJobId } from "./purge-queue";

const PURGE_MESSAGE_JOB = PURGE_JOB_NAME.message;
const purgeMessageJobId = (id: string) => purgeJobId("message", id);

const DAY = 24 * 60 * 60 * 1000;

// Contre le Redis de docker compose, sur une file dédiée supprimée à la fin.
// Vérifie la recette de docs/impl/purge-bullmq.md sur la version de BullMQ installée.
describe("Purge BullMQ (Redis réel)", () => {
  const connection = bullmqConnection(process.env.REDIS_URL ?? "redis://localhost:6379");
  const queue = new Queue(`it-media-purge-${randomUUID().slice(0, 8)}`, { connection });
  const scheduler = new BullMqPurgeScheduler(queue, systemClock, "message");
  let users: InMemoryUserRepository;
  let messages: InMemoryMessageRepository;
  let storage: InMemoryObjectStorage;
  let groups: InMemoryGroupRepository;
  let worker: PurgeWorker | undefined;
  let alice: UserAccount;
  let bob: UserAccount;

  beforeEach(async () => {
    users = new InMemoryUserRepository();
    messages = new InMemoryMessageRepository();
    storage = new InMemoryObjectStorage();
    groups = new InMemoryGroupRepository(users);
    alice = await users.seed("alice");
    bob = await users.seed("bob");
  });

  afterEach(async () => {
    await worker?.onApplicationShutdown().catch(() => undefined);
    worker = undefined;
  });

  afterAll(async () => {
    const cleanup = new Queue(queue.name, { connection });
    await cleanup.obliterate({ force: true });
    await cleanup.close();
    await queue.close();
  });

  function startWorker() {
    const useCases = new SnapPurgeUseCases(
      messages,
      users,
      storage,
      scheduler,
      new RecordingMessageNotifier(),
      systemClock,
      silentLogger,
    );
    // File dédiée, fermée par le worker à l'arrêt : on lui en donne une propre.
    const groupSnaps = new GroupSnapPurge(groups, storage, new BullMqPurgeScheduler(queue, systemClock, "groupMessage"), systemClock, silentLogger);
    const unused = { run: async () => ({ kind: "done" as const }), sweepOverdue: async () => undefined };
    worker = new PurgeWorker(
      new Queue(queue.name, { connection }),
      connection,
      {
        message: { run: (id) => useCases.runPurgeJob(id), sweepOverdue: () => useCases.sweepOverdue() },
        story: unused,
        groupMessage: { run: (id) => groupSnaps.runPurgeJob(id), sweepOverdue: () => groupSnaps.sweepOverdue() },
      },
      true,
    );
    return worker.onModuleInit();
  }

  async function snap(scheduledPurgeAt: Date) {
    const id = randomUUID();
    storage.simulateUpload(`messages/${id}`, 100, "image/jpeg");
    await messages.create({
      id,
      senderId: alice.id,
      recipientId: bob.id,
      contentType: "SNAP_PHOTO",
      content: null,
      mediaKey: `messages/${id}`,
      createdAt: new Date(),
      scheduledPurgeAt,
    });
    return id;
  }

  async function waitFor(condition: () => Promise<boolean> | boolean, timeoutMs = 10_000) {
    const start = Date.now();
    while (!(await condition())) {
      if (Date.now() - start > timeoutMs) throw new Error("Délai dépassé");
      await new Promise((r) => setTimeout(r, 100));
    }
  }

  it("identifiant déterministe : un second schedule du même Snap ne crée pas de doublon", async () => {
    const id = randomUUID();
    await scheduler.schedule(id, new Date(Date.now() + DAY));
    await scheduler.schedule(id, new Date(Date.now() + 2 * DAY));

    const job = await queue.getJob(purgeMessageJobId(id));
    expect(job?.name).toBe(PURGE_MESSAGE_JOB);
    expect(await job?.getState()).toBe("delayed");
    expect(await queue.getDelayedCount()).toBeGreaterThanOrEqual(1);
    const ids = (await queue.getDelayed()).map((j) => j.id).filter((jobId) => jobId === purgeMessageJobId(id));
    expect(ids).toHaveLength(1);
  });

  it("reschedule rapproche l'échéance d'un job différé (changeDelay, délai relatif)", async () => {
    const id = randomUUID();
    await scheduler.schedule(id, new Date(Date.now() + 30 * DAY));

    const target = Date.now() + DAY;
    await scheduler.reschedule(id, new Date(target));

    const job = (await queue.getJob(purgeMessageJobId(id)))!;
    expect(await job.getState()).toBe("delayed");
    expect(Math.abs(job.timestamp + job.delay - target)).toBeLessThan(2_000);
  });

  it("le worker purge un Snap échu, puis libère l'identifiant du job (removeOnComplete)", async () => {
    await startWorker();
    const id = await snap(new Date(Date.now() + 300));
    await scheduler.schedule(id, new Date(Date.now() + 300));

    await waitFor(() => messages.rows.get(id)!.mediaKey === null);

    expect(storage.has({ area: "private", key: `messages/${id}` })).toBe(false);
    await waitFor(async () => (await queue.getJob(purgeMessageJobId(id))) === undefined);
  });

  it("snap de groupe (ADR-032) : job purge-group-message-<id> dans la même file, traité par le même worker", async () => {
    await startWorker();
    const id = randomUUID();
    const now = new Date();
    await groups.create({ id: "g", name: null, createdById: alice.id, createdAt: now }, [alice.id, bob.id]);
    storage.simulateUpload(`group-messages/${id}`, 100, "image/jpeg");
    const purgeAt = new Date(Date.now() + 300);
    await groups.createMessage({
      id,
      groupId: "g",
      senderId: alice.id,
      contentType: "SNAP_PHOTO",
      content: null,
      mediaKey: `group-messages/${id}`,
      createdAt: now,
      scheduledPurgeAt: purgeAt,
    });
    const groupScheduler = new BullMqPurgeScheduler(queue, systemClock, "groupMessage");
    await groupScheduler.schedule(id, purgeAt);
    expect((await queue.getJob(`purge-group-message-${id}`))?.name).toBe("purge-group-message");

    await waitFor(() => groups.messages.get(id)!.mediaKey === null);
    expect(storage.has({ area: "private", key: `group-messages/${id}` })).toBe(false);
  });

  it("abonnement actif : le job en cours se re-diffère lui-même à premiumUntil + 24h (moveToDelayed)", async () => {
    const premiumUntil = new Date(Date.now() + 10 * DAY);
    Object.assign(users.rows.get(bob.id)!, { subscriptionTier: "premium", premiumUntil });
    await startWorker();
    const id = await snap(new Date(Date.now() + 200));
    await scheduler.schedule(id, new Date(Date.now() + 200));

    const expected = premiumUntil.getTime() + DAY;
    await waitFor(() => messages.rows.get(id)!.scheduledPurgeAt?.getTime() === expected);
    await waitFor(async () => (await queue.getJob(purgeMessageJobId(id)))?.getState().then((s) => s === "delayed") ?? false);

    const job = (await queue.getJob(purgeMessageJobId(id)))!;
    expect(Math.abs(job.timestamp + job.delay - expected)).toBeLessThan(2_000);
    expect(messages.rows.get(id)!.mediaKey).not.toBeNull();
  });

  it("rattrapage : un job en échec conservé est relancé (retry), pas réajouté", async () => {
    const id = randomUUID();
    const jobId = purgeMessageJobId(id);
    await queue.add(PURGE_MESSAGE_JOB, { targetId: id }, { jobId, attempts: 1, removeOnFail: { count: 50 } });
    const failing = new Worker(queue.name, async () => {
      throw new Error("R2 indisponible");
    }, { connection });
    await waitFor(async () => (await queue.getJob(jobId))?.isFailed() ?? false);
    await failing.close();

    // Un add aveugle serait ignoré sans erreur : c'est exactement le piège évité.
    await queue.add(PURGE_MESSAGE_JOB, { targetId: id }, { jobId });
    expect(await (await queue.getJob(jobId))!.getState()).toBe("failed");

    await expect(scheduler.recover(id)).resolves.toBe("retried");
    expect(await (await queue.getJob(jobId))!.getState()).toBe("waiting");
  });

  it("rattrapage : un job disparu est réajouté ; un job différé n'est pas touché", async () => {
    const lost = randomUUID();
    await expect(scheduler.recover(lost)).resolves.toBe("re-added");
    expect(await queue.getJob(purgeMessageJobId(lost))).toBeDefined();

    const pending = randomUUID();
    await scheduler.schedule(pending, new Date(Date.now() + DAY));
    await expect(scheduler.recover(pending)).resolves.toBe("untouched");
  });

  it("le balayage horaire est enregistré une seule fois, même après redémarrage", async () => {
    await startWorker();
    await worker!.onApplicationShutdown();
    await startWorker();

    const schedulers = await queue.getJobSchedulers();
    expect(schedulers.filter((s) => s.key === "purge-sweep")).toHaveLength(1);
  });
});

import { Queue } from "bullmq";
import { randomUUID } from "node:crypto";
import { systemClock } from "../../../shared/domain/clock";
import { PrismaService } from "../../../shared/infrastructure/prisma/prisma.service";
import { FakeIdentityProvider } from "../../../test/fakes";
import { InMemoryObjectStorage, silentLogger } from "../../../test/in-memory-media";
import { RecordingRealtime } from "../../../test/in-memory-notifications";
import type { UserAccount } from "../../identity/domain/user";
import { PrismaUserRepository } from "../../identity/infrastructure/prisma-user.repository";
import { PublicMediaUrls } from "../../media/domain/public-media-urls";
import { bullmqConnection } from "../../purge/infrastructure/purge-queue";
import { AccountDeletionUseCases } from "../application/account-deletion-use-cases";
import { AccountDeletionWorker, BullMqDeletionJobQueue, deletionJobId } from "./bullmq-deletion";
import { PrismaAccountDeletionRepository } from "./prisma-account-deletion.repository";

// Contre le Postgres et le Redis de docker compose (vide les tables).
describe("Suppression de compte (Postgres et BullMQ réels)", () => {
  const prisma = new PrismaService();
  const users = new PrismaUserRepository(prisma);
  const accounts = new PrismaAccountDeletionRepository(prisma);
  let alice: UserAccount;
  let bob: UserAccount;

  const newUser = (username: string) =>
    users.create({ username, firstName: "Test", firebaseUid: `uid-${randomUUID()}`, email: null, phoneNumber: null });

  const snapTo = (recipientId: string, senderId: string, mediaKey = `messages/${randomUUID()}`) =>
    prisma.message.create({
      data: { id: randomUUID(), senderId, recipientId, contentType: "SNAP_PHOTO", mediaKey, scheduledPurgeAt: new Date() },
    });

  beforeEach(async () => {
    await prisma.storyItem.deleteMany();
    await prisma.message.deleteMany();
    await prisma.friendship.deleteMany();
    await prisma.user.deleteMany();
    alice = await newUser("alice");
    bob = await newUser("bob");
  });

  afterAll(() => prisma.$disconnect());

  it("FOR UPDATE : un Snap envoyé pendant la transaction attend le verrou puis échoue, il n'est jamais perdu en silence", async () => {
    await snapTo(alice.id, bob.id, "messages/avant");
    await prisma.user.update({ where: { id: alice.id }, data: { deletionRequestedAt: new Date() } });

    let releaseTransaction!: () => void;
    const transactionHeld = new Promise<void>((resolve) => (releaseTransaction = resolve));
    let collected: string[] = [];
    let concurrentInsertSettled = false;
    let concurrentInsert!: Promise<unknown>;

    const deletion = accounts.deleteUserCollectingMedia(alice.id, async (media) => {
      collected = media.privateKeys;
      // Pendant que le verrou est tenu, Bob tente d'envoyer un Snap à Alice.
      concurrentInsert = snapTo(alice.id, bob.id, "messages/pendant").finally(() => (concurrentInsertSettled = true));
      await new Promise((r) => setTimeout(r, 500));
      expect(concurrentInsertSettled).toBe(false); // bloqué par le verrou de la ligne User
      releaseTransaction();
    });

    await transactionHeld;
    await expect(deletion).resolves.toBe(true);
    // Une fois la ligne supprimée, l'insertion échoue sur la clé étrangère au lieu de réussir.
    await expect(concurrentInsert).rejects.toThrow();
    expect(collected).toEqual(["messages/avant"]);
    expect(await prisma.message.count()).toBe(0);
  });

  it("le worker exécute la suppression et persiste les clés dans le job avant de supprimer la ligne", async () => {
    const connection = bullmqConnection(process.env.REDIS_URL ?? "redis://localhost:6379");
    const queueName = `it-account-deletion-${randomUUID().slice(0, 8)}`;
    const queue = new Queue(queueName, { connection });
    const storage = new InMemoryObjectStorage();
    const identity = new FakeIdentityProvider();
    const useCases = new AccountDeletionUseCases(
      accounts,
      new BullMqDeletionJobQueue(queue),
      identity,
      { cancelFor: async () => undefined },
      storage,
      new PublicMediaUrls("https://cdn.snapcore.test"),
      new RecordingRealtime(),
      systemClock,
      silentLogger,
    );
    const alerts: string[] = [];
    const worker = new AccountDeletionWorker(new Queue(queueName, { connection }), connection, useCases, {
      alert: (m) => alerts.push(m),
    }, { enabled: true, baseBackoffMs: 50 });

    try {
      const sent = await snapTo(bob.id, alice.id);
      storage.simulateUpload(sent.mediaKey!, 10, "image/jpeg");
      // R2 échoue une fois APRÈS la suppression en base : le rejeu doit s'appuyer sur les clés du job.
      storage.failNextDelete = true;

      await useCases.request(alice);
      await useCases.request(alice); // second appel : aucun doublon (jobId déterministe)
      expect(await queue.getJobCounts("waiting", "delayed", "active")).toMatchObject({ waiting: 1 });

      await worker.onModuleInit();
      const start = Date.now();
      while ((await queue.getJob(deletionJobId(alice.id))) && Date.now() - start < 15_000) {
        await new Promise((r) => setTimeout(r, 100));
      }

      expect(await prisma.user.findUnique({ where: { id: alice.id } })).toBeNull();
      expect(identity.deletedAccounts.has(alice.firebaseUid)).toBe(true);
      expect(storage.has({ area: "private", key: sent.mediaKey! })).toBe(false);
      expect(await prisma.user.findUnique({ where: { id: bob.id } })).not.toBeNull();
      expect(alerts).toEqual([]);
    } finally {
      await worker.onApplicationShutdown();
      const cleanup = new Queue(queueName, { connection });
      await cleanup.obliterate({ force: true });
      await cleanup.close();
      await queue.close();
    }
  });
});

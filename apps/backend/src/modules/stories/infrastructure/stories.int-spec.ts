import { Queue } from "bullmq";
import Redis from "ioredis";
import { randomUUID } from "node:crypto";
import { systemClock } from "../../../shared/domain/clock";
import { PrismaService } from "../../../shared/infrastructure/prisma/prisma.service";
import { InMemoryObjectStorage, InMemoryUploadTicketStore, silentLogger } from "../../../test/in-memory-media";
import { InMemoryFriendshipRepository } from "../../../test/in-memory-friendship.repository";
import { InMemoryUserRepository } from "../../../test/in-memory-user.repository";
import { PrismaUserRepository } from "../../identity/infrastructure/prisma-user.repository";
import { UploadPromoter } from "../../media/application/upload-promoter";
import { BullMqPurgeScheduler } from "../../purge/infrastructure/bullmq-purge.scheduler";
import { bullmqConnection, purgeJobId } from "../../purge/infrastructure/purge-queue";
import { PurgeWorker } from "../../purge/infrastructure/purge.worker";
import { StoryUseCases } from "../application/story-use-cases";
import type { StoryItem } from "../domain/story-item";
import { PrismaStoryRepository } from "./prisma-story.repository";
import { RedisStoryViewStore } from "./redis-story-view.store";

// Contre le Postgres, le Redis et BullMQ de docker compose (vide les tables).
describe("Stories (Postgres, Redis et BullMQ réels)", () => {
  const redisUrl = process.env.REDIS_URL ?? "redis://localhost:6379";
  const prisma = new PrismaService();
  const redis = new Redis(redisUrl);
  const stories = new PrismaStoryRepository(prisma);
  const views = new RedisStoryViewStore(redis);
  let authorId: string;

  beforeEach(async () => {
    await prisma.storyItem.deleteMany();
    await prisma.message.deleteMany();
    await prisma.friendship.deleteMany();
    await prisma.user.deleteMany();
    const author = await new PrismaUserRepository(prisma).create({
      username: "auteur",
      firstName: "Auteur",
      firebaseUid: `uid-${randomUUID()}`,
      email: null,
      phoneNumber: null,
    });
    authorId = author.id;
  });

  afterAll(async () => {
    await prisma.$disconnect();
    await redis.quit();
  });

  const item = (createdAt: number, lifetimeMs: number): StoryItem => {
    const id = randomUUID();
    return {
      id,
      authorId,
      mediaKey: `stories/${id}`,
      contentType: "PHOTO",
      createdAt: new Date(createdAt),
      expiresAt: new Date(createdAt + lifetimeMs),
    };
  };

  it("ne liste que les éléments non expirés, du plus ancien au plus récent", async () => {
    const now = Date.now();
    const old = await stories.create(item(now - 2000, 60_000));
    const recent = await stories.create(item(now - 1000, 60_000));
    const expired = await stories.create(item(now - 90_000, 60_000));

    const visible = await stories.listVisibleByAuthors([authorId], new Date(now));

    expect(visible.map((s) => s.id)).toEqual([old.id, recent.id]);
    expect(await stories.findExpiredIds(new Date(now), 10)).toEqual([expired.id]);
  });

  it("les vues vivent dans Redis et disparaissent avec l'élément (TTL = expiresAt)", async () => {
    const id = randomUUID();
    await views.record(id, "viewer-1", new Date(Date.now() + 800));
    await views.record(id, "viewer-1", new Date(Date.now() + 800)); // revoir ne compte pas deux fois
    await views.record(id, "viewer-2", new Date(Date.now() + 800));

    expect((await views.counts([id])).get(id)).toBe(2);
    expect(await views.viewedAmong([id, randomUUID()], "viewer-1")).toEqual(new Set([id]));

    await new Promise((r) => setTimeout(r, 1200));
    expect(await views.viewers(id)).toEqual([]);
  });

  it("le worker purge un élément à expiration : média, ligne et vues supprimés", async () => {
    const connection = bullmqConnection(redisUrl);
    const queueName = `it-story-purge-${randomUUID().slice(0, 8)}`;
    const queue = new Queue(queueName, { connection });
    const storage = new InMemoryObjectStorage();
    const scheduler = new BullMqPurgeScheduler(queue, systemClock, "story");
    const memUsers = new InMemoryUserRepository();
    const useCases = new StoryUseCases(
      stories,
      views,
      new InMemoryFriendshipRepository(memUsers),
      memUsers,
      new UploadPromoter(storage, new InMemoryUploadTicketStore(), silentLogger),
      storage,
      scheduler,
      systemClock,
      silentLogger,
    );
    const unused = { run: async () => ({ kind: "done" as const }), sweepOverdue: async () => undefined };
    const worker = new PurgeWorker(
      new Queue(queueName, { connection }),
      connection,
      { message: unused, story: { run: (id) => useCases.runPurgeJob(id), sweepOverdue: () => useCases.sweepOverdue() } },
      true,
    );
    await worker.onModuleInit();

    try {
      const story = await stories.create(item(Date.now(), 500));
      storage.simulateUpload(story.mediaKey, 10, "image/jpeg");
      await views.record(story.id, "viewer-1", story.expiresAt);
      await scheduler.schedule(story.id, story.expiresAt);
      expect(await (await queue.getJob(purgeJobId("story", story.id)))?.getState()).toBe("delayed");

      const start = Date.now();
      while ((await stories.findById(story.id)) && Date.now() - start < 10_000) {
        await new Promise((r) => setTimeout(r, 100));
      }

      expect(await stories.findById(story.id)).toBeNull();
      expect(storage.has({ area: "private", key: story.mediaKey })).toBe(false);
      expect(await redis.exists(`story:views:${story.id}`)).toBe(0);
    } finally {
      await worker.onApplicationShutdown();
      await queue.obliterate({ force: true });
      await queue.close();
    }
  });
});

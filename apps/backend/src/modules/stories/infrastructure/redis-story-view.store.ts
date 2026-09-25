import type Redis from "ioredis";
import type { StoryViewStore } from "../application/ports";

const key = (storyId: string) => `story:views:${storyId}`;

// Set Redis par élément de story, expirant avec le contenu (ADR-013).
export class RedisStoryViewStore implements StoryViewStore {
  constructor(private readonly redis: Redis) {}

  async record(storyId: string, viewerId: string, expiresAt: Date): Promise<void> {
    await this.redis.multi().sadd(key(storyId), viewerId).pexpireat(key(storyId), expiresAt.getTime()).exec();
  }

  async viewedAmong(storyIds: string[], viewerId: string): Promise<Set<string>> {
    if (storyIds.length === 0) return new Set();
    const pipeline = this.redis.pipeline();
    storyIds.forEach((id) => pipeline.sismember(key(id), viewerId));
    const results = (await pipeline.exec()) ?? [];
    return new Set(storyIds.filter((_, i) => results[i]?.[1] === 1));
  }

  async viewers(storyId: string): Promise<string[]> {
    return this.redis.smembers(key(storyId));
  }

  async counts(storyIds: string[]): Promise<Map<string, number>> {
    if (storyIds.length === 0) return new Map();
    const pipeline = this.redis.pipeline();
    storyIds.forEach((id) => pipeline.scard(key(id)));
    const results = (await pipeline.exec()) ?? [];
    return new Map(storyIds.map((id, i) => [id, Number(results[i]?.[1] ?? 0)]));
  }

  async clear(storyId: string): Promise<void> {
    await this.redis.del(key(storyId));
  }
}

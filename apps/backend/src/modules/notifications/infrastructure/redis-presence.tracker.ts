import type Redis from "ioredis";
import type { PresenceTracker } from "../application/ports";

// Durée de validité d'une connexion sans heartbeat : si une instance tombe, ses connexions
// cessent d'être "en ligne" d'elles-mêmes au bout de ce délai.
export const PRESENCE_TTL_MS = 60_000;
export const HEARTBEAT_INTERVAL_MS = 20_000;

const key = (userId: string) => `presence:online:${userId}`;

// Clé `presence:online:<userId>` d'ADR-018, sous forme de sorted set (une entrée par connexion,
// score = expiration) plutôt que d'une simple clé à TTL : un utilisateur connecté sur deux
// appareils ne passe pas "hors ligne" quand un seul des deux se déconnecte.
export class RedisPresenceTracker implements PresenceTracker {
  constructor(
    private readonly redis: Redis,
    private readonly now: () => number = Date.now,
  ) {}

  async touch(userId: string, connectionId: string): Promise<void> {
    const now = this.now();
    await this.redis
      .multi()
      .zadd(key(userId), now + PRESENCE_TTL_MS, connectionId)
      .zremrangebyscore(key(userId), "-inf", now)
      .pexpire(key(userId), PRESENCE_TTL_MS * 2)
      .exec();
  }

  async disconnect(userId: string, connectionId: string): Promise<void> {
    await this.redis.zrem(key(userId), connectionId);
  }

  async isOnline(userId: string): Promise<boolean> {
    return (await this.redis.zcount(key(userId), `(${this.now()}`, "+inf")) > 0;
  }
}

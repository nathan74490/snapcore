import type Redis from "ioredis";
import type { PendingPhoneCode, PhoneCodeStore } from "../application/ports/phone-sign-in.ports";

export const phoneCodeKey = (phone: string) => `phone-code:${phone}`;

// Code en attente par numéro : empreinte, essais et date d'envoi, avec un TTL de 5 min (ADR-003, ADR-034).
export class RedisPhoneCodeStore implements PhoneCodeStore {
  constructor(private readonly redis: Redis) {}

  async save(phone: string, code: PendingPhoneCode, ttlSeconds: number): Promise<void> {
    const key = phoneCodeKey(phone);
    await this.redis
      .multi()
      .del(key)
      .hset(key, { hash: code.hash, attempts: code.attempts, sentAt: code.sentAt.toISOString() })
      .expire(key, ttlSeconds)
      .exec();
  }

  async get(phone: string): Promise<PendingPhoneCode | null> {
    const value = await this.redis.hgetall(phoneCodeKey(phone));
    if (!value.hash) return null;
    return { hash: value.hash, attempts: Number(value.attempts ?? 0), sentAt: new Date(value.sentAt) };
  }

  async recordFailedAttempt(phone: string): Promise<number> {
    return this.redis.hincrby(phoneCodeKey(phone), "attempts", 1);
  }

  async delete(phone: string): Promise<void> {
    await this.redis.del(phoneCodeKey(phone));
  }
}

import type Redis from "ioredis";
import type { UploadTicket, UploadTicketStore } from "../application/ports/upload-ticket.store";

const ticketKey = (uploadId: string) => `tmp-upload:${uploadId}`;
const lockKey = (uploadId: string) => `tmp-upload-lock:${uploadId}`;

export class RedisUploadTicketStore implements UploadTicketStore {
  constructor(private readonly redis: Redis) {}

  async save(ticket: UploadTicket, ttlSeconds: number): Promise<void> {
    await this.redis.set(ticketKey(ticket.uploadId), JSON.stringify(ticket), "EX", ttlSeconds);
  }

  async find(uploadId: string): Promise<UploadTicket | null> {
    const raw = await this.redis.get(ticketKey(uploadId));
    return raw ? (JSON.parse(raw) as UploadTicket) : null;
  }

  async delete(uploadId: string): Promise<void> {
    await this.redis.del(ticketKey(uploadId));
  }

  async lock(uploadId: string, ttlSeconds: number): Promise<boolean> {
    return (await this.redis.set(lockKey(uploadId), "1", "EX", ttlSeconds, "NX")) === "OK";
  }

  async unlock(uploadId: string): Promise<void> {
    await this.redis.del(lockKey(uploadId));
  }
}

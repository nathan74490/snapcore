import Redis from "ioredis";
import { randomUUID } from "node:crypto";
import { ConflictError } from "../../../shared/domain/errors";
import { FixedClock } from "../../../test/fakes";
import { silentLogger } from "../../../test/in-memory-media";
import { RequestUpload, UPLOAD_TICKET_TTL_SECONDS } from "../application/request-upload";
import { UploadPromoter } from "../application/upload-promoter";
import type { UserAccount } from "../../identity/domain/user";
import { R2ObjectStorage } from "./r2-object-storage";
import { RedisUploadTicketStore } from "./redis-upload-ticket.store";

// Contre le vrai bucket R2 (clés du .env) et le Redis de docker compose.
// Les objets créés sont sous tmp/ ou it-tests/ et supprimés à la fin.
describe("Pipeline média (R2 et Redis réels)", () => {
  const redis = new Redis(process.env.REDIS_URL ?? "redis://localhost:6379");
  const storage = new R2ObjectStorage({
    accountId: process.env.R2_ACCOUNT_ID!,
    accessKeyId: process.env.R2_ACCESS_KEY_ID!,
    secretAccessKey: process.env.R2_SECRET_ACCESS_KEY!,
    privateBucket: process.env.R2_BUCKET!,
    publicBucket: process.env.R2_PUBLIC_BUCKET ?? "",
  });
  const tickets = new RedisUploadTicketStore(redis);
  const requestUpload = new RequestUpload(storage, tickets, new FixedClock());
  const promoter = new UploadPromoter(storage, tickets, silentLogger);
  const alice = { id: randomUUID() } as UserAccount;
  const created: string[] = [];

  afterAll(async () => {
    await Promise.allSettled(created.map((key) => storage.delete({ area: "private", key })));
    await redis.quit();
  });

  const body = Buffer.alloc(2048, 7);

  const requestAndUpload = async () => {
    const instructions = await requestUpload.execute(alice, {
      purpose: "SNAP_PHOTO",
      contentType: "image/jpeg",
      contentLength: body.length,
    });
    created.push(`tmp/${instructions.uploadId}`);
    const res = await fetch(instructions.uploadUrl, { method: "PUT", body, headers: instructions.headers });
    expect(res.status).toBe(200);
    return instructions.uploadId;
  };

  it("le ticket Redis porte le propriétaire et expire", async () => {
    const uploadId = await requestAndUpload();

    expect(await tickets.find(uploadId)).toMatchObject({ userId: alice.id, purpose: "SNAP_PHOTO" });
    const ttl = await redis.ttl(`tmp-upload:${uploadId}`);
    expect(ttl).toBeGreaterThan(0);
    expect(ttl).toBeLessThanOrEqual(UPLOAD_TICKET_TTL_SECONDS);
  });

  it("R2 refuse un envoi qui ne respecte pas la taille signée", async () => {
    const instructions = await requestUpload.execute(alice, { purpose: "SNAP_PHOTO", contentType: "image/jpeg", contentLength: 10 });
    const res = await fetch(instructions.uploadUrl, { method: "PUT", body: Buffer.alloc(11), headers: { "Content-Type": "image/jpeg" } });
    expect(res.status).toBe(403);
  });

  it("confirmation : copie vers la clé définitive, puis suppression du temporaire et du ticket", async () => {
    const uploadId = await requestAndUpload();
    const key = `it-tests/${uploadId}`;
    created.push(key);

    await promoter.promote({
      userId: alice.id,
      uploadId,
      allowedPurposes: ["SNAP_PHOTO"],
      destination: { area: "private", key },
      persist: async () => undefined,
    });

    expect(await storage.head(key)).toMatchObject({ size: body.length, contentType: "image/jpeg" });
    expect(await storage.head(`tmp/${uploadId}`)).toBeNull();
    expect(await tickets.find(uploadId)).toBeNull();
  });

  it("le verrou Redis empêche deux confirmations simultanées", async () => {
    const uploadId = await requestAndUpload();
    expect(await tickets.lock(uploadId, 30)).toBe(true);

    await expect(
      promoter.promote({
        userId: alice.id,
        uploadId,
        allowedPurposes: ["SNAP_PHOTO"],
        destination: { area: "private", key: `it-tests/${uploadId}` },
        persist: async () => undefined,
      }),
    ).rejects.toThrow(ConflictError);
    await tickets.unlock(uploadId);
  });

  it("la copie conditionnelle échoue si l'ETag ne correspond plus", async () => {
    const uploadId = await requestAndUpload();
    await expect(
      storage.copyFromPrivate(`tmp/${uploadId}`, { area: "private", key: `it-tests/${uploadId}` }, '"etag-perime"'),
    ).rejects.toThrow(ConflictError);
  });
});

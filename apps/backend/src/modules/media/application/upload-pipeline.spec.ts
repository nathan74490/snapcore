import { ConflictError, NotFoundError, ValidationError } from "../../../shared/domain/errors";
import { FixedClock } from "../../../test/fakes";
import { InMemoryObjectStorage, InMemoryUploadTicketStore, silentLogger } from "../../../test/in-memory-media";
import { InMemoryUserRepository } from "../../../test/in-memory-user.repository";
import type { UserAccount } from "../../identity/domain/user";
import { RequestUpload } from "./request-upload";
import { UploadPromoter } from "./upload-promoter";

describe("Pipeline d'upload (ADR-016)", () => {
  let storage: InMemoryObjectStorage;
  let tickets: InMemoryUploadTicketStore;
  let requestUpload: RequestUpload;
  let promoter: UploadPromoter;
  let alice: UserAccount;
  let bob: UserAccount;

  const destination = { area: "private" as const, key: "messages/m1" };

  beforeEach(async () => {
    storage = new InMemoryObjectStorage();
    tickets = new InMemoryUploadTicketStore();
    requestUpload = new RequestUpload(storage, tickets, new FixedClock());
    promoter = new UploadPromoter(storage, tickets, silentLogger);
    const users = new InMemoryUserRepository();
    alice = await users.seed("alice");
    bob = await users.seed("bob");
  });

  const uploadSnap = async (size = 1000) => {
    const { uploadId } = await requestUpload.execute(alice, { purpose: "SNAP_PHOTO", contentType: "image/jpeg", contentLength: size });
    storage.simulateUpload(`tmp/${uploadId}`, size, "image/jpeg");
    return uploadId;
  };

  describe("demande d'upload", () => {
    it("signe le type et la taille, vers une clé tmp/ générée par le backend", async () => {
      const result = await requestUpload.execute(alice, { purpose: "SNAP_PHOTO", contentType: "image/jpeg", contentLength: 1234 });

      expect(storage.presigned).toEqual([{ key: `tmp/${result.uploadId}`, contentType: "image/jpeg", contentLength: 1234, expiresInSeconds: 900 }]);
      expect(result.headers).toEqual({ "Content-Type": "image/jpeg", "Content-Length": "1234" });
      expect(tickets.tickets.get(result.uploadId)?.userId).toBe(alice.id);
    });

    it("refuse un type ou une taille non acceptés avant toute signature", async () => {
      await expect(
        requestUpload.execute(alice, { purpose: "SNAP_PHOTO", contentType: "application/pdf", contentLength: 10 }),
      ).rejects.toThrow(ValidationError);
      await expect(
        requestUpload.execute(alice, { purpose: "PROFILE_PHOTO", contentType: "image/jpeg", contentLength: 3 * 1024 * 1024 }),
      ).rejects.toThrow(ValidationError);
      expect(storage.presigned).toEqual([]);
    });
  });

  describe("confirmation", () => {
    it("copie vers la clé définitive, écrit en base, puis nettoie le temporaire et le ticket", async () => {
      const uploadId = await uploadSnap();
      const order: string[] = [];

      await promoter.promote({
        userId: alice.id,
        uploadId,
        allowedPurposes: ["SNAP_PHOTO"],
        destination,
        persist: async () => {
          order.push(storage.has(destination) ? "persist-après-copie" : "persist-avant-copie");
        },
      });

      expect(order).toEqual(["persist-après-copie"]);
      expect(storage.has(destination)).toBe(true);
      expect(storage.has({ area: "private", key: `tmp/${uploadId}` })).toBe(false);
      expect(tickets.tickets.has(uploadId)).toBe(false);
    });

    it("refuse l'upload d'un autre utilisateur, comme s'il n'existait pas", async () => {
      const uploadId = await uploadSnap();
      const persist = jest.fn();

      await expect(
        promoter.promote({ userId: bob.id, uploadId, allowedPurposes: ["SNAP_PHOTO"], destination, persist }),
      ).rejects.toThrow(new NotFoundError("Upload introuvable ou expiré"));
      expect(persist).not.toHaveBeenCalled();
      expect(storage.has(destination)).toBe(false);
    });

    it("refuse un upload demandé pour un autre usage", async () => {
      const uploadId = await uploadSnap();
      await expect(
        promoter.promote({ userId: alice.id, uploadId, allowedPurposes: ["PROFILE_PHOTO"], destination, persist: jest.fn() }),
      ).rejects.toThrow(ValidationError);
    });

    it("refuse si le fichier n'a pas encore été envoyé, et permet de réessayer ensuite", async () => {
      const { uploadId } = await requestUpload.execute(alice, { purpose: "SNAP_PHOTO", contentType: "image/jpeg", contentLength: 1000 });

      await expect(
        promoter.promote({ userId: alice.id, uploadId, allowedPurposes: ["SNAP_PHOTO"], destination, persist: jest.fn() }),
      ).rejects.toThrow(ValidationError);

      storage.simulateUpload(`tmp/${uploadId}`, 1000, "image/jpeg");
      await expect(
        promoter.promote({ userId: alice.id, uploadId, allowedPurposes: ["SNAP_PHOTO"], destination, persist: async () => "ok" }),
      ).resolves.toBe("ok");
    });

    it("refuse un fichier dont la taille ne correspond pas à celle demandée", async () => {
      const { uploadId } = await requestUpload.execute(alice, { purpose: "SNAP_PHOTO", contentType: "image/jpeg", contentLength: 1000 });
      storage.simulateUpload(`tmp/${uploadId}`, 999, "image/jpeg");

      await expect(
        promoter.promote({ userId: alice.id, uploadId, allowedPurposes: ["SNAP_PHOTO"], destination, persist: jest.fn() }),
      ).rejects.toThrow(ValidationError);
    });

    it("si l'écriture en base échoue, supprime la copie et garde le ticket pour un nouvel essai", async () => {
      const uploadId = await uploadSnap();

      await expect(
        promoter.promote({
          userId: alice.id,
          uploadId,
          allowedPurposes: ["SNAP_PHOTO"],
          destination,
          persist: async () => {
            throw new Error("base indisponible");
          },
        }),
      ).rejects.toThrow("base indisponible");

      expect(storage.has(destination)).toBe(false);
      expect(tickets.tickets.has(uploadId)).toBe(true);
      expect(tickets.locks.has(uploadId)).toBe(false);
    });

    it("refuse une seconde confirmation concurrente du même upload", async () => {
      const uploadId = await uploadSnap();
      await tickets.lock(uploadId);

      await expect(
        promoter.promote({ userId: alice.id, uploadId, allowedPurposes: ["SNAP_PHOTO"], destination, persist: jest.fn() }),
      ).rejects.toThrow(ConflictError);
    });

    it("un échec du nettoyage du temporaire ne fait pas échouer la confirmation", async () => {
      const uploadId = await uploadSnap();
      storage.failNextDelete = true;

      await expect(
        promoter.promote({ userId: alice.id, uploadId, allowedPurposes: ["SNAP_PHOTO"], destination, persist: async () => "ok" }),
      ).resolves.toBe("ok");
    });
  });
});

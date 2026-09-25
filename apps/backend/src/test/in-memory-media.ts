import { ConflictError } from "../shared/domain/errors";
import type {
  ObjectMetadata,
  ObjectStorage,
  StoredObject,
} from "../modules/media/application/ports/object-storage.port";
import type { UploadTicket, UploadTicketStore } from "../modules/media/application/ports/upload-ticket.store";

interface FakeObject {
  size: number;
  contentType: string;
  etag: string;
}

// Simule R2 : deux zones (buckets privé et public), ETag changeant à chaque écriture.
export class InMemoryObjectStorage implements ObjectStorage {
  readonly objects = new Map<string, FakeObject>();
  readonly presigned: Array<{ key: string; contentType: string; contentLength: number }> = [];
  failNextDelete = false;
  private version = 0;

  async presignUpload(input: { key: string; contentType: string; contentLength: number }) {
    this.presigned.push(input);
    return `https://r2.test/${input.key}?signed`;
  }

  async presignDownload(key: string, expiresInSeconds: number) {
    return `https://r2.test/${key}?get&expires=${expiresInSeconds}`;
  }

  /** Simule le PUT de l'app sur l'URL signée. */
  simulateUpload(key: string, size: number, contentType: string): void {
    this.objects.set(id({ area: "private", key }), { size, contentType, etag: `"v${++this.version}"` });
  }

  async head(key: string): Promise<ObjectMetadata | null> {
    return this.objects.get(id({ area: "private", key })) ?? null;
  }

  async copyFromPrivate(sourceKey: string, destination: StoredObject, ifMatchEtag: string) {
    const source = this.objects.get(id({ area: "private", key: sourceKey }));
    if (!source || source.etag !== ifMatchEtag) throw new ConflictError("Le fichier a été modifié pendant sa confirmation");
    this.objects.set(id(destination), { ...source });
  }

  async delete(object: StoredObject) {
    if (this.failNextDelete) {
      this.failNextDelete = false;
      throw new Error("R2 indisponible");
    }
    this.objects.delete(id(object));
  }

  has(object: StoredObject): boolean {
    return this.objects.has(id(object));
  }
}

const id = (object: StoredObject) => `${object.area}:${object.key}`;

export class InMemoryUploadTicketStore implements UploadTicketStore {
  readonly tickets = new Map<string, UploadTicket>();
  readonly locks = new Set<string>();

  async save(ticket: UploadTicket) {
    this.tickets.set(ticket.uploadId, { ...ticket });
  }
  async find(uploadId: string) {
    return this.tickets.get(uploadId) ?? null;
  }
  async delete(uploadId: string) {
    this.tickets.delete(uploadId);
  }
  async lock(uploadId: string) {
    if (this.locks.has(uploadId)) return false;
    this.locks.add(uploadId);
    return true;
  }
  async unlock(uploadId: string) {
    this.locks.delete(uploadId);
  }
}

export const silentLogger = { warn: () => undefined };

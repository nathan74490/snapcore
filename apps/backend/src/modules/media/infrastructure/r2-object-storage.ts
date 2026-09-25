import {
  CopyObjectCommand,
  DeleteObjectCommand,
  HeadObjectCommand,
  PutObjectCommand,
  S3Client,
  S3ServiceException,
} from "@aws-sdk/client-s3";
import { getSignedUrl } from "@aws-sdk/s3-request-presigner";
import { ConflictError } from "../../../shared/domain/errors";
import type {
  ObjectMetadata,
  ObjectStorage,
  StorageArea,
  StoredObject,
} from "../application/ports/object-storage.port";

export interface R2Config {
  accountId: string;
  accessKeyId: string;
  secretAccessKey: string;
  privateBucket: string;
  publicBucket: string;
}

// Cloudflare R2 via son API compatible S3 (ADR-016). Comportements vérifiés sur R2 réel :
// Content-Type et Content-Length signés dans l'URL de PUT (écart → 403), copie
// conditionnelle CopySourceIfMatch (ETag différent → 412).
export class R2ObjectStorage implements ObjectStorage {
  private readonly client: S3Client;

  constructor(private readonly config: R2Config) {
    this.client = new S3Client({
      region: "auto",
      endpoint: `https://${config.accountId}.r2.cloudflarestorage.com`,
      credentials: { accessKeyId: config.accessKeyId, secretAccessKey: config.secretAccessKey },
    });
  }

  presignUpload(input: { key: string; contentType: string; contentLength: number; expiresInSeconds: number }) {
    const command = new PutObjectCommand({
      Bucket: this.config.privateBucket,
      Key: input.key,
      ContentType: input.contentType,
      ContentLength: input.contentLength,
    });
    return getSignedUrl(this.client, command, {
      expiresIn: input.expiresInSeconds,
      signableHeaders: new Set(["content-type", "content-length"]),
    });
  }

  async head(key: string): Promise<ObjectMetadata | null> {
    try {
      const head = await this.client.send(new HeadObjectCommand({ Bucket: this.config.privateBucket, Key: key }));
      return { size: head.ContentLength ?? 0, contentType: head.ContentType ?? null, etag: head.ETag ?? "" };
    } catch (error) {
      if (httpStatus(error) === 404) return null;
      throw error;
    }
  }

  async copyFromPrivate(sourceKey: string, destination: StoredObject, ifMatchEtag: string): Promise<void> {
    try {
      await this.client.send(
        new CopyObjectCommand({
          Bucket: this.bucketFor(destination.area),
          Key: destination.key,
          CopySource: `${this.config.privateBucket}/${encodeURIComponent(sourceKey)}`,
          CopySourceIfMatch: ifMatchEtag,
        }),
      );
    } catch (error) {
      if (httpStatus(error) === 412) throw new ConflictError("Le fichier a été modifié pendant sa confirmation");
      throw error;
    }
  }

  async delete(object: StoredObject): Promise<void> {
    // DeleteObject est idempotent côté S3/R2 : un objet absent ne lève pas d'erreur.
    await this.client.send(new DeleteObjectCommand({ Bucket: this.bucketFor(object.area), Key: object.key }));
  }

  private bucketFor(area: StorageArea): string {
    return area === "public" ? this.config.publicBucket : this.config.privateBucket;
  }
}

function httpStatus(error: unknown): number | undefined {
  return error instanceof S3ServiceException ? error.$metadata.httpStatusCode : undefined;
}

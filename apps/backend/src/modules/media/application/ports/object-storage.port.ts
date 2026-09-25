export const OBJECT_STORAGE = Symbol("ObjectStorage");

// "private" : snaps, stories et uploads temporaires, jamais exposés publiquement (lecture par
// URL pré-signée). "public" : photos de profil uniquement, servies par URL publique (ADR-016).
export type StorageArea = "private" | "public";

export interface StoredObject {
  area: StorageArea;
  key: string;
}

export interface ObjectMetadata {
  size: number;
  contentType: string | null;
  etag: string;
}

export interface ObjectStorage {
  /** URL de PUT signée avec le Content-Type et le Content-Length attendus : R2 refuse tout écart. */
  presignUpload(input: {
    key: string;
    contentType: string;
    contentLength: number;
    expiresInSeconds: number;
  }): Promise<string>;

  /** Métadonnées d'un objet de la zone privée, null s'il n'existe pas. */
  head(key: string): Promise<ObjectMetadata | null>;

  /**
   * Copie serveur-à-serveur depuis la zone privée, conditionnée à l'ETag relevé au HEAD
   * (le fichier ne peut pas être remplacé entre le contrôle et la copie).
   * @throws ConflictError si l'ETag ne correspond plus.
   */
  copyFromPrivate(sourceKey: string, destination: StoredObject, ifMatchEtag: string): Promise<void>;

  /** Idempotent : supprimer un objet absent n'est pas une erreur. */
  delete(object: StoredObject): Promise<void>;
}

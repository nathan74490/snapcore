import type { MediaPurpose } from "../../domain/media-purpose";

export const UPLOAD_TICKET_STORE = Symbol("UploadTicketStore");

// Associe un upload temporaire à l'utilisateur qui l'a demandé (clé Redis `tmp-upload:<uuid>`,
// ADR-016) : sans elle, n'importe qui pourrait confirmer l'upload d'un autre.
export interface UploadTicket {
  uploadId: string;
  userId: string;
  purpose: MediaPurpose;
  contentType: string;
  contentLength: number;
}

export interface UploadTicketStore {
  save(ticket: UploadTicket, ttlSeconds: number): Promise<void>;
  find(uploadId: string): Promise<UploadTicket | null>;
  delete(uploadId: string): Promise<void>;

  /**
   * Verrou court et exclusif le temps d'une confirmation, pour qu'un même upload ne soit
   * pas promu deux fois en parallèle (double requête de l'app).
   * @returns false si une confirmation est déjà en cours.
   */
  lock(uploadId: string, ttlSeconds: number): Promise<boolean>;
  unlock(uploadId: string): Promise<void>;
}

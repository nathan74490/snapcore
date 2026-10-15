import { randomUUID } from "node:crypto";
import type { Clock } from "../../../shared/domain/clock";
import type { UserAccount } from "../../identity/domain/user";
import { assertAcceptedMedia, type MediaPurpose } from "../domain/media-purpose";
import { tmpKeyFor } from "../domain/public-media-urls";
import type { ObjectStorage } from "./ports/object-storage.port";
import type { UploadTicketStore } from "./ports/upload-ticket.store";

// Durée pour démarrer l'upload avec l'URL signée.
const UPLOAD_URL_TTL_SECONDS = 15 * 60;
// Durée pendant laquelle l'upload peut être confirmé (et réessayé) : "quelques heures",
// bien en deçà de la règle de cycle de vie R2 de 2 jours sur tmp/ (ADR-016).
export const UPLOAD_TICKET_TTL_SECONDS = 6 * 60 * 60;

export interface RequestUploadInput {
  purpose: MediaPurpose;
  contentType: string;
  contentLength: number;
}

export interface UploadInstructions {
  uploadId: string;
  uploadUrl: string;
  method: "PUT";
  // À envoyer tels quels : ils sont signés dans l'URL, tout écart est refusé par R2.
  headers: { "Content-Type": string; "Content-Length": string };
  expiresAt: string;
}

export class RequestUpload {
  constructor(
    private readonly storage: ObjectStorage,
    private readonly tickets: UploadTicketStore,
    private readonly clock: Clock,
  ) {}

  async execute(user: UserAccount, input: RequestUploadInput): Promise<UploadInstructions> {
    assertAcceptedMedia(input.purpose, input.contentType, input.contentLength);

    // L'identifiant est toujours généré par le backend, jamais fourni par l'app (ADR-016).
    const uploadId = randomUUID();
    await this.tickets.save({ uploadId, userId: user.id, ...input }, UPLOAD_TICKET_TTL_SECONDS);
    const uploadUrl = await this.storage.presignUpload({
      key: tmpKeyFor(uploadId),
      contentType: input.contentType,
      contentLength: input.contentLength,
      expiresInSeconds: UPLOAD_URL_TTL_SECONDS,
    });

    return {
      uploadId,
      uploadUrl,
      method: "PUT",
      headers: { "Content-Type": input.contentType, "Content-Length": String(input.contentLength) },
      expiresAt: new Date(this.clock.now().getTime() + UPLOAD_URL_TTL_SECONDS * 1000).toISOString(),
    };
  }
}

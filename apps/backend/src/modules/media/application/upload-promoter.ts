import { ConflictError, NotFoundError, ValidationError } from "../../../shared/domain/errors";
import type { AppLogger } from "../../../shared/domain/logger";
import { assertAcceptedMedia, type MediaPurpose } from "../domain/media-purpose";
import { tmpKeyFor } from "../domain/public-media-urls";
import type { ObjectStorage, StoredObject } from "./ports/object-storage.port";
import type { UploadTicketStore } from "./ports/upload-ticket.store";

const CONFIRMATION_LOCK_SECONDS = 120;

export interface PromoteUploadInput<T> {
  userId: string;
  uploadId: string;
  allowedPurposes: MediaPurpose[];
  destination: StoredObject;
  /** Écriture en base (Message, StoryItem, photo de profil), exécutée seulement après la copie. */
  persist: () => Promise<T>;
}

// Confirmation d'un upload temporaire, dans l'ordre imposé par ADR-016 :
// propriété (Redis) → HEAD (taille, type) → copie conditionnelle (ETag) → écriture en base
// → nettoyage du temporaire. Si l'écriture en base échoue, la copie est supprimée (best-effort).
// Utilisé par tous les contextes qui reçoivent un média (profil, snaps, stories).
export class UploadPromoter {
  constructor(
    private readonly storage: ObjectStorage,
    private readonly tickets: UploadTicketStore,
    private readonly logger: AppLogger,
  ) {}

  async promote<T>(input: PromoteUploadInput<T>): Promise<T> {
    const ticket = await this.tickets.find(input.uploadId);
    // Même erreur si l'upload est expiré ou appartient à quelqu'un d'autre : on ne confirme
    // jamais l'existence de l'upload d'un autre utilisateur.
    if (!ticket || ticket.userId !== input.userId) throw new NotFoundError("Upload introuvable ou expiré");
    if (!input.allowedPurposes.includes(ticket.purpose)) {
      throw new ValidationError(`Cet upload a été demandé pour ${ticket.purpose}`);
    }
    if (!(await this.tickets.lock(ticket.uploadId, CONFIRMATION_LOCK_SECONDS))) {
      throw new ConflictError("Cet upload est déjà en cours de confirmation");
    }

    const tmpKey = tmpKeyFor(ticket.uploadId);
    try {
      const metadata = await this.storage.head(tmpKey);
      if (!metadata) throw new ValidationError("Le fichier n'a pas encore été envoyé");
      // Déjà imposés par la signature de l'URL : revérifiés ici par défense en profondeur.
      if (metadata.size !== ticket.contentLength || metadata.contentType !== ticket.contentType) {
        throw new ValidationError("Le fichier envoyé ne correspond pas à l'upload demandé");
      }
      assertAcceptedMedia(ticket.purpose, metadata.contentType, metadata.size);

      await this.storage.copyFromPrivate(tmpKey, input.destination, metadata.etag);

      let result: T;
      try {
        result = await input.persist();
      } catch (error) {
        // Compensation : sans elle, la copie resterait orpheline dans un préfixe définitif,
        // qu'aucune règle de cycle de vie ne nettoie (ADR-016).
        await this.bestEffort(`suppression compensatoire de ${input.destination.key}`, () =>
          this.storage.delete(input.destination),
        );
        throw error;
      }

      await this.bestEffort(`nettoyage de ${tmpKey}`, () => this.storage.delete({ area: "private", key: tmpKey }));
      await this.bestEffort(`suppression du ticket ${ticket.uploadId}`, () => this.tickets.delete(ticket.uploadId));
      return result;
    } finally {
      await this.bestEffort(`déverrouillage du ticket ${ticket.uploadId}`, () => this.tickets.unlock(ticket.uploadId));
    }
  }

  private async bestEffort(what: string, action: () => Promise<void>): Promise<void> {
    try {
      await action();
    } catch (error) {
      this.logger.warn(`Échec (non bloquant) : ${what} — ${(error as Error).message}`);
    }
  }
}

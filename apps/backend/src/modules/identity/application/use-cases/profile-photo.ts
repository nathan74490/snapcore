import { randomUUID } from "node:crypto";
import { ConflictError } from "../../../../shared/domain/errors";
import type { AppLogger } from "../../../../shared/domain/logger";
import type { ObjectStorage } from "../../../media/application/ports/object-storage.port";
import type { UploadPromoter } from "../../../media/application/upload-promoter";
import type { PublicMediaUrls } from "../../../media/domain/public-media-urls";
import type { UserAccount } from "../../domain/user";
import type { UserRepository } from "../ports/user.repository";

const PHOTO_CHANGED = "La photo de profil a été modifiée entre-temps, réessayez";

// Photo de profil (ADR-014, ADR-016) : clé unique par upload (le CDN ne ressert jamais une
// image périmée sous la même URL), ancienne image supprimée une fois la nouvelle en place.
export class ProfilePhotoUseCases {
  constructor(
    private readonly users: UserRepository,
    private readonly promoter: UploadPromoter,
    private readonly storage: ObjectStorage,
    private readonly urls: PublicMediaUrls,
    private readonly logger: AppLogger,
  ) {}

  async change(user: UserAccount, uploadId: string): Promise<UserAccount> {
    const previousUrl = user.profilePhotoUrl;
    const key = `profiles/${user.id}/${randomUUID()}`;

    const updated = await this.promoter.promote({
      userId: user.id,
      uploadId,
      allowedPurposes: ["PROFILE_PHOTO"],
      destination: { area: "public", key },
      persist: async () => {
        const result = await this.users.replaceProfilePhoto(user.id, previousUrl, this.urls.urlFor(key));
        // Lever ici déclenche la suppression compensatoire de la nouvelle copie.
        if (!result) throw new ConflictError(PHOTO_CHANGED);
        return result;
      },
    });

    await this.deletePrevious(previousUrl);
    return updated;
  }

  async remove(user: UserAccount): Promise<UserAccount> {
    if (!user.profilePhotoUrl) return user;
    const updated = await this.users.replaceProfilePhoto(user.id, user.profilePhotoUrl, null);
    if (!updated) throw new ConflictError(PHOTO_CHANGED);
    await this.deletePrevious(user.profilePhotoUrl);
    return updated;
  }

  // La clé de l'ancienne image se déduit de son URL, sans champ dédié (ADR-016).
  // Best-effort : un échec laisse un objet orphelin, mais ne doit pas faire échouer la requête.
  private async deletePrevious(previousUrl: string | null): Promise<void> {
    const key = previousUrl ? this.urls.keyFromUrl(previousUrl) : null;
    if (!key) return;
    try {
      await this.storage.delete({ area: "public", key });
    } catch (error) {
      this.logger.warn(`Ancienne photo de profil non supprimée (${key}) : ${(error as Error).message}`);
    }
  }
}

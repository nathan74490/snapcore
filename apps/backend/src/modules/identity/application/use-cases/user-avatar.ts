import { noAnalytics, type Analytics } from "../../../../shared/domain/analytics";
import { parseAvatarOptions } from "../../../avatars/domain/avatar-catalog";
import type { UserAccount } from "../../domain/user";
import type { UserRepository } from "../ports/user.repository";

// Avatar dessiné du profil (ADR-028) : seules les options sont stockées, le PNG est rendu à la
// demande par GET /avatars/avataaars.png. Indépendant de la photo de profil (les deux coexistent).
export class UserAvatarUseCases {
  constructor(
    private readonly users: UserRepository,
    private readonly analytics: Analytics = noAnalytics,
  ) {}

  /** @throws ValidationError si une option est manquante, inconnue ou hors catalogue. */
  async set(user: UserAccount, rawOptions: unknown): Promise<UserAccount> {
    const options = parseAvatarOptions(rawOptions);
    const updated = await this.users.updateAvatar(user.id, options);
    this.analytics.capture(user.id, "avatar_saved");
    return updated;
  }

  async remove(user: UserAccount): Promise<UserAccount> {
    if (!user.avatar) return user;
    const updated = await this.users.updateAvatar(user.id, null);
    this.analytics.capture(user.id, "avatar_removed");
    return updated;
  }
}

import { noAnalytics, type Analytics } from "../../../../shared/domain/analytics";
import { UniqueViolationError } from "../../../../shared/domain/errors";
import { parseFirstName } from "../../domain/first-name";
import { parseLastName } from "../../domain/last-name";
import { parsePhoneNumber } from "../../domain/phone-number";
import type { UserAccount } from "../../domain/user";
import { parseUsername } from "../../domain/username";
import type { ProfilePatch, UserRepository } from "../ports/user.repository";
import { toProfileConflict } from "../unique-violation-messages";

export interface UpdateProfileInput {
  username?: string;
  firstName?: string;
  lastName?: string; // chaîne vide = retirer le nom (ADR-029)
  phoneNumber?: string | null;
}

// Tout le profil est modifiable, pseudo compris (ADR-014). L'email n'est pas modifiable ici :
// il passe par Firebase (verifyBeforeUpdateEmail) puis par SyncEmailFromIdentityProvider.
// La photo de profil passe par le pipeline média (ADR-016).
export class UpdateProfile {
  constructor(
    private readonly users: UserRepository,
    private readonly analytics: Analytics = noAnalytics,
  ) {}

  async execute(user: UserAccount, input: UpdateProfileInput): Promise<UserAccount> {
    const patch: ProfilePatch = {};
    if (input.username !== undefined) patch.username = parseUsername(input.username);
    if (input.firstName !== undefined) patch.firstName = parseFirstName(input.firstName);
    if (input.lastName !== undefined) patch.lastName = parseLastName(input.lastName);
    if (input.phoneNumber !== undefined) {
      patch.phoneNumber = input.phoneNumber === null ? null : parsePhoneNumber(input.phoneNumber);
    }
    if (Object.keys(patch).length === 0) return user;

    let updated: UserAccount;
    try {
      // L'unicité est garantie par la contrainte @unique en base, pas par une lecture préalable
      // (qui laisserait une fenêtre de course) : un conflit renvoie 409 sans modifier la ligne.
      updated = await this.users.updateProfile(user.id, patch);
    } catch (error) {
      if (error instanceof UniqueViolationError) throw toProfileConflict(error);
      throw error;
    }
    // Analytics : les NOMS des champs réellement modifiés, jamais leurs valeurs ; rien si la
    // requête renvoie les valeurs déjà en place.
    const fields = (Object.keys(patch) as Array<keyof ProfilePatch>).filter((field) => patch[field] !== user[field]);
    if (fields.length > 0) this.analytics.capture(user.id, "profile_updated", { fields: fields.join(",") });
    return updated;
  }
}

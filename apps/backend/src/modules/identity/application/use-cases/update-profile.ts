import { UniqueViolationError } from "../../../../shared/domain/errors";
import { parseFirstName } from "../../domain/first-name";
import { parsePhoneNumber } from "../../domain/phone-number";
import type { UserAccount } from "../../domain/user";
import { parseUsername } from "../../domain/username";
import type { ProfilePatch, UserRepository } from "../ports/user.repository";
import { toProfileConflict } from "../unique-violation-messages";

export interface UpdateProfileInput {
  username?: string;
  firstName?: string;
  phoneNumber?: string | null;
}

// Tout le profil est modifiable, pseudo compris (ADR-014). L'email n'est pas modifiable ici :
// il passe par Firebase (verifyBeforeUpdateEmail) puis par SyncEmailFromIdentityProvider.
// La photo de profil passe par le pipeline média (ADR-016).
export class UpdateProfile {
  constructor(private readonly users: UserRepository) {}

  async execute(user: UserAccount, input: UpdateProfileInput): Promise<UserAccount> {
    const patch: ProfilePatch = {};
    if (input.username !== undefined) patch.username = parseUsername(input.username);
    if (input.firstName !== undefined) patch.firstName = parseFirstName(input.firstName);
    if (input.phoneNumber !== undefined) {
      patch.phoneNumber = input.phoneNumber === null ? null : parsePhoneNumber(input.phoneNumber);
    }
    if (Object.keys(patch).length === 0) return user;

    try {
      // L'unicité est garantie par la contrainte @unique en base, pas par une lecture préalable
      // (qui laisserait une fenêtre de course) : un conflit renvoie 409 sans modifier la ligne.
      return await this.users.updateProfile(user.id, patch);
    } catch (error) {
      if (error instanceof UniqueViolationError) throw toProfileConflict(error);
      throw error;
    }
  }
}

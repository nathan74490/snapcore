import { UniqueViolationError } from "../../../../shared/domain/errors";
import type { UserAccount } from "../../domain/user";
import type { IdentityProvider } from "../ports/identity-provider.port";
import type { UserRepository } from "../ports/user.repository";
import { toProfileConflict } from "../unique-violation-messages";

// Appelé par l'app une fois le changement d'email confirmé côté Firebase (verifyBeforeUpdateEmail).
// L'email est relu chez Firebase, jamais pris dans la requête : User.email n'est qu'une copie
// de lecture et ne peut pas diverger de l'identifiant de connexion réel (ADR-014, ADR-015).
export class SyncEmailFromIdentityProvider {
  constructor(
    private readonly users: UserRepository,
    private readonly identity: IdentityProvider,
  ) {}

  async execute(user: UserAccount): Promise<UserAccount> {
    const email = await this.identity.getCurrentEmail(user.firebaseUid);
    if (email === user.email) return user;
    try {
      return await this.users.updateProfile(user.id, { email });
    } catch (error) {
      if (error instanceof UniqueViolationError) throw toProfileConflict(error);
      throw error;
    }
  }
}

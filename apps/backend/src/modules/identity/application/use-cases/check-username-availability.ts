import type { UsernameAvailability } from "@snapcore/types";
import { ValidationError } from "../../../../shared/domain/errors";
import { parseUsername } from "../../domain/username";
import type { UserRepository } from "../ports/user.repository";
import { USERNAME_TAKEN_MESSAGE } from "../unique-violation-messages";

// Vérification à la saisie pendant l'inscription, avant que le compte Firebase existe : la route
// est publique (ADR-029). Elle ne révèle rien d'autre que "pris ou non" : un pseudo porté par un
// compte supprimé ou suspendu est pris tant que la ligne existe, comme pour la contrainte @unique.
// Indicatif seulement : l'unicité reste garantie par la base au moment de l'inscription (409).
export class CheckUsernameAvailability {
  constructor(private readonly users: UserRepository) {}

  async execute(rawUsername: string): Promise<UsernameAvailability> {
    let username: string;
    try {
      username = parseUsername(rawUsername);
    } catch (error) {
      if (error instanceof ValidationError) return { available: false, reason: error.message };
      throw error;
    }
    if (await this.users.isUsernameTaken(username)) return { available: false, reason: USERNAME_TAKEN_MESSAGE };
    return { available: true };
  }
}

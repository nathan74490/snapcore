import { NotFoundError } from "../../../../shared/domain/errors";
import type { UserAccount } from "../../domain/user";
import type { UserRepository } from "../ports/user.repository";

// Résout le compte de la requête authentifiée. Ne crée JAMAIS de ligne User (ADR-015) et
// traite un compte en cours de suppression comme inexistant (ADR-019).
export class ResolveCurrentUser {
  constructor(private readonly users: UserRepository) {}

  async execute(firebaseUid: string): Promise<UserAccount> {
    const user = await this.users.findActiveByFirebaseUid(firebaseUid);
    if (!user) throw new NotFoundError("Compte introuvable");
    return user;
  }
}

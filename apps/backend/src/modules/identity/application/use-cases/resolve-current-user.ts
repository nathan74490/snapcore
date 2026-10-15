import { ForbiddenError, NotFoundError } from "../../../../shared/domain/errors";
import type { UserAccount } from "../../domain/user";
import type { UserRepository } from "../ports/user.repository";

// Résout le compte de la requête authentifiée. Ne crée JAMAIS de ligne User (ADR-015) et
// traite un compte en cours de suppression comme inexistant (ADR-019). Un compte suspendu
// reçoit un refus explicite (403) : sans lui, l'app croirait l'inscription interrompue (ADR-021).
export class ResolveCurrentUser {
  constructor(private readonly users: UserRepository) {}

  async execute(firebaseUid: string): Promise<UserAccount> {
    const user = await this.users.findActiveByFirebaseUid(firebaseUid);
    if (user) return user;
    const inactive = await this.users.findByFirebaseUidIncludingDeleted(firebaseUid);
    if (inactive && !inactive.deletionRequestedAt && inactive.moderationStatus === "SUSPENDED") {
      throw new ForbiddenError(SUSPENDED_MESSAGE);
    }
    throw new NotFoundError("Compte introuvable");
  }
}

export const SUSPENDED_MESSAGE = "Ce compte est suspendu";

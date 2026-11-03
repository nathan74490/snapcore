import { ConflictError, UniqueViolationError } from "../../../shared/domain/errors";

// Aussi renvoyé par GET /users/username-availability (ADR-029).
export const USERNAME_TAKEN_MESSAGE = "Ce pseudo est déjà pris";

const MESSAGES: Record<string, string> = {
  username: USERNAME_TAKEN_MESSAGE,
  email: "Cette adresse email est déjà utilisée",
  phoneNumber: "Ce numéro de téléphone est déjà utilisé",
};

export function toProfileConflict(error: UniqueViolationError): ConflictError {
  return new ConflictError(MESSAGES[error.field] ?? "Conflit avec un compte existant");
}

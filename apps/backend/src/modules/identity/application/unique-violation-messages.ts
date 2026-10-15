import { ConflictError, UniqueViolationError } from "../../../shared/domain/errors";

const MESSAGES: Record<string, string> = {
  username: "Ce pseudo est déjà pris",
  email: "Cette adresse email est déjà utilisée",
  phoneNumber: "Ce numéro de téléphone est déjà utilisé",
};

export function toProfileConflict(error: UniqueViolationError): ConflictError {
  return new ConflictError(MESSAGES[error.field] ?? "Conflit avec un compte existant");
}

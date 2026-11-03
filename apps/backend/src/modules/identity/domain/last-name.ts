import { ValidationError } from "../../../shared/domain/errors";
import { normalizePersonName } from "./first-name";

// Nom de famille facultatif (ADR-029) : mêmes règles de forme et de normalisation que le prénom.
// Une chaîne vide (ou faite d'espaces) ou absente signifie "pas de nom" : null.
export function parseLastName(raw: string | null | undefined): string | null {
  if (raw === null || raw === undefined || raw.trim() === "") return null;
  const lastName = normalizePersonName(raw);
  if (lastName === null) {
    throw new ValidationError(
      "Nom invalide : 1 à 30 caractères, lettres, espaces, apostrophes ou tirets uniquement",
    );
  }
  return lastName;
}

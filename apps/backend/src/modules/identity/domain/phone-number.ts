import { ValidationError } from "../../../shared/domain/errors";

const PHONE_PATTERN = /^\+?[0-9]{6,15}$/;

// Le numéro est collecté mais jamais vérifié (ADR-010) : on se contente d'un contrôle
// de forme et d'une normalisation (espaces, points, tirets retirés) pour que la contrainte
// d'unicité compare des valeurs comparables.
export function parsePhoneNumber(raw: string): string {
  const phone = raw.replace(/[\s.\-()]/g, "");
  if (!PHONE_PATTERN.test(phone)) {
    throw new ValidationError("Numéro de téléphone invalide");
  }
  return phone;
}

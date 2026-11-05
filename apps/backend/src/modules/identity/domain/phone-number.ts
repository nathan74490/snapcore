import { parsePhoneNumberFromString } from "libphonenumber-js";
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

/**
 * Numéro qui sert à se connecter (code SMS, ADR-034) : format international obligatoire, et numéro réellement
 * possible pour son pays (libphonenumber), comme l'exige Firebase. Un numéro français à 8 chiffres est refusé
 * dès la demande de code, plutôt qu'à sa vérification. Renvoie la forme E.164 (« +33612345678 »).
 */
export function parseInternationalPhone(raw: string): string {
  const phone = parsePhoneNumberFromString(raw.trim());
  if (!raw.trim().startsWith("+") || !phone?.isValid()) {
    throw new ValidationError("Ce numéro n'existe pas : vérifie le pays et le nombre de chiffres");
  }
  return phone.number;
}

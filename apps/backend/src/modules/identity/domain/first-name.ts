import { ValidationError } from "../../../shared/domain/errors";

// 1 à 30 caractères : lettres (accents et autres alphabets compris), espaces, apostrophes
// (droite ou typographique) et tirets, pour couvrir les prénoms composés ("Jean-Marc", "N'Golo").
// Mêmes règles pour le nom de famille (ADR-029, last-name.ts).
const PERSON_NAME_PATTERN = /^[\p{L}\p{M}' ’-]{1,30}$/u;

// Contrairement au pseudo, la casse est conservée : le nom est affiché tel que saisi.
// Les espaces multiples sont ramenés à un seul pour éviter des affichages incohérents.
// @returns le nom normalisé, ou null si sa forme est invalide.
export function normalizePersonName(raw: string): string | null {
  const name = raw.trim().replace(/\s+/g, " ");
  if (!PERSON_NAME_PATTERN.test(name) || !/\p{L}/u.test(name)) return null;
  return name;
}

export function parseFirstName(raw: string): string {
  const firstName = normalizePersonName(raw);
  if (firstName === null) {
    throw new ValidationError(
      "Prénom invalide : 1 à 30 caractères, lettres, espaces, apostrophes ou tirets uniquement",
    );
  }
  return firstName;
}

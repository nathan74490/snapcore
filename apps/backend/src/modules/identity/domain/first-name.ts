import { ValidationError } from "../../../shared/domain/errors";

// 1 à 30 caractères : lettres (accents et autres alphabets compris), espaces, apostrophes
// (droite ou typographique) et tirets, pour couvrir les prénoms composés ("Jean-Marc", "N'Golo").
const FIRST_NAME_PATTERN = /^[\p{L}\p{M}' ’-]{1,30}$/u;

// Contrairement au pseudo, la casse est conservée : le prénom est affiché tel que saisi.
// Les espaces multiples sont ramenés à un seul pour éviter des affichages incohérents.
export function parseFirstName(raw: string): string {
  const firstName = raw.trim().replace(/\s+/g, " ");
  if (!FIRST_NAME_PATTERN.test(firstName) || !/\p{L}/u.test(firstName)) {
    throw new ValidationError(
      "Prénom invalide : 1 à 30 caractères, lettres, espaces, apostrophes ou tirets uniquement",
    );
  }
  return firstName;
}

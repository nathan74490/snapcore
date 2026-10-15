import { ValidationError } from "../../../shared/domain/errors";

// 3 à 15 caractères, commence par une lettre, puis lettres, chiffres, ".", "_" ou "-"
// (mêmes règles que le pseudo Snapchat).
const USERNAME_PATTERN = /^[a-z][a-z0-9._-]{2,14}$/;

// Le pseudo est normalisé en minuscules avant stockage ET avant comparaison :
// l'unicité est insensible à la casse (ADR-014).
export function normalizeUsername(raw: string): string {
  return raw.trim().toLowerCase();
}

export function parseUsername(raw: string): string {
  const username = normalizeUsername(raw);
  if (!USERNAME_PATTERN.test(username)) {
    throw new ValidationError(
      "Pseudo invalide : 3 à 15 caractères, commence par une lettre, puis lettres, chiffres, '.', '_' ou '-'",
    );
  }
  return username;
}

import { ValidationError } from "../../../shared/domain/errors";

// Âge minimum pour s'inscrire (ADR-029), vérifié à l'inscription uniquement.
export const MINIMUM_AGE = 13;
export const TOO_YOUNG_MESSAGE = "Tu dois avoir au moins 13 ans pour t'inscrire";
const INVALID_BIRTHDAY_MESSAGE = "Date de naissance invalide : format attendu AAAA-MM-JJ, date existante et non future";

const BIRTHDAY_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/;

// Date de naissance au format YYYY-MM-DD, date réelle (pas de 31 avril ni de 29 février hors
// année bissextile), pas dans le futur, et au moins MINIMUM_AGE ans révolus à la date `today`.
// Le jour courant est celui de `today` en UTC : sans fuseau de l'utilisateur, l'anniversaire
// peut être reconnu à quelques heures près. Né un 29 février : l'anniversaire tombe le 1er mars
// les années non bissextiles.
// @returns la date à minuit UTC (colonne DATE, sans heure ni fuseau).
export function parseBirthday(raw: string, today: Date): Date {
  const match = BIRTHDAY_PATTERN.exec(raw.trim());
  if (!match) throw new ValidationError(INVALID_BIRTHDAY_MESSAGE);
  const [year, month, day] = match.slice(1).map(Number);

  const birthday = new Date(Date.UTC(year, month - 1, day));
  // Date.UTC "corrige" une date impossible (31/04 → 01/05) : on la refuse si elle a glissé.
  if (birthday.getUTCFullYear() !== year || birthday.getUTCMonth() !== month - 1 || birthday.getUTCDate() !== day) {
    throw new ValidationError(INVALID_BIRTHDAY_MESSAGE);
  }

  const todayYear = today.getUTCFullYear();
  const todayMonth = today.getUTCMonth() + 1;
  const todayDay = today.getUTCDate();
  const isFuture =
    year > todayYear || (year === todayYear && (month > todayMonth || (month === todayMonth && day > todayDay)));
  if (isFuture) throw new ValidationError(INVALID_BIRTHDAY_MESSAGE);

  const birthdayPassedThisYear = todayMonth > month || (todayMonth === month && todayDay >= day);
  const age = todayYear - year - (birthdayPassedThisYear ? 0 : 1);
  if (age < MINIMUM_AGE) throw new ValidationError(TOO_YOUNG_MESSAGE);

  return birthday;
}

// Inverse de parseBirthday pour l'API : "YYYY-MM-DD".
export function formatBirthday(birthday: Date): string {
  return birthday.toISOString().slice(0, 10);
}

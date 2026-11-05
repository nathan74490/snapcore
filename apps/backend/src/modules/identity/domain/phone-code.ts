import { createHash, randomInt, timingSafeEqual } from "node:crypto";

// Code de connexion envoyé par SMS (ADR-034).
export const PHONE_CODE_LENGTH = 6;
/** Durée de validité d'un code. */
export const PHONE_CODE_TTL_SECONDS = 5 * 60;
/** Délai minimum avant de redemander un code pour le même numéro. */
export const PHONE_CODE_RESEND_SECONDS = 30;
/** Essais avant que le code soit invalidé : il faut alors en redemander un. */
export const PHONE_CODE_MAX_ATTEMPTS = 5;

export function generatePhoneCode(): string {
  return randomInt(0, 10 ** PHONE_CODE_LENGTH).toString().padStart(PHONE_CODE_LENGTH, "0");
}

/** Le code n'est jamais stocké en clair : seulement son empreinte, liée au numéro. */
export function hashPhoneCode(phone: string, code: string): string {
  return createHash("sha256").update(`${phone}:${code}`).digest("hex");
}

export function phoneCodeMatches(phone: string, code: string, expectedHash: string): boolean {
  const actual = Buffer.from(hashPhoneCode(phone, code.trim()), "hex");
  const expected = Buffer.from(expectedHash, "hex");
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}

export function phoneCodeMessage(code: string): string {
  return `SnapCore : ton code de connexion est ${code}. Il expire dans 5 minutes. Ne le partage avec personne.`;
}

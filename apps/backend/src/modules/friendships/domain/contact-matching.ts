// Correspondance des contacts du téléphone avec les comptes SnapCore (ADR-029).
//
// Les numéros n'étant jamais vérifiés ni stockés au format international (ADR-010), on compare
// les 9 DERNIERS chiffres : "06 12 34 56 78", "+33 6 12 34 56 78" et "0033612345678" donnent tous
// "612345678", ce qui absorbe le 0 national et l'indicatif pays sans connaître le pays du contact.
// Limite assumée : deux numéros de pays différents qui partagent leurs 9 derniers chiffres
// correspondent l'un à l'autre (faux positif rare, sans fuite du numéro : seul le profil public
// est renvoyé) ; un numéro de moins de 9 chiffres ne correspond jamais à rien.
export const CONTACT_MATCH_DIGITS = 9;

// Clé de comparaison d'un numéro (saisi par l'app ou stocké) : ses 9 derniers chiffres,
// ou null s'il en compte moins (numéro ignoré).
export function contactMatchKey(rawPhoneNumber: string): string | null {
  const digits = rawPhoneNumber.replace(/\D/g, "");
  return digits.length < CONTACT_MATCH_DIGITS ? null : digits.slice(-CONTACT_MATCH_DIGITS);
}

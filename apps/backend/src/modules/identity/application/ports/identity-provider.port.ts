export const IDENTITY_PROVIDER = Symbol("IdentityProvider");

export interface VerifiedIdentity {
  firebaseUid: string;
  email: string | null;
}

// Fournisseur d'identité externe (Firebase Authentication, ADR-015).
export interface IdentityProvider {
  /**
   * Vérifie un ID token. Avec `checkRevoked`, vérifie aussi que le compte existe encore
   * et n'est ni désactivé ni révoqué — indispensable avant de créer une ligne User,
   * un token restant cryptographiquement valide ~1h après suppression du compte (ADR-015).
   * @throws UnauthenticatedError si le token est invalide, expiré, révoqué ou si le compte n'existe plus.
   */
  verifyIdToken(idToken: string, options: { checkRevoked: boolean }): Promise<VerifiedIdentity>;

  /** Email actuel du compte côté Firebase (source de vérité, ADR-014). */
  getCurrentEmail(firebaseUid: string): Promise<string | null>;
}

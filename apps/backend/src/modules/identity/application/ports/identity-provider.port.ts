export const IDENTITY_PROVIDER = Symbol("IdentityProvider");

export interface VerifiedIdentity {
  firebaseUid: string;
  email: string | null;
  /** Numéro vérifié par code SMS (ADR-034) ; null pour un compte email/mot de passe. */
  phoneNumber: string | null;
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

  /**
   * Connexion par téléphone, une fois le code SMS vérifié (ADR-034) : retrouve le compte Firebase lié à ce
   * numéro, ou le crée, et renvoie un jeton de connexion personnalisé que l'app échange contre une session.
   */
  signInWithPhoneNumber(phoneNumber: string): Promise<string>;

  /** Supprime le compte Firebase. Idempotent : un compte déjà supprimé n'est pas une erreur (ADR-019). */
  deleteAccount(firebaseUid: string): Promise<void>;
}

import { Injectable, Logger } from "@nestjs/common";
import type { App } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { UnauthenticatedError, ValidationError } from "../../../shared/domain/errors";
import type { IdentityProvider, VerifiedIdentity } from "../application/ports/identity-provider.port";

@Injectable()
export class FirebaseIdentityProvider implements IdentityProvider {
  private readonly logger = new Logger(FirebaseIdentityProvider.name);

  constructor(private readonly app: App) {}

  async verifyIdToken(idToken: string, options: { checkRevoked: boolean }): Promise<VerifiedIdentity> {
    try {
      // Avec checkRevoked = true, le SDK Admin relit le compte (getUser) : un compte supprimé
      // échoue avec auth/user-not-found, un compte désactivé avec auth/user-disabled (ADR-015).
      const decoded = await getAuth(this.app).verifyIdToken(idToken, options.checkRevoked);
      return { firebaseUid: decoded.uid, email: decoded.email ?? null, phoneNumber: decoded.phone_number ?? null };
    } catch (error) {
      // Seules les erreurs d'authentification deviennent un 401 : une erreur de configuration
      // (projet Firebase absent, identifiants manquants) doit rester visible en 500.
      if (!isFirebaseAuthError(error)) throw error;
      this.logger.debug(`ID token refusé : ${error.code}`);
      throw new UnauthenticatedError("Session invalide ou expirée");
    }
  }

  async getCurrentEmail(firebaseUid: string): Promise<string | null> {
    try {
      const record = await getAuth(this.app).getUser(firebaseUid);
      return record.email ?? null;
    } catch (error) {
      if (isUserNotFound(error)) throw new UnauthenticatedError("Session invalide ou expirée");
      throw error;
    }
  }

  async signInWithPhoneNumber(phoneNumber: string): Promise<string> {
    const auth = getAuth(this.app);
    let uid: string;
    try {
      uid = (await auth.getUserByPhoneNumber(phoneNumber)).uid;
    } catch (error) {
      if (!isUserNotFound(error)) throw error;
      // Premier code vérifié pour ce numéro : le compte Firebase naît ici, sans email ni mot de passe.
      // L'inscription SnapCore (POST /auth/register) suit, avec ce même compte.
      try {
        uid = (await auth.createUser({ phoneNumber })).uid;
      } catch (creation) {
        // Filet de sécurité : le numéro est déjà validé à la demande de code (libphonenumber).
        if (isFirebaseAuthError(creation) && creation.code === "auth/invalid-phone-number") {
          throw new ValidationError("Ce numéro n'existe pas : vérifie le pays et le nombre de chiffres");
        }
        throw creation;
      }
    }
    // Jeton personnalisé, gratuit (l'envoi de SMS par Firebase exige le plan Blaze, ADR-010) : l'app l'échange
    // contre une session Firebase ordinaire, dont l'ID token porte phone_number.
    return auth.createCustomToken(uid);
  }

  async deleteAccount(firebaseUid: string): Promise<void> {
    try {
      await getAuth(this.app).deleteUser(firebaseUid);
    } catch (error) {
      // Déjà supprimé lors d'une tentative précédente du job : succès (ADR-019).
      if (!isUserNotFound(error)) throw error;
    }
  }
}

export function isUserNotFound(error: unknown): boolean {
  return isFirebaseAuthError(error) && error.code === "auth/user-not-found";
}

function isFirebaseAuthError(error: unknown): error is { code: string } {
  const code = (error as { code?: unknown } | null)?.code;
  return typeof code === "string" && code.startsWith("auth/");
}

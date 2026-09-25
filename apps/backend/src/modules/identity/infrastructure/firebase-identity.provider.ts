import { Injectable, Logger } from "@nestjs/common";
import type { App } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { UnauthenticatedError } from "../../../shared/domain/errors";
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
      return { firebaseUid: decoded.uid, email: decoded.email ?? null };
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
      if (isFirebaseAuthError(error) && error.code === "auth/user-not-found") {
        throw new UnauthenticatedError("Session invalide ou expirée");
      }
      throw error;
    }
  }
}

function isFirebaseAuthError(error: unknown): error is { code: string } {
  const code = (error as { code?: unknown } | null)?.code;
  return typeof code === "string" && code.startsWith("auth/");
}

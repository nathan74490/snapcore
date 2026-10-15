import { CanActivate, ExecutionContext, Injectable } from "@nestjs/common";
import { ConfigService } from "@nestjs/config";
import { timingSafeEqual } from "node:crypto";
import { UnauthenticatedError } from "../../../shared/domain/errors";

export const ADMIN_SECRET_HEADER = "x-admin-secret";

// Protège l'API admin (ADR-021) : l'appelant est le serveur Next.js du backoffice, qui a déjà
// vérifié la session du modérateur (ADR-020). Le secret n'est jamais envoyé au navigateur.
// Sans ADMIN_API_SECRET configuré, l'API admin est entièrement fermée.
@Injectable()
export class AdminSecretGuard implements CanActivate {
  private readonly secret: Buffer | null;

  constructor(config: ConfigService) {
    const value = config.get<string>("ADMIN_API_SECRET");
    this.secret = value && value.length >= 32 ? Buffer.from(value) : null;
  }

  canActivate(context: ExecutionContext): boolean {
    const header = context.switchToHttp().getRequest<{ headers: Record<string, unknown> }>().headers[ADMIN_SECRET_HEADER];
    const provided = typeof header === "string" ? Buffer.from(header) : null;
    // Comparaison à temps constant : pas de fuite du secret par mesure du temps de réponse.
    if (!this.secret || !provided || provided.length !== this.secret.length || !timingSafeEqual(provided, this.secret)) {
      throw new UnauthenticatedError("Accès admin refusé");
    }
    return true;
  }
}

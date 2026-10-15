import { CanActivate, ExecutionContext, Injectable } from "@nestjs/common";
import { ConfigService } from "@nestjs/config";
import { timingSafeEqual } from "node:crypto";
import { UnauthenticatedError } from "../../../shared/domain/errors";

// Webhook RevenueCat (ADR-025) : RevenueCat envoie dans l'en-tête Authorization la valeur configurée
// dans son tableau de bord. Sans REVENUECAT_WEBHOOK_SECRET (32 caractères minimum), le webhook est fermé.
@Injectable()
export class RevenueCatWebhookGuard implements CanActivate {
  private readonly secret: Buffer | null;

  constructor(config: ConfigService) {
    const value = config.get<string>("REVENUECAT_WEBHOOK_SECRET");
    this.secret = value && value.length >= 32 ? Buffer.from(value) : null;
  }

  canActivate(context: ExecutionContext): boolean {
    const header = context.switchToHttp().getRequest<{ headers: Record<string, unknown> }>().headers.authorization;
    // Accepte la valeur brute ou préfixée de « Bearer », selon ce qui a été saisi dans le tableau de bord.
    const value = typeof header === "string" ? header.replace(/^Bearer\s+/i, "") : null;
    const provided = value ? Buffer.from(value) : null;
    // Comparaison à temps constant, comme pour l'API admin (ADR-021).
    if (!this.secret || !provided || provided.length !== this.secret.length || !timingSafeEqual(provided, this.secret)) {
      throw new UnauthenticatedError("Webhook refusé");
    }
    return true;
  }
}

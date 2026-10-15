import { NotFoundError, ValidationError } from "../../../shared/domain/errors";
import type { UserAccount } from "../../identity/domain/user";
import type { DeviceTokenRepository } from "./ports";

const MAX_TOKEN_LENGTH = 4096;

export class DeviceTokenUseCases {
  constructor(private readonly devices: DeviceTokenRepository) {}

  // Appelé par l'app à chaque démarrage et à chaque renouvellement du token FCM (ADR-018).
  async register(me: UserAccount, rawToken: string): Promise<void> {
    await this.devices.register(me.id, parseToken(rawToken));
  }

  // Au logout : l'appareil ne doit plus recevoir de push pour le compte quitté (ADR-018).
  async unregister(me: UserAccount, rawToken: string): Promise<void> {
    if (!(await this.devices.unregister(me.id, parseToken(rawToken)))) {
      throw new NotFoundError("Appareil introuvable");
    }
  }
}

function parseToken(raw: string): string {
  const token = raw.trim();
  if (!token || token.length > MAX_TOKEN_LENGTH) throw new ValidationError("Token d'appareil invalide");
  return token;
}

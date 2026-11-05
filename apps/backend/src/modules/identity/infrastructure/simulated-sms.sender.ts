import { Logger } from "@nestjs/common";
import type { SentSms, SmsSender } from "../application/ports/phone-sign-in.ports";

/**
 * Opérateur SMS fictif (ADR-034) : aucun SMS ne part. Le message est écrit dans les journaux du backend, et
 * le code revient à l'app, qui l'affiche comme un SMS reçu. Un vrai opérateur (Twilio, Firebase Phone Auth
 * avec le plan Blaze…) prendra sa place derrière le même port.
 */
export class SimulatedSmsSender implements SmsSender {
  private readonly logger = new Logger("SMS simulé");

  async send(to: string, text: string): Promise<SentSms> {
    this.logger.log(`→ ${maskPhone(to)} : ${text}`);
    return { simulated: true };
  }
}

/** « +33612345678 » → « +33•••••5678 » : les journaux ne gardent pas le numéro complet. */
export function maskPhone(phone: string): string {
  return phone.length <= 7 ? phone : phone.slice(0, 3) + "•".repeat(phone.length - 7) + phone.slice(-4);
}

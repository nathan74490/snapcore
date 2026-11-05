import { systemClock, type Clock } from "../../../../shared/domain/clock";
import { ValidationError } from "../../../../shared/domain/errors";
import {
  generatePhoneCode,
  hashPhoneCode,
  PHONE_CODE_MAX_ATTEMPTS,
  PHONE_CODE_RESEND_SECONDS,
  PHONE_CODE_TTL_SECONDS,
  phoneCodeMatches,
  phoneCodeMessage,
} from "../../domain/phone-code";
import { parseInternationalPhone } from "../../domain/phone-number";
import type { IdentityProvider } from "../ports/identity-provider.port";
import type { PhoneCodeStore, SmsSender } from "../ports/phone-sign-in.ports";

export interface PhoneCodeSent {
  phoneNumber: string;
  expiresInSeconds: number;
  resendInSeconds: number;
  /** Présent seulement quand l'envoi est simulé (ADR-034) : l'app l'affiche comme un SMS reçu. */
  simulatedCode?: string;
}

// Connexion et inscription par numéro de téléphone et code SMS (ADR-034). Les deux routes sont publiques :
// c'est le code qui prouve que l'on détient le numéro.
export class PhoneSignIn {
  constructor(
    private readonly codes: PhoneCodeStore,
    private readonly sms: SmsSender,
    private readonly identity: IdentityProvider,
    private readonly clock: Clock = systemClock,
  ) {}

  async sendCode(rawPhone: string): Promise<PhoneCodeSent> {
    const phoneNumber = parseInternationalPhone(rawPhone);
    const pending = await this.codes.get(phoneNumber);
    if (pending) {
      const elapsed = (this.clock.now().getTime() - pending.sentAt.getTime()) / 1000;
      if (elapsed < PHONE_CODE_RESEND_SECONDS) {
        const wait = Math.ceil(PHONE_CODE_RESEND_SECONDS - elapsed);
        throw new ValidationError(`Attends encore ${wait} s avant de demander un nouveau code`);
      }
    }
    const code = generatePhoneCode();
    await this.codes.save(phoneNumber, { hash: hashPhoneCode(phoneNumber, code), attempts: 0, sentAt: this.clock.now() },
      PHONE_CODE_TTL_SECONDS);
    const sent = await this.sms.send(phoneNumber, phoneCodeMessage(code));
    return {
      phoneNumber,
      expiresInSeconds: PHONE_CODE_TTL_SECONDS,
      resendInSeconds: PHONE_CODE_RESEND_SECONDS,
      ...(sent.simulated ? { simulatedCode: code } : {}),
    };
  }

  /** Renvoie un jeton de connexion Firebase si le code est le bon. */
  async verifyCode(rawPhone: string, code: string): Promise<{ customToken: string }> {
    const phoneNumber = parseInternationalPhone(rawPhone);
    const pending = await this.codes.get(phoneNumber);
    if (!pending) throw new ValidationError("Ce code a expiré. Demande un nouveau code.");
    if (!/^\d{6}$/.test(code.trim()) || !phoneCodeMatches(phoneNumber, code, pending.hash)) {
      const attempts = await this.codes.recordFailedAttempt(phoneNumber);
      if (attempts >= PHONE_CODE_MAX_ATTEMPTS) {
        await this.codes.delete(phoneNumber);
        throw new ValidationError("Trop d'essais. Demande un nouveau code.");
      }
      throw new ValidationError("Code incorrect. Vérifie le SMS et réessaie.");
    }
    // Un code ne sert qu'une fois.
    await this.codes.delete(phoneNumber);
    return { customToken: await this.identity.signInWithPhoneNumber(phoneNumber) };
  }
}

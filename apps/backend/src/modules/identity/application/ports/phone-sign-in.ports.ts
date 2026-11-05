export const PHONE_CODE_STORE = Symbol("PhoneCodeStore");
export const SMS_SENDER = Symbol("SmsSender");

export interface PendingPhoneCode {
  hash: string;
  attempts: number;
  sentAt: Date;
}

/** Code en attente par numéro, éphémère (Redis, ADR-003) : il disparaît seul à expiration. */
export interface PhoneCodeStore {
  save(phone: string, code: PendingPhoneCode, ttlSeconds: number): Promise<void>;
  get(phone: string): Promise<PendingPhoneCode | null>;
  /** Compte un essai raté et renvoie le nombre d'essais ; garde l'expiration en cours. */
  recordFailedAttempt(phone: string): Promise<number>;
  delete(phone: string): Promise<void>;
}

export interface SentSms {
  /**
   * Vrai quand l'envoi est simulé (aucun opérateur SMS) : le code est alors renvoyé à l'app, qui l'affiche
   * comme un SMS reçu. Jamais vrai avec un vrai opérateur.
   */
  simulated: boolean;
}

/** Passerelle d'envoi de SMS. */
export interface SmsSender {
  send(to: string, text: string): Promise<SentSms>;
}

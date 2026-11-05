import { ValidationError } from "../../../../shared/domain/errors";
import { FakeIdentityProvider, FixedClock } from "../../../../test/fakes";
import { InMemoryUserRepository } from "../../../../test/in-memory-user.repository";
import type { PendingPhoneCode, PhoneCodeStore, SentSms, SmsSender } from "../ports/phone-sign-in.ports";
import { PhoneSignIn } from "./phone-sign-in";
import { RegisterUser } from "./register-user";

class InMemoryPhoneCodes implements PhoneCodeStore {
  readonly codes = new Map<string, PendingPhoneCode>();
  async save(phone: string, code: PendingPhoneCode): Promise<void> {
    this.codes.set(phone, { ...code });
  }
  async get(phone: string): Promise<PendingPhoneCode | null> {
    return this.codes.get(phone) ?? null;
  }
  async recordFailedAttempt(phone: string): Promise<number> {
    const code = this.codes.get(phone)!;
    code.attempts += 1;
    return code.attempts;
  }
  async delete(phone: string): Promise<void> {
    this.codes.delete(phone);
  }
}

class RecordingSms implements SmsSender {
  readonly sent: Array<{ to: string; text: string }> = [];
  constructor(private readonly simulated = true) {}
  async send(to: string, text: string): Promise<SentSms> {
    this.sent.push({ to, text });
    return { simulated: this.simulated };
  }
}

describe("PhoneSignIn (ADR-034)", () => {
  let codes: InMemoryPhoneCodes;
  let sms: RecordingSms;
  let identity: FakeIdentityProvider;
  let clock: FixedClock;
  let phone: PhoneSignIn;

  beforeEach(() => {
    codes = new InMemoryPhoneCodes();
    sms = new RecordingSms();
    identity = new FakeIdentityProvider();
    clock = new FixedClock();
    phone = new PhoneSignIn(codes, sms, identity, clock);
  });

  it("envoie un code à 6 chiffres par SMS et ne stocke que son empreinte", async () => {
    const sent = await phone.sendCode("+33 6 12 34 56 78");

    expect(sent.phoneNumber).toBe("+33612345678");
    expect(sent.simulatedCode).toMatch(/^\d{6}$/);
    expect(sms.sent).toEqual([{ to: "+33612345678", text: expect.stringContaining(sent.simulatedCode!) }]);
    expect(codes.codes.get("+33612345678")!.hash).not.toContain(sent.simulatedCode!);
  });

  it("ne renvoie jamais le code à l'app avec un vrai opérateur", async () => {
    phone = new PhoneSignIn(codes, new RecordingSms(false), identity, clock);
    expect((await phone.sendCode("+33612345678")).simulatedCode).toBeUndefined();
  });

  it("refuse un numéro sans indicatif international", async () => {
    await expect(phone.sendCode("06 12 34 56 78")).rejects.toThrow(ValidationError);
  });

  it("refuse dès la demande de code un numéro qui n'existe pas (8 chiffres en France), sans envoyer de SMS", async () => {
    await expect(phone.sendCode("+33 6 12 34 77 89".slice(0, -1))).rejects.toThrow("Ce numéro n'existe pas");
    await expect(phone.sendCode("+33 1234")).rejects.toThrow(ValidationError);
    expect(sms.sent).toHaveLength(0);
  });

  it("fait attendre 30 s avant de redemander un code", async () => {
    await phone.sendCode("+33612345678");
    clock.current = new Date(clock.current.getTime() + 10_000);
    await expect(phone.sendCode("+33612345678")).rejects.toThrow("Attends encore 20 s");
    clock.current = new Date(clock.current.getTime() + 20_000);
    await expect(phone.sendCode("+33612345678")).resolves.toBeDefined();
  });

  it("le bon code donne un jeton de connexion, une seule fois", async () => {
    const { simulatedCode } = await phone.sendCode("+33612345678");

    const { customToken } = await phone.verifyCode("+33612345678", simulatedCode!);

    expect(customToken).toMatch(/^custom-/);
    await expect(phone.verifyCode("+33612345678", simulatedCode!)).rejects.toThrow("expiré");
  });

  it("retrouve le même compte Firebase au code suivant pour le même numéro", async () => {
    const first = await phone.sendCode("+33612345678");
    const token1 = (await phone.verifyCode("+33612345678", first.simulatedCode!)).customToken;
    clock.current = new Date(clock.current.getTime() + 60_000);
    const second = await phone.sendCode("+33612345678");
    const token2 = (await phone.verifyCode("+33612345678", second.simulatedCode!)).customToken;

    expect(token2).toBe(token1);
  });

  it("un mauvais code est refusé, et 5 erreurs invalident le code", async () => {
    const { simulatedCode } = await phone.sendCode("+33612345678");
    const wrong = simulatedCode === "000000" ? "111111" : "000000";

    for (let attempt = 1; attempt < 5; attempt++) {
      await expect(phone.verifyCode("+33612345678", wrong)).rejects.toThrow("Code incorrect");
    }
    await expect(phone.verifyCode("+33612345678", wrong)).rejects.toThrow("Trop d'essais");
    await expect(phone.verifyCode("+33612345678", simulatedCode!)).rejects.toThrow("expiré");
  });

  it("à l'inscription, le numéro vérifié du compte Firebase est enregistré, sans email", async () => {
    const { simulatedCode } = await phone.sendCode("+33612345678");
    const uid = (await phone.verifyCode("+33612345678", simulatedCode!)).customToken.slice("custom-".length);
    const users = new InMemoryUserRepository();

    const user = await new RegisterUser(users, identity, undefined, clock).execute({
      idToken: `token-${uid}`, username: "lea", firstName: "Léa", birthday: "2005-03-14", phoneNumber: "+33 7 00 00 00 00",
    });

    expect(user).toMatchObject({ phoneNumber: "+33612345678", email: null, lastName: null });
  });
});

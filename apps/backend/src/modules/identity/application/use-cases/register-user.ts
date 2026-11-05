import { noAnalytics, type Analytics } from "../../../../shared/domain/analytics";
import { systemClock, type Clock } from "../../../../shared/domain/clock";
import { ForbiddenError, NotFoundError, UniqueViolationError } from "../../../../shared/domain/errors";
import { parseBirthday } from "../../domain/birthday";
import { parseFirstName } from "../../domain/first-name";
import { parseLastName } from "../../domain/last-name";
import { parsePhoneNumber } from "../../domain/phone-number";
import type { UserAccount } from "../../domain/user";
import { parseUsername } from "../../domain/username";
import type { IdentityProvider } from "../ports/identity-provider.port";
import type { UserRepository } from "../ports/user.repository";
import { toProfileConflict } from "../unique-violation-messages";
import { SUSPENDED_MESSAGE } from "./resolve-current-user";

export interface RegisterUserInput {
  idToken: string;
  username: string;
  firstName: string;
  lastName?: string;
  birthday: string; // YYYY-MM-DD, obligatoire pour toute nouvelle inscription (ADR-029)
  phoneNumber?: string;
}

// Seul point de création d'une ligne User (ADR-015) : aucune autre requête ne recrée un compte.
export class RegisterUser {
  constructor(
    private readonly users: UserRepository,
    private readonly identity: IdentityProvider,
    private readonly analytics: Analytics = noAnalytics,
    private readonly clock: Clock = systemClock,
  ) {}

  async execute(input: RegisterUserInput): Promise<UserAccount> {
    // checkRevoked : un token émis avant une suppression de compte reste valide ~1h ;
    // sans cette vérification, rejouer ce token recréerait le compte supprimé (ADR-015, ADR-019).
    const identity = await this.identity.verifyIdToken(input.idToken, { checkRevoked: true });

    const existing = await this.users.findByFirebaseUidIncludingDeleted(identity.firebaseUid);
    if (existing) return this.alreadyRegistered(existing);

    const username = parseUsername(input.username);
    const firstName = parseFirstName(input.firstName);
    const lastName = parseLastName(input.lastName);
    const birthday = parseBirthday(input.birthday, this.clock.now());
    // Numéro vérifié par code SMS (ADR-034) : celui du compte Firebase prime sur celui du corps de la requête.
    const phoneNumber = identity.phoneNumber ?? (input.phoneNumber ? parsePhoneNumber(input.phoneNumber) : null);

    try {
      const user = await this.users.create({
        username,
        firstName,
        lastName,
        birthday,
        firebaseUid: identity.firebaseUid,
        // Firebase est la source de vérité de l'email (ADR-014) : jamais pris dans le corps de la requête.
        email: identity.email,
        phoneNumber,
      });
      this.analytics.capture(user.id, "user_registered", {
        with_phone: phoneNumber !== null,
        method: identity.phoneNumber ? "phone" : "email",
      });
      return user;
    } catch (error) {
      if (!(error instanceof UniqueViolationError)) throw error;
      if (error.field === "firebaseUid") {
        // Deux appels concurrents pour le même compte (réessai de l'app) : l'autre a gagné.
        const winner = await this.users.findByFirebaseUidIncludingDeleted(identity.firebaseUid);
        if (winner) return this.alreadyRegistered(winner);
      }
      throw toProfileConflict(error);
    }
  }

  // L'app réessaie l'inscription au lancement suivant si la réponse a été perdue (ADR-015) :
  // l'appel est donc idempotent pour un compte actif.
  private alreadyRegistered(user: UserAccount): UserAccount {
    if (user.deletionRequestedAt) throw new NotFoundError("Compte introuvable");
    if (user.moderationStatus === "SUSPENDED") throw new ForbiddenError(SUSPENDED_MESSAGE);
    return user;
  }
}

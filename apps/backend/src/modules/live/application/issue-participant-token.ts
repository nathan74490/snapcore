import { createHmac } from "node:crypto";
import type { Clock } from "../../../shared/domain/clock";
import { UnavailableError } from "../../../shared/domain/errors";
import type { UserAccount } from "../../identity/domain/user";

/** Identifiant de SnapCore parmi les App membres du serveur Live commun (ADR-035). */
export const LIVE_MEMBER_APP = "snapcore";

const TOKEN_LIFETIME_SECONDS = 5 * 60; // 5 min au plus, exigé par le serveur commun
const NAME_MAX_LENGTH = 30; // caractères visibles

/**
 * Jeton de Participant du SDK Live (ADR-035) : JWT HS256 signé avec le secret de l'App membre
 * snapcore, frais à chaque appel (le SDK en redemande un à chaque connexion). Aucun rôle :
 * le serveur commun décide seul qui est Hôte, Co-hôte ou Spectateur.
 */
export class IssueParticipantToken {
  constructor(
    private readonly secret: string | null,
    private readonly clock: Clock,
  ) {}

  execute(user: UserAccount): string {
    if (!this.secret) throw new UnavailableError("Le Live n'est pas configuré sur ce serveur.");
    const issuedAt = Math.floor(this.clock.now().getTime() / 1000);
    const picture = user.profilePhotoUrl?.startsWith("https://") ? user.profilePhotoUrl : null;
    const claims = {
      iss: LIVE_MEMBER_APP,
      sub: user.id,
      // Le prénom, comme partout dans l'app ; le nom de famille reste privé (ADR-029).
      name: Array.from(user.firstName ?? user.username).slice(0, NAME_MAX_LENGTH).join(""),
      ...(picture ? { picture } : {}),
      iat: issuedAt,
      exp: issuedAt + TOKEN_LIFETIME_SECONDS,
    };
    return signHs256(claims, this.secret);
  }
}

function signHs256(claims: object, secret: string): string {
  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString("base64url");
  const unsigned = `${encode({ alg: "HS256", typ: "JWT" })}.${encode(claims)}`;
  return `${unsigned}.${createHmac("sha256", secret).update(unsigned).digest("base64url")}`;
}

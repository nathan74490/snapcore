import { createHmac } from "node:crypto";
import type { UserAccount } from "../../identity/domain/user";
import { UnavailableError } from "../../../shared/domain/errors";
import { IssueParticipantToken } from "./issue-participant-token";

const SECRET = "s".repeat(32);
const NOW = new Date("2026-11-06T10:00:00Z");
const NOW_SECONDS = NOW.getTime() / 1000;

const user = (overrides: Partial<UserAccount> = {}): UserAccount => ({
  id: "u_42",
  username: "lea.snap",
  firstName: "Léa",
  lastName: "Martin",
  birthday: null,
  firebaseUid: "fb_42",
  profilePhotoUrl: "https://cdn.snapcore.example/p/42.jpg",
  avatar: null,
  phoneNumber: "+33600000000",
  email: null,
  subscriptionTier: "free",
  premiumUntil: null,
  createdAt: NOW,
  deletionRequestedAt: null,
  moderationStatus: "ACTIVE",
  ...overrides,
});

const base64Url = (value: Buffer | string) => Buffer.from(value).toString("base64url");

/** Vérifie la signature HS256 avec [secret] et rend l'en-tête et les claims. */
function verify(token: string, secret: string): { header: unknown; claims: Record<string, unknown> } {
  const [header, payload, signature] = token.split(".");
  expect(signature).toBe(base64Url(createHmac("sha256", secret).update(`${header}.${payload}`).digest()));
  return {
    header: JSON.parse(Buffer.from(header, "base64url").toString()),
    claims: JSON.parse(Buffer.from(payload, "base64url").toString()),
  };
}

describe("Jeton de Participant du SDK Live", () => {
  const issue = (secret: string | null = SECRET) => new IssueParticipantToken(secret, { now: () => NOW });

  it("signe en HS256 un jeton snapcore de 5 min : identifiant, prénom et photo de profil", () => {
    const { header, claims } = verify(issue().execute(user()), SECRET);

    expect(header).toEqual({ alg: "HS256", typ: "JWT" });
    expect(claims).toEqual({
      iss: "snapcore",
      sub: "u_42",
      name: "Léa",
      picture: "https://cdn.snapcore.example/p/42.jpg",
      iat: NOW_SECONDS,
      exp: NOW_SECONDS + 300,
    });
  });

  it("sans prénom, prend le nom d'utilisateur ; sans photo https, n'envoie pas de picture", () => {
    const { claims } = verify(issue().execute(user({ firstName: null, profilePhotoUrl: "http://local/p.jpg" })), SECRET);

    expect(claims.name).toBe("lea.snap");
    expect(claims).not.toHaveProperty("picture");
  });

  it("coupe le pseudo à 30 caractères, emoji compris", () => {
    const { claims } = verify(issue().execute(user({ firstName: "🦊".repeat(40) })), SECRET);

    expect(claims.name).toBe("🦊".repeat(30));
  });

  it("sans secret d'App membre, le Live est indisponible", () => {
    expect(() => issue(null).execute(user())).toThrow(UnavailableError);
  });
});

import { InMemoryUserRepository } from "../../../../test/in-memory-user.repository";
import { CheckUsernameAvailability } from "./check-username-availability";

describe("CheckUsernameAvailability", () => {
  let users: InMemoryUserRepository;
  let check: CheckUsernameAvailability;

  beforeEach(() => {
    users = new InMemoryUserRepository();
    check = new CheckUsernameAvailability(users);
  });

  it("un pseudo libre est disponible, sans raison", async () => {
    await expect(check.execute("nathan")).resolves.toEqual({ available: true });
  });

  it("normalise le pseudo (casse, espaces) avant de comparer", async () => {
    await users.seed("nathan");

    await expect(check.execute("  NaThAn ")).resolves.toEqual({ available: false, reason: "Ce pseudo est déjà pris" });
  });

  it("un pseudo d'un compte supprimé ou suspendu reste pris tant que la ligne existe", async () => {
    await users.seed("parti", { deletionRequestedAt: new Date() });
    await users.seed("suspendu", { moderationStatus: "SUSPENDED" });

    await expect(check.execute("parti")).resolves.toEqual({ available: false, reason: "Ce pseudo est déjà pris" });
    await expect(check.execute("suspendu")).resolves.toEqual({ available: false, reason: "Ce pseudo est déjà pris" });
  });

  it("un format invalide n'est pas disponible, avec le message de règle du pseudo", async () => {
    const result = await check.execute("1abc");

    expect(result).toEqual({
      available: false,
      reason: "Pseudo invalide : 3 à 15 caractères, commence par une lettre, puis lettres, chiffres, '.', '_' ou '-'",
    });
  });
});

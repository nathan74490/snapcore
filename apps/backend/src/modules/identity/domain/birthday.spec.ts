import { ValidationError } from "../../../shared/domain/errors";
import { formatBirthday, parseBirthday, TOO_YOUNG_MESSAGE } from "./birthday";

describe("parseBirthday", () => {
  const today = new Date("2026-09-25T10:00:00Z");

  it("renvoie la date à minuit UTC", () => {
    expect(parseBirthday("2000-01-31", today)).toEqual(new Date("2000-01-31T00:00:00Z"));
  });

  it("accepte 13 ans révolus le jour même de l'anniversaire, refuse la veille", () => {
    expect(parseBirthday("2013-09-25", today)).toEqual(new Date("2013-09-25T00:00:00Z"));
    expect(() => parseBirthday("2013-09-26", today)).toThrow(new ValidationError(TOO_YOUNG_MESSAGE));
    expect(() => parseBirthday("2020-01-01", today)).toThrow(new ValidationError(TOO_YOUNG_MESSAGE));
  });

  it("le message d'âge est exactement celui attendu par l'app", () => {
    expect(TOO_YOUNG_MESSAGE).toBe("Tu dois avoir au moins 13 ans pour t'inscrire");
  });

  it("né un 29 février : 13 ans le 1er mars d'une année non bissextile", () => {
    const leapBorn = "2012-02-29";
    expect(() => parseBirthday(leapBorn, new Date("2025-02-28T12:00:00Z"))).toThrow(new ValidationError(TOO_YOUNG_MESSAGE));
    expect(parseBirthday(leapBorn, new Date("2025-03-01T00:00:00Z"))).toEqual(new Date("2012-02-29T00:00:00Z"));
  });

  it.each(["", "2000-1-01", "01/01/2000", "2000-01-01T00:00:00Z", "2000-13-01", "2000-04-31", "2001-02-29", "2000-00-10"])(
    "refuse %j (format ou date inexistante)",
    (raw) => {
      expect(() => parseBirthday(raw, today)).toThrow(ValidationError);
      expect(() => parseBirthday(raw, today)).not.toThrow(TOO_YOUNG_MESSAGE);
    },
  );

  it("refuse une date future avec le message de date invalide", () => {
    expect(() => parseBirthday("2026-09-26", today)).toThrow(/Date de naissance invalide/);
    expect(() => parseBirthday("2030-01-01", today)).toThrow(/Date de naissance invalide/);
  });

  it("formatBirthday renvoie YYYY-MM-DD", () => {
    expect(formatBirthday(new Date("2005-03-14T00:00:00Z"))).toBe("2005-03-14");
  });
});

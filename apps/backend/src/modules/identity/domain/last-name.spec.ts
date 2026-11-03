import { ValidationError } from "../../../shared/domain/errors";
import { parseLastName } from "./last-name";

describe("parseLastName", () => {
  it.each([
    ["Dupont", "Dupont"],
    ["  de  La Fontaine ", "de La Fontaine"],
    ["Saint-Exupéry", "Saint-Exupéry"],
    ["O’Connor", "O’Connor"],
    ["a".repeat(30), "a".repeat(30)],
  ])("accepte %j", (raw, expected) => {
    expect(parseLastName(raw)).toBe(expected);
  });

  it.each([undefined, null, "", "   "])("traite %j comme l'absence de nom", (raw) => {
    expect(parseLastName(raw)).toBeNull();
  });

  it.each(["Dupont2", "du_pont", "<script>", "-", "'", "a".repeat(31)])("refuse %j", (raw) => {
    expect(() => parseLastName(raw)).toThrow(
      new ValidationError("Nom invalide : 1 à 30 caractères, lettres, espaces, apostrophes ou tirets uniquement"),
    );
  });
});

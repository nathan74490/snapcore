import { ValidationError } from "../../../shared/domain/errors";
import { parseFirstName } from "./first-name";

describe("parseFirstName", () => {
  it.each([
    ["Nathan", "Nathan"],
    ["  Élodie ", "Élodie"],
    ["Jean-Marc", "Jean-Marc"],
    ["N'Golo", "N'Golo"],
    ["Marie   Ève", "Marie Ève"],
    ["Zoë", "Zoë"],
    ["Ἀλέξανδρος", "Ἀλέξανδρος"],
  ])("accepte %j", (raw, expected) => {
    expect(parseFirstName(raw)).toBe(expected);
  });

  it.each(["", "   ", "Nathan74", "nathan_74", "<script>", "-", "'", "a".repeat(31)])("refuse %j", (raw) => {
    expect(() => parseFirstName(raw)).toThrow(ValidationError);
  });

  it("accepte 30 caractères", () => {
    expect(parseFirstName("a".repeat(30))).toBe("a".repeat(30));
  });
});

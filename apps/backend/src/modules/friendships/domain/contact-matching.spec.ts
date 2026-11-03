import { contactMatchKey } from "./contact-matching";

describe("contactMatchKey", () => {
  it.each(["06 12 34 56 78", "+33 6 12 34 56 78", "0033612345678", "06.12.34.56.78", "(+33) 6-12-34-56-78", "+33612345678"])(
    "ramène %j aux 9 derniers chiffres",
    (raw) => {
      expect(contactMatchKey(raw)).toBe("612345678");
    },
  );

  it.each(["", "12345678", "+33 1 23", "abc"])("ignore %j (moins de 9 chiffres)", (raw) => {
    expect(contactMatchKey(raw)).toBeNull();
  });
});

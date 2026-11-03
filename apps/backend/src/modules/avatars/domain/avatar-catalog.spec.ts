import { schema } from "@dicebear/avataaars";
import { ValidationError } from "../../../shared/domain/errors";
import { AVATAAARS_VALUES, AVATAR_KEYS, avatarCatalog, DEFAULT_AVATAR, parseAvatarOptions, readStoredAvatar } from "./avatar-catalog";

const schemaProperty = (key: string) => schema.properties![key] as { items: { enum?: string[] }; default: string[] };

describe("catalogue d'avatar (ADR-028)", () => {
  const { defaults, options } = avatarCatalog();

  it("reprend exactement les valeurs du style avataaars installé (recopiées pour ne pas charger DiceBear)", () => {
    for (const [key, values] of Object.entries(AVATAAARS_VALUES)) {
      const property = schemaProperty(key);
      expect([key, values]).toEqual([key, property.items?.enum ?? property.default]);
    }
  });

  it("expose exactement les 11 clés du contrat, avec l'avatar par défaut", () => {
    expect(Object.keys(options)).toEqual([...AVATAR_KEYS]);
    expect(defaults).toEqual({
      top: "shortFlat",
      hairColor: "2c1b18",
      skinColor: "edb98a",
      eyes: "default",
      eyebrows: "defaultNatural",
      mouth: "smile",
      facialHair: "none",
      accessories: "none",
      clothing: "hoodie",
      clothesColor: "65c9ff",
      backgroundColor: "b6e3f4",
    });
  });

  it('propose "none" en premier pour top, facialHair et accessories, et nulle part ailleurs', () => {
    for (const key of AVATAR_KEYS) {
      const optional = ["top", "facialHair", "accessories"].includes(key);
      expect(options[key][0] === "none").toBe(optional);
      expect(options[key].filter((value) => value === "none")).toHaveLength(optional ? 1 : 0);
    }
  });

  it("suit l'ordre du schéma avataaars", () => {
    expect(options.top.slice(1)).toEqual(schemaProperty("top").items.enum);
    expect(options.eyes).toEqual(schemaProperty("eyes").items.enum);
    expect(options.skinColor).toEqual(schemaProperty("skinColor").default);
    expect(options.hairColor).toEqual(schemaProperty("hairColor").default);
  });

  it("retire l'expression vomit", () => {
    expect(options.mouth).not.toContain("vomit");
    expect(options.mouth).toContain("smile");
    expect(() => parseAvatarOptions({ ...DEFAULT_AVATAR, mouth: "vomit" })).toThrow(ValidationError);
  });

  it("limite le fond à la palette fixe ; toutes les couleurs sont en hex sans #", () => {
    expect(options.backgroundColor).toEqual(["b6e3f4", "c0aede", "d1d4f9", "ffd5dc", "ffdfbf", "fffaa1", "a7ffc4", "ffffff"]);
    for (const key of ["hairColor", "skinColor", "clothesColor", "backgroundColor"] as const) {
      for (const color of options[key]) expect(color).toMatch(/^[0-9a-f]{6}$/);
    }
  });

  it("renvoie une copie : modifier la réponse ne touche pas le catalogue", () => {
    avatarCatalog().options.mouth.push("vomit");
    avatarCatalog().defaults.top = "none";
    expect(avatarCatalog()).toEqual({ defaults, options });
  });
});

describe("parseAvatarOptions", () => {
  it('accepte l\'avatar par défaut et toute valeur du catalogue, "none" compris', () => {
    expect(parseAvatarOptions({ ...DEFAULT_AVATAR })).toEqual(DEFAULT_AVATAR);
    const bald = { ...DEFAULT_AVATAR, top: "none", facialHair: "beardMedium", accessories: "round" };
    expect(parseAvatarOptions(bald)).toEqual(bald);
  });

  it.each([null, undefined, "shortFlat", 42, [DEFAULT_AVATAR]])("refuse %j (objet attendu)", (raw) => {
    expect(() => parseAvatarOptions(raw)).toThrow(ValidationError);
  });

  it("refuse une clé manquante", () => {
    const { mouth: _mouth, ...rest } = DEFAULT_AVATAR;
    expect(() => parseAvatarOptions(rest)).toThrow("Option d'avatar manquante : mouth");
  });

  it("refuse une clé inconnue, même une option avataaars non exposée", () => {
    expect(() => parseAvatarOptions({ ...DEFAULT_AVATAR, seed: "x" })).toThrow(ValidationError);
    expect(() => parseAvatarOptions({ ...DEFAULT_AVATAR, hatColor: "262e33" })).toThrow(ValidationError);
  });

  it.each([
    ["top", "mohawk"],
    ["hairColor", "#2c1b18"],
    ["hairColor", "2C1B18"],
    ["skinColor", "000000"],
    ["eyes", "none"],
    ["backgroundColor", "65c9ff"],
    ["backgroundColor", "transparent"],
    ["clothing", 3],
    ["facialHair", null],
  ])("refuse %s = %j", (key, value) => {
    expect(() => parseAvatarOptions({ ...DEFAULT_AVATAR, [key]: value })).toThrow(ValidationError);
  });
});

describe("readStoredAvatar", () => {
  it("relit un avatar valide et ignore une valeur sortie du catalogue", () => {
    expect(readStoredAvatar(null)).toBeNull();
    expect(readStoredAvatar({ ...DEFAULT_AVATAR })).toEqual(DEFAULT_AVATAR);
    expect(readStoredAvatar({ ...DEFAULT_AVATAR, mouth: "vomit" })).toBeNull();
  });
});

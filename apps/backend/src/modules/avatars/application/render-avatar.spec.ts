import type { AvatarOptions } from "@snapcore/types";
import { ValidationError } from "../../../shared/domain/errors";
import { DEFAULT_AVATAR } from "../domain/avatar-catalog";
import { DiceBearAvatarRenderer } from "../infrastructure/dicebear-avatar.renderer";
import type { AvatarRenderer } from "./ports";
import { RenderAvatar } from "./render-avatar";

const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const query = (overrides: Record<string, unknown> = {}): Record<string, unknown> => ({ ...DEFAULT_AVATAR, ...overrides });

// Dimensions lues dans le bloc IHDR, juste après la signature.
function pngSize(png: Buffer): { width: number; height: number } {
  return { width: png.readUInt32BE(16), height: png.readUInt32BE(20) };
}

describe("rendu PNG avataaars (DiceBear + resvg)", () => {
  // Sans cache : chaque appel passe réellement par le rendu.
  const render = new RenderAvatar(new DiceBearAvatarRenderer(), 0);

  it("rend un PNG carré à la taille demandée (256 par défaut)", () => {
    const png = render.execute(query());
    expect(png.subarray(0, 8).equals(PNG_SIGNATURE)).toBe(true);
    expect(pngSize(png)).toEqual({ width: 256, height: 256 });
    expect(pngSize(render.execute(query({ size: "64" })))).toEqual({ width: 64, height: 64 });
    expect(pngSize(render.execute(query({ size: "512" })))).toEqual({ width: 512, height: 512 });
  });

  it("est déterministe : mêmes options, mêmes octets ; options différentes, image différente", () => {
    const a = render.execute(query({ size: "128" }));
    const b = new RenderAvatar(new DiceBearAvatarRenderer()).execute(query({ size: "128" }));
    expect(a.equals(b)).toBe(true);
    expect(render.execute(query({ size: "128", mouth: "sad" })).equals(a)).toBe(false);
  });

  it('"none" masque l\'élément, une valeur l\'affiche', () => {
    const base = render.execute(query({ size: "128" }));
    expect(render.execute(query({ size: "128", top: "none" })).equals(base)).toBe(false);
    expect(render.execute(query({ size: "128", accessories: "round" })).equals(base)).toBe(false);
    expect(render.execute(query({ size: "128", facialHair: "beardMedium" })).equals(base)).toBe(false);
  });

  it.each([
    ["taille trop petite", { size: "63" }],
    ["taille trop grande", { size: "513" }],
    ["taille non entière", { size: "100.5" }],
    ["taille non numérique", { size: "grand" }],
    ["taille répétée", { size: ["64", "128"] }],
    ["valeur hors catalogue", { mouth: "vomit" }],
    ["paramètre inconnu", { seed: "x" }],
    ["paramètre répété", { top: ["shortFlat", "bob"] }],
  ])("refuse un paramètre invalide (%s)", (_label, overrides) => {
    expect(() => render.execute(query(overrides))).toThrow(ValidationError);
  });

  it("refuse un paramètre manquant", () => {
    const { eyes: _eyes, ...rest } = DEFAULT_AVATAR;
    expect(() => render.execute(rest)).toThrow(ValidationError);
  });
});

describe("RenderAvatar : cache LRU", () => {
  class CountingRenderer implements AvatarRenderer {
    calls = 0;
    renderPng(options: AvatarOptions, size: number): Buffer {
      this.calls++;
      return Buffer.from(`${options.mouth}@${size}`);
    }
  }

  it("ne recalcule pas un rendu récent et évince le moins récemment servi", () => {
    const renderer = new CountingRenderer();
    const render = new RenderAvatar(renderer, 2);

    render.execute(query({ mouth: "smile" }));
    render.execute(query({ mouth: "sad" }));
    render.execute(query({ mouth: "smile" })); // en cache, redevient le plus récent
    expect(renderer.calls).toBe(2);

    render.execute(query({ mouth: "twinkle" })); // évince "sad"
    render.execute(query({ mouth: "smile" }));
    expect(renderer.calls).toBe(3);
    render.execute(query({ mouth: "sad" }));
    expect(renderer.calls).toBe(4);
  });

  it("distingue les tailles", () => {
    const renderer = new CountingRenderer();
    const render = new RenderAvatar(renderer);
    expect(render.execute(query({ size: "64" })).toString()).toBe("smile@64");
    expect(render.execute(query({ size: "128" })).toString()).toBe("smile@128");
    expect(renderer.calls).toBe(2);
  });
});

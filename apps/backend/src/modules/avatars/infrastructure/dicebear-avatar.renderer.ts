import type * as AvataaarsModule from "@dicebear/avataaars";
import type * as DiceBearCore from "@dicebear/core";
import { Resvg } from "@resvg/resvg-js";
import type { AvatarOptions } from "@snapcore/types";
import type { AvatarRenderer } from "../application/ports";
import { AVATAR_NONE } from "../domain/avatar-catalog";

// Graine fixe : DiceBear tire au sort ce que les options ne fixent pas ; avec une graine constante
// et toutes les options explicites, le rendu ne dépend que des options (ADR-028).
const SEED = "snapcore";
// Détails non exposés dans l'éditeur, figés pour un rendu stable.
const ACCESSORIES_COLOR = "262e33";
const CLOTHING_GRAPHIC = "bear";

type AvataaarsOptions = DiceBearCore.StyleOptions<AvataaarsModule.Options>;

// DiceBear n'existe qu'en ESM : chargé au premier rendu (require d'un module ESM, accepté par Node 22+)
// plutôt qu'à l'import, pour que les tests d'intégration en mode ESM natif ne le chargent jamais.
let dicebear: { core: typeof DiceBearCore; avataaars: typeof AvataaarsModule } | undefined;
function loadDiceBear() {
  dicebear ??= { core: require("@dicebear/core"), avataaars: require("@dicebear/avataaars") };
  return dicebear;
}

// Style avataaars de DiceBear (Pablo Stanley, libre d'usage personnel et commercial), rasterisé
// par resvg (binaire précompilé, sans dépendance système).
export class DiceBearAvatarRenderer implements AvatarRenderer {
  renderPng(options: AvatarOptions, size: number): Buffer {
    const { core, avataaars } = loadDiceBear();
    const svg = core.createAvatar(avataaars, this.toDiceBear(options)).toString();
    // Le SVG d'avataaars ne contient pas de texte : inutile de charger les polices du système.
    const resvg = new Resvg(svg, { fitTo: { mode: "width", value: size }, font: { loadSystemFonts: false } });
    return resvg.render().asPng();
  }

  private toDiceBear(options: AvatarOptions): AvataaarsOptions {
    const part = (value: string) => (value === AVATAR_NONE ? { values: [], probability: 0 } : { values: [value], probability: 100 });
    const top = part(options.top);
    const facialHair = part(options.facialHair);
    const accessories = part(options.accessories);
    return {
      seed: SEED,
      randomizeIds: false,
      // Carré plein : l'app découpe elle-même le cercle (style "default", pas "circle").
      style: ["default"],
      backgroundColor: [options.backgroundColor],
      backgroundType: ["solid"],
      radius: 0,
      scale: 100,
      flip: false,
      rotate: 0,
      translateX: 0,
      translateY: 0,
      ...(top.values.length ? { top: top.values } : {}),
      topProbability: top.probability,
      hairColor: [options.hairColor],
      // Chapeaux, hijab, turban : même couleur que les vêtements.
      hatColor: [options.clothesColor],
      skinColor: [options.skinColor],
      eyes: [options.eyes],
      eyebrows: [options.eyebrows],
      mouth: [options.mouth],
      nose: ["default"],
      ...(facialHair.values.length ? { facialHair: facialHair.values } : {}),
      facialHairProbability: facialHair.probability,
      facialHairColor: [options.hairColor],
      ...(accessories.values.length ? { accessories: accessories.values } : {}),
      accessoriesProbability: accessories.probability,
      accessoriesColor: [ACCESSORIES_COLOR],
      clothing: [options.clothing],
      clothesColor: [options.clothesColor],
      clothingGraphic: [CLOTHING_GRAPHIC],
    } as AvataaarsOptions;
  }
}

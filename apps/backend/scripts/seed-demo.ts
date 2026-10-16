/**
 * Seed de démo (npm run seed:demo -w @snapcore/backend, depuis une base où existent nathan et rober).
 *
 * Crée des comptes, amitiés, stories, chats et snaps crédibles en passant par les VRAIS cas d'usage
 * (inscription, pipeline d'upload ADR-016 avec URL pré-signée puis promotion, messagerie, stories
 * avec vignette ADR-026), dans un contexte Nest : clés R2, jobs de purge et lignes restent cohérents.
 *
 * - Comptes Firebase email/mot de passe en @snapcore.test, mot de passe commun DEMO_PASSWORD.
 * - Idempotent autant que possible : pseudo existant réutilisé, amitié existante conservée,
 *   conversation déjà entamée ou story encore visible laissée telle quelle. Ne supprime jamais rien.
 * - L'historique est antidaté par une horloge décalée passée aux cas d'usage (aucune réécriture SQL),
 *   et ne notifie personne ; seuls les envois récents (snaps non ouverts, chats non lus, demandes
 *   d'ami en attente) partent en temps réel / push FCM vers nathan et rober.
 * - Médias générés localement avec ffmpeg (FFMPEG_PATH, sinon "ffmpeg" du PATH) : aucune photo réelle.
 */
import "reflect-metadata";
import { Logger, type INestApplication } from "@nestjs/common";
import { NestFactory } from "@nestjs/core";
import type { StoryContentType } from "@snapcore/types";
import type { App } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { execFileSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { AppModule } from "../src/app.module";
import { noAnalytics } from "../src/shared/domain/analytics";
import type { Clock } from "../src/shared/domain/clock";
import { ConflictError } from "../src/shared/domain/errors";
import { PrismaService } from "../src/shared/infrastructure/prisma/prisma.service";
import { RedisIoAdapter } from "../src/shared/infrastructure/redis/redis-io.adapter";
import { FriendshipUseCases } from "../src/modules/friendships/application/friendship-use-cases";
import {
  FRIENDSHIP_NOTIFIER,
  type FriendshipNotifier,
} from "../src/modules/friendships/application/ports/friendship-notifier.port";
import {
  FRIENDSHIP_REPOSITORY,
  type FriendshipRepository,
} from "../src/modules/friendships/application/ports/friendship.repository";
import {
  IDENTITY_PROVIDER,
  type IdentityProvider,
} from "../src/modules/identity/application/ports/identity-provider.port";
import { USER_REPOSITORY, type UserRepository } from "../src/modules/identity/application/ports/user.repository";
import { ProfilePhotoUseCases } from "../src/modules/identity/application/use-cases/profile-photo";
import { RegisterUser } from "../src/modules/identity/application/use-cases/register-user";
import type { UserAccount } from "../src/modules/identity/domain/user";
import { FIREBASE_APP } from "../src/modules/identity/infrastructure/firebase-app.provider";
import { OBJECT_STORAGE, type ObjectStorage } from "../src/modules/media/application/ports/object-storage.port";
import { RequestUpload } from "../src/modules/media/application/request-upload";
import { UploadPromoter } from "../src/modules/media/application/upload-promoter";
import type { MediaPurpose } from "../src/modules/media/domain/media-purpose";
import { MessagingUseCases } from "../src/modules/messages/application/messaging-use-cases";
import { MESSAGE_NOTIFIER, type MessageNotifier } from "../src/modules/messages/application/ports/message-notifier.port";
import { MESSAGE_REPOSITORY, type MessageRepository } from "../src/modules/messages/application/ports/message.repository";
import {
  SNAP_PURGE_SCHEDULER,
  type SnapPurgeScheduler,
} from "../src/modules/messages/application/ports/snap-purge.scheduler";
import {
  STORY_PURGE_SCHEDULER,
  STORY_REPOSITORY,
  STORY_VIEW_STORE,
  type StoryPurgeScheduler,
  type StoryRepository,
  type StoryViewStore,
} from "../src/modules/stories/application/ports";
import { StoryUseCases } from "../src/modules/stories/application/story-use-cases";

export const DEMO_PASSWORD = "SnapDemo2026!";

// Comptes réels des deux téléphones de test : jamais créés ni modifiés par ce script.
const OWNERS = {
  nathan: "5f33e2b6-bd67-4f29-a1f8-141915362abf",
  rober: "5d4f7509-7cb1-422e-9232-8c414f753ed3",
} as const;
type Owner = keyof typeof OWNERS;

// ---------------------------------------------------------------------------------------------
// Données de démo
// ---------------------------------------------------------------------------------------------

type DemoKey = "lea" | "hugo" | "ines" | "lucas" | "chloe" | "yanis" | "sarah" | "tom";

interface DemoUser {
  firstName: string;
  username: string;
  email: string;
  /** Couleurs du dégradé de l'avatar ; sans avatar, pas de photo de profil. */
  avatar?: [string, string];
}

const DEMO_USERS: Record<DemoKey, DemoUser> = {
  lea: { firstName: "Léa", username: "lea.martin", email: "lea.martin@snapcore.test", avatar: ["0xff9a9e", "0xfad0c4"] },
  hugo: { firstName: "Hugo", username: "hugo_b", email: "hugo.b@snapcore.test", avatar: ["0x2193b0", "0x6dd5ed"] },
  ines: { firstName: "Inès", username: "ines.k", email: "ines.k@snapcore.test", avatar: ["0x8e2de2", "0xff6fd8"] },
  lucas: { firstName: "Lucas", username: "lucas_dvx", email: "lucas.dvx@snapcore.test", avatar: ["0x11998e", "0x38ef7d"] },
  chloe: { firstName: "Chloé", username: "chloe.rsn", email: "chloe.rsn@snapcore.test", avatar: ["0xf7971e", "0xffd200"] },
  yanis: { firstName: "Yanis", username: "yanis_74", email: "yanis.74@snapcore.test" },
  sarah: { firstName: "Sarah", username: "sarah.lmb", email: "sarah.lmb@snapcore.test", avatar: ["0xee0979", "0xff6a00"] },
  tom: { firstName: "Tom", username: "tom_grd", email: "tom.grd@snapcore.test" },
};

// Amis de nathan ET de rober ; Sarah et Tom restent en demande d'ami en attente vers nathan.
const FRIENDS_OF_OWNERS: DemoKey[] = ["lea", "hugo", "ines", "lucas", "chloe", "yanis"];
const FRIENDSHIPS_BETWEEN_DEMO: [DemoKey, DemoKey][] = [
  ["lea", "ines"],
  ["lea", "chloe"],
  ["hugo", "lucas"],
  ["hugo", "yanis"],
  ["ines", "chloe"],
];
const PENDING_TO_NATHAN: DemoKey[] = ["sarah", "tom"];

type Gradient = "linear" | "radial" | "circular" | "spiral";
type VideoSource = "mandelbrot" | "life" | "spiral" | "aurora";

type MediaSpec =
  | { kind: "photo"; colors: string[]; gradient: Gradient; caption?: string; seed: number }
  | { kind: "video"; source: VideoSource; caption?: string };

// Images 1080×1920 et vidéos 720×1280 (H.264, 4 s), générées une fois puis réutilisées.
const MEDIA: Record<string, MediaSpec> = {
  "story-ines-soiree": { kind: "photo", colors: ["0xff5f6d", "0xffc371", "0x845ec2"], gradient: "spiral", caption: "Soirée chez Inès", seed: 7 },
  "story-ines-video": { kind: "video", source: "mandelbrot", caption: "Ambiance" },
  "story-lea-annecy": { kind: "photo", colors: ["0x0f2027", "0x2c5364", "0x6dd5ed"], gradient: "linear", caption: "Annecy, toujours aussi beau", seed: 3 },
  "story-lea-golden": { kind: "photo", colors: ["0xf12711", "0xf5af19", "0xffe29f"], gradient: "radial", caption: "Golden hour", seed: 11 },
  "story-hugo-match": { kind: "photo", colors: ["0x134e5e", "0x71b280", "0x0b3d2e"], gradient: "linear", caption: "Match ce soir, venez !", seed: 5 },
  "story-lucas-video": { kind: "video", source: "life", caption: "Ma tête en révisions" },
  "story-lucas-bu": { kind: "photo", colors: ["0x3a1c71", "0xd76d77", "0xffaf7b"], gradient: "circular", caption: "Session BU", seed: 21 },
  "story-chloe-brunch": { kind: "photo", colors: ["0xffecd2", "0xfcb69f", "0xff8a8a"], gradient: "radial", caption: "Brunch entre filles", seed: 9 },
  "story-rober-vendredi": { kind: "photo", colors: ["0x141e30", "0x243b55", "0x8e54e9"], gradient: "spiral", caption: "Vendredi soir", seed: 13 },
  "snap-lea-chat": { kind: "photo", colors: ["0xa18cd1", "0xfbc2eb"], gradient: "radial", caption: "Mon chat est jaloux", seed: 2 },
  "snap-lea-cafe": { kind: "photo", colors: ["0x603813", "0xb29f94", "0xf5e6ca"], gradient: "linear", caption: "Pause café", seed: 4 },
  "snap-hugo-video": { kind: "video", source: "spiral", caption: "Regarde ça" },
  "snap-ines-selfie": { kind: "photo", colors: ["0xff0844", "0xffb199", "0xf9d423"], gradient: "circular", caption: "T'es dessus haha", seed: 6 },
  "snap-chloe-ciel": { kind: "photo", colors: ["0x4facfe", "0x00f2fe", "0xe0c3fc"], gradient: "linear", caption: "Ce ciel", seed: 8 },
  "snap-lucas-code": { kind: "photo", colors: ["0x000000", "0x0f9b0f", "0x00ff87"], gradient: "linear", caption: "Encore un bug", seed: 10 },
  "snap-chloe-cine": { kind: "photo", colors: ["0x200122", "0x6f0000", "0xff4e50"], gradient: "radial", caption: "Ciné ce soir ?", seed: 12 },
  "snap-yanis-video": { kind: "video", source: "aurora", caption: "Prêt pour la revanche" },
  "snap-nathan-bu": { kind: "photo", colors: ["0x1d2b64", "0xf8cdda"], gradient: "linear", caption: "Toujours à la BU", seed: 14 },
  "snap-nathan-velo": { kind: "photo", colors: ["0x56ab2f", "0xa8e063", "0x2f7336"], gradient: "spiral", caption: "Balade du soir", seed: 15 },
  "snap-rober-pizza": { kind: "photo", colors: ["0xcb2d3e", "0xef473a", "0xffd452"], gradient: "circular", caption: "Pizza time", seed: 16 },
  "snap-rober-but": { kind: "photo", colors: ["0x0575e6", "0x00f260"], gradient: "linear", caption: "Quel but", seed: 17 },
};

/** Moment d'un envoi : jour(s) passé(s) à une heure donnée, ou il y a N minutes. */
type When = { daysAgo: number; time: string } | { minutesAgo: number };

interface ConversationLine {
  when: When;
  /** "ami" = l'utilisateur de démo, "moi" = nathan ou rober. */
  by: "ami" | "moi";
  text?: string;
  /** Nom d'un média de MEDIA, envoyé en snap photo ou vidéo selon son type. */
  snap?: string;
  /** Snap ouvert par le destinataire quelques minutes après l'envoi. */
  opened?: boolean;
}

interface Conversation {
  owner: Owner;
  friend: DemoKey;
  /** Antidaté, sans notification, entièrement lu des deux côtés. */
  history: ConversationLine[];
  /** Envois récents notifiés : snaps non ouverts, chats non lus. */
  recent: ConversationLine[];
}

const d = (daysAgo: number, time: string): When => ({ daysAgo, time });
const ago = (minutesAgo: number): When => ({ minutesAgo });
const ami = (when: When, text: string): ConversationLine => ({ when, by: "ami", text });
const moi = (when: When, text: string): ConversationLine => ({ when, by: "moi", text });
const snapOf = (by: "ami" | "moi", when: When, snap: string, opened = false): ConversationLine => ({ when, by, snap, opened });

const CONVERSATIONS: Conversation[] = [
  // --- nathan ---
  {
    owner: "nathan",
    friend: "lea",
    history: [
      ami(d(4, "18:12"), "Coucou ! T'as pris les notes du cours de jeudi ?"),
      moi(d(4, "18:20"), "Ouais je te les envoie ce soir"),
      ami(d(4, "18:21"), "T'es le meilleur"),
      snapOf("ami", d(2, "21:03"), "snap-lea-chat", true),
      moi(d(2, "21:11"), "Mdr la tête de ton chat"),
      ami(d(2, "21:12"), "Il est jaloux de ma série"),
      ami(d(1, "12:30"), "On mange ensemble demain midi ?"),
      moi(d(1, "12:41"), "Carrément, resto U ou kebab ?"),
      ami(d(1, "12:42"), "Kebab évidemment"),
    ],
    recent: [snapOf("moi", ago(50), "snap-nathan-velo", true), snapOf("ami", ago(25), "snap-lea-cafe")],
  },
  {
    owner: "nathan",
    friend: "hugo",
    history: [
      ami(d(6, "20:45"), "Tu viens au five dimanche ?"),
      moi(d(6, "20:50"), "Si j'ai fini mon projet oui"),
      ami(d(6, "20:51"), "Pas d'excuse cette fois"),
      moi(d(3, "22:10"), "Bien joué pour le match, t'as tout cassé"),
      ami(d(3, "22:14"), "Merci frérot, la passe de Yanis était folle"),
      ami(d(1, "19:02"), "Ce soir on regarde le PSG chez moi si tu veux"),
      moi(d(1, "19:30"), "Je ramène les chips"),
    ],
    recent: [snapOf("ami", ago(120), "snap-hugo-video"), snapOf("moi", ago(60), "snap-nathan-bu")],
  },
  {
    owner: "nathan",
    friend: "ines",
    history: [
      ami(d(5, "16:00"), "Samedi soir c'est chez moi, ramène qui tu veux"),
      moi(d(5, "16:05"), "Trop bien, je viens avec Rober"),
      ami(d(5, "16:06"), "Parfait, oubliez pas les boissons"),
      moi(d(2, "10:15"), "Merci encore pour ta soirée, c'était incroyable"),
      ami(d(2, "10:40"), "Grave, faut refaire ça vite"),
    ],
    recent: [snapOf("ami", ago(90), "snap-ines-selfie"), ami(ago(85), "Regarde mon snap, t'es dessus haha")],
  },
  {
    owner: "nathan",
    friend: "lucas",
    history: [
      ami(d(3, "14:22"), "T'as avancé sur le projet de réseau ?"),
      moi(d(3, "14:30"), "J'ai fini la partie routage, il reste le rapport"),
      ami(d(3, "14:31"), "Ok je m'occupe de l'intro et de la conclusion"),
      moi(d(3, "14:32"), "Deal"),
      ami(d(1, "23:05"), "Le prof a décalé le rendu à lundi !!"),
      moi(d(1, "23:07"), "Enfin une bonne nouvelle"),
    ],
    recent: [ami(ago(35), "T'es dispo ce soir pour finir le rapport ?"), ami(ago(34), "On peut se caler à la BU vers 18h")],
  },
  {
    owner: "nathan",
    friend: "chloe",
    history: [
      ami(d(2, "17:45"), "Tu me rends mon chargeur quand tu veux hein"),
      moi(d(2, "17:50"), "Oups, demain promis"),
      moi(d(1, "09:12"), "Je l'ai dans mon sac, t'es où ?"),
      ami(d(1, "09:20"), "Cafét du bâtiment B"),
      moi(d(1, "09:21"), "J'arrive"),
    ],
    recent: [snapOf("ami", ago(10), "snap-chloe-ciel")],
  },
  {
    owner: "nathan",
    friend: "yanis",
    history: [
      ami(d(4, "21:30"), "Frérot t'as vu le dernier épisode ?"),
      moi(d(4, "21:35"), "Pas encore, spoile pas"),
      ami(d(4, "21:36"), "Je dis rien mais prépare toi"),
    ],
    recent: [ami(ago(15), "Bon tu l'as regardé ou pas ?")],
  },
  // --- rober ---
  {
    owner: "rober",
    friend: "lea",
    history: [
      ami(d(3, "19:00"), "Tu peux me prêter ton vélo samedi ?"),
      moi(d(3, "19:20"), "Oui pas de souci, passe le chercher vers 10h"),
      ami(d(3, "19:21"), "Merci t'es un amour"),
    ],
    recent: [ami(ago(50), "Je te le rends ce soir, il roule trop bien")],
  },
  {
    owner: "rober",
    friend: "hugo",
    history: [
      moi(d(5, "18:00"), "Tu joues ce week-end ?"),
      ami(d(5, "18:10"), "Ouais on a un match à domicile"),
      snapOf("moi", d(1, "21:00"), "snap-rober-but", true),
      ami(d(1, "21:05"), "Ahah énorme"),
    ],
    recent: [ami(ago(20), "Viens au five dimanche, il nous manque un gardien")],
  },
  {
    owner: "rober",
    friend: "ines",
    history: [
      ami(d(5, "16:10"), "Nathan m'a dit que tu venais samedi, trop cool"),
      moi(d(5, "16:30"), "Évidemment, je rate pas ça"),
      moi(d(2, "11:00"), "Ta soirée était trop bien"),
      ami(d(2, "11:05"), "Merci ! T'as gagné le blind test en plus"),
    ],
    recent: [snapOf("moi", ago(40), "snap-rober-pizza")],
  },
  {
    owner: "rober",
    friend: "lucas",
    history: [
      ami(d(2, "15:00"), "T'as le lien du TP de lundi ?"),
      moi(d(2, "15:02"), "Je te l'envoie par mail"),
      ami(d(2, "15:03"), "Top merci"),
    ],
    recent: [snapOf("ami", ago(70), "snap-lucas-code")],
  },
  {
    owner: "rober",
    friend: "chloe",
    history: [
      moi(d(4, "13:00"), "Tu viens au cinéma jeudi ?"),
      ami(d(4, "13:15"), "Ça dépend du film"),
      moi(d(4, "13:16"), "Le nouveau Dune"),
      ami(d(4, "13:17"), "Alors oui !!"),
    ],
    recent: [snapOf("ami", ago(30), "snap-chloe-cine")],
  },
  {
    owner: "rober",
    friend: "yanis",
    history: [
      ami(d(3, "20:00"), "Tu me dois toujours une revanche à FIFA"),
      moi(d(3, "20:05"), "Quand tu veux, prépare toi à perdre"),
    ],
    recent: [snapOf("ami", ago(45), "snap-yanis-video")],
  },
];

// Stories publiées il y a N minutes (24h de vie chacune, ADR-013).
const STORIES: { author: DemoKey | "rober"; media: string; minutesAgo: number }[] = [
  { author: "ines", media: "story-ines-soiree", minutesAgo: 300 },
  { author: "ines", media: "story-ines-video", minutesAgo: 120 },
  { author: "lea", media: "story-lea-annecy", minutesAgo: 480 },
  { author: "lea", media: "story-lea-golden", minutesAgo: 60 },
  { author: "hugo", media: "story-hugo-match", minutesAgo: 180 },
  { author: "lucas", media: "story-lucas-bu", minutesAgo: 360 },
  { author: "lucas", media: "story-lucas-video", minutesAgo: 240 },
  { author: "chloe", media: "story-chloe-brunch", minutesAgo: 30 },
  { author: "rober", media: "story-rober-vendredi", minutesAgo: 110 },
];

// ---------------------------------------------------------------------------------------------
// Génération des médias (ffmpeg)
// ---------------------------------------------------------------------------------------------

interface MediaFile {
  path: string;
  contentType: "image/jpeg" | "video/mp4";
}

class MediaFactory {
  private readonly dir = join(tmpdir(), "snapcore-seed-demo");
  private readonly ffmpeg = process.env.FFMPEG_PATH ?? "ffmpeg";

  constructor() {
    mkdirSync(this.dir, { recursive: true });
    // Police copiée à côté des fichiers : un chemin Windows "C:\..." casse la syntaxe des filtres.
    const font = join(this.dir, "font.ttf");
    const systemFont = "C:/Windows/Fonts/segoeuib.ttf";
    if (!existsSync(font) && existsSync(systemFont)) copyFileSync(systemFont, font);
  }

  /** Média principal (1080×1920 ou 720×1280). */
  main(name: string): MediaFile {
    const spec = MEDIA[name];
    if (!spec) throw new Error(`Média inconnu : ${name}`);
    return spec.kind === "photo" ? this.photo(name, spec) : this.video(name, spec);
  }

  /** Vignette JPEG ~320 px de large (ADR-026), tirée de l'image ou d'une image de la vidéo. */
  thumbnail(name: string): MediaFile {
    const source = this.main(name);
    const output = `${name}-thumb.jpg`;
    const seek = source.contentType === "video/mp4" ? ["-ss", "1"] : [];
    this.run(output, [...seek, "-i", this.file(source.path), "-frames:v", "1", "-vf", "scale=320:-2", "-q:v", "5"]);
    return { path: join(this.dir, output), contentType: "image/jpeg" };
  }

  /** Avatar 512×512 : dégradé et initiale du prénom. */
  avatar(key: DemoKey): MediaFile {
    const user = DEMO_USERS[key];
    const [c0, c1] = user.avatar!;
    const output = `avatar-${key}.jpg`;
    const text = this.caption(`avatar-${key}`, user.firstName.charAt(0));
    this.run(output, [
      "-f", "lavfi", "-i", `gradients=s=512x512:c0=${c0}:c1=${c1}:n=2:type=linear:seed=${key.length}`,
      "-frames:v", "1",
      "-vf", `${this.drawText(text, 260, "(h-th)/2-20")}`,
      "-q:v", "3",
    ]);
    return { path: join(this.dir, output), contentType: "image/jpeg" };
  }

  private photo(name: string, spec: Extract<MediaSpec, { kind: "photo" }>): MediaFile {
    const output = `${name}.jpg`;
    const colors = spec.colors.map((color, index) => `c${index}=${color}`).join(":");
    const filters = ["vignette=PI/5"];
    if (spec.caption) filters.push(...this.captionBar(this.caption(name, spec.caption), 1920));
    this.run(output, [
      "-f", "lavfi", "-i", `gradients=s=1080x1920:${colors}:n=${spec.colors.length}:type=${spec.gradient}:seed=${spec.seed}`,
      "-frames:v", "1",
      "-vf", filters.join(","),
      "-q:v", "3",
    ]);
    return { path: join(this.dir, output), contentType: "image/jpeg" };
  }

  private video(name: string, spec: Extract<MediaSpec, { kind: "video" }>): MediaFile {
    const output = `${name}.mp4`;
    const sources: Record<VideoSource, string> = {
      mandelbrot: "mandelbrot=s=720x1280:rate=30",
      life: "life=s=180x320:mold=10:rate=30:ratio=0.2:death_color=#1b1464:life_color=#ffd166:mold_color=#ef476f,scale=720:1280:flags=neighbor",
      spiral: "gradients=s=720x1280:c0=0x00c9ff:c1=0x92fe9d:c2=0xfc466b:c3=0x3f5efb:n=4:type=spiral:speed=0.03:rate=30",
      aurora: "gradients=s=720x1280:c0=0x00467f:c1=0xa5cc82:c2=0x7f00ff:n=3:type=radial:speed=0.02:rate=30,hue=H=2*PI*t/4",
    };
    const filters = spec.caption ? this.captionBar(this.caption(name, spec.caption), 1280).join(",") : "null";
    this.run(output, [
      "-f", "lavfi", "-i", sources[spec.source],
      "-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo",
      "-t", "4",
      "-vf", filters,
      "-c:v", "libx264", "-preset", "veryfast", "-crf", "26", "-pix_fmt", "yuv420p",
      "-c:a", "aac", "-shortest", "-movflags", "+faststart",
    ]);
    return { path: join(this.dir, output), contentType: "video/mp4" };
  }

  // Bandeau de légende façon Snapchat : bande noire translucide pleine largeur, texte centré.
  private captionBar(textFile: string, height: number): string[] {
    const size = Math.round(height * 0.03);
    const y = Math.round(height * 0.62);
    return [
      `drawbox=x=0:y=${y}:w=iw:h=${Math.round(size * 2)}:color=black@0.45:t=fill`,
      this.drawText(textFile, size, `${y}+(${Math.round(size * 2)}-th)/2`),
    ];
  }

  private drawText(textFile: string, size: number, y: string): string {
    const font = existsSync(join(this.dir, "font.ttf")) ? "fontfile=font.ttf:" : "";
    return `drawtext=${font}textfile=${textFile}:fontcolor=white:fontsize=${size}:x=(w-tw)/2:y=${y}:shadowcolor=black@0.3:shadowx=2:shadowy=2`;
  }

  // Texte passé par fichier (textfile=) : accents et apostrophes sans échappement de filtre.
  private caption(name: string, text: string): string {
    const file = `${name}.txt`;
    writeFileSync(join(this.dir, file), text, "utf8");
    return file;
  }

  private file(path: string): string {
    return path.slice(this.dir.length + 1);
  }

  private run(output: string, args: string[]): void {
    if (existsSync(join(this.dir, output))) return;
    // cwd = dossier de travail : chemins relatifs, sans "C:" à échapper dans les filtres.
    execFileSync(this.ffmpeg, ["-hide_banner", "-loglevel", "error", "-y", ...args, output], { cwd: this.dir, stdio: "inherit" });
  }
}

// ---------------------------------------------------------------------------------------------
// Seed
// ---------------------------------------------------------------------------------------------

const silentMessages: MessageNotifier = {
  messageReceived: async () => undefined,
  snapOpened: async () => undefined,
  textsRead: async () => undefined,
  snapRetained: async () => undefined,
  snapExpired: async () => undefined,
};
const silentFriendships: FriendshipNotifier = {
  friendRequestReceived: async () => undefined,
  friendRequestAccepted: async () => undefined,
};

const fixedClock = (date: Date): Clock => ({ now: () => new Date(date) });

function dateOf(when: When, now: Date): Date {
  if ("minutesAgo" in when) return new Date(now.getTime() - when.minutesAgo * 60_000);
  const [hours, minutes] = when.time.split(":").map(Number);
  const date = new Date(now);
  date.setDate(date.getDate() - when.daysAgo);
  date.setHours(hours, minutes, 0, 0);
  return date;
}

const addMinutes = (date: Date, minutes: number) => new Date(date.getTime() + minutes * 60_000);

class DemoSeeder {
  private readonly logger = new Logger("SeedDemo");
  private readonly media = new MediaFactory();
  private readonly now = new Date();
  private readonly users = new Map<DemoKey | Owner, UserAccount>();
  private readonly stats = { created: [] as string[], reused: [] as string[], photos: [] as string[], pushes: 0 };

  private readonly prisma: PrismaService;
  private readonly userRepository: UserRepository;
  private readonly identity: IdentityProvider;
  private readonly friendshipRepository: FriendshipRepository;
  private readonly messageRepository: MessageRepository;
  private readonly storyRepository: StoryRepository;
  private readonly promoter: UploadPromoter;
  private readonly storage: ObjectStorage;
  private readonly requestUpload: RequestUpload;
  private readonly profilePhotos: ProfilePhotoUseCases;

  constructor(private readonly app: INestApplication) {
    const get = <T>(token: unknown): T => app.get(token as string, { strict: false });
    this.prisma = get(PrismaService);
    this.userRepository = get(USER_REPOSITORY);
    this.identity = get(IDENTITY_PROVIDER);
    this.friendshipRepository = get(FRIENDSHIP_REPOSITORY);
    this.messageRepository = get(MESSAGE_REPOSITORY);
    this.storyRepository = get(STORY_REPOSITORY);
    this.promoter = get(UploadPromoter);
    this.storage = get(OBJECT_STORAGE);
    this.requestUpload = get(RequestUpload);
    this.profilePhotos = get(ProfilePhotoUseCases);
  }

  async run(): Promise<void> {
    await this.loadOwners();
    for (const key of Object.keys(DEMO_USERS) as DemoKey[]) await this.ensureUser(key);
    await this.ensureFriendships();
    await this.ensureStories();
    for (const conversation of CONVERSATIONS) await this.ensureConversation(conversation);
    await this.report();
  }

  // --- Comptes ---

  private async loadOwners(): Promise<void> {
    for (const owner of Object.keys(OWNERS) as Owner[]) {
      const user = await this.userRepository.findActiveById(OWNERS[owner]);
      if (!user) throw new Error(`Compte ${owner} (${OWNERS[owner]}) introuvable ou inactif : seed impossible`);
      this.users.set(owner, user);
    }
  }

  private async ensureUser(key: DemoKey): Promise<void> {
    const demo = DEMO_USERS[key];
    const firebaseUid = await this.ensureFirebaseAccount(demo);

    let user = await this.userRepository.findByFirebaseUidIncludingDeleted(firebaseUid);
    if (user) {
      this.stats.reused.push(demo.username);
    } else {
      const taken = await this.prisma.user.findUnique({ where: { username: demo.username } });
      if (taken) throw new Error(`Pseudo ${demo.username} déjà pris par un autre compte Firebase`);
      user = await this.register(demo, firebaseUid);
      this.stats.created.push(demo.username);
    }
    if (user.deletionRequestedAt || user.moderationStatus !== "ACTIVE") {
      throw new Error(`Compte ${demo.username} en suppression ou suspendu : à traiter à la main`);
    }

    if (demo.avatar && !user.profilePhotoUrl) {
      const uploadId = await this.upload(user, "PROFILE_PHOTO", this.media.avatar(key));
      user = await this.profilePhotos.change(user, uploadId);
      this.stats.photos.push(demo.username);
    }
    this.users.set(key, user);
  }

  private async ensureFirebaseAccount(demo: DemoUser): Promise<string> {
    const auth = getAuth(this.app.get<App>(FIREBASE_APP as unknown as string, { strict: false }));
    try {
      const existing = await auth.getUserByEmail(demo.email);
      // Mot de passe remis à la valeur de démo : la connexion documentée fonctionne toujours.
      await auth.updateUser(existing.uid, { password: DEMO_PASSWORD, displayName: demo.firstName, disabled: false });
      return existing.uid;
    } catch (error) {
      if ((error as { code?: string }).code !== "auth/user-not-found") throw error;
      const created = await auth.createUser({
        email: demo.email,
        password: DEMO_PASSWORD,
        displayName: demo.firstName,
        emailVerified: true,
      });
      return created.uid;
    }
  }

  // Inscription par le vrai cas d'usage (ADR-015). Avec FIREBASE_WEB_API_KEY, l'ID token est obtenu
  // par une vraie connexion email/mot de passe ; sinon, le compte vient d'être créé par le SDK Admin
  // et son identité est fournie directement au cas d'usage.
  private async register(demo: DemoUser, firebaseUid: string): Promise<UserAccount> {
    const apiKey = process.env.FIREBASE_WEB_API_KEY;
    const input = { username: demo.username, firstName: demo.firstName };
    if (apiKey) {
      const idToken = await signInWithPassword(apiKey, demo.email, DEMO_PASSWORD);
      return new RegisterUser(this.userRepository, this.identity, noAnalytics).execute({ ...input, idToken });
    }
    const trusted: IdentityProvider = {
      verifyIdToken: async () => ({ firebaseUid, email: demo.email }),
      getCurrentEmail: async () => demo.email,
      deleteAccount: async () => undefined,
    };
    return new RegisterUser(this.userRepository, trusted, noAnalytics).execute({ ...input, idToken: "seed" });
  }

  // --- Amitiés ---

  private async ensureFriendships(): Promise<void> {
    const pairs: [DemoKey | Owner, DemoKey | Owner][] = [
      ...FRIENDS_OF_OWNERS.flatMap((key): [DemoKey | Owner, DemoKey | Owner][] => [
        ["nathan", key],
        ["rober", key],
      ]),
      ...FRIENDSHIPS_BETWEEN_DEMO,
    ];
    for (const [index, [a, b]] of pairs.entries()) {
      // Amitiés nouées il y a 2 à 4 semaines, sans notification (historique).
      const at = addMinutes(this.now, -(14 + (index % 14)) * 24 * 60);
      await this.befriend(a, b, at);
    }

    for (const key of PENDING_TO_NATHAN) {
      const requester = this.user(key);
      const nathan = this.user("nathan");
      if (await this.friendshipRepository.findByPair(requester.id, nathan.id)) continue;
      // Demande récente notifiée : badge et push sur le téléphone de nathan.
      await this.friendships(addMinutes(this.now, -5), true).sendRequest(requester, nathan.username);
      this.stats.pushes++;
    }
  }

  private async befriend(a: DemoKey | Owner, b: DemoKey | Owner, at: Date): Promise<void> {
    const existing = await this.friendshipRepository.findByPair(this.user(a).id, this.user(b).id);
    if (existing?.status === "ACCEPTED") return;
    if (existing?.status === "BLOCKED") throw new Error(`Paire ${a} / ${b} bloquée : à traiter à la main`);
    // Demande de a puis demande croisée de b, qui accepte la demande entrante (ADR-012).
    await this.ignoreAlreadyFriends(this.friendships(at, false).sendRequest(this.user(a), this.user(b).username));
    await this.ignoreAlreadyFriends(
      this.friendships(addMinutes(at, 90), false).sendRequest(this.user(b), this.user(a).username),
    );
  }

  private async ignoreAlreadyFriends(promise: Promise<unknown>): Promise<void> {
    try {
      await promise;
    } catch (error) {
      if (!(error instanceof ConflictError)) throw error;
    }
  }

  // --- Stories ---

  private async ensureStories(): Promise<void> {
    const authors = new Set(STORIES.map((story) => story.author));
    for (const author of authors) {
      const user = this.user(author);
      if (await this.hasSeededStory(user)) continue;
      for (const story of STORIES.filter((entry) => entry.author === author)) {
        const at = addMinutes(this.now, -story.minutesAgo);
        const main = this.media.main(story.media);
        const contentType: StoryContentType = main.contentType === "video/mp4" ? "VIDEO" : "PHOTO";
        const uploadId = await this.upload(user, contentType === "VIDEO" ? "STORY_VIDEO" : "STORY_PHOTO", main);
        const thumbnailUploadId = await this.upload(user, "STORY_THUMBNAIL", this.media.thumbnail(story.media));
        await this.stories(at).publish(user, { uploadId, contentType, thumbnailUploadId });
      }
    }
  }

  private async hasSeededStory(user: UserAccount): Promise<boolean> {
    const items = await this.storyRepository.listVisibleByAuthors([user.id], this.now);
    // Story de démo = encore visible et publiée avec une vignette (une story publiée depuis l'app
    // avant ADR-026 n'en a pas) : un nouveau lancement ne la republie pas.
    return items.some((item) => item.thumbnailKey !== null);
  }

  // --- Conversations ---

  private async ensureConversation(conversation: Conversation): Promise<void> {
    const owner = this.user(conversation.owner);
    const friend = this.user(conversation.friend);
    const existing = await this.messageRepository.listBetween(owner.id, friend.id, { limit: 1 });
    if (existing.length > 0) {
      this.logger.log(`Conversation ${conversation.owner} / ${friend.username} déjà présente : ignorée`);
      return;
    }

    let last = this.now;
    for (const line of conversation.history) {
      last = dateOf(line.when, this.now);
      await this.send(conversation, line, last, false);
    }
    // Historique lu des deux côtés (flèches "ouvert"), sans notification.
    const readAt = addMinutes(last, 5);
    await this.messaging(readAt, false).markConversationRead(owner, friend.id);
    await this.messaging(readAt, false).markConversationRead(friend, owner.id);

    for (const line of conversation.recent) {
      await this.send(conversation, line, dateOf(line.when, this.now), true);
      if (line.by === "ami") this.stats.pushes++;
    }
  }

  private async send(conversation: Conversation, line: ConversationLine, at: Date, notify: boolean): Promise<void> {
    const owner = this.user(conversation.owner);
    const friend = this.user(conversation.friend);
    const [sender, recipient] = line.by === "moi" ? [owner, friend] : [friend, owner];
    const messaging = this.messaging(at, notify);

    if (line.text) {
      await messaging.sendText(sender, recipient.id, line.text);
      return;
    }
    const file = this.media.main(line.snap!);
    const isVideo = file.contentType === "video/mp4";
    const uploadId = await this.upload(sender, isVideo ? "SNAP_VIDEO" : "SNAP_PHOTO", file);
    const snap = await messaging.sendSnap(sender, recipient.id, {
      uploadId,
      contentType: isVideo ? "SNAP_PHOTO_VIDEO" : "SNAP_PHOTO",
    });
    // Ouverture par le destinataire : purge rapprochée à +24h (ADR-017), le snap passe au gris ensuite.
    if (line.opened) await this.messaging(addMinutes(at, 4), notify).requestSnapView(recipient, snap.id);
  }

  // --- Upload réel (ADR-016) : URL pré-signée, PUT vers R2, puis promotion par le cas d'usage ---

  private async upload(user: UserAccount, purpose: MediaPurpose, file: MediaFile): Promise<string> {
    const body = readFileSync(file.path);
    const ticket = await this.requestUpload.execute(user, {
      purpose,
      contentType: file.contentType,
      contentLength: statSync(file.path).size,
    });
    const response = await fetch(ticket.uploadUrl, {
      method: ticket.method,
      headers: { "Content-Type": ticket.headers["Content-Type"] },
      body: new Uint8Array(body),
    });
    if (!response.ok) throw new Error(`PUT R2 refusé (${response.status}) pour ${purpose}`);
    return ticket.uploadId;
  }

  // --- Cas d'usage à une date donnée (horloge figée) et avec ou sans notification ---

  private messaging(at: Date, notify: boolean): MessagingUseCases {
    const get = <T>(token: unknown): T => this.app.get(token as string, { strict: false });
    return new MessagingUseCases(
      this.messageRepository,
      this.friendshipRepository,
      this.userRepository,
      this.promoter,
      this.storage,
      get<SnapPurgeScheduler>(SNAP_PURGE_SCHEDULER),
      notify ? get<MessageNotifier>(MESSAGE_NOTIFIER) : silentMessages,
      fixedClock(at),
      this.logger,
      noAnalytics,
    );
  }

  private friendships(at: Date, notify: boolean): FriendshipUseCases {
    const notifier = notify
      ? this.app.get<FriendshipNotifier>(FRIENDSHIP_NOTIFIER as unknown as string, { strict: false })
      : silentFriendships;
    return new FriendshipUseCases(this.friendshipRepository, this.userRepository, notifier, fixedClock(at), noAnalytics);
  }

  private stories(at: Date): StoryUseCases {
    const get = <T>(token: unknown): T => this.app.get(token as string, { strict: false });
    return new StoryUseCases(
      this.storyRepository,
      get<StoryViewStore>(STORY_VIEW_STORE),
      this.friendshipRepository,
      this.userRepository,
      this.promoter,
      this.storage,
      get<StoryPurgeScheduler>(STORY_PURGE_SCHEDULER),
      fixedClock(at),
      this.logger,
      noAnalytics,
    );
  }

  private user(key: DemoKey | Owner): UserAccount {
    const user = this.users.get(key);
    if (!user) throw new Error(`Utilisateur ${key} non chargé`);
    return user;
  }

  // --- Bilan ---

  private async report(): Promise<void> {
    console.log("\n=== Seed de démo terminé ===");
    console.log(`Comptes créés : ${this.stats.created.join(", ") || "aucun"}`);
    console.log(`Comptes réutilisés : ${this.stats.reused.join(", ") || "aucun"}`);
    console.log(`Photos de profil ajoutées : ${this.stats.photos.join(", ") || "aucune"}`);
    console.log(`Envois notifiés vers nathan / rober : ${this.stats.pushes}`);
    console.log(`Mot de passe des comptes de démo : ${DEMO_PASSWORD}`);
    for (const owner of Object.keys(OWNERS) as Owner[]) {
      const me = this.user(owner);
      const feed = await this.stories(new Date()).feed(me);
      const unopenedSnaps = await this.prisma.message.count({
        where: { recipientId: me.id, contentType: { not: "TEXT" }, openedAt: null, mediaKey: { not: null } },
      });
      const unreadTexts = await this.prisma.message.count({ where: { recipientId: me.id, contentType: "TEXT", openedAt: null } });
      console.log(
        `${owner} : ${feed.length} stories d'amis (${feed.filter((entry) => entry.thumbnailUrl).length} avec vignette), ` +
          `${unopenedSnaps} snaps non ouverts, ${unreadTexts} chats non lus`,
      );
    }
  }
}

async function signInWithPassword(apiKey: string, email: string, password: string): Promise<string> {
  const response = await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${apiKey}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password, returnSecureToken: true }),
  });
  if (!response.ok) throw new Error(`Connexion Firebase refusée pour ${email} (${response.status})`);
  return ((await response.json()) as { idToken: string }).idToken;
}

async function main(): Promise<void> {
  // Les workers BullMQ du backend qui tourne traitent les jobs programmés ici.
  process.env.WORKERS_ENABLED = "false";
  const app = await NestFactory.create(AppModule, { logger: ["error", "warn", "log"] });
  // Socket.io branché sur l'adaptateur Redis (ADR-006) : les événements temps réel émis par le seed
  // atteignent les téléphones connectés au backend de dev, comme depuis n'importe quelle instance.
  const ioAdapter = new RedisIoAdapter(app);
  await ioAdapter.connect(process.env.REDIS_URL ?? "redis://localhost:6379");
  app.useWebSocketAdapter(ioAdapter);
  await app.init();
  try {
    await new DemoSeeder(app).run();
  } finally {
    await app.close();
    await ioAdapter.close();
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});

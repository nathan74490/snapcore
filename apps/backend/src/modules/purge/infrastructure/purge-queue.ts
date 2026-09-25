import type { ConnectionOptions, JobsOptions } from "bullmq";

export const PURGE_QUEUE = Symbol("PurgeQueue");

// Une seule file pour toutes les purges de médias (ADR-017) : Snaps privés et éléments de story.
export type PurgeTargetKind = "message" | "story";

export const PURGE_JOB_NAME: Record<PurgeTargetKind, string> = {
  message: "purge-message",
  story: "purge-story",
};
export const PURGE_SWEEP_JOB = "purge-sweep";
export const SWEEP_EVERY_MS = 60 * 60 * 1000;

// Identifiant déterministe (ADR-017) : `purge-message-<id>` / `purge-story-<id>`. Séparateur "-" :
// BullMQ refuse ":" dans un jobId personnalisé ("Custom Id cannot contain :", vérifié sur bullmq 5.81).
export const purgeJobId = (kind: PurgeTargetKind, targetId: string) => `${PURGE_JOB_NAME[kind]}-${targetId}`;

export interface PurgeJobData {
  targetId: string;
}

export const PURGE_JOB_OPTIONS: JobsOptions = {
  // Libère l'identifiant une fois la purge faite, pour que le balayage puisse le réutiliser.
  removeOnComplete: true,
  // Historique de diagnostic borné ; un job en échec conservé est relancé par le balayage.
  removeOnFail: { count: 50 },
  attempts: 5,
  backoff: { type: "exponential", delay: 60_000 },
};

// BullMQ exige maxRetriesPerRequest: null. On passe des options (et non une instance) pour que
// BullMQ crée et ferme lui-même ses connexions, dont la connexion bloquante du worker.
export function bullmqConnection(redisUrl: string): ConnectionOptions {
  const url = new URL(redisUrl);
  return {
    host: url.hostname,
    port: Number(url.port || 6379),
    username: url.username || undefined,
    password: url.password ? decodeURIComponent(url.password) : undefined,
    db: url.pathname.length > 1 ? Number(url.pathname.slice(1)) : 0,
    maxRetriesPerRequest: null,
  };
}

import type { Queue } from "bullmq";
import type { Clock } from "../../../../shared/domain/clock";
import type { SnapPurgeScheduler } from "../../application/ports/snap-purge.scheduler";
import {
  PURGE_JOB_OPTIONS,
  PURGE_MESSAGE_JOB,
  purgeMessageJobId,
  type PurgeMessageJobData,
} from "./purge-queue";

// Recette de docs/impl/purge-bullmq.md : l'opération dépend de l'état du job, jamais de
// "supprimer puis réajouter" sur un identifiant déterministe.
export class BullMqSnapPurgeScheduler implements SnapPurgeScheduler {
  constructor(
    private readonly queue: Queue<PurgeMessageJobData>,
    private readonly clock: Clock,
  ) {}

  async schedule(messageId: string, at: Date): Promise<void> {
    await this.add(messageId, this.delayUntil(at));
  }

  async reschedule(messageId: string, at: Date): Promise<void> {
    const job = await this.queue.getJob(purgeMessageJobId(messageId));
    if (!job) {
      await this.add(messageId, this.delayUntil(at));
      return;
    }
    // changeDelay prend un délai RELATIF à maintenant et n'agit que sur un job "delayed".
    // En attente d'exécution ou en cours : le handler relit scheduledPurgeAt, rien à faire.
    if ((await job.getState()) === "delayed") await job.changeDelay(this.delayUntil(at));
  }

  async recover(messageId: string): Promise<"retried" | "re-added" | "untouched"> {
    const job = await this.queue.getJob(purgeMessageJobId(messageId));
    if (!job) {
      // Perdu malgré l'AOF : un ajout est correct uniquement dans ce cas.
      await this.add(messageId, 0);
      return "re-added";
    }
    const state = await job.getState();
    if (state === "failed") {
      // L'identifiant reste occupé par l'échec conservé : un add serait ignoré sans erreur.
      await job.retry();
      return "retried";
    }
    if (state === "completed") {
      // Filet si removeOnComplete n'a pas libéré l'identifiant.
      await job.remove();
      await this.add(messageId, 0);
      return "re-added";
    }
    // En attente, différé ou en cours : le job normal suivra son cours.
    return "untouched";
  }

  private add(messageId: string, delay: number) {
    // 1er argument = NOM du job ; l'identifiant déterministe passe par jobId (sinon BullMQ en
    // génère un aléatoire sans erreur, voir docs/impl/purge-bullmq.md).
    return this.queue.add(PURGE_MESSAGE_JOB, { messageId }, { ...PURGE_JOB_OPTIONS, jobId: purgeMessageJobId(messageId), delay });
  }

  private delayUntil(at: Date): number {
    return Math.max(0, at.getTime() - this.clock.now().getTime());
  }
}

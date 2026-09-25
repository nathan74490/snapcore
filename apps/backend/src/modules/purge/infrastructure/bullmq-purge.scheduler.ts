import type { Queue } from "bullmq";
import type { Clock } from "../../../shared/domain/clock";
import type { SnapPurgeScheduler } from "../../messages/application/ports/snap-purge.scheduler";
import type { StoryPurgeScheduler } from "../../stories/application/ports";
import { PURGE_JOB_NAME, PURGE_JOB_OPTIONS, purgeJobId, type PurgeJobData, type PurgeTargetKind } from "./purge-queue";

// Recette de docs/impl/purge-bullmq.md : l'opération dépend de l'état du job, jamais de
// "supprimer puis réajouter" sur un identifiant déterministe. Une instance par type de cible.
export class BullMqPurgeScheduler implements SnapPurgeScheduler, StoryPurgeScheduler {
  constructor(
    private readonly queue: Queue<PurgeJobData>,
    private readonly clock: Clock,
    private readonly kind: PurgeTargetKind,
  ) {}

  async schedule(targetId: string, at: Date): Promise<void> {
    await this.add(targetId, this.delayUntil(at));
  }

  async reschedule(targetId: string, at: Date): Promise<void> {
    const job = await this.queue.getJob(purgeJobId(this.kind, targetId));
    if (!job) {
      await this.add(targetId, this.delayUntil(at));
      return;
    }
    // changeDelay prend un délai RELATIF à maintenant et n'agit que sur un job "delayed".
    // En attente d'exécution ou en cours : le handler relit l'échéance en base, rien à faire.
    if ((await job.getState()) === "delayed") await job.changeDelay(this.delayUntil(at));
  }

  async recover(targetId: string): Promise<"retried" | "re-added" | "untouched"> {
    const job = await this.queue.getJob(purgeJobId(this.kind, targetId));
    if (!job) {
      // Perdu malgré l'AOF : un ajout est correct uniquement dans ce cas.
      await this.add(targetId, 0);
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
      await this.add(targetId, 0);
      return "re-added";
    }
    // En attente, différé ou en cours : le job normal suivra son cours.
    return "untouched";
  }

  private add(targetId: string, delay: number) {
    // 1er argument = NOM du job ; l'identifiant déterministe passe par jobId (sinon BullMQ en
    // génère un aléatoire sans erreur, voir docs/impl/purge-bullmq.md).
    return this.queue.add(
      PURGE_JOB_NAME[this.kind],
      { targetId },
      { ...PURGE_JOB_OPTIONS, jobId: purgeJobId(this.kind, targetId), delay },
    );
  }

  private delayUntil(at: Date): number {
    return Math.max(0, at.getTime() - this.clock.now().getTime());
  }
}

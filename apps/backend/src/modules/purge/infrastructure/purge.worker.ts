import { Logger, type OnApplicationShutdown, type OnModuleInit } from "@nestjs/common";
import { DelayedError, Worker, type ConnectionOptions, type Job, type Queue } from "bullmq";
import { PURGE_JOB_NAME, PURGE_SWEEP_JOB, SWEEP_EVERY_MS, type PurgeJobData, type PurgeTargetKind } from "./purge-queue";

// Ce qu'un contexte (messages, stories, groupes) doit fournir au worker pour une cible de purge.
export interface PurgeHandler {
  run(targetId: string): Promise<{ kind: "done" } | { kind: "delay"; until: Date }>;
  sweepOverdue(): Promise<unknown>;
}

// Consomme la file de purge : jobs purge-message-<id>, purge-story-<id>, purge-group-message-<id> et
// balayage horaire.
export class PurgeWorker implements OnModuleInit, OnApplicationShutdown {
  private readonly logger = new Logger(PurgeWorker.name);
  private worker?: Worker;

  constructor(
    private readonly queue: Queue,
    private readonly connection: ConnectionOptions,
    private readonly handlers: Record<PurgeTargetKind, PurgeHandler>,
    private readonly enabled: boolean,
  ) {}

  async onModuleInit(): Promise<void> {
    if (!this.enabled) {
      this.logger.warn("Worker de purge désactivé (WORKERS_ENABLED=false)");
      return;
    }
    // Planificateur idempotent : relancer l'application ne crée pas un second balayage.
    await this.queue.upsertJobScheduler(
      PURGE_SWEEP_JOB,
      { every: SWEEP_EVERY_MS },
      { name: PURGE_SWEEP_JOB, opts: { removeOnComplete: true, removeOnFail: { count: 20 } } },
    );
    this.worker = new Worker(this.queue.name, (job, token) => this.process(job, token), {
      connection: this.connection,
      concurrency: 5,
    });
    this.worker.on("failed", (job, error) =>
      this.logger.warn(`Job ${job?.id} en échec (tentative ${job?.attemptsMade}) : ${error.message}`),
    );
  }

  async onApplicationShutdown(): Promise<void> {
    await this.worker?.close();
    await this.queue.close();
  }

  private async process(job: Job, token?: string): Promise<unknown> {
    if (job.name === PURGE_SWEEP_JOB) {
      return {
        message: await this.handlers.message.sweepOverdue(),
        story: await this.handlers.story.sweepOverdue(),
        groupMessage: await this.handlers.groupMessage.sweepOverdue(),
      };
    }
    const kind = (Object.keys(PURGE_JOB_NAME) as PurgeTargetKind[]).find((k) => PURGE_JOB_NAME[k] === job.name);
    if (!kind) throw new Error(`Job inconnu : ${job.name}`);
    const handler = this.handlers[kind];

    const outcome = await handler.run((job.data as PurgeJobData).targetId);
    if (outcome.kind === "delay") {
      // Un job en cours ne peut être ni supprimé ni réajouté : on le re-diffère lui-même
      // (timestamp ABSOLU) puis DelayedError signale à BullMQ qu'il reprendra plus tard.
      await job.moveToDelayed(outcome.until.getTime(), token);
      throw new DelayedError();
    }
    return outcome;
  }
}

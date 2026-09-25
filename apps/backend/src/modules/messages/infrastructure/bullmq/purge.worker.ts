import { Logger, type OnApplicationShutdown, type OnModuleInit } from "@nestjs/common";
import { DelayedError, Worker, type ConnectionOptions, type Job, type Queue } from "bullmq";
import type { SnapPurgeUseCases } from "../../application/snap-purge-use-cases";
import { PURGE_MESSAGE_JOB, PURGE_SWEEP_JOB, SWEEP_EVERY_MS, type PurgeMessageJobData } from "./purge-queue";

// Consomme la file de purge : jobs purge-message-<id> et balayage horaire de rattrapage.
export class PurgeWorker implements OnModuleInit, OnApplicationShutdown {
  private readonly logger = new Logger(PurgeWorker.name);
  private worker?: Worker;

  constructor(
    private readonly queue: Queue,
    private readonly connection: ConnectionOptions,
    private readonly useCases: SnapPurgeUseCases,
    private readonly enabled: boolean,
  ) {}

  async onModuleInit(): Promise<void> {
    if (!this.enabled) {
      this.logger.warn("Worker de purge désactivé (PURGE_WORKER_ENABLED=false)");
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
    if (job.name === PURGE_SWEEP_JOB) return this.useCases.sweepOverdue();
    if (job.name !== PURGE_MESSAGE_JOB) throw new Error(`Job inconnu : ${job.name}`);

    const outcome = await this.useCases.runPurgeJob((job.data as PurgeMessageJobData).messageId);
    if (outcome.kind === "delay") {
      // Un job en cours ne peut être ni supprimé ni réajouté : on le re-diffère lui-même
      // (timestamp ABSOLU) puis DelayedError signale à BullMQ qu'il reprendra plus tard.
      await job.moveToDelayed(outcome.until.getTime(), token);
      throw new DelayedError();
    }
    return outcome;
  }
}

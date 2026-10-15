import { Logger, type OnApplicationShutdown, type OnModuleInit } from "@nestjs/common";
import { Worker, type ConnectionOptions, type Job, type Queue } from "bullmq";
import type { AccountDeletionUseCases } from "../application/account-deletion-use-cases";
import type { DeletionJobData, DeletionJobQueue, OpsAlerter } from "../application/ports";

export const ACCOUNT_DELETION_QUEUE = Symbol("AccountDeletionQueue");

const DELETE_ACCOUNT_JOB = "delete-account";
const DELETION_SWEEP_JOB = "delete-account-sweep";
const SWEEP_EVERY_MS = 60 * 60 * 1000;
const ALERT_AFTER_MS = 24 * 60 * 60 * 1000;
const MAX_BACKOFF_MS = 30 * 60 * 1000;
const CAPPED_BACKOFF = "capped-exponential";

export const deletionJobId = (userId: string) => `delete-account-${userId}`;

// Réessais quasi illimités (ADR-019) : jamais d'abandon silencieux. Avec un plafond de 30 min,
// 10 000 tentatives représentent plus de six mois.
const DELETION_JOB_OPTIONS = {
  attempts: 10_000,
  backoff: { type: CAPPED_BACKOFF },
  removeOnComplete: true,
  removeOnFail: { count: 100 },
};

export class BullMqDeletionJobQueue implements DeletionJobQueue {
  constructor(private readonly queue: Queue<DeletionJobData>) {}

  async enqueue(userId: string, firebaseUid: string): Promise<void> {
    // Charge utile minimale (ADR-019) ; jobId déterministe : un rattrapage ne crée pas de doublon.
    await this.queue.add(DELETE_ACCOUNT_JOB, { userId, firebaseUid }, { ...DELETION_JOB_OPTIONS, jobId: deletionJobId(userId) });
  }
}

// Backoff exponentiel plafonné : tentatives rapprochées au début, puis toutes les 30 min.
export function cappedBackoff(attemptsMade: number, baseDelayMs: number): number {
  return Math.min(MAX_BACKOFF_MS, baseDelayMs * 2 ** Math.max(0, attemptsMade - 1));
}

export class AccountDeletionWorker implements OnModuleInit, OnApplicationShutdown {
  private readonly logger = new Logger(AccountDeletionWorker.name);
  private worker?: Worker;
  private readonly alerted = new Set<string>();

  constructor(
    private readonly queue: Queue<DeletionJobData>,
    private readonly connection: ConnectionOptions,
    private readonly useCases: AccountDeletionUseCases,
    private readonly alerter: OpsAlerter,
    private readonly options: { enabled: boolean; baseBackoffMs: number },
  ) {}

  async onModuleInit(): Promise<void> {
    if (!this.options.enabled) return;
    await this.queue.upsertJobScheduler(
      DELETION_SWEEP_JOB,
      { every: SWEEP_EVERY_MS },
      { name: DELETION_SWEEP_JOB, opts: { removeOnComplete: true, removeOnFail: { count: 20 } } },
    );
    this.worker = new Worker<DeletionJobData>(this.queue.name, (job) => this.process(job), {
      connection: this.connection,
      concurrency: 2,
      settings: {
        backoffStrategy: (attemptsMade, type) =>
          type === CAPPED_BACKOFF ? cappedBackoff(attemptsMade, this.options.baseBackoffMs) : 0,
      },
    });
    this.worker.on("failed", (job, error) => job && this.onFailure(job, error));
  }

  async onApplicationShutdown(): Promise<void> {
    await this.worker?.close();
    await this.queue.close();
  }

  private async process(job: Job<DeletionJobData>): Promise<unknown> {
    if (job.name === DELETION_SWEEP_JOB) return this.useCases.sweepPending();
    return this.useCases.runJob({
      data: job.data,
      // Persistance des clés dans la charge utile du job (étape c, dans la transaction).
      persistCollected: (collected) => job.updateData({ ...job.data, collected }),
    });
  }

  private onFailure(job: Job<DeletionJobData>, error: Error): void {
    this.logger.warn(`Suppression ${job.id} en échec (tentative ${job.attemptsMade}) : ${error.message}`);
    // Un compte "en suppression" bloqué indéfiniment sans que personne ne le sache serait pire
    // qu'un léger délai : alerte une fois passé 24h (ADR-019).
    if (Date.now() - job.timestamp > ALERT_AFTER_MS && job.id && !this.alerted.has(job.id)) {
      this.alerted.add(job.id);
      this.alerter.alert(`Suppression de compte ${job.data.userId} toujours en échec après 24h : ${error.message}`);
    }
  }
}

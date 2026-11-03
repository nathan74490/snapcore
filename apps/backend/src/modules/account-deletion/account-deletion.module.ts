import { Logger, Module } from "@nestjs/common";
import { ConfigService } from "@nestjs/config";
import { Queue } from "bullmq";
import { CLOCK, type Clock } from "../../shared/domain/clock";
import { workersEnabled } from "../../shared/infrastructure/workers";
import { IDENTITY_PROVIDER, type IdentityProvider } from "../identity/application/ports/identity-provider.port";
import { IdentityModule } from "../identity/identity.module";
import { LOCATION_STORE, type LocationStore } from "../location/application/ports";
import { LocationModule } from "../location/location.module";
import { OBJECT_STORAGE, type ObjectStorage } from "../media/application/ports/object-storage.port";
import { PublicMediaUrls } from "../media/domain/public-media-urls";
import { MediaModule } from "../media/media.module";
import { REALTIME_CHANNEL, type RealtimeChannel } from "../notifications/application/ports";
import { NotificationsModule } from "../notifications/notifications.module";
import { bullmqConnection } from "../purge/infrastructure/purge-queue";
import { redisUrlFrom } from "../purge/purge.module";
import { AccountDeletionUseCases } from "./application/account-deletion-use-cases";
import {
  ACCOUNT_DELETION_REPOSITORY,
  DELETION_JOB_QUEUE,
  OPS_ALERTER,
  SUBSCRIPTION_CANCELLER,
  type AccountDeletionRepository,
  type DeletionJobQueue,
  type OpsAlerter,
  type SubscriptionCanceller,
} from "./application/ports";
import { ACCOUNT_DELETION_QUEUE, AccountDeletionWorker, BullMqDeletionJobQueue } from "./infrastructure/bullmq-deletion";
import { PrismaAccountDeletionRepository } from "./infrastructure/prisma-account-deletion.repository";
import { LoggingOpsAlerter } from "./infrastructure/logging-ops-alerter";
import { RevenueCatSubscriptionCanceller } from "./infrastructure/revenuecat-subscription-canceller";
import { AccountDeletionController } from "./interface/account-deletion.controller";

@Module({
  imports: [IdentityModule, MediaModule, NotificationsModule, LocationModule],
  controllers: [AccountDeletionController],
  providers: [
    { provide: ACCOUNT_DELETION_REPOSITORY, useClass: PrismaAccountDeletionRepository },
    {
      // Annulation SnapCore+ à la suppression du compte (ADR-019, BUG-009) ; sans clé, simple avertissement.
      provide: SUBSCRIPTION_CANCELLER,
      inject: [ConfigService],
      useFactory: (config: ConfigService) => {
        const secretApiKey = config.get<string>("REVENUECAT_SECRET_API_KEY");
        const projectId = config.get<string>("REVENUECAT_PROJECT_ID");
        return new RevenueCatSubscriptionCanceller(secretApiKey && projectId ? { secretApiKey, projectId } : null);
      },
    },
    { provide: OPS_ALERTER, useClass: LoggingOpsAlerter },
    {
      provide: ACCOUNT_DELETION_QUEUE,
      inject: [ConfigService],
      useFactory: (config: ConfigService) =>
        new Queue(config.get("ACCOUNT_DELETION_QUEUE_NAME", "account-deletion"), {
          connection: bullmqConnection(redisUrlFrom(config)),
        }),
    },
    {
      provide: DELETION_JOB_QUEUE,
      inject: [ACCOUNT_DELETION_QUEUE],
      useFactory: (queue: Queue) => new BullMqDeletionJobQueue(queue),
    },
    {
      provide: AccountDeletionUseCases,
      inject: [
        ACCOUNT_DELETION_REPOSITORY,
        DELETION_JOB_QUEUE,
        IDENTITY_PROVIDER,
        SUBSCRIPTION_CANCELLER,
        OBJECT_STORAGE,
        PublicMediaUrls,
        REALTIME_CHANNEL,
        CLOCK,
        LOCATION_STORE,
      ],
      useFactory: (
        accounts: AccountDeletionRepository,
        queue: DeletionJobQueue,
        identity: IdentityProvider,
        subscriptions: SubscriptionCanceller,
        storage: ObjectStorage,
        urls: PublicMediaUrls,
        realtime: RealtimeChannel,
        clock: Clock,
        locations: LocationStore,
      ) =>
        new AccountDeletionUseCases(
          accounts,
          queue,
          identity,
          subscriptions,
          storage,
          urls,
          realtime,
          clock,
          new Logger(AccountDeletionUseCases.name),
          locations,
        ),
    },
    {
      provide: AccountDeletionWorker,
      inject: [ACCOUNT_DELETION_QUEUE, ConfigService, AccountDeletionUseCases, OPS_ALERTER],
      useFactory: (queue: Queue, config: ConfigService, useCases: AccountDeletionUseCases, alerter: OpsAlerter) =>
        new AccountDeletionWorker(queue, bullmqConnection(redisUrlFrom(config)), useCases, alerter, {
          // Même interrupteur que la purge : une instance "API seule" ne consomme aucune file.
          enabled: workersEnabled(config),
          baseBackoffMs: 10_000,
        }),
    },
  ],
})
export class AccountDeletionModule {}

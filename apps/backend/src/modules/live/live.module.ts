import { Module } from "@nestjs/common";
import { ConfigService } from "@nestjs/config";
import { systemClock } from "../../shared/domain/clock";
import { IssueParticipantToken } from "./application/issue-participant-token";
import { LiveController } from "./interface/live.controller";

// SDK Live commun (ADR-035) : SnapCore signe les Jetons de Participant de ses utilisateurs ;
// les Lives eux-mêmes vivent sur le serveur commun, jamais ici.
@Module({
  controllers: [LiveController],
  providers: [
    {
      provide: IssueParticipantToken,
      inject: [ConfigService],
      useFactory: (config: ConfigService) =>
        new IssueParticipantToken(config.get<string>("LIVE_SDK_SECRET") || null, systemClock),
    },
  ],
})
export class LiveModule {}

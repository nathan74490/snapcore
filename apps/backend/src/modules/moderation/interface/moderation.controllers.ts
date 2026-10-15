import { Body, Controller, Delete, HttpCode, Param, ParseUUIDPipe, Post, UseGuards } from "@nestjs/common";
import type { ReportTargetType } from "@snapcore/types";
import { IsIn, IsString, IsUUID, MaxLength } from "class-validator";
import type { UserAccount } from "../../identity/domain/user";
import { CurrentUser, Public } from "../../identity/interface/http/auth.decorators";
import { ModerationUseCases } from "../application/moderation-use-cases";
import type { ReportTarget } from "../application/ports";
import { ReportUseCases } from "../application/report-use-cases";
import { AdminSecretGuard } from "./admin-secret.guard";

// --- Signalement depuis l'app (utilisateur authentifié par Firebase) -------------------------------

class CreateReportDto {
  @IsIn(["USER", "MESSAGE", "STORY_ITEM"])
  targetType: ReportTargetType;

  @IsUUID()
  targetId: string;

  @IsString()
  @MaxLength(1000)
  reason: string;
}

@Controller("reports")
export class ReportsController {
  constructor(private readonly reports: ReportUseCases) {}

  @Post()
  @HttpCode(204)
  async create(@CurrentUser() me: UserAccount, @Body() body: CreateReportDto): Promise<void> {
    const target: ReportTarget =
      body.targetType === "USER"
        ? { type: "USER", userId: body.targetId }
        : body.targetType === "MESSAGE"
          ? { type: "MESSAGE", messageId: body.targetId }
          : { type: "STORY_ITEM", storyItemId: body.targetId };
    await this.reports.report(me, target, body.reason);
  }
}

// --- API admin, appelée par le serveur du backoffice (ADR-021) -------------------------------------

class ModeratorActionDto {
  @IsUUID()
  moderatorId: string;

  @IsString()
  @MaxLength(1000)
  reason: string;
}

class ModeratorOnlyDto {
  @IsUUID()
  moderatorId: string;
}

class CloseReportDto extends ModeratorOnlyDto {
  @IsIn(["RESOLVED", "DISMISSED"])
  status: "RESOLVED" | "DISMISSED";
}

// @Public : pas de jeton Firebase ici (un modérateur n'a pas de compte Firebase, ADR-020) ;
// l'accès est contrôlé par AdminSecretGuard.
@Controller("admin")
@Public()
@UseGuards(AdminSecretGuard)
export class AdminController {
  constructor(private readonly moderation: ModerationUseCases) {}

  @Post("users/:userId/suspend")
  @HttpCode(204)
  suspend(@Param("userId", ParseUUIDPipe) userId: string, @Body() body: ModeratorActionDto): Promise<void> {
    return this.moderation.suspendUser(userId, body.moderatorId, body.reason);
  }

  @Post("users/:userId/reactivate")
  @HttpCode(204)
  reactivate(@Param("userId", ParseUUIDPipe) userId: string, @Body() body: ModeratorOnlyDto): Promise<void> {
    return this.moderation.reactivateUser(userId, body.moderatorId);
  }

  @Post("messages/:messageId/redact")
  @HttpCode(204)
  redact(@Param("messageId", ParseUUIDPipe) messageId: string, @Body() body: ModeratorActionDto): Promise<void> {
    return this.moderation.redactMessage(messageId, body.moderatorId, body.reason);
  }

  @Delete("stories/:storyItemId")
  @HttpCode(204)
  deleteStory(@Param("storyItemId", ParseUUIDPipe) storyItemId: string, @Body() body: ModeratorActionDto): Promise<void> {
    return this.moderation.deleteStoryItem(storyItemId, body.moderatorId, body.reason);
  }

  @Post("reports/:reportId/close")
  @HttpCode(204)
  closeReport(@Param("reportId", ParseUUIDPipe) reportId: string, @Body() body: CloseReportDto): Promise<void> {
    return this.moderation.closeReport(reportId, body.status, body.moderatorId);
  }
}

import { Body, Controller, Get, HttpCode, Param, ParseUUIDPipe, Post, Query } from "@nestjs/common";
import type { ConversationSummary, MessageView, SnapViewUrl } from "@snapcore/types";
import { Type } from "class-transformer";
import { IsIn, IsISO8601, IsInt, IsOptional, IsString, IsUUID, Max, MaxLength, Min } from "class-validator";
import type { UserAccount } from "../../../identity/domain/user";
import { CurrentUser } from "../../../identity/interface/http/auth.decorators";
import { MessagingUseCases } from "../../application/messaging-use-cases";
import { MAX_TEXT_LENGTH, type SnapContentType } from "../../domain/message";

class SendTextDto {
  @IsString()
  @MaxLength(MAX_TEXT_LENGTH * 2) // la limite exacte est appliquée après trim par le domaine
  content: string;
}

class SendSnapDto {
  @IsUUID()
  uploadId: string;

  @IsIn(["SNAP_PHOTO", "SNAP_PHOTO_VIDEO"])
  contentType: SnapContentType;
}

class ListMessagesQuery {
  @IsOptional()
  @IsISO8601()
  before?: string;

  @IsOptional()
  @Type(() => Number)
  @IsInt()
  @Min(1)
  @Max(100)
  limit?: number;
}

@Controller()
export class MessagesController {
  constructor(private readonly messaging: MessagingUseCases) {}

  @Get("conversations")
  listConversations(@CurrentUser() me: UserAccount): Promise<ConversationSummary[]> {
    return this.messaging.listConversations(me);
  }

  // Pagination vers le passé : `before` = createdAt du plus ancien message déjà affiché.
  @Get("conversations/:friendId/messages")
  listMessages(
    @CurrentUser() me: UserAccount,
    @Param("friendId", ParseUUIDPipe) friendId: string,
    @Query() query: ListMessagesQuery,
  ): Promise<MessageView[]> {
    return this.messaging.listMessages(me, friendId, {
      before: query.before ? new Date(query.before) : undefined,
      limit: query.limit,
    });
  }

  @Post("conversations/:friendId/messages/text")
  sendText(
    @CurrentUser() me: UserAccount,
    @Param("friendId", ParseUUIDPipe) friendId: string,
    @Body() body: SendTextDto,
  ): Promise<MessageView> {
    return this.messaging.sendText(me, friendId, body.content);
  }

  // Confirme un upload SNAP_PHOTO / SNAP_VIDEO obtenu via POST /media/uploads (ADR-016).
  @Post("conversations/:friendId/messages/snap")
  sendSnap(
    @CurrentUser() me: UserAccount,
    @Param("friendId", ParseUUIDPipe) friendId: string,
    @Body() body: SendSnapDto,
  ): Promise<MessageView> {
    return this.messaging.sendSnap(me, friendId, body);
  }

  @Post("conversations/:friendId/read")
  @HttpCode(204)
  markRead(@CurrentUser() me: UserAccount, @Param("friendId", ParseUUIDPipe) friendId: string): Promise<void> {
    return this.messaging.markConversationRead(me, friendId);
  }

  // Ouvre un Snap : URL de lecture valable ~1 min, à ne jamais mettre en cache disque (ADR-009).
  @Post("messages/:messageId/view")
  @HttpCode(200)
  view(@CurrentUser() me: UserAccount, @Param("messageId", ParseUUIDPipe) messageId: string): Promise<SnapViewUrl> {
    return this.messaging.requestSnapView(me, messageId);
  }
}

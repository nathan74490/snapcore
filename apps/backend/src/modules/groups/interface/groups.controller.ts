import { Body, Controller, Delete, Get, HttpCode, Param, ParseUUIDPipe, Patch, Post, Query } from "@nestjs/common";
import type { GroupMessageView, GroupSummary, GroupView, SnapViewUrl } from "@snapcore/types";
import { Type } from "class-transformer";
import {
  ArrayMaxSize,
  ArrayNotEmpty,
  IsArray,
  IsIn,
  IsISO8601,
  IsInt,
  IsOptional,
  IsString,
  IsUUID,
  Max,
  MaxLength,
  Min,
  ValidateIf,
} from "class-validator";
import type { UserAccount } from "../../identity/domain/user";
import { CurrentUser } from "../../identity/interface/http/auth.decorators";
import { MAX_TEXT_LENGTH, type SnapContentType } from "../../messages/domain/message";
import { GroupMessagingUseCases } from "../application/group-messaging-use-cases";
import { GroupUseCases } from "../application/group-use-cases";
import { MAX_GROUP_MEMBERS, MAX_GROUP_NAME_LENGTH } from "../domain/group";

// Bornes larges : les limites exactes (2 à 31 amis, 50 caractères après trim) sont appliquées par le
// domaine, avec un message explicite.
class CreateGroupDto {
  @IsOptional()
  @IsString()
  @MaxLength(MAX_GROUP_NAME_LENGTH * 4)
  name?: string | null;

  @IsArray()
  @ArrayMaxSize(MAX_GROUP_MEMBERS * 2)
  @IsUUID("all", { each: true })
  memberIds: string[];
}

class RenameGroupDto {
  // null ou "" retire le nom.
  @ValidateIf((_, value) => value !== null)
  @IsString()
  @MaxLength(MAX_GROUP_NAME_LENGTH * 4)
  name: string | null;
}

class AddMembersDto {
  @IsArray()
  @ArrayNotEmpty()
  @ArrayMaxSize(MAX_GROUP_MEMBERS * 2)
  @IsUUID("all", { each: true })
  userIds: string[];
}

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

// Groupes de discussion (ADR-032).
@Controller()
export class GroupsController {
  constructor(
    private readonly groups: GroupUseCases,
    private readonly messaging: GroupMessagingUseCases,
  ) {}

  @Post("groups")
  create(@CurrentUser() me: UserAccount, @Body() body: CreateGroupDto): Promise<GroupView> {
    return this.groups.create(me, body);
  }

  @Get("groups")
  list(@CurrentUser() me: UserAccount): Promise<GroupSummary[]> {
    return this.groups.list(me);
  }

  @Get("groups/:groupId")
  get(@CurrentUser() me: UserAccount, @Param("groupId", ParseUUIDPipe) groupId: string): Promise<GroupView> {
    return this.groups.get(me, groupId);
  }

  @Patch("groups/:groupId")
  rename(
    @CurrentUser() me: UserAccount,
    @Param("groupId", ParseUUIDPipe) groupId: string,
    @Body() body: RenameGroupDto,
  ): Promise<GroupView> {
    return this.groups.rename(me, groupId, body.name);
  }

  @Post("groups/:groupId/members")
  @HttpCode(200)
  addMembers(
    @CurrentUser() me: UserAccount,
    @Param("groupId", ParseUUIDPipe) groupId: string,
    @Body() body: AddMembersDto,
  ): Promise<GroupView> {
    return this.groups.addMembers(me, groupId, body.userIds);
  }

  @Delete("groups/:groupId/members/me")
  @HttpCode(204)
  leave(@CurrentUser() me: UserAccount, @Param("groupId", ParseUUIDPipe) groupId: string): Promise<void> {
    return this.groups.leave(me, groupId);
  }

  // Pagination vers le passé : `before` = createdAt du plus ancien message déjà affiché.
  @Get("groups/:groupId/messages")
  listMessages(
    @CurrentUser() me: UserAccount,
    @Param("groupId", ParseUUIDPipe) groupId: string,
    @Query() query: ListMessagesQuery,
  ): Promise<GroupMessageView[]> {
    return this.messaging.listMessages(me, groupId, {
      before: query.before ? new Date(query.before) : undefined,
      limit: query.limit,
    });
  }

  @Post("groups/:groupId/messages/text")
  sendText(
    @CurrentUser() me: UserAccount,
    @Param("groupId", ParseUUIDPipe) groupId: string,
    @Body() body: SendTextDto,
  ): Promise<GroupMessageView> {
    return this.messaging.sendText(me, groupId, body.content);
  }

  // Confirme un upload SNAP_PHOTO / SNAP_VIDEO obtenu via POST /media/uploads (ADR-016).
  @Post("groups/:groupId/messages/snap")
  sendSnap(
    @CurrentUser() me: UserAccount,
    @Param("groupId", ParseUUIDPipe) groupId: string,
    @Body() body: SendSnapDto,
  ): Promise<GroupMessageView> {
    return this.messaging.sendSnap(me, groupId, body);
  }

  @Post("groups/:groupId/read")
  @HttpCode(204)
  markRead(@CurrentUser() me: UserAccount, @Param("groupId", ParseUUIDPipe) groupId: string): Promise<void> {
    return this.messaging.markRead(me, groupId);
  }

  // L'app l'appelle au plus toutes les quelques secondes pendant la saisie (ADR-027).
  @Post("groups/:groupId/typing")
  @HttpCode(204)
  typing(@CurrentUser() me: UserAccount, @Param("groupId", ParseUUIDPipe) groupId: string): Promise<void> {
    return this.messaging.notifyTyping(me, groupId);
  }

  // Ouvre un snap de groupe : URL de lecture valable ~1 min, à ne jamais mettre en cache disque (ADR-009).
  @Post("group-messages/:messageId/view")
  @HttpCode(200)
  view(@CurrentUser() me: UserAccount, @Param("messageId", ParseUUIDPipe) messageId: string): Promise<SnapViewUrl> {
    return this.messaging.requestSnapView(me, messageId);
  }
}

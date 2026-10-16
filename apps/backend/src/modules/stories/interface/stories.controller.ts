import { Body, Controller, Delete, Get, HttpCode, Param, ParseUUIDPipe, Post } from "@nestjs/common";
import type { OwnStoryItemView, PublicUserProfile, SnapViewUrl, StoryContentType, StoryFeedEntry } from "@snapcore/types";
import { IsIn, IsOptional, IsUUID } from "class-validator";
import type { UserAccount } from "../../identity/domain/user";
import { CurrentUser } from "../../identity/interface/http/auth.decorators";
import { StoryUseCases } from "../application/story-use-cases";

class PublishStoryDto {
  @IsUUID()
  uploadId: string;

  @IsIn(["PHOTO", "VIDEO"])
  contentType: StoryContentType;

  // Upload STORY_THUMBNAIL facultatif ; ignoré s'il est invalide (ADR-026).
  @IsOptional()
  @IsUUID()
  thumbnailUploadId?: string;
}

@Controller("stories")
export class StoriesController {
  constructor(private readonly stories: StoryUseCases) {}

  // Stories de mes amis (ADR-013), avec la vignette de l'anneau sans compter de vue (ADR-026).
  @Get()
  feed(@CurrentUser() me: UserAccount): Promise<StoryFeedEntry[]> {
    return this.stories.feed(me);
  }

  @Get("me")
  mine(@CurrentUser() me: UserAccount): Promise<OwnStoryItemView[]> {
    return this.stories.mine(me);
  }

  // Confirme un upload STORY_PHOTO / STORY_VIDEO obtenu via POST /media/uploads (ADR-016),
  // et la vignette STORY_THUMBNAIL éventuelle (ADR-026).
  @Post()
  publish(@CurrentUser() me: UserAccount, @Body() body: PublishStoryDto): Promise<OwnStoryItemView> {
    return this.stories.publish(me, body);
  }

  // URL de lecture ~1 min ; enregistre la vue (sauf pour l'auteur lui-même).
  @Post(":storyId/view")
  @HttpCode(200)
  view(@CurrentUser() me: UserAccount, @Param("storyId", ParseUUIDPipe) storyId: string): Promise<SnapViewUrl> {
    return this.stories.view(me, storyId);
  }

  @Get(":storyId/viewers")
  viewers(@CurrentUser() me: UserAccount, @Param("storyId", ParseUUIDPipe) storyId: string): Promise<PublicUserProfile[]> {
    return this.stories.viewers(me, storyId);
  }

  @Delete(":storyId")
  @HttpCode(204)
  remove(@CurrentUser() me: UserAccount, @Param("storyId", ParseUUIDPipe) storyId: string): Promise<void> {
    return this.stories.remove(me, storyId);
  }
}

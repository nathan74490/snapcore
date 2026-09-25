import { Body, Controller, Post } from "@nestjs/common";
import { IsIn, IsInt, IsPositive, IsString, MaxLength } from "class-validator";
import type { UserAccount } from "../../../identity/domain/user";
import { CurrentUser } from "../../../identity/interface/http/auth.decorators";
import { RequestUpload, type UploadInstructions } from "../../application/request-upload";
import { MEDIA_PURPOSES, type MediaPurpose } from "../../domain/media-purpose";

class RequestUploadDto {
  @IsIn(MEDIA_PURPOSES)
  purpose: MediaPurpose;

  @IsString()
  @MaxLength(100)
  contentType: string;

  @IsInt()
  @IsPositive()
  contentLength: number;
}

@Controller("media")
export class MediaController {
  constructor(private readonly requestUpload: RequestUpload) {}

  // 1re étape d'ADR-016 : l'app reçoit une URL de PUT vers tmp/<uploadId>, puis confirme
  // l'uploadId auprès de l'endpoint métier concerné (photo de profil, snap, story).
  @Post("uploads")
  create(@CurrentUser() user: UserAccount, @Body() body: RequestUploadDto): Promise<UploadInstructions> {
    return this.requestUpload.execute(user, body);
  }
}

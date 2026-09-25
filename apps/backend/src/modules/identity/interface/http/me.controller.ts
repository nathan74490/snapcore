import { Body, Controller, Delete, Get, HttpCode, Patch, Post, Put } from "@nestjs/common";
import type { User } from "@snapcore/types";
import { IsUUID } from "class-validator";
import { ProfilePhotoUseCases } from "../../application/use-cases/profile-photo";
import { SyncEmailFromIdentityProvider } from "../../application/use-cases/sync-email";
import { UpdateProfile } from "../../application/use-cases/update-profile";
import type { UserAccount } from "../../domain/user";
import { CurrentUser } from "./auth.decorators";
import { UpdateProfileDto } from "./dto/update-profile.dto";
import { presentSelf } from "./user.presenter";

class ConfirmUploadDto {
  @IsUUID()
  uploadId: string;
}

@Controller("me")
export class MeController {
  constructor(
    private readonly updateProfile: UpdateProfile,
    private readonly syncEmail: SyncEmailFromIdentityProvider,
    private readonly profilePhoto: ProfilePhotoUseCases,
  ) {}

  @Get()
  get(@CurrentUser() user: UserAccount): User {
    return presentSelf(user);
  }

  @Patch()
  async update(@CurrentUser() user: UserAccount, @Body() body: UpdateProfileDto): Promise<User> {
    return presentSelf(await this.updateProfile.execute(user, body));
  }

  // À appeler après confirmation d'un changement d'email via verifyBeforeUpdateEmail (ADR-014).
  @Post("email/sync")
  @HttpCode(200)
  async syncEmailFromFirebase(@CurrentUser() user: UserAccount): Promise<User> {
    return presentSelf(await this.syncEmail.execute(user));
  }

  // Confirme un upload PROFILE_PHOTO obtenu via POST /media/uploads (ADR-016).
  @Put("photo")
  async changePhoto(@CurrentUser() user: UserAccount, @Body() body: ConfirmUploadDto): Promise<User> {
    return presentSelf(await this.profilePhoto.change(user, body.uploadId));
  }

  @Delete("photo")
  async removePhoto(@CurrentUser() user: UserAccount): Promise<User> {
    return presentSelf(await this.profilePhoto.remove(user));
  }
}

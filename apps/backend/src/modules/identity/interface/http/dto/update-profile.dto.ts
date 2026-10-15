import { IsOptional, IsString, MaxLength, ValidateIf } from "class-validator";

export class UpdateProfileDto {
  @IsOptional()
  @IsString()
  @MaxLength(64)
  username?: string;

  // null retire le numéro ; absent le laisse inchangé.
  @ValidateIf((_dto, value) => value !== null && value !== undefined)
  @IsString()
  @MaxLength(32)
  phoneNumber?: string | null;
}

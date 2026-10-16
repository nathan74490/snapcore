import { IsOptional, IsString, MaxLength } from "class-validator";

export class RegisterDto {
  @IsString()
  @MaxLength(64)
  username: string;

  // Obligatoire à l'inscription ; le format est validé par le domaine (parseFirstName).
  @IsString()
  @MaxLength(64)
  firstName: string;

  @IsOptional()
  @IsString()
  @MaxLength(32)
  phoneNumber?: string;
}

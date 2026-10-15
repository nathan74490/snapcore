import { IsOptional, IsString, MaxLength } from "class-validator";

export class RegisterDto {
  @IsString()
  @MaxLength(64)
  username: string;

  @IsOptional()
  @IsString()
  @MaxLength(32)
  phoneNumber?: string;
}

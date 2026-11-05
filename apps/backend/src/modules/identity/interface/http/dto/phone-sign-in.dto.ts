import { IsString, Matches, MaxLength } from "class-validator";

export class SendPhoneCodeDto {
  @IsString()
  @MaxLength(32)
  phoneNumber: string;
}

export class VerifyPhoneCodeDto {
  @IsString()
  @MaxLength(32)
  phoneNumber: string;

  @IsString()
  @Matches(/^\s*\d{6}\s*$/, { message: "Le code compte 6 chiffres" })
  code: string;
}

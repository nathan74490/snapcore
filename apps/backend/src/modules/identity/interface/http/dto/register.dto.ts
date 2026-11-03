import { IsOptional, IsString, MaxLength } from "class-validator";

export class RegisterDto {
  @IsString()
  @MaxLength(64)
  username: string;

  // Obligatoire à l'inscription ; le format est validé par le domaine (parseFirstName).
  @IsString()
  @MaxLength(64)
  firstName: string;

  // Facultatif ; chaîne vide = pas de nom. Format validé par le domaine (parseLastName, ADR-029).
  @IsOptional()
  @IsString()
  @MaxLength(64)
  lastName?: string;

  // YYYY-MM-DD, obligatoire ; date réelle et âge minimum validés par le domaine (parseBirthday, ADR-029).
  @IsString()
  @MaxLength(10)
  birthday: string;

  @IsOptional()
  @IsString()
  @MaxLength(32)
  phoneNumber?: string;
}

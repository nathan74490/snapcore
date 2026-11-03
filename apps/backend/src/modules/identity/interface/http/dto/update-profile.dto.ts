import { IsOptional, IsString, MaxLength, ValidateIf } from "class-validator";

export class UpdateProfileDto {
  @IsOptional()
  @IsString()
  @MaxLength(64)
  username?: string;

  // Le prénom ne peut pas être retiré (pas de null), seulement modifié.
  @IsOptional()
  @IsString()
  @MaxLength(64)
  firstName?: string;

  // Chaîne vide = retirer le nom de famille (ADR-029).
  @IsOptional()
  @IsString()
  @MaxLength(64)
  lastName?: string;

  // null retire le numéro ; absent le laisse inchangé.
  @ValidateIf((_dto, value) => value !== null && value !== undefined)
  @IsString()
  @MaxLength(32)
  phoneNumber?: string | null;
}

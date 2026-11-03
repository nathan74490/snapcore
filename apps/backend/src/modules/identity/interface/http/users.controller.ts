import { Controller, Get, Query } from "@nestjs/common";
import type { UsernameAvailability } from "@snapcore/types";
import { IsString, MaxLength } from "class-validator";
import { CheckUsernameAvailability } from "../../application/use-cases/check-username-availability";
import { Public } from "./auth.decorators";

class UsernameAvailabilityQuery {
  @IsString()
  @MaxLength(64)
  username: string;
}

@Controller("users")
export class UsersController {
  constructor(private readonly checkUsernameAvailability: CheckUsernameAvailability) {}

  // Publique : l'app l'appelle pendant l'inscription, avant la création du compte Firebase (ADR-029).
  @Get("username-availability")
  @Public()
  usernameAvailability(@Query() query: UsernameAvailabilityQuery): Promise<UsernameAvailability> {
    return this.checkUsernameAvailability.execute(query.username);
  }
}

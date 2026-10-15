import { Body, Controller, HttpCode, Post } from "@nestjs/common";
import type { User } from "@snapcore/types";
import { RegisterUser } from "../../application/use-cases/register-user";
import { IdentityOnly, IdToken } from "./auth.decorators";
import { RegisterDto } from "./dto/register.dto";
import { presentSelf } from "./user.presenter";

@Controller("auth")
export class AuthController {
  constructor(private readonly registerUser: RegisterUser) {}

  // Appelé une fois par l'app juste après la création du compte Firebase, et rejoué
  // au lancement suivant si la réponse n'a pas été reçue (idempotent, ADR-015).
  @Post("register")
  @HttpCode(200)
  @IdentityOnly()
  async register(@IdToken() idToken: string, @Body() body: RegisterDto): Promise<User> {
    const user = await this.registerUser.execute({ idToken, ...body });
    return presentSelf(user);
  }
}

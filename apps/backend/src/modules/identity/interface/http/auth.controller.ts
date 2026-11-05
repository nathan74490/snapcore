import { Body, Controller, HttpCode, Post } from "@nestjs/common";
import type { User } from "@snapcore/types";
import { PhoneSignIn, type PhoneCodeSent } from "../../application/use-cases/phone-sign-in";
import { RegisterUser } from "../../application/use-cases/register-user";
import { IdentityOnly, IdToken, Public } from "./auth.decorators";
import { SendPhoneCodeDto, VerifyPhoneCodeDto } from "./dto/phone-sign-in.dto";
import { RegisterDto } from "./dto/register.dto";
import { presentSelf } from "./user.presenter";

@Controller("auth")
export class AuthController {
  constructor(
    private readonly registerUser: RegisterUser,
    private readonly phoneSignIn: PhoneSignIn,
  ) {}

  // Connexion et inscription par code SMS (ADR-034) : publiques, c'est le code qui prouve le numéro.
  @Post("phone/code")
  @HttpCode(200)
  @Public()
  sendPhoneCode(@Body() body: SendPhoneCodeDto): Promise<PhoneCodeSent> {
    return this.phoneSignIn.sendCode(body.phoneNumber);
  }

  @Post("phone/verify")
  @HttpCode(200)
  @Public()
  verifyPhoneCode(@Body() body: VerifyPhoneCodeDto): Promise<{ customToken: string }> {
    return this.phoneSignIn.verifyCode(body.phoneNumber, body.code);
  }

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

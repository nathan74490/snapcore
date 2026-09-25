import { Body, Controller, Delete, HttpCode, Param, Post } from "@nestjs/common";
import { IsString, MaxLength } from "class-validator";
import type { UserAccount } from "../../identity/domain/user";
import { CurrentUser } from "../../identity/interface/http/auth.decorators";
import { DeviceTokenUseCases } from "../application/device-tokens";

class RegisterDeviceDto {
  @IsString()
  @MaxLength(4096)
  token: string;
}

@Controller("devices")
export class DevicesController {
  constructor(private readonly devices: DeviceTokenUseCases) {}

  // À appeler au démarrage de l'app et à chaque onNewToken du SDK Firebase (ADR-018).
  @Post()
  @HttpCode(204)
  register(@CurrentUser() me: UserAccount, @Body() body: RegisterDeviceDto): Promise<void> {
    return this.devices.register(me, body.token);
  }

  // À appeler au logout, avant la déconnexion Firebase (ADR-018).
  @Delete(":token")
  @HttpCode(204)
  unregister(@CurrentUser() me: UserAccount, @Param("token") token: string): Promise<void> {
    return this.devices.unregister(me, token);
  }
}

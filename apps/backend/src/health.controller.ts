import { Controller, Get } from "@nestjs/common";
import { Public } from "./modules/identity/interface/http/auth.decorators";

@Controller("health")
export class HealthController {
  @Get()
  @Public()
  check(): { status: "ok" } {
    return { status: "ok" };
  }
}

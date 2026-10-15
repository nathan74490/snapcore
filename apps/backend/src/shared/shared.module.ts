import { Global, Module } from "@nestjs/common";
import { CLOCK, systemClock } from "./domain/clock";
import { PrismaModule } from "./infrastructure/prisma/prisma.module";

@Global()
@Module({
  imports: [PrismaModule],
  providers: [{ provide: CLOCK, useValue: systemClock }],
  exports: [CLOCK],
})
export class SharedModule {}

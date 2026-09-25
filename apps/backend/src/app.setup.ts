import { INestApplication, ValidationPipe } from "@nestjs/common";
import { DomainExceptionFilter } from "./shared/interface/http/domain-exception.filter";

// Configuration HTTP commune à main.ts et aux tests d'intégration.
export function configureApp(app: INestApplication): INestApplication {
  app.useGlobalPipes(new ValidationPipe({ whitelist: true, forbidNonWhitelisted: true, transform: true }));
  app.useGlobalFilters(new DomainExceptionFilter());
  app.enableShutdownHooks();
  return app;
}

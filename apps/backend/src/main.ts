import "reflect-metadata";
import { NestFactory } from "@nestjs/core";
import { AppModule } from "./app.module";
import { configureApp } from "./app.setup";
import { RedisIoAdapter } from "./shared/infrastructure/redis/redis-io.adapter";

async function bootstrap(): Promise<void> {
  const app = configureApp(await NestFactory.create(AppModule));
  // Socket.io multi-instance via pub/sub Redis (ADR-006).
  const ioAdapter = new RedisIoAdapter(app);
  await ioAdapter.connect(process.env.REDIS_URL ?? "redis://localhost:6379");
  app.useWebSocketAdapter(ioAdapter);
  await app.listen(Number(process.env.PORT ?? 3000));
}

void bootstrap();

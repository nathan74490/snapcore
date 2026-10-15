import { IoAdapter } from "@nestjs/platform-socket.io";
import { createAdapter } from "@socket.io/redis-adapter";
import Redis from "ioredis";
import type { Server, ServerOptions } from "socket.io";

// Socket.io + pub/sub Redis (ADR-006) : un événement émis vers la room d'un utilisateur sur
// une instance atteint ses connexions ouvertes sur n'importe quelle autre instance.
export class RedisIoAdapter extends IoAdapter {
  private pub?: Redis;
  private sub?: Redis;

  async connect(redisUrl: string): Promise<void> {
    this.pub = new Redis(redisUrl);
    this.sub = this.pub.duplicate();
    await Promise.all([
      new Promise((resolve) => this.pub!.once("ready", resolve)),
      new Promise((resolve) => this.sub!.once("ready", resolve)),
    ]);
  }

  createIOServer(port: number, options?: ServerOptions): Server {
    const server: Server = super.createIOServer(port, options);
    if (this.pub && this.sub) server.adapter(createAdapter(this.pub, this.sub));
    return server;
  }

  async close(): Promise<void> {
    await Promise.allSettled([this.pub?.quit(), this.sub?.quit()]);
  }
}

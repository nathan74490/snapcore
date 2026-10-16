import type { NextConfig } from "next";

const config: NextConfig = {
  // Types partagés en TypeScript source (packages/types) : compilés par Next.
  transpilePackages: ["@snapcore/types"],
  poweredByHeader: false,
  // Pas de fichiers AGENTS.md / CLAUDE.md générés au démarrage : les conventions du projet sont dans docs/adr.
  agentRules: false,
};

export default config;

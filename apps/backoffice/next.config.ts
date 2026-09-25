import type { NextConfig } from "next";

const config: NextConfig = {
  // Types partagés en TypeScript source (packages/types) : compilés par Next.
  transpilePackages: ["@snapcore/types"],
  poweredByHeader: false,
};

export default config;

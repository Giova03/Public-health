import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // ⚠️ Pas de `output: "standalone"` : Vercel gère lui-même le bundle serveur.
  // Cette option cassait le déploiement (ENOENT .next/next-server.js.nft.json).
  typescript: {
    ignoreBuildErrors: true,
  },
  reactStrictMode: false,
};

export default nextConfig;

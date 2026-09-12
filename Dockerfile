# syntax=docker/dockerfile:1
# ============================================================================
# PUBLIC HEALTH — image Docker du frontend PWA (Next.js 16)
# ----------------------------------------------------------------------------
# Remplace le déploiement Vercel (3 échecs ENOENT) par un service Render en
# runtime Docker. Ce service Render construit depuis la RACINE du monorepo
# (chemin par défaut /Dockerfile) : ce fichier s'y trouve volontairement.
# Seule la partie frontend/ est construite et servie — elle embarque l'adaptateur
# de démonstration /api/v1 (fidèle au contrat backend, cf. audit).
# L'API Spring Boot reste déployable via render.yaml (blueprint, rootDir backend).
# ============================================================================

# ---------- Étape 1 : construction ----------
FROM node:22-alpine AS build
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1

# Dépendances d'abord (couche cachée tant que package*.json inchangés).
# npm ci = reproduction exacte du lockfile (même garantie que la CI GitHub).
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

# Sources puis build de production (type-check déjà couvert par la CI,
# ignoreBuildErrors est positionné dans next.config.ts).
COPY frontend/ ./
RUN npm run build

# ---------- Étape 2 : exécution ----------
FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1

# Application complète : node_modules + .next + public + configs
# (pas de output: standalone — retiré pour Vercel, cf. next.config.ts).
COPY --from=build /app ./

# Processus non root (durcissement, aligné sur backend/Dockerfile).
USER node

# Render injecte la variable PORT (défaut 10000) et attend le conteneur
# sur ce port : `next start` écoute process.env.PORT par défaut.
EXPOSE 10000
CMD ["npm", "start"]

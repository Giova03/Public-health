# PUBLIC HEALTH — Architecture séparée Frontend / Backend

> Version 2.0 — septembre 2026. Ce document fait foi pour l'organisation du
> code. Il supersede le modèle « monorepo workspace » du Sprint 0 (ADR-011
> amended).

## 1. Principe directeur

La plateforme PUBLIC HEALTH est composée de **deux applications
indépendantes**, déployées séparément, ne partageant **aucun code** —
seulement un **contrat d'API** (REST/JSON, `ProblemDetail` RFC 9457) :

```
┌──────────────────────────────────┐         ┌──────────────────────────────────┐
│  FRONTEND (dossier frontend/)    │         │  BACKEND (dossier backend/)      │
│                                  │  HTTPS  │                                  │
│  PWA Next.js 16 + TypeScript     │◄───────►│  API Spring Boot 3 (Java 21)     │
│  Tailwind 4 · shadcn/ui · Lucide │  JSON   │  15 modules hexagonaux           │
│  IndexedDB (outbox + miroir)     │         │  Flyway · PostgreSQL 16          │
│                                  │         │  Keycloak · FedaPay · PH HUB     │
│  Déploiement : VERCEL            │         │  Déploiement : RENDER            │
└──────────────────────────────────┘         └──────────────────────────────────┘
        │                                            │
        │  offline-first : le front fonctionne        │
        │  sans réseau (outbox IndexedDB,            │
        │  delta curseur à la reconnexion)           │
        └────────────────────────────────────────────┘
```

**Pourquoi cette séparation**
- **Cycle de vie distinct** : le front itère vite (design, UX, wireframes) ;
  le backend avance au rythme des contrats réglementaires et des tests
  d'intégration (229 tests verts sur PostgreSQL réel).
- **Déploiements indépendants** : Vercel (CDN edge, previews par PR) pour le
  front ; Render (blueprint staging, secret `FEDAPAY_WEBHOOK_SECRET`) pour
  l'API. Aucun couplage de release.
- **Équipes séparables** : un designer front ne touche jamais Java ; un
  développeur backend ne touche jamais React.
- **Sécurité** : le front ne contient **aucune donnée de santé en dur** — le
  miroir IndexedDB local est le seul état persistant côté agent de santé.

## 2. Emplacement du code (ce dépôt)

| Brique       | Dossier      | CI                          | Déploiement |
| ------------ | ------------ | --------------------------- | ----------- |
| **Frontend** | `frontend/`  | lint + build Next.js        | Vercel (`frontend/vercel.json`) |
| **Backend**  | `backend/`   | `mvn verify` + Testcontainers | Render (`render.yaml` à la racine, `rootDir: backend`) |

La racine ne contient que la documentation transverse
(`README.md`, `ARCHITECTURE.md`), la CI et le blueprint Render.

## 3. Le contrat — unique point de couplage

Le frontend ne connaît du backend **que** les routes `/api/v1/*` décrites
dans `frontend/src/lib/api-client.ts`. Un adaptateur de démonstration
(`frontend/src/app/api/v1/*`) simule fidèlement ces contrats (mêmes
ProblemDetail, même 409 doublons, même idempotence) pour le développement
sans backend.

| Épique | Routes | Contrats clés |
| ------ | ------ | ------------- |
| E1 Identité & MPI | `GET/POST /patients`, `GET /patients/{id}` | `409` + `candidates` (doublons = décision humaine), `410` + `masterId` (fusion) |
| E2 Sync offline | `POST /sync`, `GET /sync/delta?cursor=` | acks idempotents par `opId` (UUID v7), delta séquencé |
| E3 Ordonnances | `POST /prescriptions`, `POST /prescriptions/{id}` | dispensation partielle plafonnée, contre-entrée append-only |
| E4 Paiements | `POST /payments`, `POST /payments/{id}` | 8 états forward-only (rétrogradation = `409`), réconciliation |
| E6 Back-office | `GET /backoffice` | utilisateurs, structures, stats |

**Bascule démo → réel** : une seule variable d'environnement.

```bash
# frontend/.env.local (développement / démo) — vide = routes simulées
NEXT_PUBLIC_API_BASE_URL=

# frontend/.env.production — API Spring Boot réelle
NEXT_PUBLIC_API_BASE_URL=https://ph-api.onrender.com
```

Aucune vue ni composant n'est à modifier lors du re-câblage. En production,
le backend doit autoriser l'origine du front (variable `APP_ORIGINES`).

## 4. Règles d'or (inchangées, rappel)

1. Aucun JOIN ni FK entre schémas PostgreSQL des modules backend.
2. Les modules backend ne communiquent que par interfaces Java.
3. Le front est **offline-first honnête** : toute action fonctionne sans
   réseau (outbox) ; l'UI affiche toujours l'état réel de synchronisation.
4. Le `409` doublons n'est pas une erreur : c'est un **contrat UX** qui rend
   la décision à l'humain (ADR-003).
5. Les données de santé ne transitent jamais par des analytics
   (OWASP ASVS L2 visé).

## 5. Journal de la séparation

- [x] Restructuration : deux dossiers principaux `frontend/` + `backend/`
      (suppression de l'ancien squelette `apps/pwa`, du workspace racine et
      de `services/`).
- [x] CI adaptée : job `backend` (mvn verify, Testcontainers) + job
      `frontend` (npm ci + lint + build).
- [x] `render.yaml` : `rootDir: backend` ; `docker-compose` déplacé dans
      `backend/`.
- [x] `api-client.ts` basculable via `NEXT_PUBLIC_API_BASE_URL`.
- [ ] Preview Vercel (branche `main`) ↔ staging Render (secrets GitHub).

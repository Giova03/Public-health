# PUBLIC HEALTH — Burkina Faso

Plateforme nationale d'interopérabilité et de services de santé.
**Trois piliers : ID** (identité patient), **HUB** (interopérabilité),
**DATA** (données au service du soin). Offline-first non négociable,
FHIR R4 en façade, le patient d'abord.

> **Architecture v2 — deux applications séparées.** Ce dépôt contient
> exactement **deux dossiers principaux** : `frontend/` (PWA Next.js,
> déployée sur Vercel) et `backend/` (API Spring Boot, déployée sur Render).
> Ils ne partagent **aucun code** — uniquement le contrat REST `/api/v1`
> (voir [ARCHITECTURE.md](ARCHITECTURE.md)).

```
Public-health/
├── frontend/                  # PWA Next.js 16 + TypeScript strict
│                              #   Tailwind 4 · shadcn/ui · Lucide · framer-motion
│                              #   Offline-first : IndexedDB (outbox/mirror/meta)
│                              #   Design system v0.4 « Blue-Green Medical »
│                              #   7 modules : dashboard, patients (E1), consultation,
│                              #   ordonnances (E3), paiements (E4), sync (E2), back-office (E6)
│                              #   Adaptateur de démo intégré (contrats /api/v1 simulés)
│                              #   Déploiement : VERCEL (frontend/vercel.json)
└── backend/                   # API Java 21 LTS + Spring Boot 3
                               #   Monolithe modulaire 15 modules (ADR-001)
                               #   Flyway, PostgreSQL 16, paiements 8 états (ADR-006),
                               #   audit chaîné, RLS, Keycloak, FedaPay, connecteur PH HUB,
                               #   observabilité Prometheus + SLO (E8)
                               #   Déploiement : RENDER (render.yaml à la racine, rootDir backend)
```

## Démarrage rapide

### Prérequis

- JDK 21 (ou `docker compose` qui porte tout), Maven 3.9+
- Node 20.9+ et npm

### Backend (`backend/`)

```bash
# Base PostgreSQL 16 + API en un commande :
docker compose -f backend/docker-compose.dev.yml up --build

# ou, avec une base déjà lancée :
cd backend
mvn -B -ntp spring-boot:run
```

- API : `http://localhost:8080` · Santé : `GET /actuator/health`
- Carte des modules : `GET /api/v1/meta` (15 modules)

### Frontend (`frontend/`)

```bash
cd frontend
npm install
npm run dev        # http://localhost:3000 (démo autonome, sans backend)
```

La bascule vers l'API réelle se fait par `NEXT_PUBLIC_API_BASE_URL`
(voir `frontend/.env.example`) — aucune modification de code.

### Tests

```bash
cd backend && mvn -B -ntp verify    # 229 tests (unitaires + intégration + FHIR)
cd frontend && npm run lint          # ESLint 0 erreur
```

Les tests d'intégration utilisent **Testcontainers** quand Docker est
présent (CI) et basculent sur un **PostgreSQL embarqué zonky** sinon.

## Intégration continue

`.github/workflows/ci.yml` : deux jobs sur chaque push/PR —

| Job | Contenu |
|-----|---------|
| `backend` | `mvn verify` (JDK 21, cache Maven, Testcontainers) + artefacts |
| `frontend` | `npm ci` + ESLint + build Next.js |

Dependabot surveille npm, Maven et les actions GitHub.

## Déploiement (staging)

- **Backend** : Render (blueprint `render.yaml`, runtime Docker,
  healthcheck `/actuator/health`). Renseigner `FEDAPAY_WEBHOOK_SECRET`.
- **Frontend** : Vercel — projet pointé sur `frontend/` (Root Directory),
  `vercel.json` fourni (en-têtes service worker + manifeste).
- **Base** : Supabase PostgreSQL (région UE — Francbourg, D4), ou la base
  du blueprint Render pour le staging.

## Posture de sécurité

En-têtes stricts, deny-all par défaut (seuls health + /api/v1 ouverts),
webhooks HMAC en temps constant, RLS, audit append-only chaîné, JWT +
RBAC × ABAC, Bucket4j (ADR-011 : pas de CDN). Le frontend ne contient
aucune donnée de santé en dur — le miroir IndexedDB local est le seul
état persistant côté agent. **Données de santé : jamais dans les
analytics** (OWASP ASVS L2 visé).

## Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md) — séparation frontend/backend, contrat
  `/api/v1`, bascule démo → réel.
- [backend/docs/adr/](backend/docs/adr/) — ADR-001 → ADR-012 (ACCEPTÉS).
- [backend/docs/backlog-p0.md](backend/docs/backlog-p0.md) — épiques E1 → E8.
- [backend/docs/hypotheses.md](backend/docs/hypotheses.md) — hypothèses +
  drapeaux D1/D4.

## Licence des ressources

- Icônes de marques : [simple-icons](https://simpleicons.org), **CC0-1.0**
  (SVG livrés dans `frontend/public/brands/`).
- Icônes d'interface : [Lucide](https://lucide.dev) (ISC).
- Code du projet : propriété du porteur — licence à définir avant toute
  ouverture.

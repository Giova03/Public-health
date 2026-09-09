# PUBLIC HEALTH — Burkina Faso

Plateforme nationale d'interopérabilité et de services de santé.
**Trois piliers : ID** (identité patient), **HUB** (interopérabilité),
**DATA** (données au service du soin). Offline-first non négociable,
FHIR R4 en façade, le patient d'abord.

> Sprint 0 livré : socle opérationnel complet (monorepo, 15 modules,
> migrations Flyway V1→V5, tranche verticale paiements durcie, CI
> GitHub Actions, PWA offline-first). Voir
> [docs/backlog-p0.md](docs/backlog-p0.md) pour la suite.

## Structure du monorepo

```
public-health/
├── apps/
│   └── pwa/                  # Next.js 16 + TypeScript + Tailwind 4 + shadcn/ui
│                             # PWA offline-first : manifeste, service worker,
│                             # IndexedDB trois zones (outbox / mirror / meta)
├── services/
│   └── api/                  # Java 21 LTS + Spring Boot 3
│                             # Monolithe modulaire 15 modules (ADR-001)
│                             # Flyway V1→V5, paiements 8 états (ADR-006),
│                             # audit six dimensions chaîné, RLS (V5)
├── docs/
│   ├── adr/                  # ADR-001 → ADR-012 (statut ACCEPTÉ)
│   ├── backlog-p0.md         # 8 épiques + DoD + 10 scénarios critiques
│   └── hypotheses.md         # Hypothèses à valider + drapeaux D1/D4
├── scripts/
│   └── push-to-github.sh     # Publication sécurisée (PAT en var d'env)
├── .github/workflows/        # CI : api (mvn verify + Testcontainers) + pwa (lint + build)
├── docker-compose.dev.yml    # Base PostgreSQL + API en local
└── render.yaml               # Déploiement staging (blueprint Render)
```

## Démarrage rapide

### Prérequis

- JDK 21 (ou `docker compose` qui porte tout)
- Maven 3.9+ (`sdk install maven` / gestionnaire de paquets)
- Node 20.9+ et npm

### API (services/api)

```bash
# Base de données locale (PostgreSQL 16) + API, tout en un :
docker compose -f docker-compose.dev.yml up --build

# ou, avec une base déjà lancée :
cd services/api
cp .env.example .env          # ajuster si besoin
mvn -B -ntp spring-boot:run
```

- API : `http://localhost:8080`
- Santé : `GET /actuator/health`
- Carte des modules : `GET /api/v1/meta` (15 modules déclarés)

### PWA (apps/pwa)

```bash
npm install                    # à la racine du monorepo (workspaces)
npm run dev                    # http://localhost:3000
```

### Tests

```bash
npm run api:test               # mvn verify : 12 unitaires + 8 intégration
```

Les tests d'intégration (`@Tag("integration")`) utilisent
**Testcontainers** quand Docker est présent (CI) et basculent sur un
**PostgreSQL embarqué zonky** sinon (poste local sans Docker). Ils
prouvent, entre autres : l'initiation idempotente, les webhooks HMAC
signés, la déduplication par eventId, le refus des rétrogradations
(409), la garde append-only SQL, l'unicité des identifiants nationaux,
le chaînage du journal d'audit et la conformité FHIR R4 (validateur
HAPI, contrôle négatif inclus).

## Variables d'environnement

Voir `services/api/.env.example` et `apps/pwa/.env.example`.
**Aucun secret ne se versionne** — `.env` est ignoré par git, les
secrets de production vont dans les environnements Vercel/Render.

## Intégration continue

`.github/workflows/ci.yml` : deux jobs sur chaque push/PR —

| Job | Contenu |
|-----|---------|
| `api` | `mvn verify` (JDK 21, cache Maven, Testcontainers) + artefacts de rapports |
| `pwa` | `npm ci` + ESLint + build Next.js |

Dependabot surveille npm, Maven et les actions GitHub.

## Déploiement (staging)

- **API** : Render (blueprint `render.yaml`, runtime Docker,
  healthcheck `/actuator/health`). Renseigner `FEDAPAY_WEBHOOK_SECRET`.
- **PWA** : Vercel — projet pointé sur `apps/pwa` (Root Directory),
  `vercel.json` fourni (en-têtes service worker + manifeste).
- **Base** : Supabase PostgreSQL (région UE — Francfort, D4), ou la base
  du blueprint Render pour le staging.
- **Sentry / PostHog** : branchés en E8, données non sensibles uniquement.

## Posture de sécurité (Sprint 0, explicite)

La défense en profondeur sans CDN (ADR-011) pose déjà : en-têtes
stricts, deny-all par défaut (seuls health + /api/v1 sont ouverts),
webhooks HMAC en temps constant, RLS lue sur les tables sensibles,
audit append-only chaîné. Les épiques E5/E6 verrouillent : JWT Supabase,
RBAC × ABAC, Bucket4j, RLS INSERT/UPDATE intégrale. Voir
`SecurityConfig` pour la liste exacte de ce qui est ouvert aujourd'hui.

## Publication du dépôt

```bash
GH_TOKEN=ghp_xxx scripts/push-to-github.sh
```

Le jeton passe **uniquement** par la variable d'environnement : jamais
en argument, jamais en fichier, jamais en commit. Le script crée le
dépôt privé s'il n'existe pas, pousse `main` + le tag `sprint-0`,
retire le jeton de la configuration locale et rappelle de le faire
tourner (rotation) après usage.

## Licence des ressources

- Icônes de marques : [simple-icons](https://simpleicons.org), **CC0-1.0**
  (dépôt cloné en local, SVG livrés dans `apps/pwa/public/brands/`).
- Icônes d'interface : [Lucide](https://lucide.dev) (ISC).
- Code du projet : propriété du porteur du projet — licence à définir
  avant toute ouverture.

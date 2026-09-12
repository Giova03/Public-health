# PUBLIC HEALTH — Frontend (PWA)

Application clinique de la plateforme nationale de santé du Burkina Faso.
**Next.js 16 · TypeScript strict · Tailwind CSS 4 · shadcn/ui · Lucide ·
framer-motion · zustand · IndexedDB (offline-first).**

## Connexion & rôles (V14 — auth réelle)

L'application s'ouvre sur un **écran de connexion réel** (plus de « nom
libre + rôle choisi dans une liste ») :

- **Professionnel de santé** — **email + mot de passe**
  (`POST /api/v1/auth/login`, mot de passe commun démo `Demo1234!`),
  **MFA à code 6 chiffres pour le superviseur** (accès SNIS) et
  l'administrateur. Le jeton est exigé sur toutes les routes
  `/api/v1/*` (401 anonyme, 403 permission insuffisante) et le
  **RBAC est appliqué** par la matrice 6 rôles × 10 permissions
  (miroir exact de la V12 backend, `src/lib/rbac.ts`).

  | Compte démo (email) | Rôle | MFA |
  | ------------------- | ---- | --- |
  | `infirmier@demo.bf` | Infirmier/ICP | — |
  | `medecin@demo.bf` | Médecin | — |
  | `medecin2@demo.bf` | Médecin | — |
  | `pharmacien@demo.bf` | Pharmacien | — |
  | `pharmacien2@demo.bf` | Pharmacien | **oui** |
  | `caissier@demo.bf` | Agent financier (caisse) | — |
  | `superviseur@demo.bf` | Superviseur | **oui** (débloque les rapports SNIS) |
  | `admin@demo.bf` | Administrateur | **oui** |

- **Patient** — **téléphone (8 chiffres) + code SMS à usage unique**
  (OTP 4 chiffres affiché en démo). Périmètre strict : il ne voit QUE
  ses propres données (403 sinon). Comptes démo : `70123456`
  (Aïcha WÉDRAOGO), `66884422`, `74112233`.

Les défis MFA/OTP sont **sans état** (jeton « defi. » renvoyé au client
puis re-soumis) : l'émission et la vérification peuvent toucher deux
instances lambdas Vercel différentes sans casser la connexion.

La session vit dans `localStorage` (`ph.session.v2`). En production,
l'authentification HS256/JWT vit côté backend Spring Boot (BCrypt,
MFA, audit des lectures) — le contrat de routes est identique.

## Matrice des rôles et permissions (v0.6)

Le back-office expose un **onglet « Rôles et permissions »** : le miroir
exact de la matrice backend (`RolesPermissions` / migration V12,
6 rôles × 10 permissions, égalité verrouillée par `BackofficeIT`),
l'état réel d'application (route `/api/v1/admin/**` gardée par rôle, RLS
par propriété, append-only) et les **trois écarts** documentés par le
rapport d'analyse RBAC (écart matrice/RLS sur les lectures croisées,
interception par permission non câblée, périmètre structure inerte).
Données dans `src/lib/rbac.ts` — à maintenir en miroir du backend, puis à
servir par `GET /api/v1/admin/me/permissions` au re-câblage.

> Démonstration sans sécurité réelle : la simulation front n'est pas le
> périmètre de sécurité (garde JWT + RLS côté serveur uniquement).

## Modules (backlog P0)

| Module | Épique | Contenu |
| ------ | ------ | ------- |
| Tableau de bord | — | Héros dégradé signature, KPI, alertes, accès rapides |
| Patients | E1 MPI | Recherche diacritique, création, 409 doublons = contrat UX, fiche |
| Consultation | E3 | Stepper 3 étapes, lignes d'ordonnance |
| Ordonnances | E3 | Dispensation partielle plafonnée, contre-entrées append-only |
| Paiements | E4 FedaPay | 8 états forward-only, réconciliation |
| Synchronisation | E2 | Outbox IndexedDB, drain idempotent (opId), delta curseur, journal |
| Back-office | E6 | Utilisateurs, structures, rôles et permissions (matrice V12), MFA |

## Design system v0.4 « Blue-Green Medical »

Palette du porteur : **bleu médical** (actions, `primary`), **vert santé**
(succès, CTA dégradé menthe→teal), **blanc** (cartes). Rayons 20-24 px,
boutons et badges pilules, croix médicales en filigrane, badge flottant.
Tokens dans `src/app/globals.css` ; utilitaires signature :
`bg-gradient-medical`, `text-ink-medical`, `bg-plus-pattern`, `shadow-float`.

## Démarrage

```bash
npm install
npm run dev        # http://localhost:3000
```

Environnement (voir `.env.example`) :

- `NEXT_PUBLIC_API_BASE_URL` **vide** → adaptateur de démonstration intégré
  (`src/app/api/v1/*` : contrats fidèles du backend, seed Burkina) ;
- `NEXT_PUBLIC_API_BASE_URL=https://…` → API Spring Boot réelle
  (dossier `../backend`), CORS à prévoir côté API.

## Déploiement Vercel (rappel de configuration)

Le projet Vercel doit être réglé **exactement** ainsi (sinon le déploiement
échoue ou l'application démarre « cassée ») :

| Réglage | Valeur | Où |
| ------- | ------ | -- |
| **Root Directory** | `frontend` | Settings → General → Root Directory |
| Framework preset | Next.js (détecté via `frontend/vercel.json`) | automatique |
| Build Command | `npm run build` (`vercel.json`) | automatique |
| Node.js Version | 22.x | Settings → General → Node.js Version |
| `NEXT_PUBLIC_API_BASE_URL` | **VIDE / non définie** en démo | Settings → Environment Variables |

⚠️ **Démo et serverless** : l'état de démonstration (`src/lib/demo/seed.ts`)
vit en mémoire par instance lambda. Les **défis MFA/OTP sont sans état**
(jetons « defi. », robustes multi-instances) ; en revanche les données
**mutées** (patients créés, paiements…) peuvent redevenir la seed après un
redémarrage à froid — c'est la limite documentée de l'adaptateur démo ;
la persistance réelle vit dans PostgreSQL côté backend (Render).

## Architecture

Front et back sont **deux applications séparées** ne partageant que le
contrat REST `/api/v1` — voir `../ARCHITECTURE.md` à la racine du dépôt.

## Qualité

```bash
npm run lint       # ESLint (Next.js) — 0 erreur attendue
npx tsc --noEmit   # TypeScript strict — 0 erreur attendue
```

Le miroir IndexedDB est la source d'affichage unique ; toutes les actions
marchent hors ligne (outbox) ; le 409 doublons est un contrat UX, pas une
erreur. Données de santé : jamais dans les analytics.

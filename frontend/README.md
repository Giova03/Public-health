# PUBLIC HEALTH — Frontend (PWA)

Application clinique de la plateforme nationale de santé du Burkina Faso.
**Next.js 16 · TypeScript strict · Tailwind CSS 4 · shadcn/ui · Lucide ·
framer-motion · zustand · IndexedDB (offline-first).**

## Connexion & rôles (v0.5)

L'application s'ouvre sur un **écran de connexion multi-profils** :

- **Patient** — téléphone (+226) + code SMS à usage unique (OTP affiché en
  démo). Il accède au **portail patient** : ses informations (MPI), ses
  ordonnances avec l'avancement réel de la dispensation, ses paiements.
  Comptes démo : `70123456` (Aïcha WÉDRAOGO), `66884422`, `74112233`.
- **Professionnel de santé** — nom + rôle + structure. Le poste de santé
  s'ouvre avec une **navigation filtrée par rôle** (`lib/session.ts`) :

  | Rôle | Vues accessibles |
  | ---- | ---------------- |
  | Agent de saisie (MPI) | Tableau de bord, Patients, Synchronisation |
  | Infirmier | + Consultation |
  | Médecin | + Ordonnances |
  | Pharmacien | Tableau de bord, Ordonnances, Synchronisation |
  | Caissier | Tableau de bord, Patients, Paiements, Synchronisation |
  | Superviseur | Tableau de bord, Patients, Ordonnances, Paiements, Synchronisation, Back-office |
  | Administrateur | Toutes les vues + Back-office |

La session démo vit dans `localStorage` (`ph.demo.session.v1`). En
production, l'authentification est portée par le module sécurité du backend
(OIDC/Keycloak, MFA pour les rôles sensibles) — seul le point d'entrée de
session change côté front.

## Modules (backlog P0)

| Module | Épique | Contenu |
| ------ | ------ | ------- |
| Tableau de bord | — | Héros dégradé signature, KPI, alertes, accès rapides |
| Patients | E1 MPI | Recherche diacritique, création, 409 doublons = contrat UX, fiche |
| Consultation | E3 | Stepper 3 étapes, lignes d'ordonnance |
| Ordonnances | E3 | Dispensation partielle plafonnée, contre-entrées append-only |
| Paiements | E4 FedaPay | 8 états forward-only, réconciliation |
| Synchronisation | E2 | Outbox IndexedDB, drain idempotent (opId), delta curseur, journal |
| Back-office | E6 | Utilisateurs, structures, rôles, MFA |

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

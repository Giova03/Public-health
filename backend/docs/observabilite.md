# Observabilité & durcissement — épique E8

> PUBLIC HEALTH — plateforme nationale de santé du Burkina Faso.
> Document SRE de référence : SLO, sondes, métriques, alertes, sauvegarde
> 3-2-1 et exercice de restauration. Il complète `README.md` et les ADR
> (ADR-011 défense en profondeur, ADR-006 argent-qui-ne-pardonne-rien).
> Écrit par l'agent 8-c — branche `feat/e8-observabilite`.

---

## 1. Ce qui est livré dans l'API (aucune configuration requise)

| Livrable | Où | Effet |
|---|---|---|
| `micrometer-registry-prometheus` | `services/api/pom.xml` (version gérée par le parent Spring Boot, aucune version explicite) | Registre Prometheus + endpoint `/actuator/prometheus` |
| Exposition programmatique | `config/observabilite/ExpositionPrometheusEnvironnement` (EnvironmentPostProcessor, déclaré dans `META-INF/spring.factories`) | `/actuator/prometheus` exposé **sans toucher `application.yml`** : la liste d'exposition effective est lue puis enrichie de `prometheus` au bootstrap |
| Gauges métier | `config/observabilite/ObservabiliteGauges` (@Component @Scheduled 60 s, `observabilite.gauges.actif=true` par défaut) | 15 séries `ph_*` (§3) — snapshot mémoire relu au scrape, le scraping ne touche jamais la base |
| Lecture des mesures | `config/observabilite/MesuresObservabilitePostgres` | SELECT ciblés mono-schéma (`count`/`min`/`max`), jamais de `SELECT *` ; ne lève JAMAIS (table absente 42P01 → anomalie, gauge `NaN`) |
| Sondes de santé | `config/observabilite/ObservabiliteSondes` → `SondePhOutbox`, `SondePhBase` | Composants `phOutbox` et `phBase` dans `/actuator/health` |

### 1.1 Sécurité de `/actuator/prometheus` (`config/SecurityConfig`)

Les deux postures E5 sont conservées à l'identique, seule la ligne
Prometheus est ajoutée :

- **Posture Sprint 0** (`securite.jwt.actif=false`, défaut) :
  `/actuator/prometheus` en `permitAll` — *en production, le scraper
  Grafana/Prometheus présentera un jeton de service quand la posture JWT
  sera activée* (commentaire dans le code) ;
- **Posture JWT active** (`securite.jwt.actif=true`) :
  `/actuator/prometheus` **authentifié** (pas `ROLE_ADMIN` : un scraper
  n'est pas un administrateur fonctionnel — il portera un **jeton de
  service lecture**) ; sans jeton → 401 problem+json, comme le reste de
  l'API ;
- `/actuator/health/**` reste ouvert dans les deux postures (contrat
  CI/répartiteur de charge).

Preuve de non-régression : `JwtSecuriteIT` et `RateLimitIT` (E5) passent
**sans aucune modification**.

### 1.2 Ce qui marche sans `application.yml` — et ce que l'intégrateur peut faire

**Sans rien configurer**, au bootstrap :

- `management.endpoints.web.exposure.include` est lu (valeur effective :
  yml `health,info,metrics` + env + ligne de commande) puis **enrichi** de
  `prometheus` → `/actuator/prometheus` répond ;
- `management.endpoint.health.show-components=always` est posé (défaut
  code, uniquement si non défini) → `/actuator/health` montre le statut des
  composants `phOutbox`/`phBase` (les **détails** restent masqués :
  `show-details` garde sa valeur Boot par défaut — l'équipe SRE
  l'active en exploitation, voir §1.3).

Échappatoires intégrateur (toutes sans yml) :

| Objectif | Comment |
|---|---|
| Désactiver le scraping | `observabilite.prometheus.actif=false` (env/ligne de commande) |
| Exclure uniquement prometheus | `management.endpoints.web.exposure.exclude=prometheus` (l'exclusion l'emporte sur l'inclusion) |
| Garder la main sur la liste | définir `management.endpoints.web.exposure.include` — la valeur est lue puis enrichie, la volonté de l'intégrateur est conservée |
| Éteindre les gauges métier | `observabilite.gauges.actif=false` |
| Accélérer le cycle | `observabilite.gauges.frequence-ms=30000` |

**Si l'intégrateur préfère la voie yml** (valide également), la ligne
exacte à ajouter dans `services/api/src/main/resources/application.yml`
(section `management.endpoints.web.exposure`) est :

```yaml
        include: health,info,metrics,prometheus
```

(ajouter simplement `prometheus` à la liste existante ; le
post-processeur est idempotent — il détecte déjà la valeur et n'ajoute
rien). Pour les détails de santé en exploitation :

```yaml
management:
  endpoint:
    health:
      show-details: when-authorized   # ou always, derrière le VPN SRE
```

### 1.3 Tests (preuve)

- `ObservabiliteIT` (@Tag("integration"), zonky/Testcontainers comme E5) :
  scrape `/actuator/prometheus` (200, format texte, séries `ph_*`
  numériques — exposition par le post-processeur, yml intact) ;
  `/actuator/health` UP avec `phOutbox`/`phBase` ; seed d'un événement
  outbox + **refresh manuel** (`gauges.rafraichir()`) → gauge > 0 ; retard
  outbox > 3600 s → `phOutbox` DOWN → santé globale 503 (alerte « health
  DOWN ») ;
- Tests unitaires : `SondePhBaseTest` (SELECT 1, latence, DOWN si la
  connexion lève), `SondePhOutboxTest` (seuils UP/DOWN, UNKNOWN si
  JdbcTemplate/mesure lève — jamais DOWN par erreur de mesure),
  `MesuresObservabilitePostgresTest` (ne lève jamais ; 42P01 → anomalie),
  `ObservabiliteGaugesTest` (noms exacts, NaN, seed + refresh, série
  inédite), `ExpositionPrometheusEnvironnementTest` (enrichissement,
  défauts, échappatoires, idempotence).

---

## 2. SLO P0 (niveau de service opérationnel)

| # | SLO P0 | Objectif | Fenêtre | Mesure |
|---|---|---|---|---|
| SLO-1 | **Disponibilité API** | 99,5 % | mensuel | sonde synthétique `GET /actuator/health` (1 min) — uptime = 1 − (temps non-200 / total) ; le budget d'erreur mensuel ≈ 3 h 39 |
| SLO-2 | **Latence lecture patient** | P95 < 500 ms | mensuel | histogramme http_server_requests_seconds (quantile 0.95) filtré lecture patient (`GET /api/v1/patients/**`, `/fhir/Patient/**`) |
| SLO-3 | **Sortie outbox** | < 5 min | continu | `ph_sync_outbox_retard_secondes` < 300 |
| SLO-4 | **Réconciliation nocturne terminée avant 06:00** | 100 % des nuits | mensuel | `ph_reconciliation_derniere_age_secondes` mesuré à 06:00 : l'âge du dernier run fini doit être < à l'âge du cron 03:15 (~2 h 45) — et un run `finished_at` du jour existe |
| SLO-5 | **DLQ vide** | 0 message | continu | P0 = proxy « files mortes » : `ph_sync_ops{result="REJECTED"}` (uplink rejeté) et webhooks ORPHAN — *une DLQ physique arrivera avec le connecteur HUB/Kafka (E3/E9) : gauge `ph_dlq_taille` à ajouter alors* |

**RPO 24 h / RTO 4 h** : objectifs de reprise après sinistre (§5).

Chaque SLO dégradé → pageastre SRE selon la matrice d'alertes (§4).

---

## 3. Métriques métier — noms EXACTS

Publié par `ObservabiliteGauges` (gauges, cycle 60 s ; valeur `NaN` si la
mesure est indisponible — jamais d'exception, jamais de valeur trompeuse) :

| Nom scrappé (Prometheus) | Tags | Signification | Source SQL |
|---|---|---|---|
| `ph_sync_outbox_en_attente` | — | événements `sync.outbox` non publiés (`published=false`) | `count(*) … WHERE published = false` |
| `ph_sync_outbox_retard_secondes` | — | âge du plus ancien non publié | `extract(epoch from now() - min(occurred_at))` |
| `ph_sync_ops` | `result="APPLIED"\|"REJECTED"\|"CONFLICT"` | cumul des opérations d'uplink `sync.op` par résultat (CHECK V4) | `count(*) FILTER (WHERE result = …)` |
| `ph_paiements` | `statut="INITIATED"\|"PENDING"\|"AUTHORIZED"\|"SUCCEEDED"\|"FAILED"\|"CANCELLED"\|"REFUNDED"\|"RECONCILED"` | paiements `payments.payment` par état (8 états CHECK V2) | `SELECT state, count(*) … GROUP BY state` |
| `ph_identity_file_revue` | — | rapprochements `identity.identity_match` en `PENDING` (revue humaine) | `count(*) WHERE status='PENDING'` |
| `ph_reconciliation_derniere_age_secondes` | — | âge du dernier `payments.reconciliation_run` terminé (`finished_at`) | `extract(epoch from now() - max(finished_at))` |

> **Note de nommage (intégrateur)** — la demande initiale nommait ces deux
> métriques `ph_sync_ops_total` et `ph_paiements_total`. Le client Prometheus
> embarqué (Micrometer 1.13 / prometheus-metrics 1.2,
> `PrometheusNaming.sanitizeMetricName`) **réserve le suffixe `_total` aux
> compteurs et le retire automatiquement des gauges**. Comme ces séries
> sont des gauges (les paiements changent d'état : les compteurs par statut
> varient à la baisse, ce qu'un compteur Prometheus interdit), le nom
> publié et scrappé est `ph_sync_ops` / `ph_paiements`. Le nom Micrometer
> du code est volontairement IDENTIQUE au nom scrappé — une seule vérité
> pour Grafana et les alertes. Rendre ces séries monotones (compteurs)
> pour récupérer le suffixe serait SÉMANTIQUEMENT FAUX pour les paiements.

Sondes de santé (`/actuator/health`) :

| Composant | UP | DOWN | UNKNOWN |
|---|---|---|---|
| `phOutbox` | en_attente < 1000 **ET** retard < 3600 s (détail : valeurs + seuils + SLO) | au-delà des seuils | mesure indisponible (table absente 42P01, erreur SQL) — **jamais DOWN par erreur de requête** |
| `phBase` | `SELECT 1` OK, `latenceMs` + produit en détail | connexion impossible / réponse inattendue | — (c'est LE signal LB) |

Notes de conception :

- la mesure passe par une connexion dédiée, pose la GUC `app.roles='admin'`
  (vision nationale à travers la RLS V5/V10 de `payments.payment`,
  `sync.op`, `identity.identity_match`) puis la **remet à chaîne vide**
  dans un `finally` — aucune fuite de privilège vers le pool (même
  garantie que `ControleRlsDataSource` E5) ;
- aucune donnée sensible ne sort de la base : compteurs et âges uniquement
  (pas d'identifiant patient, pas de montant) ;
- cardinalité bornée par les CHECK constraints (3 + 8 séries fixes) ;
- le premier cycle part après l'instanciation des singletons (donc après
  Flyway) : pas de fenêtre NaN au démarrage, pas d'avalanche « table
  absente » sur un premier déploiement.

---

## 4. Alertes recommandées

Priorité P1 = pageastre 24/7 ; P2 = canal équipe le jour ouvré.

| Alerte | Condition (PromQL) | Priorité | Commentaire |
|---|---|---|---|
| **health DOWN** | `probe_success == 0` pendant 3 min (sonde noire `GET /actuator/health`) OU `up == 0` | P1 | le répartiteur de charge sort déjà l'instance (503 en DOWN) |
| **Outbox en retard** | `ph_sync_outbox_retard_secondes > 1800` (30 min) | P1 | SLO 5 min dépassé ×6 : le connecteur HUB (E3) ne draine plus |
| **Outbox en accumulation** | `ph_sync_outbox_en_attente > 1000` | P2 | préavis avant DOWN de `phOutbox` |
| **DLQ / uplink rejeté** | `delta(ph_sync_ops{result="REJECTED"}[24h]) > 0` (P0 : proxy DLQ) | P2 | chaque REJECTED est un bug client ou une donnée non conforme |
| **Taux 5xx** | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[10m])) / sum(rate(http_server_requests_seconds_count[10m])) > 0.01` | P1 | 1 % du trafic en erreur serveur |
| **Réconciliation manquée** | à 06:30 : pas de run fini aujourd'hui (`ph_reconciliation_derniere_age_secondes` > âge attendu du cron 03:15, soit ~9900 s) OU `max_over_time(ph_reconciliation_derniere_age_secondes[24h])` ne redescend jamais sous ~2 h 45 | P1 | la « preuve comptable » quotidienne manque (E4) |
| **File de revue MPI qui stagne** | `ph_identity_file_revue > 50` pendant 24 h | P2 | gouvernance : rapprochements en attente d'un humain |
| **Paiements non finaux** | `ph_paiements{statut=~"INITIATED\|PENDING\|AUTHORIZED"} > 100` | P2 | argent suspendu — creuser avec FedaPay |
| **Latence P95 lecture** | `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{uri=~"/api/v1/patients.*\|/fhir/Patient.*"}[5m])) by (le)) > 0.5` | P2 | SLO-2 |

Ces règles sont prêtes à coller dans `groups:` Prometheus/Grafana
Alerting — elles n'utilisent QUE des noms livrés (§3) ou des métriques
Spring Boot standard (`http_server_requests_seconds_*`, `up`).

---

## 5. Sauvegarde 3-2-1 et restauration

### 5.1 Politique

**3-2-1** : 3 copies, sur 2 supports différents, dont 1 hors site
chiffrée.

| Copie | Support | Où | Fréquence | Rétention | Chiffrement |
|---|---|---|---|---|---|
| 1 — primaire | base PostgreSQL (Render/Supabase) | infrastructure | — | — | au repos (fournisseur) |
| 2 — dump logique | stockage bloc/Object Storage (S3-compatible, même région) | zone infra | `pg_dump` quotidien 01:00 | 14 jours (7 quotidiens + 7 hebdo) | `age`/`gpg` (clé hors infra), ou SSE-KMS |
| 3 — hors site | disque dur externe / Object Storage autre région, **physiquement transporté** (archivage national) | Ouagadougou (salle d'archives) | hebdomadaire | 12 mois | `age`/`gpg` avant transport, clé conservée par le RSSI |

Complément PITR (si le fournisseur le fournit) : archivage **WAL continu**
(point-in-time recovery) — Supabase et Render le proposent nativement ;
le PITR rapproche le RPO réel de ~5 min (le SLO reste annoncé à 24 h, P0).

**Objectifs** : **RPO 24 h** (au plus 24 h de perte, pire cas, sans PITR ;
le dump part à 01:00), **RTO 4 h** (disponibilité API rétablie : restauration
+ Flyway + smoke tests).

Variantes acceptées selon l'hébergement :

- **Supabase** : snapshots quotidiens automatiques + PITR 7 jours (copie 2
  « logique » : `pg_dump --schema=public` impossible car schémas
  multiples → utiliser l'export Supabase ou `pg_dump` complet, voir
  commande ci-dessous) + téléversement hebdo chiffré de l'export (copie 3) ;
- **Render PostgreSQL** : snapshot disque quotidien (copie 2) + dump
  `pg_dump` hebdo chiffré hors site (copie 3) ;
- **on-premise CNT** : `pg_dump` quotidien + WAL archivé (`archive_command`
  → serveur de sauvegarde) + rotation bandes/disques externes (copie 3).

### 5.2 Commandes de référence (PostgreSQL)

```bash
# Sauvegarde logique complète (tous les schémas identity/clinical/payments/
# prescription/sync/audit/app), format custom compressé :
pg_dump "$DATABASE_URL" -Fc -f sauvegarde-$(date -u +%F).dump

# Chiffrement de la copie hors site (clé détenue par le RSSI) :
age -r age1q<clé-publique-du-rssi> sauvegarde-2025-06-01.dump > sauvegarde-2025-06-01.dump.age

# Restauration (dans une base VIERGE — jamais dans la base de prod) :
createdb restoration-essai
pg_restore -d restoration-essai --no-owner --no-privileges sauvegarde-2025-06-01.dump
```

Le schéma appartient à Flyway : la sauvegarde contient AUSSI la table
`flyway_schema_history` — la restauration doit se vérifier par Flyway
(dernière migration attendue) et non par un `ddl-auto`.

### 5.3 Vérification automatique — `scripts/backup-verify.sh`

Le script livré fait la **preuve de restauration** en moins de deux
minutes, sur le poste SRE (ou en CI) :

```bash
./scripts/backup-verify.sh sauvegarde-2025-06-01.dump
```

1. **préconditions** : `pg_dump`/`pg_restore`/`psql` présents, fichier
   lisible ;
2. crée une base temporaire `ph_backup_verify_$$` sur le PostgreSQL local
   (hôte/port configurable par `PH_VERIFY_HOST`/`PH_VERIFY_PORT`,
   opérateur par `PH_VERIFY_USER` — défaut `postgres` local) ;
3. restaure le dump dedans (`--no-owner --no-privileges`) ;
4. **compte les tables** (attendues : ≥ 20 — le monolithe a ~30 tables
   V1→V11) et **lit la dernière migration Flyway appliquée** (attendue :
   V11 — avertissement si la sauvegarde est plus ancienne que le code
   déployé) ;
5. vérifie les schémas métier (`identity`, `sync`, `payments`, `audit`) ;
6. nettoie la base temporaire ;
7. **exit 0** si tout est cohérent, **exit 1** sinon (avec un diagnostic
   clair) — prévu pour un cron `@daily` et un statut supervisé.

À planifier quotidiennement juste après le dump :

```cron
10 1 * * *  /opt/ph/scripts/backup-verify.sh /var/backups/ph/sauvegarde-$(date -u +\%F).dump
```

### 5.4 Exercice de restauration trimestriel (obligatoire, tracé)

Chaque trimestre, l'équipe SRE déroule le scénario complet sur un
environnement vierge — pas seulement le dump vérifié, mais le **RTO
réellement mesuré** :

1. **J-7** : planifier la fenêtre (2 h), prévenir les équipes, désigner un
   pilote et un observateur ;
2. **T0** : provisionner un PostgreSQL vierge + une instance API vierge
   (docker-compose.dev.yml de référence) ;
3. **T0** : copier la sauvegarde 3 (hors site, chiffrée) → la **déchiffrer
   sur le poste** (`age -d -i ~/.age/sre.key …`) — prouve que la clé
   fonctionne et est accessible ;
4. **T0+ε** : restaurer (`pg_restore`), puis démarrer l'API : Flyway
   valide la cohérence du schéma (aucune migration ne doit s'appliquer si
   la sauvegarde est à jour ; sinon, noter l'écart) ;
5. **fumigation** : `GET /actuator/health` → UP (sonde `phBase` vert, la
   latence est tracée) ; lecture d'un patient connu ; `GET /fhir/metadata` ;
   vérifier que les gauges `ph_*` deviennent numériques (outbox,
   paiements…) ;
6. **mesures** : RTO constaté (T_finale − T0), volume restauré, nombre de
   tables, dernière migration Flyway, âge de la sauvegarde (RPO potentiel) ;
7. **traces attendues** (à archiver 12 mois, sans données nominatives) :
   - fiche d'exercice : date, pilote, durées par étape, incidents ;
   - sortie console de `backup-verify.sh` (exit 0) ;
   - `curl /actuator/health` et un scrape `/actuator/prometheus`
     (preuves UP + gauges numériques) ;
   - journal des corrections engagées (leçons apprises) ;
8. **critère de réussite** : RTO ≤ 4 h et données ≤ 24 h — sinon action
   corrective AVANT le trimestre suivant (exercice re-planifié sous 30 j).

---

## 6. Dashboard Grafana suggéré — correspondance exacte

Panneau « Vue nationale » (rafraîchissement 30 s, seuils dans les
requêtes) :

| Panneau | Requête (noms exacts livrés) | Seuil visuel |
|---|---|---|
| Outbox en attente | `ph_sync_outbox_en_attente` | orange > 500, rouge > 1000 |
| Retard outbox (min) | `ph_sync_outbox_retard_secondes / 60` | orange > 5 min (SLO), rouge > 30 min |
| Uplink appliqué / rejeté / conflit | `ph_sync_ops` (3 séries par `result`) | `REJECTED` != 0 → badge |
| Paiements par statut | `ph_paiements` (8 séries par `statut`) | non-finaux > 100 → orange |
| File de revue MPI | `ph_identity_file_revue` | orange > 50 |
| Âge réconciliation (h) | `ph_reconciliation_derniere_age_secondes / 3600` | rouge > 2,75 h à 06:00 |
| Santé composants | statut `phOutbox`/`phBase` (source : sonde noire /actuator/health) | DOWN → rouge |
| Latence P95 lecture (ms) | `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{uri=~"/api/v1/patients.*\|/fhir/Patient.*"}[5m])) by (le)) * 1000` | rouge > 500 |
| Taux 5xx | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[10m])) / sum(rate(http_server_requests_seconds_count[10m]))` | rouge > 1 % |

Variable de dashboard : `job="public-health-api"` (job Prometheus du
scrape de `/actuator/prometheus`, intervalle 30 s).

---

## 7. Runbook (premiers réflexes)

| Symptôme | Vérifier | Action |
|---|---|---|
| `phOutbox` DOWN / retard > 30 min | état du connecteur HUB (E3), `ph_sync_outbox_en_attente` | redémarrer le drain, regarder les anomalies du composant dans `/actuator/health` (détails) |
| `phBase` DOWN | connexion pool (`hikaricp_connections_*`), latence `phBase` | vérifier le fournisseur, basculer si multi-région |
| Gauges `NaN` | anomalies du snapshot (WARN « Gauges d'exploitation partiellement indisponibles ») | migration manquante ? base inaccessible ? — la sonde ne doit JAMAIS casser l'app : c'est un signal, pas une panne applicative |
| Réconciliation manquée | job `ReconciliationNocturne` (cron 03:15), `payments.reconciliation_run` | relancer le run **manuel** (endpoint E4), analyser `reconciliation_discrepancy` |
| Scrape `/actuator/prometheus` en 401 | posture `securite.jwt.actif` | fournir le jeton de service au scraper (`Authorization: Bearer …`) |

---

*Fin du document — les écarts intégrateur connus sont listés dans le
rapport de l'agent 8-c et consolidés au worklog.*

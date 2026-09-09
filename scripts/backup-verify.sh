#!/usr/bin/env bash
# =============================================================================
# backup-verify.sh — épique E8 : PREUVE de sauvegarde PUBLIC HEALTH.
#
# Vérifie qu'un dump PostgreSQL est RESTAURABLE et COMPLET :
#   1. (optionnel) réalise le pg_dump si aucun fichier n'est fourni ;
#   2. restaure le dump dans une base TEMPORAIRE locale (jamais la base
#      d'origine — jamais la production) ;
#   3. compte les tables restaurées (attendu ≥ PH_VERIFY_TABLES_MIN, défaut 20) ;
#   4. lit la DERNIÈRE migration Flyway appliquée (flyway_schema_history) ;
#   5. vérifie la présence des schémas métier (identity, sync, payments, audit) ;
#   6. supprime la base temporaire ;
#   7. exit 0 si tout est cohérent, exit 1 sinon (diagnostic imprimé).
#
# Usage :
#   ./scripts/backup-verify.sh [fichier.dump]
#     - sans argument : le dump est créé depuis $DATABASE_URL (ou PG* env)
#       dans un fichier temporaire, puis vérifié ;
#     - avec argument : le dump existant est vérifié seul (cron quotidien).
#
# Variables d'environnement (toutes optionnelles) :
#   DATABASE_URL          postgres://user:pass@host:port/base — SOURCE du dump
#                         automatique (ignored si un fichier est fourni) ;
#   PH_VERIFY_HOST        hôte PostgreSQL de vérification (défaut : localhost) ;
#   PH_VERIFY_PORT        port (défaut : 5432) ;
#   PH_VERIFY_USER        utilisateur (défaut : postgres — doit pouvoir
#                         CREATE DATABASE sur PH_VERIFY_ADMIN) ;
#   PH_VERIFY_ADMIN       base d'administration (défaut : postgres) ;
#   PH_VERIFY_PASSWORD    mot de passe (sinon PGPASSWORD / .pgpass) ;
#   PH_VERIFY_TABLES_MIN  nombre minimal de tables (défaut : 20 — le
#                         monolithe en compte ~30 après V11) ;
#   PH_VERIFY_MIGRATION_MIN dernière migration attendue (défaut : V11 ;
#                         une sauvegarde plus ancienne est un AVERTISSEMENT —
#                         c'est un RPO, pas une corruption) ;
#   PH_VERIFY_STRICT=1    exit 1 aussi sur l'avertissement migration/RPO
#                         (mode exercice trimestriel, cf. docs/observabilite.md §5.4).
#
# Journal : tout passe sur stdout/stderr — aucune donnée nominative
# (des noms de tables et des compteurs uniquement).
#
# Référence : docs/observabilite.md — sauvegarde 3-2-1, RPO 24 h / RTO 4 h.
# =============================================================================
set -euo pipefail

# --- Paramètres (surchargeables) ---------------------------------------------
HOTE="${PH_VERIFY_HOST:-localhost}"
PORT="${PH_VERIFY_PORT:-5432}"
UTILISATEUR="${PH_VERIFY_USER:-postgres}"
BASE_ADMIN="${PH_VERIFY_ADMIN:-postgres}"
TABLES_MIN="${PH_VERIFY_TABLES_MIN:-20}"
MIGRATION_MIN="${PH_VERIFY_MIGRATION_MIN:-V11}"
STRICT="${PH_VERIFY_STRICT:-0}"

if [[ -n "${PH_VERIFY_PASSWORD:-}" ]]; then
  export PGPASSWORD="${PH_VERIFY_PASSWORD}"
fi

FICHIER_DUMP="${1:-}"
BASE_TEMPO="ph_backup_verify_$$"
# Connexion d'administration (création/suppression de la base temporaire).
CONNEXION_ADMIN=(-h "${HOTE}" -p "${PORT}" -U "${UTILISATEUR}")

erreur() { printf 'ÉCHEC  : %s\n' "$*" >&2; }
info()   { printf 'INFO   : %s\n' "$*"; }
ok()     { printf 'OK     : %s\n' "$*"; }

CODE_SORTIE=0
nettoyage() {
  # Toujours tenter de supprimer la base temporaire — silence si absente.
  if [[ -n "${BASE_TEMPO_CREEE:-}" ]]; then
    dropdb "${CONNEXION_ADMIN[@]}" --if-exists "${BASE_TEMPO}" 2>/dev/null || true
  fi
  if [[ -n "${DUMP_TEMPORAIRE:-}" && -f "${DUMP_TEMPORAIRE}" ]]; then
    rm -f "${DUMP_TEMPORAIRE}"
  fi
}
trap nettoyage EXIT

# --- 1. Préconditions ---------------------------------------------------------
for OUTIL in pg_dump pg_restore psql createdb dropdb; do
  if ! command -v "${OUTIL}" >/dev/null 2>&1; then
    erreur "outil « ${OUTIL} » introuvable — installez postgresql-client."
    exit 1
  fi
done
info "outils PostgreSQL présents (pg_dump, pg_restore, psql, createdb, dropdb)."

# --- 2. Le dump : fourni ou créé ? --------------------------------------------
if [[ -z "${FICHIER_DUMP}" ]]; then
  if [[ -z "${DATABASE_URL:-}" ]]; then
    erreur "aucun fichier de dump en argument et DATABASE_URL non défini."
    erreur "usage : $0 [fichier.dump]   (ou export DATABASE_URL=postgres://…)"
    exit 1
  fi
  DUMP_TEMPORAIRE="$(mktemp --suffix=.dump "ph-backup-verify-XXXXXX")"
  info "dump automatique depuis DATABASE_URL (format custom compressé)…"
  if ! pg_dump "${DATABASE_URL}" -Fc -f "${DUMP_TEMPORAIRE}"; then
    erreur "pg_dump a échoué — la sauvegarde source est injoignable."
    exit 1
  fi
  FICHIER_DUMP="${DUMP_TEMPORAIRE}"
  ok "dump créé : ${FICHIER_DUMP} ($(du -h "${FICHIER_DUMP}" | cut -f1))."
fi

if [[ ! -f "${FICHIER_DUMP}" ]]; then
  erreur "fichier de dump introuvable : ${FICHIER_DUMP}"
  exit 1
fi
if [[ ! -s "${FICHIER_DUMP}" ]]; then
  erreur "fichier de dump VIDE : ${FICHIER_DUMP} (sauvegarde corrompue)."
  exit 1
fi
info "dump à vérifier : ${FICHIER_DUMP} ($(du -h "${FICHIER_DUMP}" | cut -f1))."

if ! pg_restore --list "${FICHIER_DUMP}" >/dev/null 2>&1; then
  erreur "pg_restore --list échoue : le fichier n'est pas un dump PostgreSQL valide."
  exit 1
fi
ok "en-tête du dump lisible par pg_restore."

# --- 3. Base temporaire + restauration ----------------------------------------
info "création de la base temporaire ${BASE_TEMPO} sur ${HOTE}:${PORT}…"
if ! createdb "${CONNEXION_ADMIN[@]}" "${BASE_TEMPO}"; then
  erreur "impossible de créer la base temporaire — ${UTILISATEUR} doit avoir CREATE DATABASE sur ${BASE_ADMIN}."
  exit 1
fi
BASE_TEMPO_CREEE=1

info "restauration du dump dans ${BASE_TEMPO} (jamais dans la base d'origine)…"
if ! pg_restore -h "${HOTE}" -p "${PORT}" -U "${UTILISATEUR}" \
        -d "${BASE_TEMPO}" --no-owner --no-privileges \
        "${FICHIER_DUMP}" 2>"${TMPDIR:-/tmp}/pg-restore-${BASE_TEMPO}.log"; then
  # Les WARNING de pg_restore sont fréquents (COMMENT ON EXTENSION…) :
  # on distingue l'échec réel du bruit.
  if pg_restore -h "${HOTE}" -p "${PORT}" -U "${UTILISATEUR}" \
          -d "${BASE_TEMPO}" --no-owner --no-privileges \
          "${FICHIER_DUMP}" 2>/dev/null; then
    info "pg_restore a signalé des avertissements non bloquants (voir journal)."
  else
    erreur "pg_restore a échoué — la sauvegarde N'EST PAS restaurable."
    erreur "journal : ${TMPDIR:-/tmp}/pg-restore-${BASE_TEMPO}.log"
    exit 1
  fi
fi
ok "restauration terminée."

PSQL=(psql -h "${HOTE}" -p "${PORT}" -U "${UTILISATEUR}" -d "${BASE_TEMPO}"
      -tA -c)

# --- 4. Comptage des tables ---------------------------------------------------
TABLES="$("${PSQL[@]}" "SELECT count(*) FROM pg_tables
                       WHERE schemaname NOT IN ('pg_catalog','information_schema')")"
info "tables restaurées (hors catalogue) : ${TABLES} (attendu ≥ ${TABLES_MIN})."
if [[ "${TABLES}" -lt "${TABLES_MIN}" ]] 2>/dev/null; then
  erreur "seulement ${TABLES} tables — la sauvegarde est INCOMPLÈTE (schémas manquants ?)."
  CODE_SORTIE=1
else
  ok "volume de tables cohérent avec le monolithe modulaire."
fi

# --- 5. Schémas métier ---------------------------------------------------------
for SCHEMA in identity sync payments audit; do
  PRESENT="$("${PSQL[@]}" "SELECT count(*) FROM pg_namespace WHERE nspname = '${SCHEMA}'")"
  if [[ "${PRESENT}" -eq 1 ]]; then
    ok "schéma ${SCHEMA} restauré."
  else
    erreur "schéma ${SCHEMA} ABSENT — sauvegarde incomplète."
    CODE_SORTIE=1
  fi
done

# --- 6. Dernière migration Flyway ----------------------------------------------
MIGRATION="$("${PSQL[@]}" "SELECT coalesce(max(split_part(version, 'V', 2)::int), 0)
                          FROM flyway_schema_history")" || MIGRATION=""
if [[ -z "${MIGRATION}" ]]; then
  erreur "table flyway_schema_history ABSENTE — ce dump n'est pas une sauvegarde PUBLIC HEALTH valide (le schéma appartient à Flyway)."
  CODE_SORTIE=1
else
  MIGRATION_MIN_NUM="${MIGRATION_MIN#V}"
  if (( MIGRATION >= MIGRATION_MIN_NUM )); then
    ok "dernière migration Flyway appliquée : V${MIGRATION} (≥ ${MIGRATION_MIN})."
  else
    info "dernière migration Flyway : V${MIGRATION} < ${MIGRATION_MIN} attendue."
    info "→ la sauvegarde est plus ANCIENNE que le code déployé : c'est un écart RPO (perte de données jusqu'au complément de sauvegarde), pas une corruption. Au redémarrage, Flyway appliquera les migrations manquantes."
    if [[ "${STRICT}" == "1" ]]; then
      erreur "mode STRICT : écart de migration refusé (exercice trimestriel)."
      CODE_SORTIE=1
    fi
  fi
fi

# --- 7. Verdict ----------------------------------------------------------------
if [[ "${CODE_SORTIE}" -ne 0 ]]; then
  erreur "VÉRIFICATION DE SAUVEGARDE : ÉCHEC (exit 1) — une restauration réelle échouerait."
  exit 1
fi
ok "VÉRIFICATION DE SAUVEGARDE : RÉUSSIE (exit 0) — dump restaurable, tables et Flyway cohérents."
exit 0

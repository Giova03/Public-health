#!/usr/bin/env bash
# Publication du monorepo public-health vers GitHub.
#
# Sécurité du jeton :
#   - le PAT passe UNIQUEMENT par la variable d'environnement GH_TOKEN
#   - jamais en argument (visible dans `ps`), jamais écrit dans un fichier
#   - le remote configuré ne contient PAS le jeton ; l'authentification
#     passe par un credential helper éphémère en mémoire
#   - après usage : faire tourner (révoquer + recréer) le jeton sur GitHub
#
# NOTE TECHNIQUE (bash de l'environnement de développement) :
#   ne JAMAIS mettre d'apostrophe ASCII ' ni de tiret cadratin — dans le
#   mot d'erreur d'une expansion ${VAR:?...} : le parseur de ce bash sort
#   des guillemets et avale les lignes suivantes en silence (bug observé
#   et prouvé par tests). Règle d'hygiène : le message de ${VAR:?...} reste
#   en ASCII pur, sans apostrophe.
#
# Usage :
#   GH_TOKEN=ghp_xxxxxxxxxxxx bash scripts/push-to-github.sh
#   GH_TOKEN=ghp_xxxx OWNER=mon-compte REPO=public-health bash scripts/push-to-github.sh
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_DIR"

: "${GH_TOKEN:?GH_TOKEN manquant (scopes: repo) - passez un PAT GitHub via la variable GH_TOKEN}"
OWNER="${OWNER:-}"
REPO_NAME="${REPO:-public-health}"
BRANCHE="${BRANCHE:-main}"

api() {
  curl -fsSL --max-time 30 \
    -H "Authorization: Bearer ${GH_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    "$@"
}

# Propriétaire cible : argument d'environnement, sinon l'utilisateur du jeton.
if [ -z "${OWNER:-}" ]; then
  REPONSE="$(api https://api.github.com/user)" || {
    echo "ECHEC: impossible d'interroger api.github.com (jeton invalide, expire ou reseau)."
    echo "  Relancez avec OWNER=<compte> GH_TOKEN=... bash scripts/push-to-github.sh"
    exit 1
  }
  OWNER="$(printf '%s' "$REPONSE" | python3 -c 'import sys, json; print(json.load(sys.stdin).get("login", ""))')" || OWNER=""
  if [ -z "$OWNER" ]; then
    echo "ECHEC: reponse GitHub inattendue, login introuvable."
    exit 1
  fi
  echo "Compte GitHub detecte : ${OWNER}"
fi

DESCRIPTION="PUBLIC HEALTH - Plateforme nationale d'interoperabilite et de services de sante du Burkina Faso (monorepo : API Spring Boot 3 + PWA Next.js 16 offline-first)"

echo "Cible : ${OWNER}/${REPO_NAME} (dépôt privé)"

# Créer le dépôt s'il n'existe pas encore (422 = existe déjà, on continue).
api -X POST https://api.github.com/user/repos \
  -d "{\"name\":\"${REPO_NAME}\",\"private\":true,\"description\":\"${DESCRIPTION}\"}" \
  > /dev/null 2>&1 || echo "Le dépôt existe déjà (ou création refusée) — on pousse."

URL_PROPRE="https://github.com/${OWNER}/${REPO_NAME}.git"

# Remote sans jeton ; l'authentification passe par le helper éphémère.
if git remote get-url origin > /dev/null 2>&1; then
  git remote set-url origin "$URL_PROPRE"
else
  git remote add origin "$URL_PROPRE"
fi

GIT_ASKPASS_HELPER="$(mktemp)"
cat > "$GIT_ASKPASS_HELPER" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  Username*) echo "x-access-token" ;;
  Password*) echo "${GH_TOKEN}" ;;
esac
EOF
chmod +x "$GIT_ASKPASS_HELPER"
trap 'rm -f "$GIT_ASKPASS_HELPER"' EXIT

echo "Poussée de la branche ${BRANCHE} (et du tag sprint-0 s'il existe)…"
GIT_ASKPASS="$GIT_ASKPASS_HELPER" \
  git push -u "$URL_PROPRE" "${BRANCHE}"
if git rev-parse sprint-0 > /dev/null 2>&1; then
  GIT_ASKPASS="$GIT_ASKPASS_HELPER" \
    git push "$URL_PROPRE" "refs/tags/sprint-0"
fi

echo
echo "OK - Dépôt publié : https://github.com/${OWNER}/${REPO_NAME} (privé)"
echo
echo "ATTENTION REQUISE : ce jeton est apparu en clair dans une conversation."
echo "  1. GitHub > Settings > Developer settings > Personal access tokens"
echo "  2. Révoquez ce jeton et créez-en un neuf pour le prochain usage."
echo
echo "Prochaines étapes utiles :"
echo "  - vérifier GitHub Actions (workflow .github/workflows/ci.yml déjà poussé)"
echo "  - créer un environnement 'staging' et y renseigner FEDAPAY_WEBHOOK_SECRET"
echo "  - brancher Render (render.yaml) et Vercel (rootDirectory: apps/pwa)"

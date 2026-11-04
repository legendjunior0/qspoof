#!/usr/bin/env bash
set -euo pipefail

PROJECT_REF="aearzrtzrtyvuywjcwni"
CLI=(npx --yes supabase@latest)
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
  echo "Install Node.js and npm first, then rerun this script." >&2
  exit 1
fi

echo "Supabase project: $PROJECT_REF"
echo "The Supabase CLI may ask you to sign in. Use your Supabase personal access token in its prompt; do not paste it into this script."
"${CLI[@]}" --version
"${CLI[@]}" login

LINKED_REF=""
if [[ -f supabase/.temp/project-ref ]]; then
  LINKED_REF="$(<supabase/.temp/project-ref)"
fi
if [[ "$LINKED_REF" != "$PROJECT_REF" ]]; then
  "${CLI[@]}" link --project-ref "$PROJECT_REF"
fi

echo
echo "Remote/local migration history:"
"${CLI[@]}" migration list
echo
echo "Dry run: migrations the CLI proposes to apply:"
"${CLI[@]}" db push --dry-run
echo
echo "Read the dry-run list above. If it includes unexpected old migrations, press Ctrl+C and ask for migration-history reconciliation. Do not run migration repair blindly."
read -r -p "Type APPLY to apply the listed migrations and deploy the premium function: " CONFIRM
if [[ "$CONFIRM" != "APPLY" ]]; then
  echo "Stopped without changing the remote database or function."
  exit 0
fi

"${CLI[@]}" db push
"${CLI[@]}" functions deploy premium --project-ref "$PROJECT_REF"

echo
echo "Deployment commands completed. Refresh admin/premium.html and confirm the stale-deployment warning is gone and device rows show their reference IDs."

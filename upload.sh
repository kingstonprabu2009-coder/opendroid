#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ZIP="$HOME/storage/downloads/jarvis-fix-17.zip"
PROJECT="$HOME/jarvis-fix-17"
BRANCH="jarvis/home-ui-handoff3"

if [ ! -f "$ZIP" ]; then
  echo "ERROR: ZIP not found: $ZIP"
  exit 1
fi
command -v unzip >/dev/null 2>&1 || { echo "Install unzip first: pkg install unzip"; exit 1; }
command -v git >/dev/null 2>&1 || pkg install -y git
mkdir -p "$PROJECT"
unzip -oq "$ZIP" -d "$PROJECT"
cd "$PROJECT"

git rev-parse --is-inside-work-tree >/dev/null 2>&1 || { echo "ERROR: ZIP is missing its Git repository metadata."; exit 1; }
if ! git remote get-url origin >/dev/null 2>&1; then
  echo "ERROR: origin remote is missing from the bundled Git configuration."
  exit 1
fi
if git show-ref --verify --quiet "refs/heads/$BRANCH"; then
  git switch "$BRANCH"
else
  git switch -c "$BRANCH"
fi
git config user.name "JARVIS Builder"
git config user.email "jarvis-builder@users.noreply.github.com"
git add -A
if git diff --cached --quiet; then
  echo "No new changes to commit."
else
  git commit -m "JARVIS Fix 17 update"
fi
git push -u origin "$BRANCH"
echo "Upload complete: GitHub branch $BRANCH updated."
echo "APK workflow is manual; start Build APK from GitHub Actions when you want to build."

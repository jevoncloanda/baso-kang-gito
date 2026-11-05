#!/usr/bin/env bash
# Run from the checked-out repository on Cloudra. No Docker or Android operations.
set -euo pipefail
state=/srv/baso-kang-gito-release-state
test "$(readlink /srv/baso-kang-gito-downloads)" = "$state/current/public"
stage="$state/staging/upload-$(python3 -c 'import uuid; print(uuid.uuid4().hex)')"
umask 022
mkdir -- "$stage"
cp -- distribution/index.html distribution/site.css distribution/site.js \
  distribution/favicon.ico distribution/favicon-32x32.png distribution/apple-touch-icon.png "$stage/"
result=$(mktemp)
attempted=false
finish() {
  status=$?
  trap - EXIT
  if [ "$status" -ne 0 ] && [ "$attempted" = true ]; then
    echo "Website verification failed; requesting receipt-based rollback" >&2
    python3 backend/scripts/publish-cloudra.py rollback --state-root "$state" --stage "$stage" || \
      echo "Rollback needs operator review: $stage/receipt.json (a newer release is never replaced)" >&2
  fi
  rm -f -- "$result"
  exit "$status"
}
trap finish EXIT
attempted=true
python3 backend/scripts/publish-cloudra.py publish-website --state-root "$state" --stage "$stage" > "$result"
python3 backend/scripts/verify-website.py "$result" "$stage"
echo "Website verified; retained recovery stage: $stage"

#!/usr/bin/env bash
# Runs every flow in order; exits non-zero if any failed.
cd "$(dirname "$0")"
rc=0
for f in 0*_*.sh; do
  echo; echo "== $f"
  bash "$f" || rc=1
done
exit $rc

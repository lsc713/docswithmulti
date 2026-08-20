#!/usr/bin/env bash
set -euo pipefail

started=${1:?started epoch required}
ended=${2:?ended epoch required}
stage_seconds=${3:-180}
[[ "$started" =~ ^[0-9]+$ && "$ended" =~ ^[0-9]+$ && "$stage_seconds" =~ ^[1-9][0-9]*$ ]] || {
  echo 'stage windows require non-negative epochs and positive stage seconds' >&2
  exit 1
}

for ((stage=1; stage<=4; stage++)); do
  start=$((started + (stage - 1) * stage_seconds))
  end=$((started + stage * stage_seconds))
  [ "$ended" -lt "$end" ] && end=$ended
  [ "$end" -gt "$start" ] && printf '%s %s %s\n' "$stage" "$start" "$end"
done

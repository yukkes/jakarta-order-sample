#!/usr/bin/env bash
#
# Throughput and latency for the three benchmark endpoints in docs/performance.md.
#
#   /api/auth/status                   no auth, no DB  (HTTP/JSON layer)
#   /api/auth/me                       JWT only, no DB
#   /api/products?page=0&size=100      JWT + one DB query
#
# usage: scripts/bench.sh [base_url]
#   BASE_URL  default http://127.0.0.1:8080
#   RUNS      number of measured runs, median is reported (default 3)
#   DURATION  seconds per wrk run (default 10)
#   WARMUP    seconds of warm-up per endpoint (default 3)
#
# The server has to run with `order.demo-users=true` (user/user) and a freshly
# created schema, which is what the default demo configuration does on startup.
set -euo pipefail

BASE="${1:-http://127.0.0.1:8080}"
THREADS="${THREADS:-4}"
CONNECTIONS="${CONNECTIONS:-100}"
DURATION="${DURATION:-10}"
WARMUP="${WARMUP:-3}"
RUNS="${RUNS:-3}"

command -v wrk >/dev/null || { echo "wrk が見つかりません" >&2; exit 1; }

token="$(curl -sS -X POST "$BASE/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d '{"username":"user","password":"user"}' \
    | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')"
if [ -z "$token" ]; then
    echo "ログインできませんでした（order.demo-users=true で起動していますか？）" >&2
    exit 1
fi

median() { sort -n | awk '{v[NR] = $1} END {print (NR % 2) ? v[(NR + 1) / 2] : int((v[NR / 2] + v[NR / 2 + 1]) / 2)}'; }

measure() { # name path auth(yes|no)
    local name="$1" path="$2" auth="${3:-no}"
    local header=()
    if [ "$auth" = yes ]; then
        header=(-H "Authorization: Bearer $token")
    fi

    wrk -t"$THREADS" -c"$CONNECTIONS" -d"${WARMUP}s" "${header[@]}" "$BASE$path" >/dev/null

    local rps=() p99=()
    for _ in $(seq "$RUNS"); do
        local out
        out="$(wrk -t"$THREADS" -c"$CONNECTIONS" -d"${DURATION}s" --latency "${header[@]}" "$BASE$path")"
        rps+=("$(awk '/Requests\/sec/ {print int($2)}' <<<"$out")")
        p99+=("$(awk '$1 == "99%" {gsub(/ms/, "", $2); print int($2)}' <<<"$out")")
    done

    printf '%-10s rps=%-8s p99=%-6s runs=%s\n' \
        "$name" \
        "$(printf '%s\n' "${rps[@]}" | median)" \
        "$(printf '%s\n' "${p99[@]}" | median)ms" \
        "$(IFS=,; echo "${rps[*]}")"
}

measure status "/api/auth/status" no
measure me "/api/auth/me" yes
measure products "/api/products?page=0&size=100" yes

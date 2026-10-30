#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
TMP_FILE="$(mktemp)"
trap 'rm -f "$TMP_FILE"' EXIT

TOKEN="$(curl -sS -X POST "$BASE_URL/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d '{"username":"user","password":"user"}' \
    | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')"
if [ -z "$TOKEN" ]; then
    echo "NG: ログインできませんでした" >&2
    exit 1
fi

check() {
    local path="$1"
    local expected="$2"
    local status

    status="$(curl -sS -o "$TMP_FILE" -w '%{http_code}' \
        -H "Authorization: Bearer $TOKEN" "$BASE_URL$path")"
    if [ "$status" != "200" ]; then
        echo "NG: GET $path -> HTTP $status" >&2
        cat "$TMP_FILE" >&2
        exit 1
    fi
    if ! grep -q "$expected" "$TMP_FILE"; then
        echo "NG: GET $path に '$expected' が含まれていません" >&2
        cat "$TMP_FILE" >&2
        exit 1
    fi
    echo "OK: GET $path"
}

ANON_STATUS="$(curl -sS -o /dev/null -w '%{http_code}' "$BASE_URL/api/orders")"
if [ "$ANON_STATUS" != "401" ]; then
    echo "NG: 未認証の /api/orders が 401 ではありません: HTTP $ANON_STATUS" >&2
    exit 1
fi
echo "OK: unauthenticated /api/orders -> 401"

HEADERS="$(curl -sS -D - -o /dev/null "$BASE_URL/")"
check_header() { # substring, matched case-insensitively against the response headers
    if ! printf '%s' "$HEADERS" | tr 'A-Z' 'a-z' | grep -qiF -- "$1"; then
        echo "NG: セキュリティヘッダがありません: $1" >&2
        printf '%s\n' "$HEADERS" >&2
        exit 1
    fi
}
check_header "content-security-policy:"
check_header "default-src 'self'"
check_header "script-src 'self'"
check_header "style-src 'self'"
check_header "frame-ancestors 'none'"
check_header "x-content-type-options: nosniff"
check_header "referrer-policy: no-referrer"
echo "OK: security headers"

check / 'orders-sort'
check /api/products '"P001"'
check /api/customers '"C001"'
check '/api/orders?page=0&size=2&sort=id,desc' '"content"'

echo "smoke test passed: $BASE_URL"

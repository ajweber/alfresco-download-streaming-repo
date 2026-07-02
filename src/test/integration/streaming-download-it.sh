#!/usr/bin/env bash
#
# Integration test for the ADF Download Manager streaming addon, exercised
# against a REAL running ACS (the deployment/ Docker stack). Unlike the JUnit
# unit tests (pure logic, no repository), this drives the live HTTP endpoint end
# to end: full download, HEAD, Range/206, unsatisfiable/416, malformed-ignore,
# suffix range, resume (curl -C -), permission/403, not-found/404, ETag
# stability, and truncation-safe multi-GB transfer + concurrency.
#
# Prerequisites: the stack from ../deployment is up (`docker compose up -d`) and
# ACS is ready. Run:
#
#   src/test/integration/streaming-download-it.sh
#
# Options (env vars):
#   ACS_HOST=localhost:8080      ACS host:port (default)
#   ACS_USER=admin ACS_PASS=admin admin credentials (default)
#   BIG_GB=2                     also run the multi-GB test with an N-GB sparse
#                                file (default: skipped — small-file coverage)
#   KEEP=1                       keep uploaded test nodes (default: clean up)
#
# NB: credential vars are ACS_USER / ACS_PASS, NOT USER — the shell exports USER
# to your login name, which would silently override an admin default.
#
# Exits non-zero if any assertion fails.

set -u

HOST="${ACS_HOST:-localhost:8080}"
ACS_USER="${ACS_USER:-admin}"
ACS_PASS="${ACS_PASS:-admin}"
BIG_GB="${BIG_GB:-0}"
KEEP="${KEEP:-0}"

API="http://${HOST}/alfresco/api/-default-/public/alfresco/versions/1"
ADDON="http://${HOST}/alfresco/s/adf-download-manager/download"
AUTH="-u ${ACS_USER}:${ACS_PASS}"

PASS_N=0
FAIL_N=0
CREATED_NODES=()
TMPDIR="$(mktemp -d)"

# ── helpers ────────────────────────────────────────────────────────────────

log()  { printf '%s\n' "$*"; }
ok()   { PASS_N=$((PASS_N+1)); printf '  \033[32mPASS\033[0m %s\n' "$*"; }
bad()  { FAIL_N=$((FAIL_N+1)); printf '  \033[31mFAIL\033[0m %s\n' "$*"; }

cleanup() {
  if [ "$KEEP" != "1" ]; then
    for n in "${CREATED_NODES[@]:-}"; do
      [ -n "$n" ] && curl -s $AUTH -X DELETE "${API}/nodes/${n}?permanent=true" -o /dev/null
    done
  fi
  rm -rf "$TMPDIR"
}
trap cleanup EXIT

# Upload a local file to the repo root, echo the new node id.
upload() {
  local path="$1" name="$2"
  curl -s $AUTH \
    -F "filedata=@${path};type=application/octet-stream" \
    -F "name=${name}" \
    "${API}/nodes/-root-/children" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['entry']['id'])" 2>/dev/null
}

# Assert helpers: assert_status <expected> <actual> <label>
assert_status() {
  if [ "$2" = "$1" ]; then ok "$3 (status $2)"; else bad "$3 (expected $1, got $2)"; fi
}
assert_eq() {
  if [ "$2" = "$1" ]; then ok "$3"; else bad "$3 (expected '$1', got '$2')"; fi
}

require_ready() {
  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" "${API}/probes/-ready-")
  if [ "$code" != "200" ]; then
    log "ERROR: ACS not ready at ${HOST} (probe returned ${code}). Start the stack:"
    log "  cd deployment && docker compose up -d"
    exit 2
  fi
}

# ── setup ──────────────────────────────────────────────────────────────────

log "== ADF streaming-download integration test =="
log "Host: ${HOST}"
require_ready

# A small deterministic file (1 MB of ramp bytes) for the correctness cases.
SMALL="${TMPDIR}/small.bin"
python3 -c "import sys; sys.stdout.buffer.write(bytes(i & 0xFF for i in range(1048576)))" > "$SMALL"
SMALL_SIZE=$(wc -c < "$SMALL" | tr -d ' ')
NID=$(upload "$SMALL" "it-small-$(date +%s).bin")
if [ -z "$NID" ]; then log "ERROR: upload failed"; exit 2; fi
CREATED_NODES+=("$NID")
BASE="${ADDON}/${NID}"
log "Test node: ${NID} (${SMALL_SIZE} bytes)"

# ── 1. Full GET (200) + integrity vs stock API ──────────────────────────────
log "[1] Full download (200) + integrity"
code=$(curl -s $AUTH "$BASE" -o "${TMPDIR}/full.bin" -w "%{http_code}")
assert_status 200 "$code" "full GET"
curl -s $AUTH "${API}/nodes/${NID}/content" -o "${TMPDIR}/stock.bin"
if cmp -s "${TMPDIR}/full.bin" "$SMALL" && cmp -s "${TMPDIR}/full.bin" "${TMPDIR}/stock.bin"; then
  ok "addon body byte-identical to source and stock API"
else
  bad "addon body differs from source/stock"
fi

# ── 2. HEAD: metadata only, no body ─────────────────────────────────────────
log "[2] HEAD (headers only)"
curl -s $AUTH -I "$BASE" -o "${TMPDIR}/head.txt" -w "%{http_code}" > "${TMPDIR}/head.code"
assert_status 200 "$(cat "${TMPDIR}/head.code")" "HEAD"
cl=$(grep -i '^content-length:' "${TMPDIR}/head.txt" | tr -d '\r' | awk '{print $2}')
assert_eq "$SMALL_SIZE" "$cl" "HEAD Content-Length matches size"
grep -qi '^accept-ranges: bytes' "${TMPDIR}/head.txt" && ok "HEAD advertises Accept-Ranges: bytes" || bad "HEAD missing Accept-Ranges"
etag_head=$(grep -i '^etag:' "${TMPDIR}/head.txt" | tr -d '\r' | awk '{print $2}')
[ -n "$etag_head" ] && ok "HEAD returns an ETag ($etag_head)" || bad "HEAD missing ETag"

# ── 3. Range → 206 + Content-Range ──────────────────────────────────────────
log "[3] Range request (206)"
code=$(curl -s $AUTH -H "Range: bytes=0-99" "$BASE" -o "${TMPDIR}/r.bin" -D "${TMPDIR}/r.hdr" -w "%{http_code}")
assert_status 206 "$code" "ranged GET bytes=0-99"
cr=$(grep -i '^content-range:' "${TMPDIR}/r.hdr" | tr -d '\r' | awk '{print $2, $3}')
assert_eq "bytes 0-99/${SMALL_SIZE}" "$cr" "Content-Range header"
assert_eq "100" "$(wc -c < "${TMPDIR}/r.bin" | tr -d ' ')" "206 body length is 100"

# ── 4. Unsatisfiable range → 416 + Content-Range: bytes */total ─────────────
log "[4] Unsatisfiable range (416)"
code=$(curl -s $AUTH -H "Range: bytes=99999999-" "$BASE" -o /dev/null -D "${TMPDIR}/416.hdr" -w "%{http_code}")
assert_status 416 "$code" "out-of-bounds range"
cr=$(grep -i '^content-range:' "${TMPDIR}/416.hdr" | tr -d '\r' | awk '{print $2, $3}')
assert_eq "bytes */${SMALL_SIZE}" "$cr" "416 Content-Range: bytes */total"

# ── 5. Malformed / multi-range → ignored → 200 full ─────────────────────────
log "[5] Malformed & multi-range ignored (200)"
code=$(curl -s $AUTH -H "Range: bytes=500-100" "$BASE" -o /dev/null -w "%{http_code}")
assert_status 200 "$code" "reversed range ignored"
code=$(curl -s $AUTH -H "Range: bytes=0-10,20-30" "$BASE" -o /dev/null -w "%{http_code}")
assert_status 200 "$code" "multi-range ignored"
code=$(curl -s $AUTH -H "Range: items=0-10" "$BASE" -o /dev/null -w "%{http_code}")
assert_status 200 "$code" "unknown unit ignored"

# ── 6. Suffix range → last N bytes ──────────────────────────────────────────
log "[6] Suffix range (206, last bytes)"
code=$(curl -s $AUTH -H "Range: bytes=-50" "$BASE" -o "${TMPDIR}/suf.bin" -D "${TMPDIR}/suf.hdr" -w "%{http_code}")
assert_status 206 "$code" "suffix range bytes=-50"
last=$((SMALL_SIZE-50)); end=$((SMALL_SIZE-1))
cr=$(grep -i '^content-range:' "${TMPDIR}/suf.hdr" | tr -d '\r' | awk '{print $2, $3}')
assert_eq "bytes ${last}-${end}/${SMALL_SIZE}" "$cr" "suffix Content-Range"

# ── 7. Resume via curl -C - (append) → byte-identical ───────────────────────
log "[7] Interrupted + resumed download (curl -C -)"
# Grab first 400 KB, then resume the rest with -C -.
curl -s $AUTH -H "Range: bytes=0-409599" "$BASE" -o "${TMPDIR}/resume.bin"
curl -s $AUTH -C - "$BASE" -o "${TMPDIR}/resume.bin" -w "" 2>/dev/null
if cmp -s "${TMPDIR}/resume.bin" "$SMALL"; then
  ok "resumed download reassembles to byte-identical file"
else
  bad "resumed download differs (size $(wc -c < "${TMPDIR}/resume.bin" | tr -d ' '))"
fi

# ── 8. ETag stability + GET/HEAD parity ─────────────────────────────────────
log "[8] ETag stability and GET/HEAD parity"
e1=$(curl -s $AUTH -I "$BASE" | grep -i '^etag:' | tr -d '\r' | awk '{print $2}')
e2=$(curl -s $AUTH -I "$BASE" | grep -i '^etag:' | tr -d '\r' | awk '{print $2}')
eg=$(curl -s $AUTH -D - "$BASE" -o /dev/null | grep -i '^etag:' | tr -d '\r' | awk '{print $2}')
assert_eq "$e1" "$e2" "ETag stable across HEADs"
assert_eq "$e1" "$eg" "ETag identical on GET and HEAD"

# ── 9. Not found → 404 ──────────────────────────────────────────────────────
log "[9] Non-existent node (404)"
code=$(curl -s $AUTH "${ADDON}/00000000-0000-0000-0000-000000000000" -o /dev/null -w "%{http_code}")
assert_status 404 "$code" "bogus node id"

# ── 10. Permission enforced → 403 ───────────────────────────────────────────
log "[10] Permission enforcement (403)"
# Create a restricted user + a private folder (inheritance off) + a file inside.
curl -s $AUTH -X POST "${API}/people" -H "Content-Type: application/json" \
  -d '{"id":"it-restricted","firstName":"IT","password":"test1234","email":"it@example.com"}' -o /dev/null
FOLDER=$(curl -s $AUTH -X POST "${API}/nodes/-root-/children" -H "Content-Type: application/json" \
  -d '{"name":"it-private-'"$(date +%s)"'","nodeType":"cm:folder"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['entry']['id'])" 2>/dev/null)
if [ -n "$FOLDER" ]; then
  CREATED_NODES+=("$FOLDER")
  curl -s $AUTH -X PUT "${API}/nodes/${FOLDER}" -H "Content-Type: application/json" \
    -d '{"permissions":{"isInheritanceEnabled":false}}' -o /dev/null
  PRIV=$(curl -s $AUTH -F "filedata=@${SMALL};type=application/octet-stream" -F "name=secret.bin" \
    "${API}/nodes/${FOLDER}/children" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['entry']['id'])" 2>/dev/null)
  pcode=$(curl -s -u it-restricted:test1234 "${ADDON}/${PRIV}" -o /dev/null -w "%{http_code}")
  acode=$(curl -s $AUTH "${ADDON}/${PRIV}" -o /dev/null -w "%{http_code}")
  assert_status 403 "$pcode" "restricted user denied"
  assert_status 200 "$acode" "admin allowed on same node"
else
  bad "could not create private folder for permission test"
fi

# ── 11. Multi-GB truncation-safe transfer + concurrency (opt-in) ────────────
if [ "$BIG_GB" -gt 0 ] 2>/dev/null; then
  log "[11] Multi-GB (${BIG_GB} GB) transfer + concurrency"
  BIGF="${TMPDIR}/big.bin"
  # Sparse file — instant to create, streams as zeros.
  dd if=/dev/zero of="$BIGF" bs=1 count=0 seek=$((BIG_GB * 1024 * 1024 * 1024)) 2>/dev/null
  BIG_SIZE=$(wc -c < "$BIGF" | tr -d ' ')
  BNID=$(upload "$BIGF" "it-big-$(date +%s).bin")
  if [ -n "$BNID" ]; then
    CREATED_NODES+=("$BNID")
    BBASE="${ADDON}/${BNID}"
    # Full download → correct size (proves no truncation at multi-GB).
    got=$(curl -s $AUTH "$BBASE" -o /dev/null -w "%{size_download}")
    assert_eq "$BIG_SIZE" "$got" "${BIG_GB}GB full download size"
    # Tail range (last 1 MB) → O(1) seek path.
    tstart=$((BIG_SIZE - 1048576))
    code=$(curl -s $AUTH -H "Range: bytes=${tstart}-" "$BBASE" -o /dev/null -w "%{http_code}")
    assert_status 206 "$code" "${BIG_GB}GB tail range (O(1) seek)"
    # 4 concurrent downloads → all correct size (no txn/connection exhaustion).
    log "  launching 4 concurrent ${BIG_GB}GB downloads..."
    cok=0
    for i in 1 2 3 4; do
      ( curl -s $AUTH "$BBASE" -o /dev/null -w "%{size_download}" > "${TMPDIR}/c$i.txt" ) &
    done
    wait
    for i in 1 2 3 4; do [ "$(cat "${TMPDIR}/c$i.txt")" = "$BIG_SIZE" ] && cok=$((cok+1)); done
    assert_eq "4" "$cok" "4/4 concurrent ${BIG_GB}GB downloads correct size"
  else
    bad "multi-GB upload failed"
  fi
else
  log "[11] Multi-GB test skipped (set BIG_GB=2 to enable)"
fi

# ── summary ─────────────────────────────────────────────────────────────────
log ""
log "== Results: ${PASS_N} passed, ${FAIL_N} failed =="
[ "$FAIL_N" -eq 0 ]

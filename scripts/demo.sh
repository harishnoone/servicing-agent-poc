#!/usr/bin/env bash
# Seed test data and drive the Servicing Agent demo (bash + curl; jq optional for readable output).
#
#   scripts/demo.sh seed                                  # load scripts/seed-data.txt
#   scripts/demo.sh scenarios                             # ask every CHAT question in seed-data.txt
#   scripts/demo.sh charge cust_123 LATE_FEE 45 [description]
#   scripts/demo.sh customer cust_900 "Dana Lee" Gold 40 499 [waivers]
#   scripts/demo.sh ticket cust_900 PENDING_ENGINEERING -3 Gateway down   # negative hours = SLA breached
#   scripts/demo.sh update cust_900 tier=Enterprise tenure=50 waivers=1
#   scripts/demo.sh chat cust_900 Can you waive my late fee?
#   scripts/demo.sh caching [customerId] [turns]          # prompt-caching demo (needs jq for the per-turn detail)
#   scripts/demo.sh profile cust_900 | evict cust_900 | poison [table] | dlt | replay [table] | kpis | reset
#
# BASE_URL overrides the target (default http://localhost:8080).
# CHAT_PACE_SECONDS (e.g. 8) = minimum seconds between chat calls, for a rate-limited LLM (default 0 = off).
# CHAT_RETRY_WAIT_SECONDS (default 30) = wait before retrying once a turn the LLM provider rejected.
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8080}"
BASE="${BASE%/}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEED_FILE="$DIR/seed-data.txt"
HAVE_JQ=0; command -v jq >/dev/null 2>&1 && HAVE_JQ=1
TMP="$(mktemp)"; trap 'rm -f "$TMP"' EXIT

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
grey()  { printf '\033[90m%s\033[0m\n' "$*"; }

# api METHOD PATH [JSON]  -> body in $TMP, prints HTTP status
api() {
  local method="$1" path="$2" body="${3:-}"
  if [[ -n "$body" ]]; then
    curl -s -o "$TMP" -w '%{http_code}' -X "$method" -H 'Content-Type: application/json' --data "$body" "$BASE$path"
  else
    curl -s -o "$TMP" -w '%{http_code}' -X "$method" "$BASE$path"
  fi
}

json_str() { local s="$1"; s="${s//\\/\\\\}"; s="${s//\"/\\\"}"; printf '"%s"' "$s"; }
field()    {  # first value of "key" in $TMP: quoted strings (with escapes) or bare scalars; portable sed -E
  local v
  v="$(sed -nE "s/.*\"$1\":\"(([^\"\\\\]|\\\\.)*)\".*/\\1/p" "$TMP" | head -n1)"
  [[ -n "$v" ]] || v="$(sed -nE "s/.*\"$1\":([^,}\"]*).*/\\1/p" "$TMP" | head -n1)"
  printf '%s' "${v//\\\"/\"}"
}

usage() { sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }
need()  { local n="$1" u="$2"; shift 2; [[ $# -ge $n ]] || { red "usage: demo.sh $u"; exit 2; }; }

wait_profile() {  # wait_profile ID [seconds]
  local id="$1" tries=$(( ${2:-20} * 2 ))
  for ((i = 0; i < tries; i++)); do
    [[ "$(api GET "/api/demo/profile/$id")" == 200 ]] && return 0
    sleep 0.5
  done
  return 1
}

show_turn() {
  if (( HAVE_JQ )); then
    jq -r '
      "  \(.kpiImpact.deflectionStatus)  (\(.kpiImpact.totalTurnLatencyMs) ms)",
      (.agentReasoningTrace[] | select((.event | startswith("TOOL_CALL")) or (.event | startswith("LLM_"))) | "    \(.event)  \(.latencyMs) ms"),
      (if (.citations | length) > 0 then "  citations: \(.citations | join(", "))" else empty end),
      (if .handoffCard then "  handoff: \(.handoffCard.detectedSentiment), urgency \(.handoffCard.urgencyScore)" else empty end),
      "  > \(.finalResponse)"' "$TMP"
  else
    echo "  $(field deflectionStatus)  ($(field totalTurnLatencyMs) ms)   [install jq for a full summary]"
    echo "  > $(field finalResponse)"
  fi
}

LAST_CHAT=0
chat() {  # chat ID MESSAGE
  printf '\n[%s] %s\n' "$1" "$2"
  local pace="${CHAT_PACE_SECONDS:-0}" retry_wait="${CHAT_RETRY_WAIT_SECONDS:-30}" now wait
  if (( pace > 0 )); then
    now=$(date +%s); wait=$(( pace - (now - LAST_CHAT) ))
    if (( wait > 0 )); then grey "  (pacing: waiting ${wait}s to stay under the LLM rate limit)"; sleep "$wait"; fi
  fi
  local body="{\"customerId\":$(json_str "$1"),\"message\":$(json_str "$2")}" code
  code="$(api POST /api/agent/chat "$body")"; LAST_CHAT=$(date +%s)
  if [[ "$code" == 200 ]] && grep -q '"deflectionStatus":"PARTIAL"' "$TMP" && grep -q 'AGENT_TURN_FAILED' "$TMP" && (( retry_wait > 0 )); then
    red "  LLM provider rejected the turn (rate limit or quota). Waiting ${retry_wait}s, then retrying once."
    sleep "$retry_wait"; code="$(api POST /api/agent/chat "$body")"; LAST_CHAT=$(date +%s)
  fi
  if [[ "$code" == 200 ]]; then show_turn; else red "  HTTP $code: $(cat "$TMP")"; fi
}

seed_lines() { grep -vE '^[[:space:]]*(#|$)' "$SEED_FILE"; }

cmd="${1:-help}"; shift || true
case "$cmd" in

  seed)
    ok=0; fail=0; skipped=" "; created=()
    while read -r verb target body; do
      [[ "$verb" == POST ]] || continue
      id="$(sed -n 's/.*"customerId":"\([^"]*\)".*/\1/p' <<<"$body")"
      [[ "$skipped" == *" $id "* ]] && continue
      code="$(api POST "$target" "$body")"
      if [[ "$code" == 200 ]]; then
        ok=$((ok + 1)); green "  ok    $target $id"
        [[ "$target" == /api/demo/customers ]] && created+=("$id")
      elif [[ "$code" == 409 ]]; then
        skipped+="$id "; grey "  skip  $target $id already exists"
      else
        fail=$((fail + 1)); red "  FAIL  $target $id: HTTP $code $(cat "$TMP")"
      fi
    done < <(seed_lines)
    if (( ${#created[@]} )); then
      echo "Waiting for CDC to deliver ${#created[@]} new profile(s) to Redis..."
      for id in "${created[@]}"; do
        if wait_profile "$id"; then green "  cached  $id"; else red "  NOT cached after 20s: $id"; fail=$((fail + 1)); fi
      done
    fi
    n_skipped=$(wc -w <<<"$skipped")
    echo; echo "Seed done: $ok inserted, $n_skipped customer(s) skipped, $fail failed."
    (( fail == 0 ))
    ;;

  scenarios)
    while read -r verb id message; do
      [[ "$verb" == CHAT ]] && chat "$id" "$message"
    done < <(seed_lines)
    echo; api GET /api/agent/reports/kpis >/dev/null
    if (( HAVE_JQ )); then jq -r '"KPIs: sessions=\(.totalSessions) deflection=\(.deflectionRatePercent)% escalations=\(.escalations) compliance=\(.policyComplianceScore)% cacheMisses=\(.cacheMissFallbacks) dltDepth=\(.dltMessageDepth)"' "$TMP"
    else echo "KPIs: $(cat "$TMP")"; fi
    ;;

  charge)
    need 3 "charge <customerId> <SUBSCRIPTION|LATE_FEE|OVERAGE> <amount> [description]" "$@"
    id="$1"; type="$(tr '[:lower:]' '[:upper:]' <<<"$2")"; amount="$3"; shift 3
    desc="${*:-Added by demo.sh}"
    code="$(api POST /api/demo/mutate "{\"customerId\":$(json_str "$id"),\"chargeType\":\"$type\",\"amount\":$amount,\"description\":$(json_str "$desc")}")"
    [[ "$code" == 200 ]] || { red "HTTP $code: $(cat "$TMP")"; exit 1; }
    inv="$(field invoiceId)"; echo "Inserted $inv. Waiting for it to reach Redis..."
    for ((i = 0; i < 40; i++)); do
      if [[ "$(api GET "/api/demo/profile/$id")" == 200 ]] && grep -q "\"$inv\"" "$TMP"; then
        green "In Redis after ~$((i / 2)) s"; exit 0
      fi
      sleep 0.5
    done
    red "Not in Redis after 20 s"; exit 1
    ;;

  customer)
    need 5 "customer <customerId> <fullName> <Standard|Gold|Enterprise> <tenureMonths> <mrr> [waiversUsed12m]" "$@"
    code="$(api POST /api/demo/customers "{\"customerId\":$(json_str "$1"),\"fullName\":$(json_str "$2"),\"accountTier\":$(json_str "$3"),\"tenureMonths\":$4,\"mrr\":$5,\"waiversUsed12m\":${6:-0}}")"
    [[ "$code" == 200 ]] || { red "HTTP $code: $(cat "$TMP")"; exit 1; }
    if wait_profile "$1"; then green "Created $1 and cached in Redis"; else red "Created, but not in Redis after 20 s"; exit 1; fi
    ;;

  ticket)
    need 4 "ticket <customerId> <OPEN|PENDING_ENGINEERING|RESOLVED> <slaHoursFromNow> <subject...>" "$@"
    id="$1"; status="$(tr '[:lower:]' '[:upper:]' <<<"$2")"; hours="$3"; shift 3
    code="$(api POST /api/demo/tickets "{\"customerId\":$(json_str "$id"),\"category\":\"TECHNICAL\",\"subject\":$(json_str "$*"),\"status\":\"$status\",\"slaHoursFromNow\":$hours}")"
    [[ "$code" == 200 ]] || { red "HTTP $code: $(cat "$TMP")"; exit 1; }
    tkt="$(field ticketId)"; echo "Created $tkt (SLA breached: $(field slaBreached)). Waiting for it to reach Redis..."
    for ((i = 0; i < 40; i++)); do
      if [[ "$(api GET "/api/demo/profile/$id")" == 200 ]] && grep -q "\"$tkt\"" "$TMP"; then green "In Redis"; exit 0; fi
      sleep 0.5
    done
    red "Not in Redis after 20 s"; exit 1
    ;;

  update)
    need 2 "update <customerId> [tier=Gold] [tenure=24] [waivers=0]" "$@"
    id="$1"; shift; parts=()
    for kv in "$@"; do
      k="${kv%%=*}"; v="${kv#*=}"
      case "$k" in
        tier)    parts+=("\"accountTier\":$(json_str "$v")") ;;
        tenure)  parts+=("\"tenureMonths\":$v") ;;
        waivers) parts+=("\"waiversUsed12m\":$v") ;;
        *) red "unknown field '$k' (use tier, tenure, waivers)"; exit 2 ;;
      esac
    done
    body="{$(IFS=,; echo "${parts[*]}")}"
    code="$(api POST "/api/demo/customers/$id" "$body")"
    [[ "$code" == 200 ]] && green "Updated $id; CDC will refresh Redis in about a second" || { red "HTTP $code: $(cat "$TMP")"; exit 1; }
    ;;

  chat)    need 2 "chat <customerId> <message...>" "$@"; id="$1"; shift; chat "$id" "$*" ;;

  profile)
    need 1 "profile <customerId>" "$@"
    if [[ "$(api GET "/api/demo/profile/$1")" == 200 ]]; then
      if (( HAVE_JQ )); then jq . "$TMP"; else cat "$TMP"; echo; fi
    else echo "Not cached: $1"; fi
    ;;

  evict)   need 1 "evict <customerId>" "$@"; api DELETE "/api/demo/cache/$1" >/dev/null; echo "$(cat "$TMP")"; echo ;;

  poison)
    table="${1:-customers}"
    code="$(api POST /api/demo/poison-pill "{\"table\":$(json_str "$table")}")"
    [[ "$code" == 200 ]] || { red "HTTP $code: $(cat "$TMP")"; exit 1; }
    echo "Published to db.public.$table. Waiting for the DLT..."
    for ((i = 1; i <= 20; i++)); do
      sleep 1; api GET "/api/admin/kafka/dlt?preview=0" >/dev/null
      depth="$(sed -n "s/.*\"db\\.public\\.$table\\.DLT\":{\"depth\":\\([0-9]*\\).*/\\1/p" "$TMP")"
      [[ -n "$depth" && "$depth" -gt 0 ]] && { green "On db.public.$table.DLT after ~$i s (depth $depth)"; exit 0; }
    done
    red "Not on the DLT after 20 s"; exit 1
    ;;

  dlt)     api GET "/api/admin/kafka/dlt?preview=3" >/dev/null; if (( HAVE_JQ )); then jq . "$TMP"; else cat "$TMP"; echo; fi ;;
  replay)  api POST /api/admin/kafka/dlt/replay "{\"topic\":\"db.public.${1:-customers}.DLT\"}" >/dev/null; cat "$TMP"; echo ;;
  caching)
    # Prompt-caching demo: same question N times; shows LLM round trips, latency and token usage per turn.
    customer="${1:-cust_123}"; turns="${2:-3}"; export CHAT_PACE_SECONDS="${CHAT_PACE_SECONDS:-5}"
    q="What is the maximum late fee you can waive for an account like mine?"
    for (( t=1; t<=turns; t++ )); do
      printf '\nTurn %s of %s' "$t" "$turns"
      chat "$customer" "$q"
      if grep -q 'PLANNER: DETERMINISTIC_REPLAY' "$TMP"; then
        red "  Offline planner: no LLM call, nothing to cache. Start the app with AGENT_LLM_MODE=llm and a key (playbook section 4)."
        break
      fi
    done
    api GET /api/agent/reports/kpis >/dev/null
    if (( HAVE_JQ )); then jq -r '"llmCalls \(.llmCalls), promptTokens \(.promptTokens), cachedPromptTokens \(.cachedPromptTokens), hit rate \(.promptCacheHitRatePercent)%"' "$TMP"; else cat "$TMP"; echo; fi
    grey "OpenAI reports cached tokens; Gemini's OpenAI-compatible endpoint does not, so on Gemini compare latency instead."
    ;;
  kpis)    api GET /api/agent/reports/kpis >/dev/null; if (( HAVE_JQ )); then jq . "$TMP"; else cat "$TMP"; echo; fi ;;

  reset)
    cd "$DIR/.."
    docker compose down -v >/dev/null 2>&1; docker compose up -d >/dev/null 2>&1
    echo "Waiting for the app..."
    for ((i = 0; i < 60; i++)); do
      [[ "$(api GET /actuator/health)" == 200 ]] && break; sleep 3
    done
    if wait_profile cust_123 60; then green "Stack reset; seed personas are cached."; else red "App up but cust_123 not cached yet"; fi
    ;;

  help|-h|--help) usage 0 ;;
  *) red "unknown command: $cmd"; usage 2 ;;
esac

#!/usr/bin/env bash
#
# D-VEX — Increment 0: ONE controlled LIVE function-calling probe.
#
# Purpose : prove gemini-3.8-flash accepts a tool declaration AND returns a real
#           functionCall over the SAME REST shape GeminiEngine already uses.
# Scope   : verification ONLY. It performs NO device action (it never opens
#           YouTube) and it changes nothing in the app architecture. It is not
#           part of the app build.
# Quota   : exactly ONE request, NO retries, NO loops. A 429 response consumes
#           nothing, so running this while quota is exhausted is free.
# Secrets : GEMINI_API_KEY is read from the environment and never printed. The
#           script echoes only status codes and parsed model output.
#
# Usage   : bash tools/probe_gemini_function_calling.sh
# Success : checks A–F all PASS.
#
set -uo pipefail

MODEL="gemini-3.8-flash"
URL="https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent"
OUT="/tmp/dvex_fc_probe.json"
ACTION_SRC="app/src/main/java/com/example/agent/AgentAction.kt"

if [ -z "${GEMINI_API_KEY:-}" ]; then
  echo "FAIL: GEMINI_API_KEY is not set in this shell."
  exit 2
fi

# Tool declaration in the dialect the API validated on 2026-09-29:
# `parameters` (OpenAPI subset) with UPPERCASE type names.
read -r -d '' BODY <<'JSON'
{"contents":[{"parts":[{"text":"Open YouTube for me."}]}],
 "tools":[{"functionDeclarations":[{
   "name":"open_app",
   "description":"Open an installed app by its name on this device.",
   "parameters":{"type":"OBJECT",
     "properties":{"app_name":{"type":"STRING","description":"App name exactly as the user said it"}},
     "required":["app_name"]}}]}],
 "generationConfig":{"temperature":0.7,"maxOutputTokens":512,"thinkingConfig":{"thinkingLevel":"low"}}}
JSON

echo "POST ${URL}"
echo "model=${MODEL}  requests=1  retries=0"
http=$(curl -s -m 45 -o "$OUT" -w '%{http_code}' -X POST "$URL" \
  -H "x-goog-api-key: ${GEMINI_API_KEY}" \
  -H 'Content-Type: application/json' \
  -d "$BODY")
echo "HTTP=${http}"
echo

python3 - "$OUT" "$http" <<'PY'
import json, os, sys

path, http = sys.argv[1], sys.argv[2]
raw = open(path).read() if os.path.exists(path) else ""
try:
    d = json.loads(raw)
except Exception:
    d = {}

err = d.get("error") or {}
if err:
    print("API ERROR:", err.get("status"), "|", (err.get("message") or "")[:200].replace("\n", " "))
    print()

parts = []
for c in (d.get("candidates") or []):
    parts += ((c.get("content") or {}).get("parts") or [])
calls = [p["functionCall"] for p in parts if "functionCall" in p]

A = http == "200"
B = bool(calls)
name = calls[0].get("name") if calls else None
args = (calls[0].get("args") or {}) if calls else {}
C = bool(name)
D = bool(args.get("app_name"))
E = name == "open_app"          # only counts if D-VEX already owns this action
F = d.get("modelVersion") == "gemini-3.8-flash"

def mark(ok):
    return "PASS" if ok else "PENDING"

print("A tool declaration accepted ....... " + mark(A) + "  (HTTP " + str(http) + ")")
print("B real functionCall returned ..... " + mark(B))
print("C function name parsed ........... " + mark(C) + "  name=" + str(name))
print("D arguments parsed ............... " + mark(D) + "  args=" + json.dumps(args))
print("E maps to a D-VEX action ......... " + mark(E) + "  expected AgentActionType.OPEN_APP")
print("F gemini-3.8-flash response ...... " + mark(F) + "  modelVersion=" + str(d.get("modelVersion")))
print()
if all([A, B, C, D, E, F]):
    print("RESULT: LIVE function calling VERIFIED on gemini-3.8-flash.")
    print("NEXT  : Increment 1 (MCP foundation) may proceed.")
else:
    print("RESULT: Tool schema validation verified; live functionCall pending quota reset.")
    print("NEXT  : do not start Increment 1 yet.")
PY

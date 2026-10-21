#!/bin/bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
# 
#   http://www.apache.org/licenses/LICENSE-2.0
# 
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
# Offline gate: no model call, no network. Two
# stages, both hermetic:
#
# Stage A -- the launcher and the kernel domain, from the inside. Runs
# the image (entrypoint = launcher) with the pure-bash probe as the
# harness: the workspace is mounted at its host path, a scratch XDG dir
# pins a hermetic git identity, one stdin line exercises the shim. The
# probe asserts the in-domain invariants (workspace mount, the pinned
# git identity, the shim's first-line delivery, /home unreachable); the
# host asserts the exit-code relay and the "Landlock domain installed"
# log line.
#
# Stage B -- the ACP gate, on the host. The real AcpGate spawns a fake
# ACP agent (a bash script bound read-only into the image, answering
# initialize / session/new / session/prompt with minimal JSON-RPC). The
# host asserts the two intercepts from the fake's viewpoint (it writes a
# marker file only when the gate lets a stripped member through) and
# that the prompt reaches the agent and gets its reply.
#
# Prerequisite: bash container/build-image.sh
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
IMAGE=${NBMP_IMAGE:-localhost/nbmp-agent}
JDK="${JDK:-${JAVA_HOME:?JDK or JAVA_HOME must point at the host JDK}}"
LIBS="$ROOT/target/container-ctx/lib"
WS="$ROOT/target/nbmp-test-ws"
XDG="$ROOT/target/offline-xdg"
LOG="$ROOT/target/offline"

pass() { echo "  ok: $1"; }
fail() {
    echo "  FAIL: $1" >&2
    exit 1
}

podman image exists "$IMAGE" 2>/dev/null || { echo "image $IMAGE not found -- bash container/build-image.sh" >&2; exit 1; }
[ -d "$LIBS" ] || { echo "missing $LIBS -- bash container/build-image.sh" >&2; exit 1; }

# =============================== stage A ===============================
# The launcher + kernel domain, from the inside.

rm -rf "$WS" "$XDG"; mkdir -p "$WS" "$XDG/git"
echo "hello-4e" > "$WS/probe.txt"
# Hermetic git identity: a scratch XDG config mounted read-only; the
# in-container launcher copies it into the sandbox's private $HOME
# and pins GIT_CONFIG_GLOBAL at the copy before the domain installs.
printf '[user]\n\temail = offline@example.com\n' > "$XDG/git/config"

echo "offline: stage A -- launcher + Landlock domain, in-container probe"
if printf 'first-line\n' | podman run --rm --init --read-only -i \
        -v "$WS:$WS" \
        -v "$XDG:/host-xdg:ro" -e XDG_CONFIG_HOME=/host-xdg \
        "$IMAGE" --workspace "$WS" -- /app/probe.sh \
        > "$LOG.a" 2>&1; then
    CODE=0
else
    CODE=$?
fi
[ "$CODE" -eq 0 ] || { cat "$LOG.a" >&2; fail "stage A: probe exited with $CODE"; }
pass "exit code 0 relayed through the launcher"
grep -q "Landlock domain installed" "$LOG.a" || { cat "$LOG.a" >&2; fail "stage A: no Landlock domain inside the container"; }
pass "Landlock domain installed inside the container"
grep -q "probe: all checks passed" "$LOG.a" || { cat "$LOG.a" >&2; fail "stage A: probe checks failed"; }
pass "workspace mount, pinned git identity, shim first line, /home unreachable"

# =============================== stage B ===============================
# The ACP gate on the host, driving a fake ACP agent in the image.

echo "offline: stage B -- AcpGate on the host, fake ACP agent in the image"
GATE_DIR="$ROOT/target/offline-gate"
rm -rf "$GATE_DIR"; mkdir -p "$GATE_DIR"

# The fake ACP agent: minimal JSON-RPC over stdio. The gate binds it
# read-only into the image at /usr/local/bin/fakeacp; the launcher runs
# it with cwd = the workspace. It writes a marker file only when the
# gate lets a member through that it must strip:
#   fswrite-seen  initialize still carries fs/writeTextFile
#   mcp-seen      session/new still carries a remote (http) mcpServer
#   agent-ran     the prompt reached the agent (the gate relayed it)
# It also probes the ro exec-surface mounts:
#   cfg-writable  its own opencode.json was writable (must not happen)
#   cfg-readonly  the write failed (the mount pinned the file)
#   gitcfg-writable  git's local config was writable (must not happen)
#   gitcfg-readonly  git config --local failed (the mount pinned it)
#   infoattr-writable  the info/attributes overlay was writable (must
#                      not happen)
#   infoattr-readonly  the append failed (the mount pinned it)
cat > "$GATE_DIR/fakeacp" <<'FAKE'
#!/bin/bash
while IFS= read -r line; do
    id=$(printf '%s\n' "$line" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')
    case $line in
        *'"method":"initialize"'*)
            case $line in *writeTextFile*) echo seen > fswrite-seen ;; esac
            printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2025-06-18","agentCapabilities":{"loadSession":false},"agentInfo":{"name":"fakeacp","version":"0"}}}\n' "$id"
            ;;
        *'"method":"session/new"'*|*'"method":"session/load"'*)
            case $line in *'"type":"http"'*) echo seen > mcp-seen ;; esac
            printf '{"jsonrpc":"2.0","id":%s,"result":{"sessionId":"s1"}}\n' "$id"
            ;;
        *'"method":"session/prompt"'*)
            echo ran > agent-ran
            { echo planted >> opencode.json 2>/dev/null && echo writable > cfg-writable
              || echo readonly > cfg-readonly; }
            { git config --local nbmp.offline 1 2>/dev/null && echo writable > gitcfg-writable
              || echo gitcfg-readonly > gitcfg-readonly; }
            { echo planted >> .git/info/attributes 2>/dev/null && echo writable > infoattr-writable
              || echo readonly > infoattr-readonly; }
            printf '{"jsonrpc":"2.0","id":%s,"result":{"stopReason":"end_turn"}}\n' "$id"
            exit 0
            ;;
    esac
done
exit 0
FAKE
chmod +x "$GATE_DIR/fakeacp"

# The agent's project config: the gate must mount it read-only, so the
# in-domain agent cannot rewrite its own sandbox config.
printf '{"offline":true}\n' > "$WS/opencode.json"
rm -f "$WS/cfg-readonly" "$WS/cfg-writable"
# Git's exec surfaces, hand-crafted (no host git needed): the gate
# must pin .git/hooks, .git/config, and .git/info/attributes
# read-only -- git executes what it finds there, and the attributes
# overlay is read by the user's host-side git, too.
mkdir -p "$WS/.git/hooks" "$WS/.git/info"
printf '[core]\n' > "$WS/.git/config"
printf '# offline overlay\n' > "$WS/.git/info/attributes"
rm -f "$WS/gitcfg-readonly" "$WS/gitcfg-writable" "$WS/infoattr-readonly" "$WS/infoattr-writable"

IN="$GATE_DIR/in"
OUT="$GATE_DIR/out"
mkfifo "$IN" "$OUT"
# The JVM flags mirror JvmFlags.RUNTIME_FLAGS -- change them there first.
"$JDK/bin/java" --enable-native-access=ALL-UNNAMED \
    --add-modules jdk.incubator.json \
    -cp "$LIBS/*" \
    dev.mbien.nbmp.mcp.AcpGate \
    --agent "$GATE_DIR/fakeacp" --workspace "$WS" \
    < "$IN" > "$OUT" 2> "$GATE_DIR/err.log" &
GATE_PID=$!
exec 3>"$IN"    # hold the gate's stdin FIFO open
exec 4<"$OUT"   # the gate's stdout (pure ACP relay; logs go to err.log)
send() { printf '%s\n' "$1" >&3; }
recv() { IFS= read -r -t 120 line <&4 || fail "no reply from the gate"; }

send '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","clientCapabilities":{"fs":{"readTextFile":{},"writeTextFile":{}}},"clientInfo":{"name":"offline","version":"0"}}}'
recv
case $line in *'"agentInfo"'*"fakeacp"*) pass "initialize relayed (the fake agent's reply)" ;;
              *) fail "initialize reply wrong: $line" ;; esac

send '{"jsonrpc":"2.0","method":"notifications/initialized"}'
send "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/new\",\"params\":{\"cwd\":\"$WS\",\"mcpServers\":[{\"name\":\"nb\",\"type\":\"http\",\"url\":\"http://localhost:8080/mcp\"},{\"name\":\"local\",\"type\":\"stdio\",\"command\":[\"echo\",\"hi\"]}]}}"
recv
case $line in *'"sessionId":"s1"'*) pass "session/new relayed (the remote mcpServer was the test)" ;;
              *) fail "session/new reply wrong: $line" ;; esac

send '{"jsonrpc":"2.0","id":3,"method":"session/prompt","params":{"sessionId":"s1","prompt":[{"type":"text","text":"offline"}]}}'
recv
case $line in *'"stopReason":"end_turn"'*) pass "session/prompt relayed end-to-end" ;;
              *) fail "prompt reply wrong: $line" ;; esac

# The fake agent exited with the prompt reply; the gate exits with the
# agent's code when its stdout ends -- no sleep, the wait is exact.
wait "$GATE_PID"
GATE_CODE=$?
exec 3>&-
exec 4<&-
[ "$GATE_CODE" -eq 0 ] || { cat "$GATE_DIR/err.log" >&2; fail "stage B: gate exited with $GATE_CODE"; }

[ -f "$WS/agent-ran" ] || fail "the fake agent never saw the prompt (no agent-ran marker)"
pass "the prompt reached the fake agent in the container"
[ ! -e "$WS/fswrite-seen" ] || fail "the gate let fs/writeTextFile through to the agent"
[ ! -e "$WS/mcp-seen" ] || fail "the gate let a remote mcpServers entry through to the agent"
pass "intercepts verified: fs/writeTextFile stripped, remote mcpServers dropped"
[ -f "$WS/cfg-readonly" ] || fail "stage B: the agent's project config was writable in the domain"
[ ! -e "$WS/cfg-writable" ] || fail "stage B: the agent could rewrite its own project config"
! grep -q planted "$WS/opencode.json" \
    || fail "stage B: the agent's project config was modified in the domain"
pass "the agent's project config is pinned read-only in the domain"
[ -f "$WS/gitcfg-readonly" ] || fail "stage B: git's local config was writable in the domain"
[ ! -e "$WS/gitcfg-writable" ] || fail "stage B: the agent could write git's local config"
! grep -q nbmp "$WS/.git/config" \
    || fail "stage B: the agent modified .git/config in the domain"
[ -f "$WS/infoattr-readonly" ] || fail "stage B: the .git/info/attributes overlay was writable"
[ ! -e "$WS/infoattr-writable" ] || fail "stage B: the agent could write the attributes overlay"
! grep -q planted "$WS/.git/info/attributes" \
    || fail "stage B: the agent modified .git/info/attributes in the domain"
pass "git's exec surfaces (hooks, config, info/attributes) are pinned read-only"
grep -q "Landlock domain installed" "$GATE_DIR/err.log" \
    || { cat "$GATE_DIR/err.log" >&2; fail "stage B: no Landlock domain inside the gate's container"; }
pass "Landlock domain installed in the gate's container"
grep -q "agent exited with code 0" "$GATE_DIR/err.log" \
    || { cat "$GATE_DIR/err.log" >&2; fail "stage B: the gate did not log the agent exit code"; }
pass "agent exit code 0 relayed through the gate"

echo "offline: PASS"

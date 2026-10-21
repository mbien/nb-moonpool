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
# In-container build gate: the agent container
# gets the host build toolchain -- the JDK and the mvnd distribution --
# as read-only bind mounts at their host paths (identity mounts), and
# the launcher's --exec-root grants make them executable inside the
# container's Landlock domain, deriving JAVA_HOME and PATH from the
# roots. The agent then runs a real Maven build from within the
# container -- the in-container analog of the host dev session's
# `mvnd package`.
#
# This validates the in-container toolchain part -- that the read-only
# toolchain mounts + exec roots cooperate with the in-container Landlock
# domain (the JDK and the mvnd client actually execute, and mvnd drives
# a Maven lifecycle on the workspace). The agent path itself is already
# proven by agent-hello.sh; this adds the toolchain.
#
# Scope (fast by design -- NOT a full package):
#   1. java -version                   the mounted JDK executes in the domain
#   2. mvnd --version                  the mounted mvnd client executes
#   3. mvn -version                    the mvnd dist's nested full Maven
#                                      (mvn/bin/mvn) executes
#   4. mvnd -B -Dmaven.repo.local=.m2 <args> <phase>
#                                      mvnd drives a real Maven lifecycle on
#                                      the workspace (default phase: validate)
# Escalate to a full build (verbose by default; the validate gate is -q):
#   NBMP_BUILD_PHASE=package bash test/agent-build.sh
#   ... or package without the test suite:
#   NBMP_BUILD_PHASE=package NBMP_BUILD_ARGS="-DskipTests" bash test/agent-build.sh
# Point at a different workspace (e.g. a scratch):
#   NBMP_WS=/path/to/ws bash test/agent-build.sh
#
# Needs network + model access (the agent makes a live model call). The
# offline gate is offline.sh.
#
# Tradeoff: the container now depends on the host toolchain paths
# (mounted read-only); the mvnd daemon is a container process and
# restarts on each run (its registry persists in the workspace's
# .nbmp/mvnd, so a warm registry self-heals a fresh daemon).
#
# The agent is the deterministic test agent (opencode.sh, sourced
# below): a one-shot `opencode run` has no one to answer "ask"
# permissions, and opencode's own agent/config choice is not part of
# what this gate validates. The test agent allows bash (the smoke
# runs fixed commands) and denies edit and task; its name is a canary
# in the container log ('> nbmp-test · <model>', not 'build'). The
# test config is mounted read-only over the workspace's opencode.json
# path (host file untouched), and the git exec surfaces are pinned as
# in the gate, so the agent's shell cannot plant persistence.
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
. "$SCRIPT_DIR/opencode.sh"
IMAGE=${NBMP_IMAGE:-localhost/nbmp-agent}
JDK="${JDK:-${JAVA_HOME:?JDK or JAVA_HOME must point at the host JDK}}"
MVND_HOME="${MVND_HOME:?MVND_HOME must point at the mvnd distribution}"
WS="${NBMP_WS:-$ROOT}"   # the real project by default; override for a scratch
PHASE="${NBMP_BUILD_PHASE:-validate}"
LOG="$ROOT/target/build-gate"

# Identity mounts: the toolchain roots are bound at their host paths, and
# the --exec-root args below MUST match the mount sources.

fail() { echo "  FAIL: $1" >&2; exit 1; }

[ -d "$JDK" ] || fail "JDK not found at $JDK"
[ -d "$MVND_HOME" ] || fail "mvnd distribution not found at $MVND_HOME"
[ -d "$WS" ] || fail "workspace not found at $WS"
podman image exists "$IMAGE" 2>/dev/null || fail "image $IMAGE not found -- bash container/build-image.sh"

OPENCODE_BIN=$(command -v opencode) \
    || { echo "host opencode not found on PATH (the image carries no agent binary)" >&2; exit 1; }
# The agent's models/auth/sessions: same-path bind mounts of the host's
# XDG dirs + base var overrides (mirrors AcpGate.agentStateMounts).
XDGMOUNTS=()
config_base="${XDG_CONFIG_HOME:-$HOME/.config}"
[ -d "$config_base/opencode" ] && XDGMOUNTS+=(-v "$config_base/opencode:$config_base/opencode:ro" -e "XDG_CONFIG_HOME=$config_base")
data_base="${XDG_DATA_HOME:-$HOME/.local/share}"
[ -d "$data_base/opencode" ] && XDGMOUNTS+=(-v "$data_base/opencode:$data_base/opencode" -e "XDG_DATA_HOME=$data_base")
state_base="${XDG_STATE_HOME:-$HOME/.local/state}"
[ -d "$state_base/opencode" ] && XDGMOUNTS+=(-v "$state_base/opencode:$state_base/opencode" -e "XDG_STATE_HOME=$state_base")

# The agent's project config: the test agent's config, read-only over
# the workspace's opencode.json path (deterministic agent, host file
# untouched -- same pattern as the gate's config pin). Plus the git
# exec surfaces, read-only (mirrors AcpGate.gitExecSurfaceMounts):
# the agent's shell cannot plant persistence.
CFGMOUNTS=(-v "$NBMP_TEST_CONFIG:$WS/opencode.json:ro")
if [ -d "$WS/.git" ]; then
    [ -d "$WS/.git/hooks" ] && CFGMOUNTS+=(-v "$WS/.git/hooks:$WS/.git/hooks:ro")
    [ -f "$WS/.git/config" ] && CFGMOUNTS+=(-v "$WS/.git/config:$WS/.git/config:ro")
    [ -f "$WS/.git/info/attributes" ] && CFGMOUNTS+=(-v "$WS/.git/info/attributes:$WS/.git/info/attributes:ro")
fi

# -- the agent container: the workspace + the read-only identity
#    toolchain mounts and the launcher's --exec-root grants. The launcher
#    derives JAVA_HOME (first root with a bin/java) and PATH (each root's
#    bin/, plus the mvnd dist's nested mvn/bin) from the roots, so java,
#    mvnd and mvn resolve by bare name. mvnd is not in the launcher's
#    auto-resolve list (git/mvn/java/sh), so it needs the explicit grant.
# Extra mvnd args: the validate gate is quiet by default, a full build is
# verbose by default (you want to see compile + tests). Override either way
# via NBMP_BUILD_ARGS ("" forces quiet, "-DskipTests" skips the suite).
if [ "$PHASE" = "validate" ]; then
    BUILD_ARGS=${NBMP_BUILD_ARGS--q}
else
    BUILD_ARGS=${NBMP_BUILD_ARGS-}
fi
BUILD_CMD="mvnd -B -Dmaven.repo.local=.m2 $BUILD_ARGS $PHASE"
PROMPT="A Java build toolchain is mounted read-only in this environment and is on PATH (java, mvnd, mvn; JAVA_HOME is set). Use the shell tool (bash) to run these four exact commands in the workspace, one at a time. Do not modify them. After all four, reply with exactly one line:
  BUILD-GATE OK
if all four exited 0, otherwise:
  BUILD-GATE FAIL: <the number(s) of the command(s) that failed, and the error>
1. java -version
2. mvnd --version
3. mvn -version
4. $BUILD_CMD"

podman run --rm --init --read-only -i \
    -v "$WS:$WS" \
    -v "$OPENCODE_BIN:/usr/local/bin/opencode:ro" \
    -v "$JDK:$JDK:ro" \
    -v "$MVND_HOME:$MVND_HOME:ro" \
    "${XDGMOUNTS[@]}" \
    "${CFGMOUNTS[@]}" \
    "$IMAGE" --workspace "$WS" \
    --exec-root "$JDK" --exec-root "$MVND_HOME" \
    -- opencode run --agent "$NBMP_TEST_AGENT" "$PROMPT" \
    < /dev/null \
    > "$LOG.container" 2>&1
CODE=$?

echo "=== agent exit: $CODE ==="
echo "=== container log (agent reply + launcher) ==="
cat "$LOG.container"
echo "=== build-gate result ==="
if grep -q "BUILD-GATE OK" "$LOG.container"; then
    echo "PASS: the in-container toolchain (JDK + mvnd + mvn) executed under the Landlock domain"
elif grep -q "BUILD-GATE FAIL" "$LOG.container"; then
    echo "FAIL: the agent reported a failing command -- see the container log above"
    exit 1
else
    echo "INCONCLUSIVE: no BUILD-GATE line in the agent reply (model/access issue, or the"
    echo "             agent did not follow the prompt). Read the container log above and"
    echo "             tell a toolchain failure from a model failure."
    exit 1
fi

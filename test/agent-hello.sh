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
# The "it works for real" smoke: a real opencode run in the agent
# container (image entrypoint = launcher: Landlock domain, git-config
# pin, the stdin shim), one-shot `opencode run`. Stdin is /dev/null, so
# the shim degrades to a plain cat and the agent sees EOF exactly as
# without the shim.
#
# Needs network + model access (the agent makes a live model call).
# The offline gate is offline.sh; this is the "it works for real" check.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
. "$SCRIPT_DIR/opencode.sh"
IMAGE=${NBMP_IMAGE:-localhost/nbmp-agent}
WS="${NBMP_WS:-$ROOT/target/nbmp-test-ws}"

rm -rf "$WS"; mkdir -p "$WS"
echo "hello-4e" > "$WS/probe.txt"
# The deterministic test agent (opencode.sh) as the project config.
cp "$NBMP_TEST_CONFIG" "$WS/opencode.json"

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
podman run --rm --init --read-only -i \
    -v "$WS:$WS" \
    -v "$OPENCODE_BIN:/usr/local/bin/opencode:ro" \
    "${XDGMOUNTS[@]}" \
    "$IMAGE" --workspace "$WS" \
    -- opencode run --agent "$NBMP_TEST_AGENT" "Read the file probe.txt in the workspace and reply with its exact contents. Do not guess." \
    < /dev/null
echo "agent-exit=$?"

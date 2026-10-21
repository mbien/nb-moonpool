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
# Stage A probe: runs as the in-container harness (the image's
# entrypoint is the launcher, which wraps this in the stdin shim, pins
# GIT_CONFIG_GLOBAL, and applies the Landlock domain before spawning).
# It asserts the sandbox invariants from the inside, then exits 0 so the
# launcher relays a clean code to the host:
#   - the workspace is mounted at its host path (cwd)
#   - a managed git config copy exists and the identity resolves through it
#   - the first stdin line reached the harness (the shim delivered it)
#   - /home is unreachable outside the domain (EACCES, not "empty")
# The host (test/offline.sh) also asserts the launcher's "Landlock
# domain installed" log line from the container's stderr.
set -u

fail() { echo "probe FAIL: $1" >&2; exit 1; }
ok() { echo "probe ok: $1"; }

# -- the workspace is the harness's cwd and is mounted at its host path
[ -f probe.txt ] || fail "workspace not mounted (no probe.txt in cwd $PWD)"
grep -q "hello-4e" probe.txt || fail "workspace content wrong"
ok "workspace mounted at host path ($PWD)"

# -- the git identity resolves through the launcher's managed copy
[ -n "${GIT_CONFIG_GLOBAL:-}" ] || fail "GIT_CONFIG_GLOBAL not pinned by the launcher"
[ -r "$GIT_CONFIG_GLOBAL" ] || fail "managed git config not readable: $GIT_CONFIG_GLOBAL"
grep -q "e2e@example.com" "$GIT_CONFIG_GLOBAL" \
    || fail "managed git config has no e2e identity: $GIT_CONFIG_GLOBAL"
ok "GIT_CONFIG_GLOBAL pinned at the managed copy"

# -- the first stdin line survived the shim (race fix)
first=$(head -n1)
[ "$first" = "first-line" ] || fail "first stdin line wrong (got: '$first')"
ok "first stdin line delivered through the shim"

# -- /home is outside the domain: a read must fail with EACCES (not just
#    be empty). In the rootless container /home is a real dir on the
#    host rootfs; only the Landlock domain makes it unreachable.
if ls /home >/dev/null 2>&1; then
    fail "/home is reachable inside the domain (Landlock not enforced)"
fi
ok "/home unreachable (Landlock domain enforced)"

echo "probe: all checks passed"
exit 0

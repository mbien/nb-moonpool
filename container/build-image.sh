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
# Assembles the container build context (nbmp jar + runtime dependencies) and builds the agent image.
# Vendor-neutral: any buildah-compatible builder works (the default is podman build).
#
# Prerequisite: mvnd -B -Dmaven.repo.local=.m2 package (the jar in target/)
# -- or via the Maven profile, which builds + tests first:
#    mvnd -B -Dmaven.repo.local=.m2 -Pcontainer verify
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
CTX="$ROOT/target/container-ctx"
IMAGE=${NBMP_IMAGE:-localhost/nbmp-agent}
JDK="${JDK:-${JAVA_HOME:?JDK or JAVA_HOME must point at the host JDK}}"

JAR=$(ls "$ROOT"/target/nbmp-*.jar 2>/dev/null | grep -vE 'sources|javadoc' | head -1 || true)
[ -n "$JAR" ] || { echo "no nbmp jar in target/ -- run: mvnd -B -Dmaven.repo.local=.m2 package" >&2; exit 1; }

rm -rf "$CTX"
mkdir -p "$CTX/lib"
cp "$JAR" "$CTX/lib/"

# Runtime dependency classpath, copied into the context: the
# -Pcontainer verify build resolves it (Maven passes NBMP_CP_FILE
# and JDK, since the mvnd daemon's PATH is sanitized); standalone,
# resolve it here via mvnd.
if [ -n "${NBMP_CP_FILE:-}" ]; then
    [ -f "$NBMP_CP_FILE" ] || { echo "NBMP_CP_FILE $NBMP_CP_FILE missing" >&2; exit 1; }
else
    # mvn (the mvnd dist's nested full Maven) works too -- same goal.
    : "${MVND:=$(command -v mvnd || command -v mvn || true)}"
    [ -n "$MVND" ] || { echo "neither mvnd nor mvn on PATH -- set MVND" >&2; exit 1; }
    NBMP_CP_FILE="$ROOT/target/image-cp.txt"
    (cd "$ROOT" && JAVA_HOME="$JDK" "$MVND" -B -q -Dmaven.repo.local=.m2 \
        dependency:build-classpath -DincludeScope=runtime \
        -Dmdep.outputFile="$NBMP_CP_FILE")
fi
while IFS= read -r jar || [ -n "$jar" ]; do
    [ -n "$jar" ] && cp "$jar" "$CTX/lib/"
done < <(tr ':' '\n' < "$NBMP_CP_FILE")

# -- jlinked runtime for the agent image: the app module plus the JDK
#    modules its closure needs (the full JDK 28 EA base it replaces was
#    564 MB). The runtime classpath is empty (no MCP proxy, no third-party
#    runtime deps), so CTX/lib holds the nbmp jar alone and jlink needs
#    no automatic-module descriptor patching.
rm -rf "$ROOT/target/runtime"
# Explicit --compress: this EA build links uncompressed without the
# flag; zip-9 is this jlink's best level (no zstd). The
# no-header-files/no-man-pages pairs trim ~0.5 MB of content the image
# never uses (no JNI development, no man readers).
"$JDK/bin/jlink" --module-path "$CTX/lib" --add-modules dev.mbien.nbmp.mcp \
    --compress=zip-9 --no-header-files --no-man-pages \
    --output "$ROOT/target/runtime"
cp -r "$ROOT/target/runtime" "$CTX/runtime"
echo "jlinked runtime: $(du -sh "$ROOT/target/runtime" | cut -f1) in $CTX/runtime"

# The agent binary (opencode) is not part of the image: AcpGate binds
# the host's binary read-only into the container, so the image build has
# no dependency on the npm artifact (and no pin to update).

cp "$ROOT/container/Containerfile" "$CTX/"
cp "$ROOT/container/probe.sh" "$CTX/"

# Image customization: the Maven properties nbmp.image.base /
# nbmp.image.packages arrive as NBMP_IMAGE_BASE / NBMP_PACKAGES
# (or set them directly) and override the Containerfile's ARG defaults.
PODMAN_ARGS=(-q -t "$IMAGE")
if [ -n "${NBMP_IMAGE_BASE:-}" ]; then
    PODMAN_ARGS+=(--build-arg "NBMP_IMAGE_BASE=$NBMP_IMAGE_BASE")
fi
if [ -n "${NBMP_PACKAGES:-}" ]; then
    PODMAN_ARGS+=(--build-arg "NBMP_PACKAGES=$NBMP_PACKAGES")
fi
podman build "${PODMAN_ARGS[@]}" "$CTX"
echo "built $IMAGE (context: $CTX)"

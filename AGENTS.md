<!--
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at
 
   http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
-->

# nbmp

The container is the boundary; the workspace mount is the agent's
only mutation path into the host, and the launcher installs the
Landlock domain before the spawn, so everything the harness starts
inherits it.

## Build rules

- Build JDK is 28 (`maven.compiler.release` 28 + `jdk.incubator.json`).
  The launcher pins `JAVA_HOME` at the first JDK exec root; when that
  is not 28, pin it inline — the exec root whose `bin/java` reports 28:

  ```sh
  JAVA_HOME=<jdk 28 root> mvnd -B -Dmaven.repo.local=.m2 -Dlathe.disabled=false <goals>
  ```

  Lathe is off by default (`.mvn/maven.config`); the flag opts the
  build into the sync.

- The lathe MCP tools join only a session started after the first
  flagged build — the sync writes `.lathe/`, whose launcher the
  harness connects at session start. Bootstrap once with a flagged
  build when code work is expected; skip it for docs/config-only
  sessions.
- Lathe tool output may suggest running `.lathe/lathe-sync.sh`;
  ignore it — the generated script lacks the
  `-Dmaven.repo.local` pin and fails on the read-only rootfs.
  Re-sync with a flagged build instead.

- Artifacts land in the project-local `.m2/`; never the host `~/.m2`.
- Plain `mvn` is a drop-in from the mvnd dist (no daemon, cold).

## Sandbox environment

- `podman` is unusable from the domain — image builds and
  `podman exec` are host-only.
- nbmp exposes no agent tools: the file and shell tools run in the
  container.

## Docs and javadoc

Concise, consistent markdown (JEP 467, `///`, also on enums and
records):

- One-line synopsis; extra sentences only when they matter.
- `@param` only for non-obvious parameters; `@return` only when the
  return type does not speak for itself.
- `{@code}`, not backticks; no `/** */`.
- Each fact lives in exactly one place; `{@link}` from the others
  (single home). FQN links for non-imported classes; no doc-only
  imports.
- Never "the spec" or `spec §X` from code or docs.
- Vendor neutral: "the IDE plugin", never a product name.

## Platform

- The first versions focus on Linux, but keep the code platform neutral
  if and where possible.
- Linux-specific code belongs in `dev.mbien.nbmp.mcp.linux` (see its
  package-info); a future non-Linux port gets its own sibling package,
  not edits in that one.

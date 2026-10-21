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

# nb-moonpool[^1]

Sandboxed agent sessions behind a thin ACP control plane. The
container is the boundary: the host gate (`AcpGate`) relays the ACP
stream into a container, where the launcher (`SandboxLauncher`)
installs a kernel fs domain (Landlock) around the agent (e.g opencode or goose).

The current implementation is Linux only and intentionally
kept minimal (Java 28, 0 dependencies, jlinked runtime).

## Build your Container

Needs JDK 28 and podman on the host PATH.

```sh
mvn -Pcontainer verify                             # build, tests, image
rm -rf runtime && cp -r target/runtime runtime     # gate runtime
```

Build the agent image (`localhost/nbmp-agent`) with:

| Option | Default | Effect |
|--------|---------|--------|
| `-Pcontainer` | — | runs the tests, links the runtime, and builds the image |
| `-Dnbmp.image.base` | `docker.io/library/debian:trixie-slim` | the image's base |
| `-Dnbmp.image.packages` | `ca-certificates curl git jq procps ripgrep unzip zip` | the packages installed into the base |

Base and packages are apt family only. Keep the image light since most tools can be read-only mounted
on demand (see below). After a code or configuration change, rerun the build and recopy the runtime —
the launcher ships inside the image, so the image and the host runtime stay in sync.

## Usage

One entry point, `scripts/nbmp`: a terminal on stdin gives the TUI,
the IDE plugin (no TTY) gets the ACP gate — the same container
command, the gate adding the ACP relay and its intercepts.

- **TUI.** `cd my-project && bash scripts/nbmp` — interactive
  opencode or goose with the current directory as the workspace. The TUI sees
  the host's sessions, so it doubles as a debugging view into the
  ACP flow's state.
- **IDE plugin (ACP).** Point the plugin's ACP client at
  `scripts/nbmp` with `--acp` as argument (plus the
  options below); `--workspace /path/to/project` may be omitted
  when the gate is spawned at the project root.

Options (both forms):

| Option | Default | Effect |
|--------|---------|--------|
| `--workspace <dir>` | the current directory | the workspace |
| `--agent <binary>` | opencode | the agent binary (opencode or goose) |
| `--image <name>` | localhost/nbmp-agent | the agent image |
| `--runtime <command>` | podman | the container runtime |
| `--exec-root <dir>` | — | a read-only toolchain root (repeatable; colon-separated `NBMP_EXEC_ROOTS` as an alternative) |
| `--acp` | — | force the ACP form in a terminal |

The agent binary always runs from the host (bind-mounted into the
container, no image rebuild for an upgrade). Both harnesses keep
their state in the host's XDG dirs — opencode's
`~/.…/opencode`, goose's `~/.…/goose` — which are mounted
at their same paths, so sessions continue where the host left them.

Configuration is mounted read-only if possible. For example: if your
workspace contains `opencode.json`, the file won't be writable by the
agent even though it exists within the writable project workspace —
same for git's exec surfaces (`.git/hooks`, `.git/config`), which
could leak back into the host.

## Path and container isolation

Both user-space podman and the in-container Landlock domain restrict
the agent to its workspace and the sanctioned mounts (read-only
toolchain roots, config, its own state dirs) — nothing else on the
host is reachable. Each workspace gets a deterministic container
name: `/home/duke/NetBeansProjects/my-project` runs as
`nbmp-home-duke-NetBeansProjects-my-project` (the TUI form:
`nbmp-tui-…`), so while a session is live you can peek in with:

    podman exec -it nbmp-home-duke-NetBeansProjects-my-project bash

(attached shells are outside the agent's Landlock domain — the
container's own view, without the agent's restrictions).

## Aspects not covered

- **Egress.** By default the agent keeps its network access (model
  traffic, build downloads). Restricting it is left to a more
  elaborate podman network setup if needed — e.g. an offline network,
  with the model reached through a local endpoint. The leak surface
  is bounded by the path isolation above: only what the agent can
  read can leave.
- **MCP.** No MCP connections are offered by default. The
  recommended approach is to isolate the LSP server as well. This
  project uses Lathe, for example (see `pom.xml`, `opencode.json`
  and `AGENTS.md`).
- **No workspace.** Where the use case does not require a workspace
  mount, projects like `isx` are a better fit — with even more
  agent isolation.

## Verification

- `bash test/offline.sh` — offline (no model call, no network): the
  launcher and the kernel domain from the inside, and the gate's
  intercepts from the outside.
- `bash test/agent-hello.sh` — a live agent run (needs network +
  model access) against a scratch workspace.
- `bash test/agent-build.sh` — the agent runs a real Maven build
  with the host toolchain mounted read-only.

[^1]: A moonpool is a covered opening in the deck of an offshore
vessel or habitat: equipment passes through it and operates in the
calm compartment below, isolated from the surface. Here the
workspace mount is the opening, the container the compartment, and
the host the vessel — unaffected.

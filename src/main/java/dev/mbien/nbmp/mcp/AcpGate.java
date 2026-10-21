/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package dev.mbien.nbmp.mcp;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

import jdk.incubator.json.Json;
import jdk.incubator.json.JsonArray;
import jdk.incubator.json.JsonObject;
import jdk.incubator.json.JsonString;
import jdk.incubator.json.JsonValue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/// ACP control plane: the executable the IDE plugin starts in place
/// of the agent binary. It spawns the real agent at t=0 in the sandbox
/// image (whose entrypoint is the launcher core: the Landlock domain,
/// the git-config and mvnd pins, the agent's working directory) and
/// relays the ACP stream (newline-delimited JSON-RPC) between the
/// IDE plugin
/// and the agent.
///
/// Two intercept points, everything else byte-transparent:
/// 1. {@code initialize} — strips {@code fs/writeTextFile} from the
///    client capabilities (the IDE write tunnel); the reply passes
///    through verbatim, so the IDE plugin's version sniffing sees the
///    real agent.
/// 2. {@code session/new} / {@code session/load} — drops the remote
///    (http/sse) {@code mcpServers} entries (the {@code nb} side
///    channel). The {@code cwd} passes through unchanged: the
///    workspace is bind-mounted at its host path, so the agent sees
///    the same absolute path the host sessions were created under
///    (the agent scopes its sessions per project worktree).
///
/// One gate process = one workspace = one agent. The TUI form shares
/// the container assembly ({@link #containerCommand} in its
/// interactive form) through {@link TuiCommand}.
public class AcpGate {

    private static final System.Logger LOG = System.getLogger(AcpGate.class.getName());

    /// The in-container dir the host agent binary is bind-mounted into
    /// (on PATH for the launcher; the domain grants it read/exec).
    static final String CONTAINER_BIN_DIR = "/usr/local/bin/";

    private final Path workspace;

    private AcpGate(Path workspace) {
        this.workspace = workspace;
    }

    /// Returns the {@code --workspace} value when given,
    /// otherwise the process's current directory.
    ///
    /// A gate spawned from the project root needs no flag at all.
    static Path resolveWorkspace(String workspaceArg) {
        return workspaceArg != null
                ? Path.of(workspaceArg).toAbsolutePath()
                : Path.of(".").toAbsolutePath();
    }

    /// The CLI options shared by the ACP gate and the TUI command
    /// printer ({@link TuiCommand}).
    record Options(String workspaceArg, Path workspace, String agent, String image,
            String runtime, List<Path> execRoots) {
    }

    /// The CLI options parsed from the command line.
    static Options options(String[] args) {
        String workspaceArg = null;
        String agent = "opencode";
        String image = "localhost/nbmp-agent";
        String runtime = "podman";
        List<Path> execRoots = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--workspace" -> {
                    workspaceArg = value(args, i);
                    i++;
                }
                case "--exec-root" -> {
                    execRoots.add(Path.of(value(args, i)).toAbsolutePath());
                    i++;
                }
                case "--agent" -> {
                    agent = value(args, i);
                    i++;
                }
                case "--image" -> {
                    image = value(args, i);
                    i++;
                }
                case "--runtime" -> {
                    runtime = value(args, i);
                    i++;
                }
                default -> throw new IllegalArgumentException("unknown option: " + args[i]
                        + " (options: --workspace <dir> [default: current directory], "
                        + "--agent <binary> [default: opencode], --image <name>, "
                        + "--runtime <command>, --exec-root <dir>)");
            }
        }
        Path workspace = resolveWorkspace(workspaceArg);
        if (!Files.isDirectory(workspace)) {
            throw new IllegalArgumentException("workspace is not a directory: " + workspace);
        }
        return new Options(workspaceArg, workspace, agent, image, runtime, execRoots);
    }

    public static void main(String[] args) throws Exception {
        Log.init();
        Options options = options(args);
        Path workspace = options.workspace();
        ProcessBuilder builder = new ProcessBuilder(
                containerCommand(
                        options.runtime(), options.image(), workspace,
                        options.agent(), options.execRoots(), false
                )
        );
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process agentProcess = builder.start();
        LOG.log(INFO, "container mode, workspace {0}{1}", workspace,
                options.workspaceArg() == null
                        ? " (resolved from current directory)" : "");
        // The name is deterministic, so the line doubles as the attach
        // recipe for a host terminal.
        LOG.log(INFO, "container {0} (attach: podman exec -it {0} bash)",
                containerName(workspace));

        relay(agentProcess.getInputStream(), agentProcess.getOutputStream(),
                System.in, System.out, workspace);
        agentProcess.waitFor();
        LOG.log(INFO, "agent exited with code {0}", agentProcess.exitValue());
        System.exit(agentProcess.exitValue());
    }

    /// Pumps both directions between the agent's and the client's
    /// streams, applying the intercepts.
    ///
    /// Returns when the agent's output ends (agent death); the
    /// client→agent pump is a daemon that ends with the client's
    /// stdin EOF or the JVM.
    static void relay(InputStream agentOut, OutputStream agentIn,
                      InputStream clientIn, OutputStream clientOut,
                      Path workspace) {
        AcpGate gate = new AcpGate(workspace);
        Writer agentWriter = new BufferedWriter(new OutputStreamWriter(agentIn, StandardCharsets.UTF_8));
        Writer clientWriter = new BufferedWriter(new OutputStreamWriter(clientOut, StandardCharsets.UTF_8));
        Thread agentToClient = Thread.ofVirtual().unstarted(
                () -> pumpAgentToClient(agentOut, clientWriter));
        agentToClient.setDaemon(true);
        agentToClient.start();
        Thread clientToAgent = Thread.ofVirtual().unstarted(
                () -> pumpClientToAgent(gate, clientIn, agentWriter));
        clientToAgent.setDaemon(true);
        clientToAgent.start();
        try {
            agentToClient.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// Client → agent: applies the outgoing intercepts; returns the line
    /// to forward (the original when untouched).
    String toAgent(String line) {
        try {
            if (!(Json.parse(line) instanceof JsonObject message)) {
                return line;
            }
            String method = methodOf(message);
            if ("initialize".equals(method)) {
                return stripFsWrite(message);
            }
            if ("session/new".equals(method) || "session/load".equals(method)) {
                return dropRemoteMcpServers(message);
            }
            return line;
        } catch (Exception e) {
            LOG.log(WARNING, "forwarding unparseable line as-is: {0}", e.getMessage());
            return line;
        }
    }

    /// Strips {@code fs/writeTextFile} from the client capabilities so
    /// the agent cannot invoke the IDE's write path.
    private String stripFsWrite(JsonObject message) {
        if (!(message.tryGet("params").orElse(null) instanceof JsonObject params)
                || !(params.tryGet("clientCapabilities").orElse(null) instanceof JsonObject capabilities)
                || !(capabilities.tryGet("fs").orElse(null) instanceof JsonObject fs)) {
            return message.toString();
        }
        LinkedHashMap<String, JsonValue> fsMap = new LinkedHashMap<>(fs.asMap());
        if (fsMap.remove("writeTextFile") == null) {
            return message.toString();
        }
        LinkedHashMap<String, JsonValue> capsMap = new LinkedHashMap<>(capabilities.asMap());
        capsMap.put("fs", JsonObject.of(fsMap));
        LinkedHashMap<String, JsonValue> paramsMap = new LinkedHashMap<>(params.asMap());
        paramsMap.put("clientCapabilities", JsonObject.of(capsMap));
        LinkedHashMap<String, JsonValue> messageMap = new LinkedHashMap<>(message.asMap());
        messageMap.put("params", JsonObject.of(paramsMap));
        LOG.log(INFO, "initialize: stripped fs/writeTextFile from the client capabilities");
        return JsonObject.of(messageMap).toString();
    }

    /// Drops the remote (http/sse) MCP server entries — the IDE's
    /// {@code nb} server is a side channel to the host. Stdio entries
    /// are kept (the agent ignores them anyway).
    private String dropRemoteMcpServers(JsonObject message) {
        if (!(message.tryGet("params").orElse(null) instanceof JsonObject params)) {
            return message.toString();
        }
        LinkedHashMap<String, JsonValue> paramsMap = new LinkedHashMap<>(params.asMap());
        boolean changed = false;
        if (paramsMap.get("mcpServers") instanceof JsonArray servers) {
            List<JsonValue> entries = servers.asList();
            List<JsonValue> kept = new ArrayList<>();
            for (JsonValue entry : entries) {
                if (isRemote(entry)) {
                    LOG.log(INFO, "session: dropped remote mcpServers entry {0}", entryName(entry));
                    continue;
                }
                kept.add(entry);
            }
            if (kept.size() != entries.size()) {
                paramsMap.put("mcpServers", JsonArray.of(kept));
                changed = true;
            }
        }
        if (!changed) {
            return message.toString();
        }
        LinkedHashMap<String, JsonValue> messageMap = new LinkedHashMap<>(message.asMap());
        messageMap.put("params", JsonObject.of(paramsMap));
        return JsonObject.of(messageMap).toString();
    }

    /// A remote (http/sse) MCP server entry — the only kind the agent
    /// actually connects to, and always a channel out to the host.
    private static boolean isRemote(JsonValue entry) {
        return entry instanceof JsonObject object
                && object.tryGet("type").filter(JsonString.class::isInstance)
                        .map(type -> {
                            String value = type.asString();
                            return "http".equals(value) || "sse".equals(value);
                        })
                        .orElse(false);
    }

    private static String entryName(JsonValue entry) {
        return entry instanceof JsonObject object
                ? object.tryGet("name").map(JsonValue::toString).orElse("?")
                : "?";
    }

    private static String methodOf(JsonObject message) {
        return message.tryGet("method")
                .filter(JsonString.class::isInstance)
                .map(value -> ((JsonString) value).asString())
                .orElse(null);
    }

    private static void pumpAgentToClient(InputStream agentOut, Writer clientWriter) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(agentOut, StandardCharsets.UTF_8));
             Writer out = clientWriter) {
            String line;
            while ((line = in.readLine()) != null) {
                out.write(line);
                out.write('\n');
                out.flush();
            }
        } catch (IOException e) {
            LOG.log(WARNING, "agent→client pump ended: {0}", e.getMessage());
        }
    }

    private static void pumpClientToAgent(AcpGate gate, InputStream clientIn, Writer agentWriter) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.UTF_8));
             Writer out = agentWriter) {
            String line;
            while ((line = in.readLine()) != null) {
                out.write(gate.toAgent(line));
                out.write('\n');
                out.flush();
            }
        } catch (IOException e) {
            LOG.log(WARNING, "client→agent pump ended: {0}", e.getMessage());
        }
    }

    /// Builds the podman command for the agent image.
    ///
    /// @param runtime the container runtime, e.g podman
    /// @param image the name of the container image
    /// @param workspace the workspace root the container runs in
    /// @param agent the harness, by name or absolute path
    /// @param execRoots the toolchain dirs to bind and grant to the launcher
    /// @param interactive the form: ACP gate (false) or TUI (true)
    /// @return the command as a list
    static List<String> containerCommand(String runtime, String image, Path workspace,
            String agent, List<Path> execRoots, boolean interactive) {
        // The profile is keyed by the binary name, so an absolute
        // --agent path still gets its harness's layout.
        HarnessProfile profile = HarnessProfile.of(Path.of(agent).getFileName().toString());
        Path hostAgent = hostAgent(agent, profile.binaryEnv());
        if (hostAgent == null) {
            throw new IllegalStateException("no host agent binary for container mode: " + agent
                    + (profile.binaryEnv() != null
                            ? " (not on PATH; set " + profile.binaryEnv()
                                    + " or pass an absolute --agent)"
                            : " (not on PATH; pass an absolute --agent)"));
        }
        return containerCommand(runtime, image, workspace, hostAgent, execRoots, interactive);
    }

    /// Same command without the {@code --exec-root} grants, ACP form
    /// (the form the tests use).
    static List<String> containerCommand(String runtime, String image, Path workspace, Path hostAgent) {
        return containerCommand(runtime, image, workspace, hostAgent, List.of(), false);
    }

    /// Same, ACP form, additionally binding the {@code --exec-root}
    /// dirs.
    static List<String> containerCommand(String runtime, String image, Path workspace,
            Path hostAgent, List<Path> execRoots) {
        return containerCommand(runtime, image, workspace, hostAgent, execRoots, false);
    }

    /// Assembles the container command: the {@code run} flags, the
    /// same-path mounts and ro pins, and the launcher's grants.
    ///
    /// The interactive (TUI) form differs in three places: {@code -it}
    /// instead of {@code -i} (the wrapper execs podman on the host
    /// terminal, so its size and resizes reach the in-container
    /// harness), the {@code nbmp-tui-} name prefix (a TUI can run next
    /// to a gate for the same workspace), and the bare harness argv
    /// (the TUI, not an ACP server, is the in-container process).
    ///
    /// The {@code --exec-root} grants follow the mounted roots
    /// ({@link #execRootMounts}); the state and project-config mounts
    /// follow the binary's {@link HarnessProfile}.
    static List<String> containerCommand(String runtime, String image, Path workspace,
            Path hostAgent, List<Path> execRoots, boolean interactive) {
        String agentName = hostAgent.getFileName().toString();
        HarnessProfile profile = HarnessProfile.of(agentName);
        String stateRootEnv = profile.stateRootEnv();
        if (stateRootEnv != null && System.getenv(stateRootEnv) != null) {
            LOG.log(WARNING,
                    "{0} is set on the host but not honored in the container: "
                            + "the agent keeps the classic XDG state locations "
                            + "(fresh state, not the host's)", stateRootEnv);
        }
        List<String> command = new ArrayList<>();
        command.add(runtime);
        command.add("run");
        command.add("--rm");
        // --name: deterministic per workspace (see containerName), so the
        // start-up log can carry the attach recipe; --init: an init as
        // PID 1 reaps the orphaned grandchild processes (the mvnd daemon)
        // our Java launcher never waits for; --read-only: everything the
        // container legitimately writes is on a bind mount or under /tmp,
        // so the rootfs can be immutable.
        command.add("--name");
        command.add(containerName(workspace, interactive ? "nbmp-tui" : "nbmp"));
        command.add("--init");
        command.add("--read-only");
        // -i: keep the container's stdin open and piped through. Without it
        // the in-container agent sees EOF on stdin at start and a stdio
        // agent (opencode acp) exits silently before answering initialize.
        // -it (the TUI form): additionally allocates a TTY; the wrapper
        // execs podman on the host terminal, so its size and resizes
        // reach the in-container harness.
        command.add(interactive ? "-it" : "-i");
        command.add("-v");
        command.add(workspace + ":" + workspace);
        command.add("-v");
        command.add(hostAgent + ":" + CONTAINER_BIN_DIR + agentName + ":ro");
        agentStateMounts(command, profile);
        gitConfigMount(command);
        agentConfigMounts(command, workspace, profile);
        gitExecSurfaceMounts(command, workspace);
        List<Path> roots = execRootMounts(command, workspace, execRoots);
        command.add(image);
        command.add("--workspace");
        command.add(workspace.toString());
        for (Path root : roots) {
            command.add("--exec-root");
            command.add(root.toString());
        }
        command.add("--");
        command.add(agentName);
        // The ACP form runs the agent's ACP server; the TUI form runs
        // the bare agent (its TUI owns the terminal).
        if (!interactive) {
            command.add("acp");
        }
        return command;
    }

    /// The ACP form's container name ({@link #containerName(Path, String)}
    /// with the gate's prefix).
    static String containerName(Path workspace) {
        return containerName(workspace, "nbmp");
    }

    /// The container's podman name: {@code prefix} + the workspace
    /// path slugged (non-alphanumerics to dashes; podman names allow
    /// letters, digits, {@code _}, {@code .}, {@code -}, capped at 255).
    ///
    /// Deterministic per workspace; a slug over the cap falls
    /// back to the basename plus a hash of the full path.
    static String containerName(Path workspace, String prefix) {
        String path = workspace.toAbsolutePath().toString();
        String slug = path.replaceAll("[^a-zA-Z0-9]+", "-");
        while (slug.startsWith("-")) {
            slug = slug.substring(1);
        }
        while (slug.endsWith("-")) {
            slug = slug.substring(0, slug.length() - 1);
        }
        if (slug.length() > 240) {
            String base = workspace.getFileName().toString()
                    .replaceAll("[^a-zA-Z0-9]+", "-");
            while (base.startsWith("-")) {
                base = base.substring(1);
            }
            if (base.length() > 200) {
                base = base.substring(0, 200);
            }
            slug = base + "-" + Integer.toHexString(path.hashCode());
        }
        return prefix + "-" + slug;
    }

    /// The same-path read-only mounts for the {@code --exec-root}
    /// dirs, so the agent reaches them at the host's absolute paths.
    ///
    /// Roots at or below the workspace (the rw workspace mount covers
    /// them) and missing roots (warned) are skipped.
    ///
    /// @return the mounted roots, for the caller's launcher grants
    static List<Path> execRootMounts(List<String> command, Path workspace,
            List<Path> execRoots) {
        List<Path> mounted = new ArrayList<>();
        for (Path root : execRoots) {
            if (root.startsWith(workspace)) {
                continue;
            }
            if (!Files.isDirectory(root)) {
                LOG.log(WARNING, "--exec-root {0} is not a directory: no mount, no grant", root);
                continue;
            }
            command.add("-v");
            command.add(root + ":" + root + ":ro");
            mounted.add(root);
        }
        return mounted;
    }

    /// The host agent binary the container mounts read-only: an
    /// absolute value as-is, else the harness's override env var, else
    /// the first hit on the host's PATH.
    ///
    /// @param binaryEnv the harness's override env var, or null
    /// @return the absolute host path, or null when unresolved
    static Path hostAgent(String agent, String binaryEnv) {
        Path direct = Path.of(agent);
        if (direct.isAbsolute()) {
            return direct;
        }
        String env = binaryEnv == null ? null : System.getenv(binaryEnv);
        if (env != null && !env.isBlank()) {
            return Path.of(env).toAbsolutePath();
        }
        String pathVar = System.getenv("PATH");
        if (pathVar == null) {
            return null;
        }
        for (String dir : pathVar.split(File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            Path candidate = Path.of(dir, agent);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath();
            }
        }
        return null;
    }

    /// The same-path mounts of the host agent's XDG state dirs plus
    /// the base-var overrides, so the in-container agent sees the
    /// host's models, auth, and sessions.
    ///
    /// The dirs come from the harness's {@link HarnessProfile}; the
    /// in-container launcher grants the same dirs to its domain
    /// through {@link dev.mbien.nbmp.mcp.linux.LandlockGuard#agentStateDirs}.
    /// The config dir is ro, data and state rw.
    static void agentStateMounts(List<String> command, HarnessProfile profile) {
        stateDirMounts(command, profile.stateDirs(
                Path.of(System.getProperty("user.home")), System::getenv));
    }

    /// The same-path bind mounts (source == target) plus the base-var
    /// overrides that point the in-container resolution at the host
    /// bases.
    static void stateDirMounts(List<String> command, List<HarnessProfile.StateDir> stateDirs) {
        for (HarnessProfile.StateDir dir : stateDirs) {
            if (!Files.isDirectory(dir.dir())) {
                continue;
            }
            command.add("-v");
            command.add(dir.dir() + ":" + dir.dir() + (dir.readOnly() ? ":ro" : ""));
            command.add("-e");
            command.add(dir.envVar() + "=" + dir.base());
        }
    }

    /// The XDG base dir the host uses for a var.
    ///
    /// The var's value when set, else the classic {@code $HOME}
    /// fallback.
    ///
    /// Mirrors {@link dev.mbien.nbmp.mcp.linux.LandlockGuard#addXdgDir}.
    private static Path xdgBase(String var, Path home, Path homeDefault) {
        String base = System.getenv(var);
        if (base != null && !base.isEmpty()) {
            return Path.of(base);
        }
        return home.resolve(homeDefault);
    }

    /// The read-only same-path mount of the host's global git config
    /// dir ({@code $XDG_CONFIG_HOME/git}).
    ///
    /// The launcher's copy ({@link SandboxLauncher#prepareGitConfig})
    /// resolves git's global cascade against this mount, via the
    /// {@code XDG_CONFIG_HOME} override from {@link #agentStateMounts},
    /// and pins it as {@code GIT_CONFIG_GLOBAL} — so in-domain git
    /// has the user's identity.
    static void gitConfigMount(List<String> command) {
        gitConfigMount(command, xdgBase(
                "XDG_CONFIG_HOME",
                Path.of(System.getProperty("user.home")), Path.of(".config")).resolve("git")
        );
    }

    /// The dir form of {@link #gitConfigMount(List<String>)}.
    ///
    /// A missing dir adds no mount (the container's git then proceeds without
    /// identity, as on a fresh account).
    static void gitConfigMount(List<String> command, Path dir) {
        if (Files.isDirectory(dir)) {
            command.add("-v");
            command.add(dir + ":" + dir + ":ro");
        }
    }

    /// The read-only same-path pins of the agent's project config
    /// files (the profile's {@code projectConfig}).
    ///
    /// A prompt-injected agent cannot rewrite its own sandbox config.
    /// The ro flag is VFS-level, independent of Landlock; a missing
    /// file adds no mount, the next session pins it.
    static void agentConfigMounts(List<String> command, Path workspace, HarnessProfile profile) {
        for (String name : profile.projectConfig()) {
            Path file = workspace.resolve(name);
            if (Files.isRegularFile(file)) {
                command.add("-v");
                command.add(file + ":" + file + ":ro");
            }
        }
    }

    /// The read-only same-path pins of git's exec surfaces.
    ///
    /// {@code .git/hooks}, {@code .git/config}, and the
    /// {@code .git/info/attributes} overlay, when present: git
    /// *executes* what it finds there, so they are a persistence
    /// channel — the attributes overlay one that also reaches the
    /// host's git. Everything else in {@code .git} stays writable; a
    /// worktree's {@code .git} file (not a dir) is skipped.
    static void gitExecSurfaceMounts(List<String> command, Path workspace) {
        Path gitDir = workspace.resolve(".git");
        if (!Files.isDirectory(gitDir)) {
            return;
        }
        Path hooks = gitDir.resolve("hooks");
        if (Files.isDirectory(hooks)) {
            command.add("-v");
            command.add(hooks + ":" + hooks + ":ro");
        }
        Path config = gitDir.resolve("config");
        if (Files.isRegularFile(config)) {
            command.add("-v");
            command.add(config + ":" + config + ":ro");
        }
        Path infoAttributes = gitDir.resolve("info", "attributes");
        if (Files.isRegularFile(infoAttributes)) {
            command.add("-v");
            command.add(infoAttributes + ":" + infoAttributes + ":ro");
        }
    }

    private static String value(String[] args, int i) {
        if (i + 1 >= args.length) {
            throw new IllegalArgumentException("missing value for " + args[i]);
        }
        return args[i + 1];
    }
}

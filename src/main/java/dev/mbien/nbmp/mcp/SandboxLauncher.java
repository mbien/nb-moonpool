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

import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;

import dev.mbien.nbmp.mcp.linux.LandlockGuard;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/// Starts the agent harness inside the kernel fs domain: the
/// launcher applies the domain to itself before the spawn, so the
/// harness — and everything it starts — inherits the domain and
/// {@code no_new_privs} across fork+exec. It is the sandbox's main
/// process: it waits for the harness and relays its exit code.
///
/// Before the spawn it creates the agent's XDG state dirs
/// ({@link LandlockGuard#agentStateDirs}), so the domain can grant
/// them and the harness can build its subdirectories inside; it
/// copies the user's git global config into the sandbox's private
/// {@code $HOME} and pins
/// {@code GIT_CONFIG_GLOBAL} at the copy ({@link #prepareGitConfig}),
/// so in-domain git reads the identity from the domain-covered copy.
///
/// Each {@code --exec-root <dir>} (repeatable) is granted read/exec
/// so the harness can run build tools that live outside the
/// workspace; the toolchain environment is derived from the roots
/// ({@link #toolchainEnv}) and the mvnd daemon storage pinned in
/// the managed state dir ({@link #pinMvndStorage}).
public class SandboxLauncher {

    private static final System.Logger LOG = System.getLogger(SandboxLauncher.class.getName());

    private static final List<String> DEFAULT_HARNESS = List.of("opencode");
    private static final Duration WAIT_CHUNK = Duration.ofMinutes(1);

    /// The workspace's managed state dir (the mvnd daemon storage):
    /// hidden, and domain-covered by the workspace root.
    static final String MANAGED_DIR = ".nbmp";

    private final ProcessStarter starter;

    public SandboxLauncher() {
        this(builder -> new DelegatingProcess(builder.start()));
    }

    /// Test seam: same launcher with an injectable process starter.
    SandboxLauncher(ProcessStarter starter) {
        this.starter = starter;
    }

    /// Starts the harness at the workspace as working directory: the
    /// argv wrapped in {@link #stdinShim(List)}, the mvnd daemon
    /// storage and {@code user.home} pinned.
    ///
    /// @param workspace the domain root; also the harness's working directory
    /// @param harness the harness command (argv), resolved on {@code PATH}
    /// @return the harness exit code
    int launch(Path workspace, List<String> harness) throws IOException, InterruptedException {
        return launch(workspace, harness, null);
    }

    /// Same launch, pinning {@code GIT_CONFIG_GLOBAL} at the given git
    /// global config copy (null = no pin).
    int launch(Path workspace, List<String> harness, Path gitConfigCopy)
            throws IOException, InterruptedException {
        return launch(workspace, harness, gitConfigCopy, List.of());
    }

    /// Same launch, additionally deriving the toolchain environment
    /// ({@code JAVA_HOME}, {@code PATH}) from the {@code --exec-root}
    /// dirs ({@link #toolchainEnv}).
    int launch(Path workspace, List<String> harness, Path gitConfigCopy,
            List<Path> execRoots) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(stdinShim(harness));
        builder.directory(workspace.toFile());
        builder.inheritIO();
        if (gitConfigCopy != null) {
            builder.environment().put("GIT_CONFIG_GLOBAL", gitConfigCopy.toString());
        }
        pinMvndStorage(builder, workspace);
        toolchainEnv(builder, execRoots);
        pinUserHome(builder);
        try (ProcessProxy process = starter.start(builder)) {
            while (!process.waitFor(WAIT_CHUNK)) {
                // the harness is the main workload: unbounded
            }
            return process.exitValue();
        }
    }

    /// The shell command behind {@link #stdinShim(List)}: it reads the
    /// harness's first stdin line itself, so the line is pending in the
    /// pipe before the agent subscribes.
    ///
    /// A TTY stdin (an interactive client, the opencode TUI) takes the
    /// first branch and executes the harness as-is: there is no line
    /// protocol to fix, and the TUI owns the terminal. Otherwise, the
    /// ACP agent (opencode) subscribes to its stdin only after the
    /// serve process it spawns is ready; a line arriving in that narrow
    /// window is lost inside the agent's runtime stream handling and
    /// never retried, while a line pending in the pipe before the
    /// subscription is always delivered. The shim's blocking read is
    /// immune to the race: it holds the line in a shell variable and
    /// only then starts the agent with the line pre-written into its
    /// stdin pipe. {@code cat} then forwards the remaining lines (the
    /// pipe stays open until EOF), so a live session is unaffected and
    /// the agent's exit code still reaches the launcher. A harness
    /// whose stdin is already at EOF (one-shot forms like
    /// {@code opencode run}) takes the no-line branch: the shim degrades
    /// to a plain {@code cat}, so the harness sees EOF exactly as it
    /// would without the shim.
    static final String STDIN_SHIM =
            "[ -t 0 ] && exec \"$@\"; "
                    + "if IFS= read -r __nbmp_first; then (printf \"%s\\n\" \"$__nbmp_first\"; exec cat); "
                    + "else exec cat; fi | exec \"$@\"";

    /// Wraps the harness argv in {@link #STDIN_SHIM}.
    ///
    /// {@code sh -c} with the harness as positional parameters
    /// ({@code $0} = {@code sh}, {@code $1..} = the harness). The
    /// line-buffering branch assumes a line-oriented stdio protocol
    /// (ACP JSON-RPC), which every pipe-fed harness is.
    static List<String> stdinShim(List<String> harness) {
        List<String> command = new ArrayList<>();
        command.add("sh");
        command.add("-c");
        command.add(STDIN_SHIM);
        command.add("sh");
        command.addAll(harness);
        return command;
    }

    /// Pins the mvnd daemon's storage at the workspace's managed state
    /// dir ({@code .nbmp/mvnd}).
    ///
    /// Mvnd's default storage (a versioned folder under
    /// {@code $HOME/.m2}) is unreachable in the domain, and the
    /// daemon's registry and logs must sit where both the client and the
    /// daemon it starts can reach them. The storage is per workspace, so
    /// a warm daemon is reusable across sessions of the same project but
    /// can never be picked up by another workspace's domain.
    private static void pinMvndStorage(ProcessBuilder builder, Path workspace) {
        Path storage = workspace.resolve(MANAGED_DIR).resolve("mvnd");
        try {
            Files.createDirectories(storage);
        } catch (IOException e) {
            LOG.log(WARNING, "cannot create mvnd daemon storage {0} -- {1}", storage, e.getMessage());
        }
        builder.environment().put("MVND_DAEMON_STORAGE", storage.toString());
    }

    /// Derives the toolchain environment from the exec roots.
    ///
    /// Sets {@code JAVA_HOME} at the first root with a
    /// {@code bin/java}, and prepends each root's {@code bin/} to
    /// {@code PATH}, plus the mvnd dist's nested {@code mvn/bin} (it
    /// bundles a full Maven one level down). Roots without a
    /// {@code bin/} add nothing.
    private static void toolchainEnv(ProcessBuilder builder, List<Path> execRoots) {
        List<String> bins = new ArrayList<>();
        Path jdk = null;
        for (Path root : execRoots) {
            Path bin = root.resolve("bin");
            if (Files.isDirectory(bin)) {
                bins.add(bin.toString());
            }
            // The mvnd distribution nests a full Maven below the client
            // (mvn/bin/mvn): reach it by bare name from the same dist.
            Path nestedMvn = root.resolve("mvn", "bin");
            if (Files.isDirectory(nestedMvn)) {
                bins.add(nestedMvn.toString());
            }
            if (jdk == null && Files.isRegularFile(bin.resolve("java"))) {
                jdk = root;
            }
        }
        if (jdk != null) {
            builder.environment().put("JAVA_HOME", jdk.toString());
        }
        if (!bins.isEmpty()) {
            String path = String.join(File.pathSeparator, bins);
            String existing = builder.environment().get("PATH");
            if (existing != null && !existing.isEmpty()) {
                path = path + File.pathSeparator + existing;
            }
            builder.environment().put("PATH", path);
        }
    }

    /// Pins {@code user.home} at the harness's {@code $HOME}, through
    /// {@code JDK_JAVA_OPTIONS}.
    ///
    /// The sandbox's {@code $HOME} is a private tmpfs, but recent JDKs
    /// take {@code user.home} from the passwd entry, not {@code $HOME},
    /// so every JVM the harness spawns (mvn, the mvnd daemon, test
    /// forks) would resolve {@code ~/.m2} at the read-only rootfs
    /// {@code /root}. {@code JDK_JAVA_OPTIONS} is picked up by every
    /// HotSpot JVM, so one pin here points them all back at the
    /// harness's {@code $HOME}; the explicit {@code -Dmaven.repo.local}
    /// stays the warm path.
    private static void pinUserHome(ProcessBuilder builder) {
        String home = builder.environment().get("HOME");
        if (home == null || home.isEmpty()) {
            return;
        }
        String option = "-Duser.home=" + home;
        String existing = builder.environment().get("JDK_JAVA_OPTIONS");
        if (existing == null || existing.isEmpty()) {
            builder.environment().put("JDK_JAVA_OPTIONS", option);
            return;
        }
        for (String opt : existing.split("\\s+")) {
            if (opt.equals(option)) {
                return; // already pinned (idempotent across nested launches)
            }
        }
        builder.environment().put("JDK_JAVA_OPTIONS", existing + " " + option);
    }

    /// Git's global-config cascade, resolved over the given env values:
    /// {@code $GIT_CONFIG_GLOBAL} if set, else
    /// {@code $XDG_CONFIG_HOME/git/config} if {@code $XDG_CONFIG_HOME}
    /// is set — the XDG file replaces the HOME one, there is no
    /// fallback — else {@code $HOME/.gitconfig}, and when that file is
    /// missing the default XDG base {@code $HOME/.config/git/config}
    /// (git, with the base var unset, reads the classic file first and
    /// the default base when the classic is missing).
    ///
    /// @return the path git resolves, or null when no env leg resolves
    static Path globalGitConfig(String gitConfigGlobal, String xdgConfigHome, String home) {
        if (gitConfigGlobal != null && !gitConfigGlobal.isEmpty()) {
            return Path.of(gitConfigGlobal);
        }
        if (xdgConfigHome != null && !xdgConfigHome.isEmpty()) {
            return Path.of(xdgConfigHome, "git", "config");
        }
        if (home != null && !home.isEmpty()) {
            Path classic = Path.of(home, ".gitconfig");
            if (!Files.isRegularFile(classic)) {
                return Path.of(home, ".config", "git", "config");
            }
            return classic;
        }
        return null;
    }

    /// The process-environment form of
    /// {@link #globalGitConfig(String, String, String)}.
    static Path globalGitConfig() {
        return globalGitConfig(System.getenv("GIT_CONFIG_GLOBAL"),
                System.getenv("XDG_CONFIG_HOME"), System.getenv("HOME"));
    }

    /// Copies the user's git global config into the sandbox's
    /// {@code $HOME}, so the harness's git — which runs in the domain,
    /// where a Landlock rule cannot grant the real config file itself
    /// (path rules require a directory parent) — reads the user's
    /// identity defaults.
    ///
    /// The target sits in the container's private tmpfs home under
    /// {@code /tmp}, a {@code FULL_FS} domain root in every form:
    /// nothing has to be granted for it, and no persistence is needed
    /// (the copy lives exactly one session, and the container dies with
    /// it). The copy lands at git's classic location, and
    /// {@code GIT_CONFIG_GLOBAL} pins it there regardless of the XDG
    /// base var the agent-state mounts may carry in.
    ///
    /// Run before the domain installs: reading the source is the last
    /// unprivileged act.
    ///
    /// @param home the sandbox's {@code $HOME} (the container's private
    ///         tmpfs home)
    /// @return the copy to pin {@code GIT_CONFIG_GLOBAL} at, or null when
    ///         the user has no global config (git then proceeds without
    ///         one, as on a fresh account)
    static Path prepareGitConfig(Path home) {
        Path source = globalGitConfig();
        if (source == null || !Files.isRegularFile(source)) {
            return null;
        }
        Path target = home.resolve(".gitconfig");
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            LOG.log(WARNING, "cannot copy the git global config {0} -> {1} -- {2}",
                    source, target, e.getMessage());
            return null;
        }
    }

    /// The {@code $HOME} form of {@link #prepareGitConfig(Path)}.
    static Path prepareGitConfig() {
        String home = System.getenv("HOME");
        if (home == null || home.isBlank()) {
            return null;
        }
        return prepareGitConfig(Path.of(home));
    }

    public static void main(String[] args) {
        Log.init();
        try {
            run(args);
        } catch (Exception e) {
            LOG.log(ERROR, e.getMessage());
            System.exit(1);
        }
    }

    /// The CLI: {@code --workspace <dir>} (default: the current
    /// directory), repeatable {@code --config-root <dir>} and
    /// {@code --exec-root <dir>} grants, and the harness argv after
    /// {@code --} (default: {@code opencode}).
    private static void run(String[] args) throws Exception {
        Path workspace = Path.of(".");
        List<Path> configRoots = new ArrayList<>();
        List<Path> execRoots = new ArrayList<>();
        List<String> harness = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--workspace" -> {
                    workspace = Path.of(value(args, i)).toAbsolutePath();
                    i++;
                }
                case "--config-root" -> {
                    configRoots.add(Path.of(value(args, i)).toAbsolutePath());
                    i++;
                }
                case "--exec-root" -> {
                    execRoots.add(Path.of(value(args, i)).toAbsolutePath());
                    i++;
                }
                case "--" -> {
                    harness.addAll(List.of(Arrays.copyOfRange(args, i + 1, args.length)));
                    i = args.length;
                }
                default -> throw new IllegalArgumentException(
                        "unknown option: " + args[i] + " (options: --workspace <dir>, "
                                + "--config-root <dir>, --exec-root <dir>, -- <harness argv>)");
            }
        }
        if (harness.isEmpty()) {
            harness = DEFAULT_HARNESS;
        }
        if (!Files.isDirectory(workspace)) {
            throw new IllegalArgumentException("workspace is not a directory: " + workspace);
        }
        for (Path root : configRoots) {
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("config root is not a directory: " + root);
            }
        }
        for (Path root : execRoots) {
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("exec root is not a directory: " + root);
            }
        }
        for (Path dir : LandlockGuard.agentStateDirs(harness.getFirst())) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                LOG.log(WARNING, "cannot create agent state dir {0} -- {1}", dir, e.getMessage());
            }
        }
        Path gitConfigCopy = prepareGitConfig();
        LandlockGuard.apply(workspace, LandlockGuard.launcherRoots(harness, configRoots, execRoots));
        System.exit(new SandboxLauncher().launch(workspace, harness, gitConfigCopy,
                execRoots));
    }

    private static String value(String[] args, int i) {
        if (i + 1 >= args.length) {
            throw new IllegalArgumentException("missing value for " + args[i]);
        }
        return args[i + 1];
    }
}

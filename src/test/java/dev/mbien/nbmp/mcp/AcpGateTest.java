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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import jdk.incubator.json.Json;
import jdk.incubator.json.JsonArray;
import jdk.incubator.json.JsonNumber;
import jdk.incubator.json.JsonObject;
import jdk.incubator.json.JsonString;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Hermetic AcpGate tests: piped streams in place of the agent process
/// and the IDE client — no processes, no container, no model. The
/// intercept points are asserted against canned ACP lines; everything
/// else is asserted byte-transparent.
class AcpGateTest {

    /// The relay under test and its two stream pairs: the client side
    /// (the test feeds lines in and collects the relay's outbound lines)
    /// and the fake agent side (the test plays the agent and collects the
    /// lines the relay forwards to it).
    private final class Harness implements AutoCloseable {
        final PipedOutputStream clientIn = new PipedOutputStream();
        final PipedInputStream clientOut = new PipedInputStream();
        final PipedOutputStream agentOut = new PipedOutputStream();
        final PipedInputStream agentIn = new PipedInputStream();
        final PipedInputStream relayClientIn = new PipedInputStream(clientIn);
        final PipedOutputStream relayClientOut = new PipedOutputStream(clientOut);
        final PipedInputStream relayAgentOut = new PipedInputStream(agentOut);
        final PipedOutputStream relayAgentIn = new PipedOutputStream(agentIn);
        final List<String> agentReceived = new CopyOnWriteArrayList<>();
        final List<String> clientReceived = new CopyOnWriteArrayList<>();
        final Thread relay;

        Harness(Path workspace) throws IOException {
            collector(agentIn, agentReceived);
            collector(clientOut, clientReceived);
            relay = new Thread(() -> AcpGate.relay(
                    relayAgentOut, relayAgentIn, relayClientIn, relayClientOut,
                    workspace));
            relay.setDaemon(true);
            relay.start();
        }

        void clientSends(String line) throws IOException {
            send(clientIn, line);
        }

        void agentSends(String line) throws IOException {
            send(agentOut, line);
        }

        /// Agent death: its output ends and the relay returns.
        void agentDies() throws Exception {
            agentOut.close();
            relay.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(relay.isAlive(), "relay did not end after agent death");
        }

        @Override
        public void close() throws IOException {
            clientIn.close();
            agentOut.close();
            agentIn.close();
            clientOut.close();
        }
    }

    private static Thread collector(InputStream in, List<String> sink) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sink.add(line);
                }
            } catch (IOException ignored) {
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void send(PipedOutputStream out, String line) throws IOException {
        out.write(line.getBytes(StandardCharsets.UTF_8));
        out.write('\n');
        out.flush();
    }

    @Test
    void appliesAllIntercepts(@TempDir Path workspace) throws Exception {
        try (Harness h = new Harness(workspace)) {
            // 1. initialize: fs/writeTextFile stripped, the rest intact
            String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                    + "{\"protocolVersion\":1,\"clientCapabilities\":"
                    + "{\"fs\":{\"readTextFile\":true,\"writeTextFile\":true},\"terminal\":false},"
                    + "\"clientInfo\":{\"name\":\"ide-plugin\",\"version\":\"1.26.1\"}}}";
            h.clientSends(init);
            JsonObject forwarded = (JsonObject) Json.parse(awaitNext(h.agentReceived, "initialize"));
            JsonObject caps = (JsonObject) ((JsonObject) forwarded.get("params")).get("clientCapabilities");
            JsonObject fs = (JsonObject) caps.get("fs");
            assertFalse(fs.tryGet("writeTextFile").isPresent(), "writeTextFile must be stripped");
            assertTrue(fs.tryGet("readTextFile").isPresent(), "readTextFile must survive");
            assertTrue(caps.tryGet("terminal").isPresent(), "terminal capability must survive");
            assertEquals(1, ((JsonNumber) forwarded.get("id")).asInt(), "the id must pass through");
            assertEquals("ide-plugin",
                    ((JsonString) ((JsonObject) ((JsonObject) forwarded.get("params")).get("clientInfo")).get("name")).asString());

            // 2. session/new: cwd passes through (the workspace is mounted at
            //    its host path, so the host's session history stays visible),
            //    remote mcp entry dropped, stdio kept
            String session = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session/new\",\"params\":"
                    + "{\"cwd\":\"/home/u/proj\",\"mcpServers\":["
                    + "{\"name\":\"nb\",\"type\":\"http\",\"url\":\"http://127.0.0.1:8765/mcp\",\"headers\":[]},"
                    + "{\"name\":\"local\",\"type\":\"stdio\",\"command\":[\"x\"]}]}}";
            h.clientSends(session);
            JsonObject sessionParams = (JsonObject) ((JsonObject) Json.parse(
                    awaitNext(h.agentReceived, "session/new"))).get("params");
            assertEquals("/home/u/proj", ((JsonString) sessionParams.get("cwd")).asString());
            JsonArray servers = (JsonArray) sessionParams.get("mcpServers");
            assertEquals(1, servers.asList().size(), "the remote entry must be dropped");
            assertEquals("local", ((JsonString) ((JsonObject) servers.get(0)).get("name")).asString());

            // 3. prompt: byte-transparent (no gate; the agent's tools are its own)
            String prompt = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"session/prompt\",\"params\":"
                    + "{\"sessionId\":\"s1\",\"prompt\":[{\"type\":\"text\",\"text\":\"hi\"}]}}";
            h.clientSends(prompt);
            assertEquals(prompt, awaitNext(h.agentReceived, "session/prompt"));

            // 4. fs/* passes through byte-transparent in container mode
            //    (same-path workspace mount: the agent's paths are host paths)
            String fsRead = "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"fs/readTextFile\",\"params\":"
                    + "{\"path\":\"/home/u/proj/src/Foo.java\"}}";
            h.agentSends(fsRead);
            assertEquals(fsRead, awaitNext(h.clientReceived, "fs/readTextFile"));

            // 5. everything else byte-transparent
            String update = "{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":"
                    + "{\"sessionId\":\"s1\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\"}}}";
            h.agentSends(update);
            assertEquals(update, awaitNext(h.clientReceived, "session/update"));

            h.agentDies();
        }
    }

    /// The container command binds the host agent binary read-only into the
    /// container at {@code /usr/local/bin/<name>} (the image carries no
    /// agent binary) and hands the launcher the bare name: in the
    /// container it resolves on PATH to the mounted file, and the launcher
    /// domain grants its parent dir read/exec. The workspace is mounted at
    /// its host path (the agent keys sessions per project worktree, so the
    /// host's session history must stay visible) and the launcher gets the
    /// host path as well.
    @Test
    void containerCommandMountsTheHostAgentBinary() {
        List<String> command = AcpGate.containerCommand(
                "podman", "img", Path.of("/ws"), Path.of("/usr/bin/opencode"));

        int mount = command.indexOf("/usr/bin/opencode:/usr/local/bin/opencode:ro");
        assertTrue(mount > 0, "the host agent binary must be bind-mounted read-only");
        assertEquals("-v", command.get(mount - 1), "the mount is a -v argument");
        int ws = command.indexOf("/ws:/ws");
        assertTrue(ws > 0, "the workspace must be bind-mounted at its host path");
        assertEquals("-v", command.get(ws - 1), "the workspace mount is a -v argument");
        int wsArg = command.indexOf("--workspace");
        assertEquals("/ws", command.get(wsArg + 1), "the launcher gets the host workspace path");
        int sep = command.lastIndexOf("--");
        assertEquals("opencode", command.get(sep + 1), "the harness argv carries the bare name");
        assertEquals("acp", command.get(sep + 2));
        int name = command.indexOf("--name");
        assertTrue(name > 0, "the container must carry a deterministic name");
        assertEquals("nbmp-ws", command.get(name + 1),
                "the name derives from the workspace path");
        assertTrue(command.contains("--init"),
                "an init must reap the launcher's orphaned grandchildren");
        assertTrue(command.contains("--read-only"),
                "the rootfs must be read-only; persistence lives on the bind mounts");
        assertTrue(command.contains("-i"), "stdin stays piped through");
    }

    /// The goose harness assembles through the same command: the binary
    /// is host-mounted, the ACP form runs {@code goose acp}, and the
    /// state mounts follow the goose profile (the classic {@code goose}
    /// subpath -- asserted in HarnessProfileTest, since their presence
    /// here depends on the test host's XDG dirs).
    @Test
    void containerCommandAssemblesTheGooseHarness() {
        List<String> command = AcpGate.containerCommand(
                "podman", "img", Path.of("/ws"), Path.of("/usr/local/bin/goose"));

        int mount = command.indexOf("/usr/local/bin/goose:/usr/local/bin/goose:ro");
        assertTrue(mount > 0, "the host goose binary must be bind-mounted read-only");
        assertEquals("-v", command.get(mount - 1), "the mount is a -v argument");
        int sep = command.lastIndexOf("--");
        assertEquals("goose", command.get(sep + 1), "the harness argv carries the bare name");
        assertEquals("acp", command.get(sep + 2));
        int name = command.indexOf("--name");
        assertEquals("nbmp-ws", command.get(name + 1),
                "the name derives from the workspace path");
    }

    /// The TUI form of the command: the same assembly as the ACP form,
    /// with the three interactive deltas — {@code -it} instead of
    /// {@code -i} (the wrapper execs podman on the host terminal), the
    /// {@code nbmp-tui-} name prefix (a TUI runs next to a gate for
    /// the same workspace), and the bare harness argv (the TUI, not an
    /// ACP server, is the in-container process).
    @Test
    void containerCommandInteractiveForm() {
        List<String> command = AcpGate.containerCommand(
                "podman", "img", Path.of("/ws"), Path.of("/usr/bin/opencode"),
                List.of(), true);

        assertTrue(command.contains("-it"), "the TUI form allocates a TTY");
        assertFalse(command.contains("-i"),
                "the TUI form does not use the piped-stdin flag");
        int name = command.indexOf("--name");
        assertEquals("nbmp-tui-ws", command.get(name + 1),
                "the TUI container is named nbmp-tui- + slug");
        int sep = command.lastIndexOf("--");
        assertEquals("opencode", command.get(sep + 1),
                "the harness argv carries the bare name");
        assertEquals(sep + 2, command.size(),
                "the bare harness argv: no acp subcommand");
    }

    /// The container command honors --exec-root too: each existing root is
    /// bind-mounted read-only at its host path (the agent keeps the
    /// host's absolute paths) and granted to the in-container launcher
    /// via its --exec-root, which sits among the launcher options —
    /// after the image, before the harness argv.
    @Test
    void containerCommandForwardsExecRoots(@TempDir Path workspace, @TempDir Path root) {
        List<String> command = AcpGate.containerCommand(
                "podman", "img", workspace, Path.of("/usr/bin/opencode"), List.of(root));

        int mount = command.indexOf(root + ":" + root + ":ro");
        assertTrue(mount > 0, "the exec root must be bind-mounted read-only at its host path");
        assertEquals("-v", command.get(mount - 1), "the mount is a -v argument");
        int grantValue = command.indexOf(root.toString());
        assertTrue(grantValue > 0, "the launcher must get the exec root grant");
        assertEquals("--exec-root", command.get(grantValue - 1));
        assertTrue(grantValue > command.indexOf("img")
                && grantValue < command.lastIndexOf("--"),
                "the grant sits among the launcher options, before the harness argv");
    }

    /// A --exec-root that is missing on the host degrades to absent
    /// (with a warning) rather than failing the container start or
    /// letting podman create the host dir.
    @Test
    void containerCommandSkipsAMissingExecRoot(@TempDir Path workspace) {
        Path missing = Path.of("/definitely/missing/nbmp-toolchain");

        List<String> command = AcpGate.containerCommand(
                "podman", "img", workspace, Path.of("/usr/bin/opencode"), List.of(missing));

        assertFalse(command.contains(missing + ":" + missing + ":ro"),
                "a missing root must not be mounted");
        assertFalse(command.contains(missing.toString()),
                "a missing root must not be granted");
    }

    /// A --exec-root that is the workspace or lies below it adds nothing:
    /// the read-write workspace mount already covers it, and an extra
    /// read-only mount would only shrink its permissions.
    @Test
    void containerCommandSkipsExecRootsUnderTheWorkspace(@TempDir Path workspace) throws IOException {
        Path sub = workspace.resolve("tools");
        Files.createDirectories(sub);

        List<String> command = AcpGate.containerCommand(
                "podman", "img", workspace, Path.of("/usr/bin/opencode"),
                List.of(workspace, sub));

        assertFalse(command.contains(workspace + ":" + workspace + ":ro"),
                "the workspace must not be mounted again read-only");
        assertFalse(command.contains(sub + ":" + sub + ":ro"),
                "a root under the workspace must not be mounted again");
        assertFalse(command.contains(sub.toString()),
                "a root under the workspace must not be granted separately");
    }

    /// The container name is nbmp- + the workspace path with every run
    /// of non-alphanumerics collapsed to a dash (leading dashes dropped);
    /// a slug overrunning podman's 255-char cap falls back to the
    /// basename plus a hash of the full path.
    @Test
    void containerNameDerivesFromTheWorkspacePath() {
        assertEquals("nbmp-ws", AcpGate.containerName(Path.of("/ws")));
        assertEquals("nbmp-home-mbien-nbmp",
                AcpGate.containerName(Path.of("/home/mbien/nbmp")));
        // A workspace resolved from the current directory carries a
        // /./ suffix — the trailing non-alphanumeric must not become a
        // dangling dash.
        assertEquals("nbmp-home-mbien-nbmp",
                AcpGate.containerName(Path.of("/home/mbien/nbmp/.")));
        Path deep = Path.of("/home/mbien/"
                + "very-long-directory-name/".repeat(15) + "project");
        String name = AcpGate.containerName(deep);
        assertTrue(name.length() <= 255, "podman names are capped at 255 chars");
        assertTrue(name.startsWith("nbmp-project-"),
                "an over-long path falls back to the basename plus a hash");
    }

    /// An absolute --agent value is used as is (it does not go through the
    /// PATH search or the binary-override env var).
    @Test
    void hostAgentUsesAnAbsoluteAgentValueAsIs() {
        assertEquals(Path.of("/opt/agents/opencode"),
                AcpGate.hostAgent("/opt/agents/opencode", "NBMP_CTR_OPENCODE"));
    }

    /// State-dir same-path mounts: the host dir is mounted at its
    /// identical path (source == target, so the agent sees the host's
    /// absolute paths) plus the base-var override pointing at the host
    /// base. A missing dir adds neither -- it degrades to the
    /// container default (fresh state) rather than failing the mount.
    @Test
    void stateDirMountsBindTheDirAtItsSamePath(@TempDir Path home) throws IOException {
        Path configDir = home.resolve(".config").resolve("opencode");
        Path dataDir = home.resolve(".local", "share").resolve("opencode");
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);
        List<String> command = new ArrayList<>();

        AcpGate.stateDirMounts(command, List.of(
                new HarnessProfile.StateDir("XDG_CONFIG_HOME",
                        home.resolve(".config"), configDir, true),
                new HarnessProfile.StateDir("XDG_DATA_HOME",
                        home.resolve(".local", "share"), dataDir, false)));

        assertEquals(List.of(
                "-v", configDir + ":" + configDir + ":ro",
                "-e", "XDG_CONFIG_HOME=" + home.resolve(".config"),
                "-v", dataDir + ":" + dataDir,
                "-e", "XDG_DATA_HOME=" + home.resolve(".local", "share")), command);
    }

    /// A missing state dir adds neither the mount nor the env var.
    @Test
    void stateDirMountsSkipAMissingDir(@TempDir Path home) {
        List<String> command = new ArrayList<>();

        AcpGate.stateDirMounts(command, List.of(new HarnessProfile.StateDir(
                "XDG_CONFIG_HOME", home.resolve(".config"),
                home.resolve(".config", "opencode"), true)));

        assertTrue(command.isEmpty(), "a missing host dir adds no mount or env var");
    }

    /// The host user's global git config dir (git's XDG location) is
    /// bind-mounted read-only at its same path, so the in-container
    /// launcher's config copy sources the user's git identity from it
    /// (the launcher then pins GIT_CONFIG_GLOBAL at the copy in the
    /// sandbox's $HOME before the domain installs).
    @Test
    void gitConfigMountBindsTheDirAtItsSamePath(@TempDir Path home) throws IOException {
        Path gitDir = home.resolve(".config", "git");
        Files.createDirectories(gitDir);
        List<String> command = new ArrayList<>();

        AcpGate.gitConfigMount(command, gitDir);

        assertEquals(List.of("-v", gitDir + ":" + gitDir + ":ro"), command);
    }

    /// A missing git config dir adds no mount -- the container's git
    /// proceeds without identity, as on a fresh account.
    @Test
    void gitConfigMountSkipsAMissingDir(@TempDir Path home) {
        List<String> command = new ArrayList<>();

        AcpGate.gitConfigMount(command, home.resolve(".config", "git"));

        assertTrue(command.isEmpty(), "a missing git config dir adds no mount");
    }

    /// The agent's project config files are bind-mounted read-only at
    /// their same paths (over the rw workspace mount), so the in-domain
    /// agent cannot rewrite its own sandbox config -- a planted local
    /// MCP server entry would otherwise be honored in every future
    /// session.
    @Test
    void agentConfigMountsBindExistingFilesReadOnly(@TempDir Path workspace) throws IOException {
        Path json = workspace.resolve("opencode.json");
        Files.createFile(json);
        Path jsonc = workspace.resolve("opencode.jsonc");
        Files.createFile(jsonc);
        List<String> command = new ArrayList<>();

        AcpGate.agentConfigMounts(command, workspace, HarnessProfile.OPENCODE);

        assertEquals(List.of("-v", json + ":" + json + ":ro",
                "-v", jsonc + ":" + jsonc + ":ro"), command);
    }

    /// The pinned files are the harness's: goose has no known
    /// workspace project config, so an opencode.json in the workspace
    /// gets no pin for it.
    @Test
    void agentConfigMountsFollowTheHarnessProfile(@TempDir Path workspace)
            throws IOException {
        Path json = workspace.resolve("opencode.json");
        Files.createFile(json);
        List<String> command = new ArrayList<>();

        AcpGate.agentConfigMounts(command, workspace, HarnessProfile.GOOSE);

        assertTrue(command.isEmpty(), "goose has no project config to pin");
    }

    /// No config file, no mount: the agent (or the user) may still
    /// create one; the next session pins it.
    @Test
    void agentConfigMountsSkipMissingFiles(@TempDir Path workspace) {
        List<String> command = new ArrayList<>();

        AcpGate.agentConfigMounts(command, workspace, HarnessProfile.OPENCODE);

        assertTrue(command.isEmpty(), "no config file adds no mount");
    }

    /// Git's exec surfaces (.git/hooks, .git/config,
    /// .git/info/attributes) are pinned read-only at their same paths:
    /// git executes what it finds there (hooks; alias/!, fsmonitor,
    /// hooksPath; inline smudge/clean commands), so a planted exec
    /// vector would otherwise persist across sessions -- and the
    /// attributes overlay is read by the user's host-side git, too.
    /// The rest of .git stays writable -- git needs it.
    @Test
    void gitExecSurfaceMountsBindTheExecSurfacesReadOnly(@TempDir Path workspace) throws IOException {
        Path gitDir = workspace.resolve(".git");
        Path hooks = gitDir.resolve("hooks");
        Files.createDirectories(hooks);
        Path config = gitDir.resolve("config");
        Files.createFile(config);
        Path infoAttrs = gitDir.resolve("info", "attributes");
        Files.createDirectories(infoAttrs.getParent());
        Files.createFile(infoAttrs);
        List<String> command = new ArrayList<>();

        AcpGate.gitExecSurfaceMounts(command, workspace);

        assertEquals(List.of("-v", hooks + ":" + hooks + ":ro",
                "-v", config + ":" + config + ":ro",
                "-v", infoAttrs + ":" + infoAttrs + ":ro"), command);
    }

    /// A worktree's .git is a file, not a dir -- its real gitdir may
    /// sit outside the workspace, so no mount.
    @Test
    void gitExecSurfaceMountsSkipAWorktreeDotGitFile(@TempDir Path workspace) throws IOException {
        Files.createFile(workspace.resolve(".git"));
        List<String> command = new ArrayList<>();

        AcpGate.gitExecSurfaceMounts(command, workspace);

        assertTrue(command.isEmpty(), "a worktree's .git file adds no mount");
    }

    /// A .git dir without the exec surfaces (fresh or partial) adds no
    /// mount.
    @Test
    void gitExecSurfaceMountsSkipMissingSurfaces(@TempDir Path workspace) throws IOException {
        Files.createDirectories(workspace.resolve(".git"));
        List<String> command = new ArrayList<>();

        AcpGate.gitExecSurfaceMounts(command, workspace);

        assertTrue(command.isEmpty(), "missing exec surfaces add no mount");
    }

    /// Without --workspace the gate resolves the workspace from its own
    /// current directory: a gate spawned from the project root needs no
    /// flag at all.
    @Test
    void resolveWorkspaceDefaultsToTheCurrentDirectory() {
        assertEquals(Path.of(".").toAbsolutePath(), AcpGate.resolveWorkspace(null));
    }

    /// A given --workspace value wins over the current directory (and is
    /// made absolute, as before).
    @Test
    void resolveWorkspacePrefersTheFlagValue() {
        assertEquals(Path.of("/ws"), AcpGate.resolveWorkspace("/ws"));
    }

    private static String awaitNext(List<String> sink, String what) {
        awaitUntil(() -> !sink.isEmpty(), "a line for " + what);
        String line = sink.remove(0);
        if (!line.contains(what)) {
            fail("expected a line containing " + what + " but got: " + line);
        }
        return line;
    }

    private static void awaitUntil(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() >= deadline) {
                fail("timed out waiting for " + what);
            }
            try {
                TimeUnit.MILLISECONDS.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for " + what);
            }
        }
    }
}

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Hermetic launcher tests: the harness is a {@link ProcessProxy} double,
/// so no real process is ever spawned.
class SandboxLauncherTest {

    @TempDir
    Path workspace;

    @Test
    void launchWaitsAndRelaysTheExitCode() throws Exception {
        ScriptedProcess harness = new ScriptedProcess(42, 0);
        CapturingStarter starter = new CapturingStarter(harness);

        int code = new SandboxLauncher(starter).launch(workspace, List.of("opencode"));

        assertEquals(42, code);
        assertTrue(harness.closed());
    }

    @Test
    void launchWaitsAcrossChunksUntilTheHarnessExits() throws Exception {
        ScriptedProcess harness = new ScriptedProcess(7, 2);

        int code = new SandboxLauncher(new CapturingStarter(harness))
                .launch(workspace, List.of("opencode"));

        assertEquals(7, code);
        assertEquals(3, harness.waitCount());
    }

    @Test
    void launchWrapsTheHarnessInTheStdinShim() throws Exception {
        ScriptedProcess harness = new ScriptedProcess(0, 0);
        CapturingStarter starter = new CapturingStarter(harness);

        new SandboxLauncher(starter).launch(workspace, List.of("opencode", "acp"));

        assertEquals(List.of("sh", "-c", SandboxLauncher.STDIN_SHIM, "sh",
                "opencode", "acp"), starter.builder().command());
    }

    @Test
    void globalGitConfigFollowsTheGitCascade() {
        assertEquals(Path.of("/explicit/config"),
                SandboxLauncher.globalGitConfig("/explicit/config", "/xdg", "/home/u"));
        assertEquals(Path.of("/xdg/git/config"),
                SandboxLauncher.globalGitConfig(null, "/xdg", "/home/u"));
        assertNull(SandboxLauncher.globalGitConfig(null, null, null));
        assertNull(SandboxLauncher.globalGitConfig("", "", ""));
    }

    /// With the XDG base var unset, git reads {@code $HOME/.gitconfig}
    /// and only falls back to the default XDG base
    /// ({@code $HOME/.config/git/config}) when the classic file is
    /// missing -- the post-migration layout. A present classic file
    /// fully shadows the default base (verified against git 2.47: a
    /// key only in the default base stays invisible while the classic
    /// file exists).
    @Test
    void globalGitConfigFallsBackToTheDefaultXdgBase(@TempDir Path home) throws Exception {
        assertEquals(home.resolve(".config", "git", "config"),
                SandboxLauncher.globalGitConfig(null, null, home.toString()));
        Files.createFile(home.resolve(".gitconfig"));
        assertEquals(home.resolve(".gitconfig"),
                SandboxLauncher.globalGitConfig(null, null, home.toString()));
    }

    /// The copy lands at the sandbox home's classic git location, a
    /// byte-for-byte replica of the source.
    @Test
    void prepareGitConfigCopiesTheGlobalConfigIntoTheHome(@TempDir Path home) throws Exception {
        Path source = SandboxLauncher.globalGitConfig();
        assumeTrue(source != null && Files.isRegularFile(source),
                "no global git config in the test environment");

        Path copy = SandboxLauncher.prepareGitConfig(home);

        assertEquals(home.resolve(".gitconfig"), copy);
        assertTrue(Arrays.equals(Files.readAllBytes(source), Files.readAllBytes(copy)),
                "the copy must be a byte-for-byte replica of the source");
    }

    @Test
    void launchPinsGitConfigGlobalAtTheCopy() throws Exception {
        Path copy = Path.of("/tmp/agent-home/.gitconfig");
        CapturingStarter starter = new CapturingStarter(new ScriptedProcess(0, 0));

        new SandboxLauncher(starter).launch(workspace, List.of("opencode"), copy);

        assertEquals(copy.toString(),
                starter.builder().environment().get("GIT_CONFIG_GLOBAL"));
    }

    /// mvnd's default daemon storage sits under {@code $HOME/.m2} --
    /// unreachable in the domain. The launcher pins it at the
    /// workspace's managed state dir instead, so the client and the
    /// daemon it starts share a registry the domain covers.
    @Test
    void launchPinsMvndDaemonStorageInTheStateDir() throws Exception {
        CapturingStarter starter = new CapturingStarter(new ScriptedProcess(0, 0));

        new SandboxLauncher(starter).launch(workspace, List.of("opencode"));

        Path storage = workspace.resolve(".nbmp", "mvnd");
        assertEquals(storage.toString(),
                starter.builder().environment().get("MVND_DAEMON_STORAGE"));
        assertTrue(Files.isDirectory(storage), "the storage dir must exist");
    }

    /// The toolchain environment is derived from the exec roots:
    /// JAVA_HOME at the root with a bin/java, and each root's bin/ dir
    /// prepended to PATH -- plus the mvnd distribution's nested mvn/bin
    /// (it bundles a full Maven one level down) -- so the harness
    /// reaches java/mvnd/mvn by name.
    @Test
    void launchDerivesTheToolchainEnvFromTheExecRoots(@TempDir Path jdk,
            @TempDir Path mvnd) throws Exception {
        Files.createDirectories(jdk.resolve("bin"));
        Files.createFile(jdk.resolve("bin", "java"));
        Files.createDirectories(mvnd.resolve("bin"));
        Files.createDirectories(mvnd.resolve("mvn", "bin"));
        CapturingStarter starter = new CapturingStarter(new ScriptedProcess(0, 0));

        new SandboxLauncher(starter).launch(workspace, List.of("opencode"), null,
                List.of(jdk, mvnd));

        Map<String, String> env = starter.builder().environment();
        assertEquals(jdk.toString(), env.get("JAVA_HOME"));
        String[] entries = env.get("PATH").split(File.pathSeparator);
        assertEquals(jdk.resolve("bin").toString(), entries[0]);
        assertEquals(mvnd.resolve("bin").toString(), entries[1]);
        assertEquals(mvnd.resolve("mvn", "bin").toString(), entries[2]);
    }

    /// The sandbox's {@code $HOME} is a private tmpfs, but recent JDKs no
    /// longer read {@code $HOME} for {@code user.home} (passwd entry
    /// instead), so JVMs the harness spawns would resolve
    /// {@code ~/.m2} against {@code /root} -- the read-only rootfs. The
    /// launcher pins JDK_JAVA_OPTIONS at the harness's home so the JVM
    /// default lands on the tmpfs.
    @Test
    void launchPinsTheJvmUserHome() throws Exception {
        CapturingStarter starter = new CapturingStarter(new ScriptedProcess(0, 0));

        new SandboxLauncher(starter).launch(workspace, List.of("opencode"));

        Map<String, String> env = starter.builder().environment();
        assertNotNull(env.get("HOME"), "test environment must carry a HOME");
        assertEquals("-Duser.home=" + env.get("HOME"), env.get("JDK_JAVA_OPTIONS"));
    }

    /// A {@link ProcessProxy} double with scripted {@code waitFor} answers
    /// and close tracking.
    private static final class ScriptedProcess implements ProcessProxy {

        private final int exitCode;
        private final int waitsUntilDone;
        private int waits;
        private boolean closed;

        ScriptedProcess(int exitCode, int waitsUntilDone) {
            this.exitCode = exitCode;
            this.waitsUntilDone = waitsUntilDone;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public boolean waitFor(Duration timeout) {
            waits++;
            return waits > waitsUntilDone;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public void destroyForcibly() {
        }

        @Override
        public void close() {
            closed = true;
        }

        boolean closed() {
            return closed;
        }

        int waitCount() {
            return waits;
        }
    }
}

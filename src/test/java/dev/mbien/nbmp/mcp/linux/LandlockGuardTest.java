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
package dev.mbien.nbmp.mcp.linux;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Hermetic guard tests: struct packing, UAPI syscall numbers, root
/// computation. One test performs a real (unrestricted) ruleset round trip
/// against the host kernel and skips when Landlock is absent.
class LandlockGuardTest {

    @TempDir
    Path workspace;

    @Test
    void rulesetAttrPacksTheHandledFsMaskAtOffsetZero() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = LandlockGuard.rulesetAttr(LandlockGuard.FULL_FS, arena);

            assertEquals(32, seg.byteSize());
            assertEquals(LandlockGuard.FULL_FS, seg.get(JAVA_LONG, 0));
            assertEquals(0L, seg.get(JAVA_LONG, 8));
            assertEquals(0L, seg.get(JAVA_LONG, 16));
            assertEquals(0L, seg.get(JAVA_LONG, 24));
        }
    }

    @Test
    void pathRuleIsPackedTwelveBytes() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = LandlockGuard.pathRule(0xFFL, 7, arena);

            assertEquals(12, seg.byteSize());
            assertEquals(0xFFL, seg.get(JAVA_LONG, 0));
            assertEquals(7, seg.get(JAVA_INT, 8));
        }
    }

    /// Regression: when many rules are packed into one arena (as a real
    /// install with dozens of roots does), every struct must stay
    /// 8-byte aligned -- a size-only arena allocation does not guarantee
    /// that, and a container allocator exposed the resulting misaligned
    /// {@code JAVA_LONG} store.
    @Test
    void pathRuleStaysEightByteAlignedWhenPacked() {
        try (Arena arena = Arena.ofConfined()) {
            arena.allocate(32); // the ruleset attr shifts the arena's offset
            for (int i = 0; i < 64; i++) {
                MemorySegment seg = LandlockGuard.pathRule(1L, 3, arena);
                assertEquals(0, seg.address() % 8, "rule " + i + " is not 8-aligned");
                assertEquals(1L, seg.get(JAVA_LONG, 0));
            }
        }
    }

    @Test
    void syscallNumbersMatchTheUapi() {
        // Landlock is 444..446 on both x86_64 and aarch64; open/close are
        // 2/3 everywhere; only prctl differs (157 x86_64 / 167 aarch64).
        assertEquals(444L, LandlockGuard.sysNumber(LandlockGuard.Op.CREATE_RULESET));
        assertEquals(445L, LandlockGuard.sysNumber(LandlockGuard.Op.ADD_RULE));
        assertEquals(446L, LandlockGuard.sysNumber(LandlockGuard.Op.RESTRICT_SELF));
        assertEquals(2L, LandlockGuard.sysNumber(LandlockGuard.Op.OPEN));
        assertEquals(3L, LandlockGuard.sysNumber(LandlockGuard.Op.CLOSE));
        long prctl = "aarch64".equals(System.getProperty("os.arch")) ? 167L : 157L;
        assertEquals(prctl, LandlockGuard.sysNumber(LandlockGuard.Op.PRCTL));
    }

    @Test
    void resolveOnPathReturnsTheRealLocation() {
        Path sh = LandlockGuard.resolveOnPath("sh");

        assertNotNull(sh);
        assertTrue(sh.isAbsolute());
        assertEquals(sh, LandlockGuard.resolveOnPath("sh"));
        assertNull(LandlockGuard.resolveOnPath("no-such-binary-xyz"));
    }

    /// Regression: an absolute name must be used as is. {@code Path.of(dir,
    /// abs)} concatenates instead of letting the absolute path win, so a
    /// plain PATH loop never finds an absolute harness path (the in-image
    /// harness ran into exactly this).
    @Test
    void resolveOnPathTakesAbsoluteNamesDirectly() throws IOException {
        Path script = workspace.resolve("harness.sh");
        Files.writeString(script, "#!/bin/sh\nexit 0\n");
        script.toFile().setExecutable(true);

        assertEquals(script, LandlockGuard.resolveOnPath(script.toString()));
        assertNull(LandlockGuard.resolveOnPath("/no/such/harness-xyz"));
    }

    @Test
    void standardRootsGrantTheWorkspaceEverythingAndStayUnique() {
        List<LandlockGuard.Root> roots = LandlockGuard.standardRoots(workspace);

        long access = roots.stream()
                .filter(r -> r.path().equals(workspace))
                .findFirst()
                .orElseThrow()
                .access();
        assertEquals(LandlockGuard.FULL_FS, access);
        assertEquals(roots.size(), roots.stream().map(LandlockGuard.Root::path).distinct().count());
    }

    @Test
    void mergedRootsKeepStandardAccessAndAppendExtras() {
        Path extra = Path.of("/no/nbmp/extra");
        List<LandlockGuard.Root> merged = LandlockGuard.mergedRoots(workspace,
                List.of(new LandlockGuard.Root(workspace, LandlockGuard.READ_ONLY),
                        new LandlockGuard.Root(extra, LandlockGuard.READ_ONLY)));

        assertEquals(LandlockGuard.FULL_FS, merged.stream()
                .filter(r -> r.path().equals(workspace))
                .findFirst()
                .orElseThrow()
                .access());
        assertEquals(LandlockGuard.READ_ONLY, merged.stream()
                .filter(r -> r.path().equals(extra))
                .findFirst()
                .orElseThrow()
                .access());
    }

    @Test
    void launcherRootsGrantConfigRootsReadOnly() {
        List<LandlockGuard.Root> roots =
                LandlockGuard.launcherRoots(List.of("java"), List.of(Path.of("/config")));

        assertEquals(LandlockGuard.READ_ONLY, roots.stream()
                .filter(r -> r.path().equals(Path.of("/config")))
                .findFirst()
                .orElseThrow()
                .access());
    }

    @Test
    void launcherRootsGrantExecRootsReadExec() {
        List<LandlockGuard.Root> roots = LandlockGuard.launcherRoots(
                List.of("java"), List.of(), List.of(Path.of("/toolchain")));

        assertEquals(LandlockGuard.EXEC_READ, roots.stream()
                .filter(r -> r.path().equals(Path.of("/toolchain")))
                .findFirst()
                .orElseThrow()
                .access());
    }

    @Test
    void launcherRootsGrantProcReadOnly() {
        assumeTrue(Files.isDirectory(Path.of("/proc")), "no /proc");

        List<LandlockGuard.Root> roots = LandlockGuard.launcherRoots(List.of("java"), List.of());

        assertEquals(LandlockGuard.READ_ONLY, roots.stream()
                .filter(r -> r.path().equals(Path.of("/proc")))
                .findFirst()
                .orElseThrow()
                .access());
    }

    @Test
    void launcherRootsGrantTheHarnessBinaryReadExec() {
        Path resolved = LandlockGuard.resolveOnPath("java");
        if (resolved == null || resolved.getParent() == null) {
            return; // no java on PATH: nothing to check
        }
        Path binDir = resolved.getParent();

        List<LandlockGuard.Root> roots = LandlockGuard.launcherRoots(List.of("java"), List.of());

        assertEquals(LandlockGuard.EXEC_READ, roots.stream()
                .filter(r -> r.path().equals(binDir))
                .findFirst()
                .orElseThrow()
                .access());
    }

    @Test
    void agentStateDirsAreNamespacedByTheHarness() {
        String home = System.getenv("HOME");
        assumeTrue(home != null && !home.isEmpty(), "no HOME in the test environment");

        List<Path> dirs = LandlockGuard.agentStateDirs("opencode");

        assertEquals(4, dirs.size());
        for (Path dir : dirs) {
            assertEquals("opencode", dir.getFileName().toString());
        }
    }

    /// The goose dirs use the same classic single-name layout as
    /// opencode ({@code .../goose}), so the grants agree with the
    /// gate's goose mounts (same profile).
    @Test
    void agentStateDirsForGooseUseTheClassicLayout() {
        String home = System.getenv("HOME");
        assumeTrue(home != null && !home.isEmpty(), "no HOME in the test environment");

        List<Path> dirs = LandlockGuard.agentStateDirs("goose");

        assertEquals(4, dirs.size());
        for (Path dir : dirs) {
            assertEquals("goose", dir.getFileName().toString());
        }
    }

    @Test
    void launcherRootsGrantTheAgentStateDirsWhenPresent() {
        List<LandlockGuard.Root> roots = LandlockGuard.launcherRoots(List.of("opencode"), List.of());

        for (Path dir : LandlockGuard.agentStateDirs("opencode")) {
            if (Files.isDirectory(dir)) {
                assertEquals(LandlockGuard.FULL_FS, roots.stream()
                        .filter(r -> r.path().equals(dir))
                        .findFirst()
                        .orElseThrow()
                        .access());
            }
        }
    }

    /// Regression: a path-beneath rule cannot target a regular file --
    /// the kernel rejects the non-directory parent fd with EINVAL. That
    /// is why the launcher copies the git global config into the domain
    /// instead of granting the file itself.
    @Test
    void pathBeneathRulesRejectRegularFiles() throws IOException {
        Path file = workspace.resolve("gitconfig");
        Files.writeString(file, "[user]\n");
        try (Arena arena = Arena.ofConfined()) {
            long rulesetFd;
            try {
                rulesetFd = LandlockGuard.createRuleset(arena);
            } catch (LandlockGuard.NoLandlockException e) {
                assumeTrue(false, "kernel without Landlock");
                return;
            }
            try {
                assertThrows(IllegalStateException.class,
                        () -> LandlockGuard.addPathRule(rulesetFd, arena, file, LandlockGuard.READ_ONLY));
            } finally {
                LandlockGuard.close((int) rulesetFd);
            }
        }
    }

    @Test
    void realRulesetRoundTrip() {
        try (Arena arena = Arena.ofConfined()) {
            long rulesetFd;
            try {
                rulesetFd = LandlockGuard.createRuleset(arena);
            } catch (LandlockGuard.NoLandlockException e) {
                assumeTrue(false, "kernel without Landlock");
                return;
            }
            try {
                LandlockGuard.addPathRule(rulesetFd, arena, workspace, LandlockGuard.FULL_FS);
            } finally {
                LandlockGuard.close((int) rulesetFd);
            }
        }
    }
}

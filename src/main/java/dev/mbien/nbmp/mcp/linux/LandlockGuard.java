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

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import dev.mbien.nbmp.mcp.HarnessProfile;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/// Kernel-enforced fs boundary (Landlock), pure Java over the
/// {@link KernelSyscall} channel.
///
/// The JVM starts unrestricted; {@link #apply} is called by the launcher
/// before it spawns the agent, so the harness — and everything it
/// starts — inherits the domain. The pre-restriction window contains
/// only bootstrap code, and no application thread exists yet, so
/// {@code TSYNC} is not needed. Threads spawned later inherit the domain.
///
/// Failure semantics: kernel without Landlock (ENOSYS) → domain-less with
/// a loud stderr note; Landlock present but any step fails → fail fast.
/// A silently unbounded agent is the worst state.
public final class LandlockGuard {

    private static final System.Logger LOG = System.getLogger(LandlockGuard.class.getName());

    /// Outcome of a guard attempt.
    public enum Outcome {
        /// Landlock domain installed; fs access outside it is kernel-denied.
        APPLIED,
        /// Kernel without Landlock; the process continues domain-less
        /// (loud note).
        DEGRADED
    }

    /// Landlock fs access rights (UAPI bits, stable since ABI 1).
    ///
    /// {@code FULL_FS} handles bits 0..15 (EXECUTE .. APPEND), i.e. every
    /// right is default-deny unless a root grants it; {@code EXEC_READ} is
    /// what a toolchain root needs so children can start; {@code DEV_RW}
    /// covers {@code /dev} (git and friends open {@code /dev/null} read-write);
    /// {@code READ_ONLY} is what launcher config roots grant (e.g.
    /// {@code ~/.m2}).
    static final long EXECUTE = 1L << 0;
    static final long WRITE_FILE = 1L << 1;
    static final long READ_FILE = 1L << 2;
    static final long READ_DIR = 1L << 3;
    static final long FULL_FS = (1L << 16) - 1;
    static final long EXEC_READ = EXECUTE | READ_FILE | READ_DIR;
    static final long DEV_RW = READ_FILE | WRITE_FILE | READ_DIR;
    static final long READ_ONLY = READ_FILE | READ_DIR;

    private static final int PR_SET_NO_NEW_PRIVS = 38;
    private static final long PR_SET_NO_NEW_PRIVS_ENABLE = 1; // the kernel requires exactly 1
    private static final long RULE_PATH_BENEATH = 1;
    private static final long RULESET_ATTR_SIZE = 32; // ABI 7; the size arg is the version negotiation
    private static final long RULE_ATTR_SIZE = 12; // {u64 allowed_access, s32 parent_fd}, packed
    private static final int O_RDONLY = 0;
    private static final int O_CLOEXEC = 0x80000;
    private static final int ENOSYS = 38;

    /// Syscall numbers addressed through the {@link KernelSyscall} channel.
    ///
    /// The landlock trio is 444..446 on both x86_64 and aarch64; only prctl
    /// differs.
    enum Op {
        CREATE_RULESET, ADD_RULE, RESTRICT_SELF, PRCTL, OPEN, CLOSE
    }

    static long sysNumber(Op op) {
        return switch (op) {
            case CREATE_RULESET -> 444;
            case ADD_RULE -> 445;
            case RESTRICT_SELF -> 446;
            case OPEN -> 2;
            case CLOSE -> 3;
            case PRCTL -> "aarch64".equals(System.getProperty("os.arch")) ? 167 : 157;
        };
    }

    /// Runs a syscall through the channel and fails fast, naming the errno.
    private static long sysOk(String what, long number, long a1, long a2, long a3, long a4, long a5) {
        long result = KernelSyscall.invoke(number, a1, a2, a3, a4, a5);
        if (result < 0) {
            throw new IllegalStateException(what + " failed: " + KernelSyscall.name(KernelSyscall.errno()));
        }
        return result;
    }

    /// Installs the domain: the standard roots ({@link #standardRoots})
    /// plus the given extra path-beneath grants (the launcher's read-only
    /// config roots and harness roots).
    public static Outcome apply(Path workspace, List<Root> extraRoots) {
        return install(workspace, mergedRoots(workspace, extraRoots));
    }

    /// Returns the launcher's extra domain roots: the config folders
    /// read-only, the harness binary's bin dir and install root read/exec,
    /// plus {@code /proc} read.
    ///
    /// The read/exec grants let the harness start whatever it is;
    /// runtimes like Bun probe {@code /proc} at startup (cgroup, limits).
    /// In the agent container that surface is the container's own
    /// processes only.
    ///
    /// The agent's own XDG state dirs ({@link #agentStateDirs}) get full
    /// rights: the harness creates and fills them at startup, and
    /// opencode in particular opens its log file in the data dir as one
    /// of its first acts — without the grant it dies with EACCES before
    /// answering {@code initialize}.
    public static List<Root> launcherRoots(List<String> harness, List<Path> configRoots) {
        return launcherRoots(harness, configRoots, List.of());
    }

    /// Same roots, plus the host toolchain roots the harness may need to
    /// execute outside the workspace (a user's dev tree with the build
    /// JDK and build tools) granted read/exec.
    public static List<Root> launcherRoots(List<String> harness, List<Path> configRoots,
            List<Path> execRoots) {
        List<Root> roots = new ArrayList<>();
        for (Path root : configRoots) {
            roots.add(new Root(root, READ_ONLY));
        }
        for (Path root : execRoots) {
            roots.add(new Root(root, EXEC_READ));
        }
        if (Files.isDirectory(Path.of("/proc"))) {
            roots.add(new Root(Path.of("/proc"), READ_ONLY));
        }
        Path resolved = resolveOnPath(harness.getFirst());
        if (resolved != null && resolved.getParent() != null) {
            Path binDir = resolved.getParent();
            roots.add(new Root(binDir, EXEC_READ));
            Path install = installRoot(binDir);
            if (install != null) {
                roots.add(new Root(install, EXEC_READ));
            }
        }
        for (Path dir : agentStateDirs(harness.getFirst())) {
            if (Files.isDirectory(dir)) {
                roots.add(new Root(dir, FULL_FS));
            }
        }
        return roots;
    }

    /// The agent's own state dirs: the harness profile's XDG
    /// config/data/state dirs ({@link HarnessProfile#stateDirs}) plus
    /// the cache dir, namespaced by the profile's state subpaths.
    ///
    /// In the agent container these resolve inside the domain
    /// ({@code HOME=/tmp/agent-home}); on the host they are the user's
    /// real dirs.
    ///
    /// @return the namespaced dirs whose base is resolvable
    public static List<Path> agentStateDirs(String harnessBinary) {
        String home = System.getenv("HOME");
        if (home == null || home.isEmpty()) {
            return List.of();
        }
        HarnessProfile profile = HarnessProfile.of(Path.of(harnessBinary).getFileName().toString());
        List<Path> dirs = new ArrayList<>(
                profile.stateDirs(Path.of(home), System::getenv).stream()
                    .map(HarnessProfile.StateDir::dir)
                    .toList()
        );
        for (Path subpath : profile.stateSubpaths()) {
            addXdgDir(dirs, "XDG_CACHE_HOME", home, Path.of(".cache"), subpath);
        }
        return dirs;
    }

    private static void addXdgDir(List<Path> dirs, String var, String home, Path homeDefault, Path subpath) {
        String base = System.getenv(var);
        Path root;
        if (base != null && !base.isEmpty()) {
            root = Path.of(base);
        } else if (home != null && !home.isEmpty()) {
            root = Path.of(home).resolve(homeDefault);
        } else {
            return;
        }
        dirs.add(root.resolve(subpath));
    }

    /// Standard roots plus the extras; a duplicate path keeps the standard
    /// access.
    static List<Root> mergedRoots(Path workspace, List<Root> extraRoots) {
        Map<Path, Long> byPath = new LinkedHashMap<>();
        for (Root root : standardRoots(workspace)) {
            byPath.put(root.path(), root.access());
        }
        for (Root root : extraRoots) {
            byPath.putIfAbsent(root.path(), root.access());
        }
        return rootsOf(byPath);
    }

    static Outcome install(Path workspace, List<Root> roots) {
        try (Arena arena = Arena.ofConfined()) {
            long rulesetFd;
            try {
                rulesetFd = createRuleset(arena);
            } catch (NoLandlockException e) {
                LOG.log(WARNING, "Landlock is unavailable on this kernel (ENOSYS). "
                        + "Continuing with POLICY-ONLY enforcement -- the kernel fs boundary is OFF.");
                return Outcome.DEGRADED;
            }
            try {
                for (Root root : roots) {
                    addPathRule(rulesetFd, arena, root.path(), root.access());
                }
                noNewPrivs();
                restrictSelf(rulesetFd);
            } finally {
                close((int) rulesetFd);
            }
            LOG.log(INFO, "Landlock domain installed ({0} roots, workspace {1})",
                    roots.size(), workspace);
            return Outcome.APPLIED;
        }
    }

    /// Creates the ruleset with the full handled fs mask.
    ///
    /// @return the ruleset fd
    static long createRuleset(Arena arena) {
        MemorySegment attr = rulesetAttr(FULL_FS, arena);
        long fd = KernelSyscall.invoke(sysNumber(Op.CREATE_RULESET), attr.address(), RULESET_ATTR_SIZE, 0, 0, 0);
        if (fd < 0) {
            int error = KernelSyscall.errno();
            if (error == ENOSYS) {
                throw new NoLandlockException();
            }
            throw new IllegalStateException("landlock_create_ruleset failed: " + KernelSyscall.name(error));
        }
        return fd;
    }

    static void addPathRule(long rulesetFd, Arena arena, Path dir, long access) {
        int dirFd = (int) sysOk("open " + dir, sysNumber(Op.OPEN),
                arena.allocateFrom(dir.toString()).address(), O_RDONLY | O_CLOEXEC, 0, 0, 0);
        try {
            MemorySegment rule = pathRule(access, dirFd, arena);
            sysOk("landlock_add_rule", sysNumber(Op.ADD_RULE), rulesetFd, RULE_PATH_BENEATH, rule.address(), 0, 0);
        } finally {
            close(dirFd);
        }
    }

    static void noNewPrivs() {
        sysOk("prctl PR_SET_NO_NEW_PRIVS", sysNumber(Op.PRCTL),
                PR_SET_NO_NEW_PRIVS, PR_SET_NO_NEW_PRIVS_ENABLE, 0, 0, 0);
    }

    static void restrictSelf(long rulesetFd) {
        sysOk("landlock_restrict_self", sysNumber(Op.RESTRICT_SELF), rulesetFd, 0, 0, 0, 0);
    }

    static void close(int fd) {
        sysOk("close", sysNumber(Op.CLOSE), fd, 0, 0, 0, 0);
    }

    /// Packed {@code struct landlock_ruleset_attr} (ABI 7: 32 bytes).
    ///
    /// The fs handled mask at offset 0, the net/io/pci masks left zero so those
    /// axes stay unhandled and permitted.
    ///
    /// Allocated 8-byte aligned: a size-only {@code allocate} guarantees byte
    /// alignment only, and some allocators (observed in a container) return an
    /// address that is not a multiple of 8, which the {@code JAVA_LONG} store at
    /// offset 0 rejects.
    static MemorySegment rulesetAttr(long handledFs, Arena arena) {
        MemorySegment seg = arena.allocate(RULESET_ATTR_SIZE, 8);
        seg.set(JAVA_LONG, 0, handledFs);
        return seg;
    }

    /// Packed {@code struct landlock_path_beneath_attr}.
    ///
    /// 12 bytes, {@code {u64 allowed_access, s32 parent_fd}}, no padding.
    /// Allocated 8-byte aligned for the same reason as {@link #rulesetAttr}.
    static MemorySegment pathRule(long allowedAccess, int parentFd, Arena arena) {
        MemorySegment seg = arena.allocate(RULE_ATTR_SIZE, 8);
        seg.set(JAVA_LONG, 0, allowedAccess);
        seg.set(JAVA_INT, 8, parentFd);
        return seg;
    }

    /// A path-beneath grant: everything beneath the directory gets the access bits.
    public record Root(Path path, long access) {
    }

    /// The standard domain: the workspace with every fs right, the world
    /// scratch dirs ({@code /tmp}, {@code /var/tmp}) with every right,
    /// plus the read/exec roots the harness's children (git, mvn, their
    /// JVMs, the dynamic loader) need to start.
    static List<Root> standardRoots(Path workspace) {
        Map<Path, Long> byPath = new LinkedHashMap<>();
        addRoot(byPath, workspace, FULL_FS);
        for (String name : List.of("git", "mvn", "java", "sh")) {
            Path resolved = resolveOnPath(name);
            if (resolved == null || resolved.getParent() == null) {
                continue;
            }
            Path parent = resolved.getParent();
            addRoot(byPath, parent, EXEC_READ);
            addRoot(byPath, installRoot(parent), EXEC_READ);
        }
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            Path p = Path.of(entry);
            addRoot(byPath, Files.isDirectory(p) ? p : p.getParent(), EXEC_READ);
        }
        String javaHome = System.getProperty("java.home", "");
        if (!javaHome.isEmpty()) {
            addRoot(byPath, Path.of(javaHome), EXEC_READ);
        }
        if (Files.isDirectory(Path.of("/dev"))) {
            addRoot(byPath, Path.of("/dev"), DEV_RW);
        }
        for (Path scratch : List.of(Path.of("/tmp"), Path.of("/var/tmp"))) {
            if (Files.isDirectory(scratch)) {
                addRoot(byPath, scratch, FULL_FS);
            }
        }
        for (Path sysDir : List.of(
                Path.of("/etc"), Path.of("/lib"), Path.of("/lib64"),
                Path.of("/usr/lib"), Path.of("/usr/lib64"))) {
            if (Files.isDirectory(sysDir)) {
                addRoot(byPath, sysDir, EXEC_READ);
            }
        }
        return rootsOf(byPath);
    }

    /// A root list in insertion order.
    private static List<Root> rootsOf(Map<Path, Long> byPath) {
        return new ArrayList<>(
                byPath.entrySet().stream()
                        .map(e -> new Root(e.getKey(), e.getValue()))
                        .toList());
    }

    /// Merges a root into the map, OR-ing the access bits when the path is
    /// already present. A null path is ignored.
    private static void addRoot(Map<Path, Long> byPath, Path path, long access) {
        if (path != null) {
            byPath.merge(path, access, (a, b) -> a | b);
        }
    }

    /// Resolves a binary to its real location.
    ///
    /// An absolute name is used as-is (the in-image harness path); a
    /// relative name is searched on {@code PATH}. Symlinks are
    /// followed: the Landlock check runs against the resolved dentry,
    /// so a rule for the symlink's parent would not cover the real
    /// target.
    /// @return the absolute real path, or null when the binary is not found
    static Path resolveOnPath(String name) {
        List<Path> candidates = new ArrayList<>();
        Path direct = Path.of(name);
        if (direct.isAbsolute()) {
            candidates.add(direct);
        } else {
            String pathVar = System.getenv("PATH");
            if (pathVar == null) {
                return null;
            }
            for (String dir : pathVar.split(File.pathSeparator)) {
                if (!dir.isEmpty()) {
                    candidates.add(Path.of(dir, name));
                }
            }
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                try {
                    return candidate.toRealPath();
                } catch (IOException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /// The install root of a {@code bin/} directory that also carries a
    /// {@code lib/} with regular files (the maven layout).
    ///
    /// Its child JVM reads its classpath jars from there.
    /// @return the install root, or null when the layout does not match
    static Path installRoot(Path binDir) {
        if (!"bin".equals(binDir.getFileName().toString())) {
            return null;
        }
        Path root = binDir.getParent();
        if (root == null || !Files.isDirectory(root.resolve("lib"))) {
            return null;
        }
        try (Stream<Path> entries = Files.list(root.resolve("lib"))) {
            return entries.anyMatch(Files::isRegularFile) ? root : null;
        } catch (IOException e) {
            return null;
        }
    }

    /// The kernel has no Landlock (create returned ENOSYS): degrade, do not die.
    static final class NoLandlockException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        NoLandlockException() {
            super("kernel without Landlock");
        }
    }

    private LandlockGuard() {
    }
}

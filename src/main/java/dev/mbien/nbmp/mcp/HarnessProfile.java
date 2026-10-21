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

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/// The per-harness facts the host and the in-container launcher must
/// agree on: where the agent keeps its XDG state dirs, how the host
/// resolves the agent binary, and which workspace project-config
/// files to pin read-only.
///
/// Both sides look the profile up by the harness binary name
/// ({@link #of}), so the gate's mounts and the launcher's Landlock
/// grants resolve the same dirs from one definition:
/// {@link AcpGate#agentStateMounts} mounts what
/// {@link dev.mbien.nbmp.mcp.linux.LandlockGuard#agentStateDirs} grants.
///
/// Both harnesses use the classic single-name layout ({@code
/// <base>/opencode}, {@code <base>/goose}) under the XDG base vars
/// with the classic {@code $HOME} fallbacks (verified against the
/// dirs each harness actually creates). They differ in the binary
/// override, the workspace project-config files, and goose's
/// {@code GOOSE_PATH_ROOT} single-root override: the container does
/// not honor it ({@link #stateRootEnv} carries the name for a
/// warning) — the agent then keeps the classic XDG locations, fresh
/// when the host has no state there.
public record HarnessProfile(String name, Path stateSubpath, String binaryEnv,
        List<String> projectConfig, String stateRootEnv) {

    public static final HarnessProfile OPENCODE =
            new HarnessProfile("opencode", Path.of("opencode"), "NBMP_CTR_OPENCODE",
                    List.of("opencode.json", "opencode.jsonc"), null);

    public static final HarnessProfile GOOSE =
            new HarnessProfile("goose", Path.of("goose"), "NBMP_CTR_GOOSE",
                    List.of(), "GOOSE_PATH_ROOT");

    /// The profile for a harness binary name. Unknown names get the
    /// generic classic layout: state under {@code <base>/<name>}, no
    /// binary-override env var, no known project-config files.
    public static HarnessProfile of(String binaryName) {
        return switch (binaryName) {
            case "opencode" -> OPENCODE;
            case "goose" -> GOOSE;
            default -> new HarnessProfile(binaryName, Path.of(binaryName), null, List.of(), null);
        };
    }

    /// One state-dir role: the base-var override to pass into the
    /// container, the base that var points at, the dir to mount and
    /// grant, and whether the mount is read-only.
    public record StateDir(String envVar, Path base, Path dir, boolean readOnly) {}

    /// The agent's XDG state dirs (config read-only, data and state
    /// read-write), from the XDG base vars ({@code env}) with the
    /// classic {@code $HOME} fallbacks. The caller decides what exists
    /// and applies the mounts or grants.
    public List<StateDir> stateDirs(Path home, Function<String, String> env) {
        Path configBase = base(env, "XDG_CONFIG_HOME", home, Path.of(".config"));
        Path dataBase = base(env, "XDG_DATA_HOME", home, Path.of(".local", "share"));
        Path stateBase = base(env, "XDG_STATE_HOME", home, Path.of(".local", "state"));
        return List.of(
                new StateDir("XDG_CONFIG_HOME", configBase, configBase.resolve(stateSubpath), true),
                new StateDir("XDG_DATA_HOME", dataBase, dataBase.resolve(stateSubpath), false),
                new StateDir("XDG_STATE_HOME", stateBase, stateBase.resolve(stateSubpath), false)
        );
    }

    private static Path base(Function<String, String> env, String var, Path home, Path homeDefault) {
        String value = env.apply(var);
        return value != null && !value.isEmpty() ? Path.of(value) : home.resolve(homeDefault);
    }
}

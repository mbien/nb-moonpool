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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/// The per-harness facts the host and in-container launcher must agree on:
/// XDG state directory locations, agent binary resolution, and read-only
/// workspace configuration.
///
/// The gate's mounts ({@link AcpGate#agentStateMounts}) and the launcher's
/// Landlock grants ({@link dev.mbien.nbmp.mcp.linux.LandlockGuard#agentStateDirs})
/// resolve the same directories from this profile via {@link #of(String)}.
///
/// @param name the harness's binary name, used as the {@link #of(String)} lookup key
/// @param stateSubpaths subpaths under each XDG base ({@link #stateDirs})
/// @param binaryEnv the env var that overrides the agent binary on the host;
///     {@code null} if none
/// @param projectConfig workspace file names pinned read-only in the container
/// @param stateRootEnv the name of the environment variable used to detect
///     single-root state layouts and trigger a warning; {@code null} if none
public record HarnessProfile(
        String name,
        List<Path> stateSubpaths,
        String binaryEnv,
        List<String> projectConfig,
        String stateRootEnv) {

    public static final HarnessProfile OPENCODE =
            new HarnessProfile("opencode", List.of(Path.of("opencode"), Path.of("opentui")),
                    "NBMP_CTR_OPENCODE", List.of("opencode.json", "opencode.jsonc"), null);

    public static final HarnessProfile GOOSE =
            new HarnessProfile("goose", List.of(Path.of("goose")),
                    "NBMP_CTR_GOOSE", List.of(), "GOOSE_PATH_ROOT");

    /// The profile for a harness binary name. Unknown names get the
    /// generic classic layout: state under {@code <base>/<name>}, no
    /// binary-override env var, no known project-config files.
    public static HarnessProfile of(String binaryName) {
        return switch (binaryName) {
            case "opencode" -> OPENCODE;
            case "goose" -> GOOSE;
            default -> new HarnessProfile(binaryName, List.of(Path.of(binaryName)), null, List.of(), null);
        };
    }

    /// A single XDG state directory role and its associated mount/grant metadata.
    public record StateDir(String envVar, Path base, Path dir, boolean readOnly) {}

    /// Returns the XDG state directories for each subpath, using {@code env} with
    /// {@code $HOME} fallbacks. Config is read-only; data and state are read-write.
    public List<StateDir> stateDirs(Path home, Function<String, String> env) {
        Path configBase = base(env, "XDG_CONFIG_HOME", home, Path.of(".config"));
        Path dataBase = base(env, "XDG_DATA_HOME", home, Path.of(".local", "share"));
        Path stateBase = base(env, "XDG_STATE_HOME", home, Path.of(".local", "state"));
        List<StateDir> dirs = new ArrayList<>();
        for (Path subpath : stateSubpaths) {
            dirs.add(new StateDir("XDG_CONFIG_HOME", configBase, configBase.resolve(subpath), true));
            dirs.add(new StateDir("XDG_DATA_HOME", dataBase, dataBase.resolve(subpath), false));
            dirs.add(new StateDir("XDG_STATE_HOME", stateBase, stateBase.resolve(subpath), false));
        }
        return List.copyOf(dirs);
    }

    private static Path base(Function<String, String> env, String var, Path home, Path homeDefault) {
        String value = env.apply(var);
        return value != null && !value.isEmpty() ? Path.of(value) : home.resolve(homeDefault);
    }
}

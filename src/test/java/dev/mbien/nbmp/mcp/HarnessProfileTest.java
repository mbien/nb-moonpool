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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The per-harness state resolution: both harnesses use the classic
/// single-name layout under the XDG base vars (verified against the
/// dirs each harness actually creates on the host).
class HarnessProfileTest {

    private static final Function<String, String> NO_VARS = var -> null;

    @Test
    void profilesResolveByBinaryName() {
        assertSame(HarnessProfile.OPENCODE, HarnessProfile.of("opencode"));
        assertSame(HarnessProfile.GOOSE, HarnessProfile.of("goose"));
        assertEquals("NBMP_CTR_GOOSE", HarnessProfile.GOOSE.binaryEnv());
        assertTrue(HarnessProfile.GOOSE.projectConfig().isEmpty());
        assertEquals("GOOSE_PATH_ROOT", HarnessProfile.GOOSE.stateRootEnv());
        HarnessProfile generic = HarnessProfile.of("custom-agent");
        assertEquals(Path.of("custom-agent"), generic.stateSubpath());
        assertNull(generic.binaryEnv());
        assertTrue(generic.projectConfig().isEmpty());
        assertNull(generic.stateRootEnv());
    }

    /// The classic layout: each base var (or its $HOME fallback) plus
    /// the single-name subpath; the config dir is read-only, data and
    /// state read-write.
    @Test
    void opencodeStateDirsUseTheClassicBases(@TempDir Path home) {
        List<HarnessProfile.StateDir> dirs =
                HarnessProfile.OPENCODE.stateDirs(home, NO_VARS);

        assertEquals(List.of(
                new HarnessProfile.StateDir("XDG_CONFIG_HOME",
                        home.resolve(".config"), home.resolve(".config", "opencode"), true),
                new HarnessProfile.StateDir("XDG_DATA_HOME",
                        home.resolve(".local", "share"),
                        home.resolve(".local", "share", "opencode"), false),
                new HarnessProfile.StateDir("XDG_STATE_HOME",
                        home.resolve(".local", "state"),
                        home.resolve(".local", "state", "opencode"), false)), dirs);
    }

    /// The base vars point the bases at custom locations (the host's
    /// values are mirrored into the container).
    @Test
    void stateDirsFollowTheXdgBaseVars(@TempDir Path home, @TempDir Path custom) {
        Function<String, String> env = var -> switch (var) {
            case "XDG_CONFIG_HOME" -> custom.resolve("config").toString();
            case "XDG_DATA_HOME" -> custom.resolve("data").toString();
            default -> null;
        };

        List<HarnessProfile.StateDir> dirs =
                HarnessProfile.OPENCODE.stateDirs(home, env);

        assertEquals(custom.resolve("config"), dirs.get(0).base());
        assertEquals(custom.resolve("data"), dirs.get(1).base());
    }

    /// Goose uses the same classic layout as opencode (the dirs the
    /// host goose run actually creates): the single-name subpath under
    /// each base.
    @Test
    void gooseStateDirsUseTheClassicLayout(@TempDir Path home) {
        List<HarnessProfile.StateDir> dirs = HarnessProfile.GOOSE.stateDirs(home, NO_VARS);

        assertEquals(home.resolve(".config", "goose"), dirs.get(0).dir());
        assertEquals("XDG_CONFIG_HOME", dirs.get(0).envVar());
        assertEquals(home.resolve(".local", "share", "goose"), dirs.get(1).dir());
        assertEquals("XDG_DATA_HOME", dirs.get(1).envVar());
        assertEquals(home.resolve(".local", "state", "goose"), dirs.get(2).dir());
        assertEquals("XDG_STATE_HOME", dirs.get(2).envVar());
    }
}

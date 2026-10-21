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

import java.util.List;

/// The JVM flags for every production nbmp launch: FFM native
/// access plus the JSON incubator module.
///
/// The native-access value is mode-dependent — the JVM matches it
/// against the modules actually present (no package names), so a
/// classpath launch (only the unnamed module) needs
/// {@code ALL-UNNAMED}, while a modular (jlinked runtime) launch
/// must name the app module, which {@code ALL-UNNAMED} does not
/// cover.
///
/// The surefire {@code argLine} mirrors {@link #RUNTIME_FLAGS}; the
/// image ENTRYPOINT and the IDE plugin launcher script
/// ({@code scripts/nbmp}) mirror {@link #RUNTIME_FLAGS_MODULAR}.
/// Change the values here first.
public final class JvmFlags {

    /// The app's named module — the {@code --enable-native-access}
    /// value of a modular launch.
    public static final String MODULE = "dev.mbien.nbmp.mcp";

    /// Classpath (unnamed-module) launches: surefire.
    public static final List<String> RUNTIME_FLAGS = List.of(
            "--enable-native-access=ALL-UNNAMED",
            "--add-modules", "jdk.incubator.json");

    /// Modular (jlinked runtime) launches: the image ENTRYPOINT, the
    /// IDE plugin gate launcher.
    public static final List<String> RUNTIME_FLAGS_MODULAR = List.of(
            "--enable-native-access=" + MODULE,
            "--add-modules", "jdk.incubator.json");

    private JvmFlags() {
    }
}

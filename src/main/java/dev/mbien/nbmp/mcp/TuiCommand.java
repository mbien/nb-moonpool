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

/// The TUI form's entry point: prints the TUI container command
/// ({@link AcpGate#containerCommand} in its interactive form, the
/// same mounts and pins as the ACP gate) NUL-separated on stdout, for
/// the {@code scripts/nbmp} wrapper to exec.
///
/// The JVM stays out of the terminal's data path: the wrapper execs
/// podman directly on the host terminal, so podman carries its size
/// and resizes into the container; this process only computes the
/// command and exits.
public class TuiCommand {

    public static void main(String[] args) {
        Log.init();
        AcpGate.Options options = AcpGate.options(args);
        List<String> command = AcpGate.containerCommand(
                options.runtime(), options.image(), options.workspace(),
                options.agent(), options.execRoots(), true);
        for (String arg : command) {
            System.out.print(arg);
            System.out.print('\0');
        }
        System.out.flush();
    }
}

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

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

/// Narrow surface of a child process, the only thing
/// {@link SandboxLauncher} depends on. Production wraps a real
/// {@link Process} ({@link DelegatingProcess}); tests re-implement this
/// interface, so no process is ever spawned.
public interface ProcessProxy extends AutoCloseable {

    InputStream getInputStream();

    InputStream getErrorStream();

    /// @return true if the process exited within the timeout
    boolean waitFor(Duration timeout) throws InterruptedException;

    int exitValue();

    void destroyForcibly();

    @Override
    void close() throws IOException;
}

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

/// {@link ProcessProxy} that delegates every call to a real {@link Process}.
public final class DelegatingProcess implements ProcessProxy {

    private final Process process;

    public DelegatingProcess(Process process) {
        this.process = process;
    }

    @Override
    public InputStream getInputStream() {
        return process.getInputStream();
    }

    @Override
    public InputStream getErrorStream() {
        return process.getErrorStream();
    }

    @Override
    public boolean waitFor(Duration timeout) throws InterruptedException {
        return process.waitFor(timeout);
    }

    @Override
    public int exitValue() {
        return process.exitValue();
    }

    @Override
    public void destroyForcibly() {
        process.destroyForcibly();
    }

    @Override
    public void close() throws IOException {
        process.close();
    }
}

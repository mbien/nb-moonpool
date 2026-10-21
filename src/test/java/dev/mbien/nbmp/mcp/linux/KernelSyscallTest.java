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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import org.junit.jupiter.api.Test;

/// Hermetic channel tests: a syscall that cannot fail on any host
/// (getpid) plus errno reporting through a guaranteed failure (open of a
/// nonexistent path).
class KernelSyscallTest {

    @Test
    void getpidRoundTrips() {
        long pid = KernelSyscall.invoke(39, 0, 0, 0, 0, 0);

        assertEquals(ProcessHandle.current().pid(), pid);
    }

    @Test
    void errnoIsReportedAfterAFailure() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment path = arena.allocateFrom("/no/such/file-nbmp-xyz");

            long fd = KernelSyscall.invoke(2, path.address(), 0, 0, 0, 0);

            assertTrue(fd < 0);
            assertEquals(2, KernelSyscall.errno()); // ENOENT
        }
    }
}

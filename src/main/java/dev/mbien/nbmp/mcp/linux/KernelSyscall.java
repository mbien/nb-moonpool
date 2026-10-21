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

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.Map;

/// The single Java→kernel syscall channel: a fixed 6-long downcall to
/// glibc's variadic {@code syscall(2)}.
///
/// glibc declares {@code syscall} variadic, which the FFM API cannot
/// construct; the symbol is therefore called through a fixed 6-arity
/// function pointer. The SysV (x86_64) and AAPCS64 (aarch64) ABIs pass
/// the first six integer arguments in registers, so for up to six
/// arguments the call is arity-agnostic (register placement
/// rdi/rsi/rdx/rcx/r8/r9 verified on the host).
///
/// On failure glibc's {@code syscall} returns -1 and sets the C errno;
/// {@link #errno()} reads it back through {@code __errno_location}.
/// The process must link no other native code through this channel.
///
/// The FFM entry points used here are restricted methods; the class is
/// their deliberate quarantine zone.
@SuppressWarnings("restricted")
public final class KernelSyscall {

    private static final FunctionDescriptor SYS6 = FunctionDescriptor.of(
            JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);

    private static final MethodHandle SYS_CALL = link("syscall", SYS6);
    private static final MethodHandle ERNO_LOCATION =
            link("__errno_location", FunctionDescriptor.of(ADDRESS));

    private static MethodHandle link(String symbol, FunctionDescriptor descriptor) {
        Linker linker = Linker.nativeLinker();
        return linker.downcallHandle(
                linker.defaultLookup().find(symbol).orElseThrow(() ->
                        new IllegalStateException("native symbol not found: " + symbol)),
                descriptor);
    }

    /// Runs a syscall with up to six long arguments.
    ///
    /// @return the kernel's return value, or -1 on failure (glibc
    /// normalization); after a failure, {@link #errno()} holds the error number
    public static long invoke(long number, long a1, long a2, long a3, long a4, long a5) {
        try {
            return (long) SYS_CALL.invokeExact(number, a1, a2, a3, a4, a5);
        } catch (Throwable t) {
            throw new IllegalStateException("syscall " + number + " failed", t);
        }
    }

    /// The C errno of the current thread (meaningful after a failed
    /// {@link #invoke}).
    public static int errno() {
        try {
            MemorySegment location = (MemorySegment) ERNO_LOCATION.invokeExact();
            return location.reinterpret(4).get(JAVA_INT, 0);
        } catch (Throwable t) {
            throw new IllegalStateException("errno read failed", t);
        }
    }

    /// Human-readable name for an error number (falls back to the raw number).
    public static String name(int errno) {
        return ERRNOS.getOrDefault((long) errno, "errno " + errno);
    }

    private static final Map<Long, String> ERRNOS = Map.ofEntries(
            Map.entry(1L, "EPERM"), Map.entry(2L, "ENOENT"), Map.entry(7L, "E2BIG"),
            Map.entry(9L, "EBADF"), Map.entry(12L, "ENOMEM"), Map.entry(13L, "EACCES"),
            Map.entry(14L, "EFAULT"), Map.entry(20L, "ENOTDIR"), Map.entry(22L, "EINVAL"),
            Map.entry(38L, "ENOSYS"));

    private KernelSyscall() {
    }
}

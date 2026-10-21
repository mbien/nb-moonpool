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

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/// The one-line console format for the entry points' diagnostic output:
/// {@code [yyyy-MM-ddTHH:mm:ss] LEVEL [logger]: message}, the throwable's
/// stack trace on the lines that follow when present.
///
/// {@code SimpleFormatter} has no token for the logger name (its tokens
/// cover date, source class, level, message and throwable), so
/// {@link #init()} re-points the root logger's console output at an
/// instance of this formatter; each entry point's {@code main} calls
/// {@link #init()} as its first act, before the first log call.
final class Log extends Formatter {

    /// Re-points the root logger's console output at this formatter:
    /// whatever handlers the root carries — including the lazily
    /// created default, if something logged before {@code init()}
    /// ran — is replaced by a single console handler.
    static void init() {
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) {
            root.removeHandler(handler);
        }
        ConsoleHandler console = new ConsoleHandler();
        console.setFormatter(new Log());
        root.addHandler(console);
    }

    @Override
    public String format(LogRecord record) {
        // The record's instant in the JVM's default zone (CEST on the
        // host, UTC in the container), whole seconds.
        String date = ISO_LOCAL_DATE_TIME.format(Instant.ofEpochMilli(record.getMillis())
                .truncatedTo(ChronoUnit.SECONDS)
                .atZone(ZoneId.systemDefault()));
        StringBuilder line = new StringBuilder(128)
                .append('[').append(date).append("] ")
                .append(record.getLevel().getName()).append(" [")
                .append(record.getLoggerName()).append("]: ")
                .append(formatMessage(record));
        Throwable thrown = record.getThrown();
        if (thrown != null) {
            StringWriter stack = new StringWriter();
            thrown.printStackTrace(new PrintWriter(stack));
            line.append(System.lineSeparator()).append(stack);
        }
        return line.append(System.lineSeparator()).toString();
    }
}

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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// The one-line console format: [date] LEVEL [logger]: message.
class LogTest {

    private Handler[] saved;

    @BeforeEach
    void saveRootHandlers() {
        saved = Logger.getLogger("").getHandlers();
    }

    @AfterEach
    void restoreRootHandlers() {
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) {
            root.removeHandler(handler);
        }
        for (Handler handler : saved) {
            root.addHandler(handler);
        }
    }

    /// init() leaves the root logger with a single console handler
    /// carrying the Log formatter.
    @Test
    void initInstallsTheFormatterOnTheRootLogger() {
        Log.init();
        Handler[] handlers = Logger.getLogger("").getHandlers();
        assertEquals(1, handlers.length);
        assertTrue(handlers[0] instanceof ConsoleHandler);
        assertTrue(handlers[0].getFormatter() instanceof Log);
    }

    /// format() renders one line: [date] LEVEL [logger]: message.
    @Test
    void formatRendersTheOneLineShape() {
        LogRecord record = new LogRecord(Level.INFO, "hello world");
        record.setLoggerName("dev.mbien.nbmp.mcp.SandboxLauncher");
        String line = new Log().format(record);
        String date = ISO_LOCAL_DATE_TIME.format(Instant.ofEpochMilli(record.getMillis())
                .truncatedTo(ChronoUnit.SECONDS).atZone(ZoneId.systemDefault()));
        assertEquals("[" + date + "] INFO [dev.mbien.nbmp.mcp.SandboxLauncher]: hello world"
                + System.lineSeparator(), line);
    }

    /// A throwable's stack trace follows the line, not on it.
    @Test
    void formatAppendsTheThrowableAfterTheLine() {
        LogRecord record = new LogRecord(Level.SEVERE, "boom");
        record.setLoggerName("dev.mbien.nbmp.mcp.SandboxLauncher");
        record.setThrown(new IllegalStateException("kaputt"));
        String[] lines = new Log().format(record).split(System.lineSeparator());
        assertTrue(lines[0].endsWith("SEVERE [dev.mbien.nbmp.mcp.SandboxLauncher]: boom"));
        assertEquals("java.lang.IllegalStateException: kaputt", lines[1]);
    }
}

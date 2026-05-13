package com.migration.s3.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailedLogTest {

    @TempDir
    Path tmp;

    @Test
    void recordWritesKeyToKeysFileAndDetailsToLogFile() throws IOException {
        Path keys = tmp.resolve("failed.keys");
        Path log = tmp.resolve("failed.log");

        try (FailedLog failed = FailedLog.open(keys, log, () -> Instant.ofEpochSecond(1700000000L))) {
            failed.record("k1", "throttled");
            failed.record("k%202", "oversized");
            failed.flush();
        }

        assertEquals(List.of("k1", "k%202"), Files.readAllLines(keys));
        List<String> details = Files.readAllLines(log);
        assertEquals(2, details.size());
        assertTrue(details.get(0).startsWith("k1\tthrottled\t"));
        assertTrue(details.get(0).endsWith("\t1700000000"));
        assertTrue(details.get(1).startsWith("k%202\toversized\t"));
    }

    @Test
    void duplicateKeyIsIdempotentInKeysFile() throws IOException {
        Path keys = tmp.resolve("failed.keys");
        Path log = tmp.resolve("failed.log");
        try (FailedLog failed = FailedLog.open(keys, log, () -> Instant.ofEpochSecond(1L))) {
            failed.record("k", "r1");
            failed.record("k", "r2");
            failed.flush();
        }
        assertEquals(List.of("k"), Files.readAllLines(keys));
        // log keeps both rows for debugging
        assertEquals(2, Files.readAllLines(log).size());
    }
}

package com.migration.s3.checkpoint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CheckpointLogTest {

    @TempDir
    Path tmp;

    @Test
    void opensEmptyFileWhenMissing() throws IOException {
        Path file = tmp.resolve("ckpt.log");
        try (CheckpointLog log = CheckpointLog.open(file)) {
            assertEquals(0, log.size());
            assertFalse(log.contains("anything"));
        }
        assertTrue(Files.exists(file));
    }

    @Test
    void recordCompletedPersistsAcrossOpen() throws IOException {
        Path file = tmp.resolve("ckpt.log");
        try (CheckpointLog log = CheckpointLog.open(file)) {
            log.recordCompleted("key/a");
            log.recordCompleted("key/b");
            log.flushLocal();
        }
        assertEquals(List.of("key/a", "key/b"), Files.readAllLines(file));

        try (CheckpointLog reopened = CheckpointLog.open(file)) {
            assertEquals(2, reopened.size());
            assertTrue(reopened.contains("key/a"));
            assertTrue(reopened.contains("key/b"));
            assertFalse(reopened.contains("key/c"));
        }
    }

    @Test
    void duplicateRecordIsIdempotent() throws IOException {
        Path file = tmp.resolve("ckpt.log");
        try (CheckpointLog log = CheckpointLog.open(file)) {
            log.recordCompleted("k");
            log.recordCompleted("k");
            log.recordCompleted("k");
            log.flushLocal();
            assertEquals(1, log.size());
        }
        assertEquals(List.of("k"), Files.readAllLines(file));
    }

    @Test
    void loadIgnoresBlankLines() throws IOException {
        Path file = tmp.resolve("ckpt.log");
        Files.writeString(file, "a\n\nb\n");
        try (CheckpointLog log = CheckpointLog.open(file)) {
            assertEquals(2, log.size());
            assertTrue(log.contains("a"));
            assertTrue(log.contains("b"));
        }
    }
}

package com.migration.s3.worker;

import com.migration.s3.checkpoint.CheckpointLog;
import com.migration.s3.metrics.MetricsEmitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorkerPoolTest {

    @TempDir
    Path tmp;

    private CheckpointLog checkpoint;
    private FailedLog failed;

    @BeforeEach
    void setUp() throws IOException {
        checkpoint = CheckpointLog.open(tmp.resolve("ckpt.log"));
        failed = FailedLog.open(tmp.resolve("failed.keys"), tmp.resolve("failed.log"),
                () -> Instant.ofEpochSecond(1L));
    }

    private static WorkerPool.KeyMigrator from(Map<String, MigrationWorker.Result> table) {
        return key -> table.getOrDefault(key,
                MigrationWorker.Result.failed(key, "no-mock-result"));
    }

    @Test
    void countsSuccessFailedOversizedAndRecordsCheckpoint() throws Exception {
        Map<String, MigrationWorker.Result> table = new HashMap<>();
        table.put("a", MigrationWorker.Result.succeeded("a", 10));
        table.put("b", MigrationWorker.Result.failed("b", "AccessDenied"));
        table.put("c", MigrationWorker.Result.oversized("c"));

        WorkerPool.Summary s = WorkerPool.run(
                List.of("a", "b", "c").iterator(),
                from(table), 2,
                checkpoint, failed, MetricsEmitter.noop(), () -> false);

        assertEquals(3, s.attempted());
        assertEquals(1, s.succeeded());
        assertEquals(1, s.failed());
        assertEquals(1, s.oversized());
        assertEquals(0, s.skipped());

        assertTrue(checkpoint.contains("a"));
        assertFalse(checkpoint.contains("b"));
        assertFalse(checkpoint.contains("c"));

        failed.flush();
        List<String> failedKeys = Files.readAllLines(tmp.resolve("failed.keys"));
        assertTrue(failedKeys.contains("b"));
        assertTrue(failedKeys.contains("c"));
    }

    @Test
    void skipsKeysAlreadyInCheckpoint() throws Exception {
        checkpoint.recordCompleted("a");
        AtomicInteger callCount = new AtomicInteger();
        WorkerPool.KeyMigrator migrator = key -> {
            callCount.incrementAndGet();
            return MigrationWorker.Result.succeeded(key, 1);
        };

        WorkerPool.Summary s = WorkerPool.run(
                List.of("a", "b").iterator(),
                migrator, 1,
                checkpoint, failed, MetricsEmitter.noop(), () -> false);

        assertEquals(2, s.attempted());
        assertEquals(1, s.succeeded());
        assertEquals(1, s.skipped());
        assertEquals(1, callCount.get(), "should not call migrator for skipped key");
    }

    @Test
    void honorsStopSignalAndDrainsRemainingKeysGracefully() throws Exception {
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger calls = new AtomicInteger();
        WorkerPool.KeyMigrator migrator = key -> {
            int n = calls.incrementAndGet();
            if (n == 1) stop.set(true);
            return MigrationWorker.Result.succeeded(key, 1);
        };

        WorkerPool.Summary s = WorkerPool.run(
                List.of("a", "b", "c", "d", "e").iterator(),
                migrator, 1,
                checkpoint, failed, MetricsEmitter.noop(), stop::get);

        assertTrue(s.attempted() < 5, "should stop early; attempted=" + s.attempted());
        assertEquals(s.succeeded(), s.attempted());
    }

    @Test
    void emitsMetricsForEachOutcome() throws Exception {
        Map<String, Long> counts = new HashMap<>();
        MetricsEmitter metrics = new MetricsEmitter() {
            @Override public void increment(String name, long value) {
                counts.merge(name, value, Long::sum);
            }
            @Override public void timing(String name, long millis) {}
        };
        Map<String, MigrationWorker.Result> table = new HashMap<>();
        table.put("a", MigrationWorker.Result.succeeded("a", 1234));
        table.put("b", MigrationWorker.Result.failed("b", "x"));

        WorkerPool.run(List.of("a", "b").iterator(),
                from(table), 1, checkpoint, failed, metrics, () -> false);

        assertEquals(2L, counts.get("keys.attempted"));
        assertEquals(1L, counts.get("keys.succeeded"));
        assertEquals(1L, counts.get("keys.failed"));
        assertEquals(1234L, counts.get("bytes.decrypted"));
    }
}

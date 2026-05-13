package com.migration.s3.worker;

import com.migration.s3.checkpoint.CheckpointLog;
import com.migration.s3.metrics.MetricsEmitter;

import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

public final class WorkerPool {

    private WorkerPool() {}

    @FunctionalInterface
    public interface KeyMigrator {
        MigrationWorker.Result migrate(String percentEncodedKey);
    }

    public record Summary(long attempted, long succeeded, long failed,
                          long oversized, long skipped) {}

    public static Summary run(Iterator<String> keys,
                              KeyMigrator migrator,
                              int concurrency,
                              CheckpointLog checkpoint,
                              FailedLog failed,
                              MetricsEmitter metrics,
                              BooleanSupplier stopSignal) throws InterruptedException {
        AtomicLong attempted = new AtomicLong();
        AtomicLong succeeded = new AtomicLong();
        AtomicLong failedCount = new AtomicLong();
        AtomicLong oversized = new AtomicLong();
        AtomicLong skipped = new AtomicLong();

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            while (keys.hasNext()) {
                if (stopSignal.getAsBoolean()) break;
                final String key = keys.next();
                pool.submit(() -> {
                    if (stopSignal.getAsBoolean()) return;
                    processOne(key, migrator, checkpoint, failed, metrics,
                            attempted, succeeded, failedCount, oversized, skipped);
                });
            }
        } finally {
            pool.shutdown();
            if (!pool.awaitTermination(1, TimeUnit.HOURS)) {
                pool.shutdownNow();
            }
        }

        return new Summary(attempted.get(), succeeded.get(), failedCount.get(),
                oversized.get(), skipped.get());
    }

    private static void processOne(String key, KeyMigrator migrator,
                                   CheckpointLog checkpoint, FailedLog failed,
                                   MetricsEmitter metrics,
                                   AtomicLong attempted, AtomicLong succeeded,
                                   AtomicLong failedCount, AtomicLong oversized,
                                   AtomicLong skipped) {
        attempted.incrementAndGet();
        metrics.increment("keys.attempted");
        if (checkpoint.contains(key)) {
            skipped.incrementAndGet();
            metrics.increment("keys.skipped");
            return;
        }
        MigrationWorker.Result result = migrator.migrate(key);
        try {
            switch (result.status()) {
                case SUCCEEDED -> {
                    checkpoint.recordCompleted(key);
                    succeeded.incrementAndGet();
                    metrics.increment("keys.succeeded");
                    metrics.increment("bytes.decrypted", result.bytesDecrypted());
                }
                case OVERSIZED -> {
                    failed.record(key, "oversized");
                    oversized.incrementAndGet();
                    metrics.increment("keys.oversized");
                }
                case FAILED -> {
                    failed.record(key, result.reason());
                    failedCount.incrementAndGet();
                    metrics.increment("keys.failed");
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("failed to write log for key=" + key, e);
        }
    }
}

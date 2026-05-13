package com.migration.s3.worker;

public record BackoffPolicy(long initialMs, long maxMs, int maxAttempts) {

    public static BackoffPolicy defaultKms() {
        return new BackoffPolicy(100L, 5_000L, 6);
    }

    public static BackoffPolicy noWait(int maxAttempts) {
        return new BackoffPolicy(0L, 0L, maxAttempts);
    }

    public long delayForAttempt(int attempt) {
        long ms = initialMs;
        for (int i = 1; i < attempt; i++) {
            ms = Math.min(ms * 2, maxMs);
        }
        return ms;
    }
}

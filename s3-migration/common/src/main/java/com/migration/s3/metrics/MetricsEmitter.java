package com.migration.s3.metrics;

public interface MetricsEmitter {
    void increment(String name, long value);
    void timing(String name, long millis);

    default void increment(String name) {
        increment(name, 1L);
    }

    static MetricsEmitter noop() {
        return new MetricsEmitter() {
            @Override public void increment(String name, long value) {}
            @Override public void timing(String name, long millis) {}
        };
    }
}

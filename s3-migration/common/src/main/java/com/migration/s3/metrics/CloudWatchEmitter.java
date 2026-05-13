package com.migration.s3.metrics;

import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class CloudWatchEmitter implements MetricsEmitter, AutoCloseable {

    private static final int MAX_BATCH = 1000;

    private final CloudWatchClient client;
    private final String namespace;
    private final List<MetricDatum> buffer = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();

    public CloudWatchEmitter(CloudWatchClient client, String namespace) {
        this.client = client;
        this.namespace = namespace;
    }

    @Override
    public void increment(String name, long value) {
        buffer(name, (double) value, StandardUnit.COUNT);
    }

    @Override
    public void timing(String name, long millis) {
        buffer(name, (double) millis, StandardUnit.MILLISECONDS);
    }

    private void buffer(String name, double value, StandardUnit unit) {
        lock.lock();
        try {
            buffer.add(MetricDatum.builder()
                    .metricName(name)
                    .value(value)
                    .unit(unit)
                    .timestamp(Instant.now())
                    .build());
            if (buffer.size() >= MAX_BATCH) {
                flushLocked();
            }
        } finally {
            lock.unlock();
        }
    }

    public void flush() {
        lock.lock();
        try {
            flushLocked();
        } finally {
            lock.unlock();
        }
    }

    private void flushLocked() {
        if (buffer.isEmpty()) return;
        client.putMetricData(PutMetricDataRequest.builder()
                .namespace(namespace)
                .metricData(buffer)
                .build());
        buffer.clear();
    }

    @Override
    public void close() {
        flush();
    }
}

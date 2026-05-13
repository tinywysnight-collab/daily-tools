package com.migration.s3.worker;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public class FailedLog implements AutoCloseable {

    private final BufferedWriter keysWriter;
    private final BufferedWriter logWriter;
    private final Set<String> recordedKeys;
    private final Supplier<Instant> clock;
    private final ReentrantLock lock = new ReentrantLock();

    private FailedLog(BufferedWriter keysWriter, BufferedWriter logWriter,
                      Set<String> recordedKeys, Supplier<Instant> clock) {
        this.keysWriter = keysWriter;
        this.logWriter = logWriter;
        this.recordedKeys = recordedKeys;
        this.clock = clock;
    }

    public static FailedLog open(Path keysFile, Path logFile) throws IOException {
        return open(keysFile, logFile, Clock.systemUTC()::instant);
    }

    public static FailedLog open(Path keysFile, Path logFile, Supplier<Instant> clock) throws IOException {
        if (keysFile.getParent() != null) Files.createDirectories(keysFile.getParent());
        if (logFile.getParent() != null) Files.createDirectories(logFile.getParent());
        if (!Files.exists(keysFile)) Files.createFile(keysFile);
        if (!Files.exists(logFile)) Files.createFile(logFile);

        Set<String> existing = new HashSet<>();
        for (String line : Files.readAllLines(keysFile, StandardCharsets.UTF_8)) {
            if (!line.isEmpty()) existing.add(line);
        }

        BufferedWriter kw = Files.newBufferedWriter(keysFile, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        BufferedWriter lw = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        return new FailedLog(kw, lw, existing, clock);
    }

    public void record(String percentEncodedKey, String reason) throws IOException {
        lock.lock();
        try {
            if (recordedKeys.add(percentEncodedKey)) {
                keysWriter.write(percentEncodedKey);
                keysWriter.write('\n');
            }
            logWriter.write(percentEncodedKey);
            logWriter.write('\t');
            logWriter.write(reason == null ? "" : reason);
            logWriter.write('\t');
            logWriter.write(Long.toString(clock.get().getEpochSecond()));
            logWriter.write('\n');
        } finally {
            lock.unlock();
        }
    }

    public void flush() throws IOException {
        lock.lock();
        try {
            keysWriter.flush();
            logWriter.flush();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            keysWriter.close();
            logWriter.close();
        } finally {
            lock.unlock();
        }
    }
}

package com.migration.s3.checkpoint;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

public class CheckpointLog implements AutoCloseable {

    private final Path file;
    private final Set<String> completed;
    private final BufferedWriter writer;
    private final ReentrantLock writeLock = new ReentrantLock();

    private CheckpointLog(Path file, Set<String> completed, BufferedWriter writer) {
        this.file = file;
        this.completed = completed;
        this.writer = writer;
    }

    public static CheckpointLog open(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        if (!Files.exists(file)) {
            Files.createFile(file);
        }
        Set<String> completed = new HashSet<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isEmpty()) {
                completed.add(line);
            }
        }
        BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        return new CheckpointLog(file, completed, writer);
    }

    public boolean contains(String key) {
        return completed.contains(key);
    }

    public int size() {
        return completed.size();
    }

    public Path file() {
        return file;
    }

    public void recordCompleted(String key) throws IOException {
        writeLock.lock();
        try {
            if (completed.add(key)) {
                writer.write(key);
                writer.write('\n');
            }
        } finally {
            writeLock.unlock();
        }
    }

    public void flushLocal() throws IOException {
        writeLock.lock();
        try {
            writer.flush();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        writeLock.lock();
        try {
            writer.close();
        } finally {
            writeLock.unlock();
        }
    }
}

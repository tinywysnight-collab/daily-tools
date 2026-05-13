package com.migration.s3.inventory;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.S3Object;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ManifestLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MAX_BACKOFF_MS = Duration.ofMinutes(60).toMillis();

    private final AmazonS3 s3;
    private final Duration initialBackoff;
    private final int maxAttempts;

    public ManifestLoader(AmazonS3 s3, Duration initialBackoff, int maxAttempts) {
        this.s3 = s3;
        this.initialBackoff = initialBackoff;
        this.maxAttempts = maxAttempts;
    }

    public Manifest load(String bucket, String key) {
        long backoffMs = Math.max(initialBackoff.toMillis(), 1L);
        AmazonServiceException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                try (S3Object obj = s3.getObject(bucket, key);
                     InputStream in = obj.getObjectContent()) {
                    return parse(in);
                }
            } catch (AmazonServiceException e) {
                if (!isRetryable(e)) {
                    throw e;
                }
                last = e;
                if (attempt == maxAttempts) break;
                sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
            } catch (IOException e) {
                throw new RuntimeException("failed to read manifest", e);
            }
        }
        throw new ManifestUnavailableException(
                "manifest still unavailable after " + maxAttempts + " attempts: s3://" + bucket + "/" + key,
                last);
    }

    private static boolean isRetryable(AmazonServiceException e) {
        return e.getStatusCode() == 404 || "NoSuchKey".equals(e.getErrorCode());
    }

    private static Manifest parse(InputStream in) throws IOException {
        JsonNode root = MAPPER.readTree(in);
        String format = text(root, "fileFormat");
        if (!"CSV".equalsIgnoreCase(format)) {
            throw new IllegalStateException("only CSV inventory is supported, got: " + format);
        }
        List<String> columns = Arrays.stream(text(root, "fileSchema").split(","))
                .map(String::trim)
                .toList();
        List<Manifest.InventoryFile> files = new ArrayList<>();
        for (JsonNode f : root.withArray("files")) {
            files.add(new Manifest.InventoryFile(
                    text(f, "key"),
                    f.path("size").asLong(),
                    text(f, "MD5checksum")));
        }
        return new Manifest(text(root, "sourceBucket"), format, columns, files);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}

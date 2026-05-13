package com.migration.s3.worker;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

public class MigrationWorker {

    public enum Status { SUCCEEDED, OVERSIZED, FAILED }

    public record Result(String key, Status status, String reason, long bytesDecrypted) {
        public static Result succeeded(String key, long bytes) {
            return new Result(key, Status.SUCCEEDED, null, bytes);
        }
        public static Result oversized(String key) {
            return new Result(key, Status.OVERSIZED, "oversized", 0L);
        }
        public static Result failed(String key, String reason) {
            return new Result(key, Status.FAILED, reason, 0L);
        }
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    private static final Set<String> ENVELOPE_HEADERS = Set.of(
            "x-amz-key-v2",
            "x-amz-iv",
            "x-amz-matdesc",
            "x-amz-cek-alg",
            "x-amz-wrap-alg",
            "x-amz-tag-len",
            "x-amz-unencrypted-content-length"
    );

    private final AmazonS3 srcS3;
    private final AmazonS3 srcEncryptedS3;
    private final AmazonS3 dstS3;
    private final String srcBucket;
    private final String dstBucket;
    private final long oversizeThresholdBytes;
    private final BackoffPolicy backoff;
    private final Sleeper sleeper;

    public MigrationWorker(AmazonS3 srcS3, AmazonS3 srcEncryptedS3, AmazonS3 dstS3,
                           String srcBucket, String dstBucket,
                           long oversizeThresholdBytes, BackoffPolicy backoff, Sleeper sleeper) {
        this.srcS3 = srcS3;
        this.srcEncryptedS3 = srcEncryptedS3;
        this.dstS3 = dstS3;
        this.srcBucket = srcBucket;
        this.dstBucket = dstBucket;
        this.oversizeThresholdBytes = oversizeThresholdBytes;
        this.backoff = backoff;
        this.sleeper = sleeper;
    }

    public Result migrate(String percentEncodedKey) {
        String rawKey = URLDecoder.decode(percentEncodedKey, StandardCharsets.UTF_8);

        ObjectMetadata head;
        try {
            head = srcS3.getObjectMetadata(srcBucket, rawKey);
        } catch (AmazonServiceException e) {
            return Result.failed(percentEncodedKey, "head:" + e.getErrorCode());
        }
        if (head.getContentLength() >= oversizeThresholdBytes) {
            return Result.oversized(percentEncodedKey);
        }

        AmazonServiceException lastError = null;
        for (int attempt = 1; attempt <= backoff.maxAttempts(); attempt++) {
            try {
                return copyOnce(percentEncodedKey, rawKey);
            } catch (AmazonServiceException e) {
                if (!isThrottling(e)) {
                    return Result.failed(percentEncodedKey, e.getErrorCode());
                }
                lastError = e;
                if (attempt == backoff.maxAttempts()) break;
                try {
                    sleeper.sleep(backoff.delayForAttempt(attempt));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return Result.failed(percentEncodedKey, "interrupted");
                }
            } catch (IOException e) {
                return Result.failed(percentEncodedKey, "io:" + e.getMessage());
            }
        }
        return Result.failed(percentEncodedKey,
                "throttled:" + (lastError == null ? "?" : lastError.getErrorCode()));
    }

    private Result copyOnce(String percentEncodedKey, String rawKey) throws IOException {
        try (S3Object obj = srcEncryptedS3.getObject(srcBucket, rawKey);
             InputStream body = obj.getObjectContent()) {
            ObjectMetadata src = obj.getObjectMetadata();
            long plaintextLength = plaintextLength(src);
            ObjectMetadata put = buildPutMetadata(src, plaintextLength);
            PutObjectRequest req = new PutObjectRequest(dstBucket, rawKey, body, put);
            dstS3.putObject(req);
            return Result.succeeded(percentEncodedKey, plaintextLength);
        }
    }

    private static long plaintextLength(ObjectMetadata src) {
        String header = src.getUserMetaDataOf("x-amz-unencrypted-content-length");
        if (header != null) {
            return Long.parseLong(header);
        }
        return src.getContentLength();
    }

    private static ObjectMetadata buildPutMetadata(ObjectMetadata src, long plaintextLength) {
        ObjectMetadata md = new ObjectMetadata();
        md.setContentLength(plaintextLength);
        if (src.getContentType() != null) md.setContentType(src.getContentType());
        if (src.getContentEncoding() != null) md.setContentEncoding(src.getContentEncoding());
        if (src.getCacheControl() != null) md.setCacheControl(src.getCacheControl());
        if (src.getContentDisposition() != null) md.setContentDisposition(src.getContentDisposition());
        if (src.getHttpExpiresDate() != null) md.setHttpExpiresDate(src.getHttpExpiresDate());

        Map<String, String> user = src.getUserMetadata();
        if (user != null) {
            for (Map.Entry<String, String> e : user.entrySet()) {
                if (!ENVELOPE_HEADERS.contains(e.getKey().toLowerCase())) {
                    md.addUserMetadata(e.getKey(), e.getValue());
                }
            }
        }
        return md;
    }

    private static boolean isThrottling(AmazonServiceException e) {
        int status = e.getStatusCode();
        if (status == 429 || status == 503) return true;
        String code = e.getErrorCode();
        if (code == null) return false;
        String c = code.toLowerCase();
        return c.contains("throttl") || c.contains("slowdown") || c.contains("toomany");
    }
}

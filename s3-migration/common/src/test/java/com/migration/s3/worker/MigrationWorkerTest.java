package com.migration.s3.worker;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MigrationWorkerTest {

    private static final String SRC = "src-bucket";
    private static final String DST = "dst-bucket";

    private static S3Object decryptedObject(byte[] plaintext, ObjectMetadata md) {
        S3Object obj = new S3Object();
        obj.setBucketName(SRC);
        obj.setKey("k");
        obj.setObjectContent(new S3ObjectInputStream(new ByteArrayInputStream(plaintext), null));
        obj.setObjectMetadata(md);
        return obj;
    }

    private static ObjectMetadata sourceMetadata(long plaintextLength) {
        ObjectMetadata md = new ObjectMetadata();
        md.setContentType("application/octet-stream");
        md.setContentEncoding("identity");
        md.setCacheControl("no-store");
        md.setContentDisposition("attachment; filename=x");
        md.setHttpExpiresDate(new Date(0));
        md.addUserMetadata("x-amz-key-v2", "ENVELOPE_KEY");
        md.addUserMetadata("x-amz-iv", "ENVELOPE_IV");
        md.addUserMetadata("x-amz-matdesc", "{\"kms_cmk_id\":\"...\"}");
        md.addUserMetadata("x-amz-cek-alg", "AES/GCM/NoPadding");
        md.addUserMetadata("x-amz-wrap-alg", "kms+context");
        md.addUserMetadata("x-amz-tag-len", "128");
        md.addUserMetadata("x-amz-unencrypted-content-length", Long.toString(plaintextLength));
        md.addUserMetadata("business-id", "42");
        md.addUserMetadata("uploader", "alice");
        return md;
    }

    private static AmazonServiceException awsError(int status, String code) {
        AmazonServiceException e = new AmazonServiceException(code);
        e.setStatusCode(status);
        e.setErrorCode(code);
        return e;
    }

    private static ObjectMetadata headMetadata(long size) {
        ObjectMetadata md = new ObjectMetadata();
        md.setContentLength(size);
        return md;
    }

    private MigrationWorker worker(AmazonS3 src, AmazonS3 srcEnc, AmazonS3 dst,
                                   BackoffPolicy backoff) {
        return new MigrationWorker(src, srcEnc, dst, SRC, DST,
                5L * 1024L * 1024L * 1024L, backoff, ms -> { /* no-op sleep */ });
    }

    @Test
    void successPathStripsEnvelopePreservesMetadataAndUsesPlaintextLength() throws Exception {
        AmazonS3 src = mock(AmazonS3.class);
        AmazonS3 srcEnc = mock(AmazonS3.class);
        AmazonS3 dst = mock(AmazonS3.class);

        when(src.getObjectMetadata(SRC, "path/foo bar")).thenReturn(headMetadata(123L));
        byte[] plaintext = new byte[]{1, 2, 3};
        when(srcEnc.getObject(SRC, "path/foo bar"))
                .thenReturn(decryptedObject(plaintext, sourceMetadata(plaintext.length)));

        MigrationWorker.Result result = worker(src, srcEnc, dst, BackoffPolicy.noWait(1))
                .migrate("path/foo%20bar");

        assertEquals(MigrationWorker.Status.SUCCEEDED, result.status());
        assertEquals(3L, result.bytesDecrypted());

        ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(dst).putObject(req.capture());
        PutObjectRequest put = req.getValue();
        assertEquals(DST, put.getBucketName());
        assertEquals("path/foo bar", put.getKey());

        ObjectMetadata sent = put.getMetadata();
        assertEquals(3L, sent.getContentLength());
        assertEquals("application/octet-stream", sent.getContentType());
        assertEquals("identity", sent.getContentEncoding());
        assertEquals("no-store", sent.getCacheControl());
        assertEquals("attachment; filename=x", sent.getContentDisposition());
        assertNotNull(sent.getHttpExpiresDate());

        var userMd = sent.getUserMetadata();
        assertEquals("42", userMd.get("business-id"));
        assertEquals("alice", userMd.get("uploader"));
        assertFalse(userMd.containsKey("x-amz-key-v2"));
        assertFalse(userMd.containsKey("x-amz-iv"));
        assertFalse(userMd.containsKey("x-amz-matdesc"));
        assertFalse(userMd.containsKey("x-amz-cek-alg"));
        assertFalse(userMd.containsKey("x-amz-wrap-alg"));
        assertFalse(userMd.containsKey("x-amz-tag-len"));
        assertFalse(userMd.containsKey("x-amz-unencrypted-content-length"));
    }

    @Test
    void oversizedSkipsGetAndPutAndReportsReason() {
        AmazonS3 src = mock(AmazonS3.class);
        AmazonS3 srcEnc = mock(AmazonS3.class);
        AmazonS3 dst = mock(AmazonS3.class);

        long fiveGb = 5L * 1024L * 1024L * 1024L;
        when(src.getObjectMetadata(SRC, "huge")).thenReturn(headMetadata(fiveGb));

        MigrationWorker.Result result = worker(src, srcEnc, dst, BackoffPolicy.noWait(1))
                .migrate("huge");

        assertEquals(MigrationWorker.Status.OVERSIZED, result.status());
        assertEquals("oversized", result.reason());
        verify(srcEnc, never()).getObject(any(String.class), any(String.class));
        verify(dst, never()).putObject(any(PutObjectRequest.class));
    }

    @Test
    void retriesOnKmsThrottleThenSucceeds() throws Exception {
        AmazonS3 src = mock(AmazonS3.class);
        AmazonS3 srcEnc = mock(AmazonS3.class);
        AmazonS3 dst = mock(AmazonS3.class);

        when(src.getObjectMetadata(SRC, "k")).thenReturn(headMetadata(10L));
        byte[] plaintext = new byte[]{9};
        when(srcEnc.getObject(SRC, "k"))
                .thenThrow(awsError(400, "ThrottlingException"))
                .thenThrow(awsError(503, "SlowDown"))
                .thenReturn(decryptedObject(plaintext, sourceMetadata(plaintext.length)));

        MigrationWorker.Result result = worker(src, srcEnc, dst, BackoffPolicy.noWait(6))
                .migrate("k");

        assertEquals(MigrationWorker.Status.SUCCEEDED, result.status());
        verify(srcEnc, times(3)).getObject(SRC, "k");
        verify(dst, times(1)).putObject(any(PutObjectRequest.class));
    }

    @Test
    void failsAfterMaxThrottleAttempts() {
        AmazonS3 src = mock(AmazonS3.class);
        AmazonS3 srcEnc = mock(AmazonS3.class);
        AmazonS3 dst = mock(AmazonS3.class);

        when(src.getObjectMetadata(SRC, "k")).thenReturn(headMetadata(10L));
        when(srcEnc.getObject(SRC, "k")).thenThrow(awsError(400, "ThrottlingException"));

        MigrationWorker.Result result = worker(src, srcEnc, dst, BackoffPolicy.noWait(3))
                .migrate("k");

        assertEquals(MigrationWorker.Status.FAILED, result.status());
        assertTrue(result.reason().toLowerCase().contains("throttl"));
        verify(srcEnc, times(3)).getObject(SRC, "k");
        verify(dst, never()).putObject(any(PutObjectRequest.class));
    }

    @Test
    void doesNotRetryOnNonRetryableError() {
        AmazonS3 src = mock(AmazonS3.class);
        AmazonS3 srcEnc = mock(AmazonS3.class);
        AmazonS3 dst = mock(AmazonS3.class);

        when(src.getObjectMetadata(SRC, "k")).thenReturn(headMetadata(10L));
        when(srcEnc.getObject(SRC, "k")).thenThrow(awsError(403, "AccessDenied"));

        MigrationWorker.Result result = worker(src, srcEnc, dst, BackoffPolicy.noWait(5))
                .migrate("k");

        assertEquals(MigrationWorker.Status.FAILED, result.status());
        assertTrue(result.reason().contains("AccessDenied"));
        verify(srcEnc, times(1)).getObject(SRC, "k");
    }

    @Test
    void putErrorIsAlsoSubjectToThrottleBackoff() {
        AmazonS3 src = mock(AmazonS3.class);
        AmazonS3 srcEnc = mock(AmazonS3.class);
        AmazonS3 dst = mock(AmazonS3.class);

        when(src.getObjectMetadata(SRC, "k")).thenReturn(headMetadata(10L));
        byte[] plaintext = new byte[]{1};
        // Each retry must re-fetch the source (stream isn't replayable).
        when(srcEnc.getObject(SRC, "k"))
                .thenReturn(decryptedObject(plaintext, sourceMetadata(plaintext.length)))
                .thenReturn(decryptedObject(plaintext, sourceMetadata(plaintext.length)));
        when(dst.putObject(any(PutObjectRequest.class)))
                .thenThrow(awsError(503, "SlowDown"))
                .thenReturn(null);

        MigrationWorker.Result result = worker(src, srcEnc, dst, BackoffPolicy.noWait(3))
                .migrate("k");

        assertEquals(MigrationWorker.Status.SUCCEEDED, result.status());
        verify(srcEnc, times(2)).getObject(SRC, "k");
        verify(dst, times(2)).putObject(any(PutObjectRequest.class));
    }
}

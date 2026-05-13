package com.migration.s3.inventory;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ManifestLoaderTest {

    private static final String MANIFEST_JSON = """
            {
              "sourceBucket": "src-bucket",
              "destinationBucket": "arn:aws:s3:::inv-bucket",
              "version": "2016-11-30",
              "creationTimestamp": "1700000000000",
              "fileFormat": "CSV",
              "fileSchema": "Bucket, Key, ETag, Size, LastModifiedDate",
              "files": [
                {"key": "inv/data/abc.csv.gz", "size": 123, "MD5checksum": "deadbeef"},
                {"key": "inv/data/def.csv.gz", "size": 456, "MD5checksum": "cafef00d"}
              ]
            }
            """;

    private S3Object s3ObjectFrom(String body) {
        S3Object obj = new S3Object();
        obj.setObjectContent(new S3ObjectInputStream(
                new ByteArrayInputStream(body.getBytes()), null));
        return obj;
    }

    @Nested
    class Parsing {

        @Test
        void parsesManifestFields() {
            AmazonS3 s3 = mock(AmazonS3.class);
            when(s3.getObject(eq("inv-bucket"), eq("inv/manifest.json")))
                    .thenReturn(s3ObjectFrom(MANIFEST_JSON));

            ManifestLoader loader = new ManifestLoader(s3, Duration.ZERO, 1);
            Manifest m = loader.load("inv-bucket", "inv/manifest.json");

            assertEquals("src-bucket", m.sourceBucket());
            assertEquals("CSV", m.fileFormat());
            assertEquals(List.of("Bucket", "Key", "ETag", "Size", "LastModifiedDate"), m.columns());
            assertEquals(2, m.files().size());
            assertEquals("inv/data/abc.csv.gz", m.files().get(0).key());
            assertEquals(123L, m.files().get(0).size());
            assertEquals("deadbeef", m.files().get(0).md5());
        }

        @Test
        void rejectsNonCsvFormat() {
            String parquetJson = MANIFEST_JSON.replace("\"CSV\"", "\"Parquet\"");
            AmazonS3 s3 = mock(AmazonS3.class);
            when(s3.getObject(eq("inv-bucket"), eq("k")))
                    .thenReturn(s3ObjectFrom(parquetJson));

            ManifestLoader loader = new ManifestLoader(s3, Duration.ZERO, 1);
            assertThrows(IllegalStateException.class, () -> loader.load("inv-bucket", "k"));
        }
    }

    @Nested
    class RetryOnMissing {

        private AmazonServiceException notFound() {
            AmazonServiceException ex = new AmazonServiceException("not found");
            ex.setStatusCode(404);
            ex.setErrorCode("NoSuchKey");
            return ex;
        }

        @Test
        void retriesUntilManifestIsAvailable() {
            AmazonS3 s3 = mock(AmazonS3.class);
            when(s3.getObject(any(String.class), any(String.class)))
                    .thenThrow(notFound())
                    .thenThrow(notFound())
                    .thenReturn(s3ObjectFrom(MANIFEST_JSON));

            ManifestLoader loader = new ManifestLoader(s3, Duration.ofMillis(1), 5);
            Manifest m = loader.load("inv-bucket", "inv/manifest.json");

            assertEquals("src-bucket", m.sourceBucket());
            verify(s3, times(3)).getObject(any(String.class), any(String.class));
        }

        @Test
        void givesUpAfterMaxAttempts() {
            AmazonS3 s3 = mock(AmazonS3.class);
            when(s3.getObject(any(String.class), any(String.class))).thenThrow(notFound());

            ManifestLoader loader = new ManifestLoader(s3, Duration.ofMillis(1), 3);
            assertThrows(ManifestUnavailableException.class,
                    () -> loader.load("inv-bucket", "inv/manifest.json"));
            verify(s3, times(3)).getObject(any(String.class), any(String.class));
        }

        @Test
        void doesNotRetryOnNonRetryableError() {
            AmazonServiceException denied = new AmazonServiceException("denied");
            denied.setStatusCode(403);
            denied.setErrorCode("AccessDenied");

            AmazonS3 s3 = mock(AmazonS3.class);
            when(s3.getObject(any(String.class), any(String.class))).thenThrow(denied);

            ManifestLoader loader = new ManifestLoader(s3, Duration.ofMillis(1), 5);
            assertThrows(AmazonServiceException.class,
                    () -> loader.load("inv-bucket", "inv/manifest.json"));
            verify(s3, times(1)).getObject(any(String.class), any(String.class));
        }
    }
}

package com.migration.s3.inventory;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class InventoryStreamTest {

    private static InputStream gzipCsv(String csv) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(buf);
                 OutputStreamWriter w = new OutputStreamWriter(gz, StandardCharsets.UTF_8)) {
                w.write(csv);
            }
            return new ByteArrayInputStream(buf.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Manifest manifest(List<String> columns, String... fileKeys) {
        List<Manifest.InventoryFile> files = java.util.Arrays.stream(fileKeys)
                .map(k -> new Manifest.InventoryFile(k, 0L, ""))
                .toList();
        return new Manifest("src", "CSV", columns, files);
    }

    @Test
    void emitsTsvWithPercentEncodedKeyAndQuotedEtagStripped() throws IOException {
        Manifest m = manifest(
                List.of("Bucket", "Key", "ETag", "Size", "LastModifiedDate"),
                "part-1.csv.gz");
        Map<String, InputStream> parts = Map.of(
                "part-1.csv.gz",
                gzipCsv("\"src\",\"path/foo.txt\",\"abc123\",\"42\",\"2026-05-13T10:00:00.000Z\"\n"));

        StringWriter out = new StringWriter();
        new InventoryStream(m, parts::get, "").writeRecords(out);

        assertEquals("path/foo.txt\tabc123\t42\t2026-05-13T10:00:00.000Z\n", out.toString());
    }

    @Test
    void percentEncodesSpacesTabsAndUnicode() throws IOException {
        // S3 Inventory URL-encodes keys with form-style + for spaces and %XX for the rest.
        // Raw key: "a b\tc/中"  --> S3 inventory CSV column: "a+b%09c/%E4%B8%AD"
        Manifest m = manifest(
                List.of("Bucket", "Key", "ETag", "Size", "LastModifiedDate"),
                "p.csv.gz");
        Map<String, InputStream> parts = Map.of(
                "p.csv.gz",
                gzipCsv("\"src\",\"a+b%09c/%E4%B8%AD\",\"e\",\"1\",\"t\"\n"));

        StringWriter out = new StringWriter();
        new InventoryStream(m, parts::get, "").writeRecords(out);

        // Output must percent-encode space as %20, tab as %09, preserve /, encode unicode bytes
        assertEquals("a%20b%09c/%E4%B8%AD\te\t1\tt\n", out.toString());
    }

    @Test
    void filtersBySuffix() throws IOException {
        Manifest m = manifest(
                List.of("Bucket", "Key", "ETag", "Size", "LastModifiedDate"),
                "p.csv.gz");
        Map<String, InputStream> parts = Map.of(
                "p.csv.gz",
                gzipCsv("""
                        "src","data/a.txt","e","1","t"
                        "src","data/a.txt.instruction","e","1","t"
                        "src","data/b.txt","e","2","t"
                        """));

        StringWriter out = new StringWriter();
        new InventoryStream(m, parts::get, ".instruction").writeRecords(out);

        assertEquals("data/a.txt\te\t1\tt\ndata/b.txt\te\t2\tt\n", out.toString());
    }

    @Test
    void concatenatesMultipleParts() throws IOException {
        Manifest m = manifest(
                List.of("Bucket", "Key", "ETag", "Size", "LastModifiedDate"),
                "a.csv.gz", "b.csv.gz");
        Map<String, InputStream> parts = Map.of(
                "a.csv.gz", gzipCsv("\"s\",\"a\",\"e\",\"1\",\"t\"\n"),
                "b.csv.gz", gzipCsv("\"s\",\"b\",\"e\",\"2\",\"t\"\n"));

        StringWriter out = new StringWriter();
        new InventoryStream(m, parts::get, "").writeRecords(out);

        assertEquals("a\te\t1\tt\nb\te\t2\tt\n", out.toString());
    }

    @Test
    void rejectsMissingRequiredColumn() {
        Manifest m = manifest(List.of("Bucket", "Size"), "x.csv.gz");
        Map<String, InputStream> parts = Map.of("x.csv.gz", gzipCsv(""));

        assertThrows(IllegalStateException.class,
                () -> new InventoryStream(m, parts::get, "").writeRecords(new StringWriter()));
    }
}

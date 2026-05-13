package com.migration.s3.inventory;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

public class InventoryStream {

    private static final List<String> REQUIRED = List.of("Key", "ETag", "Size", "LastModifiedDate");

    private final Manifest manifest;
    private final Function<String, InputStream> partOpener;
    private final String filterSuffix;
    private final int keyCol;
    private final int etagCol;
    private final int sizeCol;
    private final int lastModifiedCol;

    public InventoryStream(Manifest manifest, Function<String, InputStream> partOpener, String filterSuffix) {
        this.manifest = manifest;
        this.partOpener = partOpener;
        this.filterSuffix = filterSuffix == null ? "" : filterSuffix;
        List<String> columns = manifest.columns();
        for (String required : REQUIRED) {
            if (!columns.contains(required)) {
                throw new IllegalStateException("inventory schema missing column: " + required
                        + "; got " + columns);
            }
        }
        this.keyCol = columns.indexOf("Key");
        this.etagCol = columns.indexOf("ETag");
        this.sizeCol = columns.indexOf("Size");
        this.lastModifiedCol = columns.indexOf("LastModifiedDate");
    }

    public void writeRecords(Writer out) throws IOException {
        for (Manifest.InventoryFile file : manifest.files()) {
            try (InputStream raw = partOpener.apply(file.key());
                 GZIPInputStream gz = new GZIPInputStream(raw);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(gz, StandardCharsets.UTF_8));
                 CSVParser parser = CSVFormat.DEFAULT.builder().build().parse(reader)) {
                for (CSVRecord row : parser) {
                    String rawKey = row.get(keyCol);
                    String decoded = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
                    if (!filterSuffix.isEmpty() && decoded.endsWith(filterSuffix)) {
                        continue;
                    }
                    out.write(percentEncode(decoded));
                    out.write('\t');
                    out.write(stripQuotes(row.get(etagCol)));
                    out.write('\t');
                    out.write(row.get(sizeCol));
                    out.write('\t');
                    out.write(row.get(lastModifiedCol));
                    out.write('\n');
                }
            }
        }
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    static String percentEncode(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || c == '/') {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xf, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }
}

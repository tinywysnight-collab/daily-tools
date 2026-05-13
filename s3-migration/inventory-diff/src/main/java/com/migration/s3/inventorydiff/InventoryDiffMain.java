package com.migration.s3.inventorydiff;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.S3Object;
import com.migration.s3.delta.ExternalSortDiff;
import com.migration.s3.inventory.InventoryStream;
import com.migration.s3.inventory.Manifest;
import com.migration.s3.inventory.ManifestLoader;
import com.migration.s3.inventory.ManifestUnavailableException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public final class InventoryDiffMain {

    private InventoryDiffMain() {}

    public static void main(String[] args) {
        try {
            int exit = run(parseArgs(args));
            System.exit(exit);
        } catch (IllegalArgumentException e) {
            System.err.println("config error: " + e.getMessage());
            System.exit(1);
        } catch (ManifestUnavailableException e) {
            System.err.println("manifest unavailable: " + e.getMessage());
            System.exit(3);
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(4);
        }
    }

    static int run(Args a) throws IOException {
        AmazonS3 s3 = buildS3(a);
        ManifestLoader loader = new ManifestLoader(s3, Duration.ofMinutes(5), 12);

        Path workDir = Files.createTempDirectory(a.scratchDir, "inv-diff-");
        Path currentRecords = workDir.resolve("current.records");
        try (BufferedWriter w = Files.newBufferedWriter(currentRecords, StandardCharsets.UTF_8)) {
            Manifest current = loader.load(a.currentBucket, a.currentManifestKey);
            new InventoryStream(current, key -> openPart(s3, a.inventoryBucket, key),
                    a.filterSuffix).writeRecords(w);
        }

        ExternalSortDiff diff = new ExternalSortDiff(workDir, a.sortMem, a.sortParallel);
        Path currentSorted = workDir.resolve("current.sorted");
        diff.sortRecords(currentRecords, currentSorted);

        Path output = a.outFile;
        if (a.baselineManifestKey == null) {
            diff.allKeys(currentSorted, output);
        } else {
            Path baselineRecords = workDir.resolve("baseline.records");
            try (BufferedWriter w = Files.newBufferedWriter(baselineRecords, StandardCharsets.UTF_8)) {
                Manifest baseline = loader.load(a.baselineBucket, a.baselineManifestKey);
                new InventoryStream(baseline, key -> openPart(s3, a.inventoryBucket, key),
                        a.filterSuffix).writeRecords(w);
            }
            Path baselineSorted = workDir.resolve("baseline.sorted");
            diff.sortRecords(baselineRecords, baselineSorted);
            diff.deltaKeys(baselineSorted, currentSorted, output);
        }

        return 0;
    }

    private static InputStream openPart(AmazonS3 s3, String bucket, String key) {
        S3Object obj = s3.getObject(bucket, key);
        return obj.getObjectContent();
    }

    private static AmazonS3 buildS3(Args a) {
        AmazonS3ClientBuilder b = AmazonS3ClientBuilder.standard();
        if (a.region != null) b.setRegion(a.region);
        return b.build();
    }

    record Args(String currentManifestUri,
                String baselineManifestUri,
                String currentBucket, String currentManifestKey,
                String baselineBucket, String baselineManifestKey,
                String inventoryBucket,
                Path outFile,
                Path scratchDir,
                String sortMem, int sortParallel,
                String filterSuffix,
                String region) {
    }

    static Args parseArgs(String[] argv) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            if (!a.startsWith("--")) throw new IllegalArgumentException("unexpected token: " + a);
            String key = a.substring(2);
            if (i + 1 >= argv.length || argv[i + 1].startsWith("--")) {
                opts.put(key, "true");
            } else {
                opts.put(key, argv[++i]);
            }
        }

        String currentManifest = required(opts, "current-manifest");
        String inventoryBucket = required(opts, "inventory-bucket");
        String baselineManifest = opts.get("baseline-manifest");
        Path scratchDir = Paths.get(opts.getOrDefault("scratch-dir", System.getProperty("java.io.tmpdir")));
        Path outFile = Paths.get(required(opts, "out"));
        String sortMem = opts.getOrDefault("sort-mem", "8G");
        int sortParallel = Integer.parseInt(opts.getOrDefault("sort-parallel",
                String.valueOf(Runtime.getRuntime().availableProcessors())));
        String filterSuffix = opts.getOrDefault("filter-suffix", "");
        String region = opts.get("region");

        String[] cur = parseS3Uri(currentManifest);
        String[] base = baselineManifest == null ? null : parseS3Uri(baselineManifest);

        return new Args(currentManifest, baselineManifest,
                cur[0], cur[1],
                base == null ? null : base[0],
                base == null ? null : base[1],
                inventoryBucket, outFile, scratchDir,
                sortMem, sortParallel, filterSuffix, region);
    }

    static String[] parseS3Uri(String uri) {
        URI u = URI.create(uri);
        if (!"s3".equals(u.getScheme())) {
            throw new IllegalArgumentException("expected s3:// URI, got: " + uri);
        }
        String bucket = u.getHost();
        String key = u.getPath();
        if (key.startsWith("/")) key = key.substring(1);
        return new String[]{bucket, key};
    }

    private static String required(Map<String, String> opts, String key) {
        String v = opts.get(key);
        if (v == null) throw new IllegalArgumentException("--" + key + " is required");
        return v;
    }
}

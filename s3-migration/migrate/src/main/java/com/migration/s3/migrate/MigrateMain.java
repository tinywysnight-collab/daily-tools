package com.migration.s3.migrate;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.AmazonS3EncryptionClient;
import com.amazonaws.services.s3.model.CryptoConfiguration;
import com.amazonaws.services.s3.model.CryptoMode;
import com.amazonaws.services.s3.model.KMSEncryptionMaterialsProvider;
import com.migration.s3.checkpoint.CheckpointLog;
import com.migration.s3.metrics.MetricsEmitter;
import com.migration.s3.worker.BackoffPolicy;
import com.migration.s3.worker.FailedLog;
import com.migration.s3.worker.MigrationWorker;
import com.migration.s3.worker.WorkerPool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MigrateMain {

    private static final String STOP_FLAG_KEY_DEFAULT = "migration-logs/STOP";
    private static final long OVERSIZE_BYTES = 5L * 1024L * 1024L * 1024L;

    private MigrateMain() {}

    public static void main(String[] args) {
        try {
            int code = run(parseArgs(args));
            System.exit(code);
        } catch (IllegalArgumentException e) {
            System.err.println("config error: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(4);
        }
    }

    static int run(Args a) throws Exception {
        AmazonS3 src = buildS3(a.region);
        AmazonS3 dst = buildS3(a.region);
        AmazonS3 srcEnc = buildEncryptionClient(a.region, a.kmsKeyId);
        MetricsEmitter metrics = MetricsEmitter.noop();

        try (CheckpointLog checkpoint = CheckpointLog.open(a.checkpointFile);
             FailedLog failed = FailedLog.open(a.failedKeysFile, a.failedLogFile)) {

            AtomicBoolean stop = new AtomicBoolean(false);
            ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "stop-flag-poller");
                t.setDaemon(true);
                return t;
            });
            poller.scheduleAtFixedRate(() -> {
                try {
                    if (dst.doesObjectExist(a.dstBucket, a.stopFlagKey)) {
                        stop.set(true);
                    }
                } catch (Exception e) {
                    System.err.println("stop-flag poll failed: " + e.getMessage());
                }
            }, 0, 10, TimeUnit.SECONDS);

            ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "checkpoint-flusher");
                t.setDaemon(true);
                return t;
            });
            flusher.scheduleAtFixedRate(() -> {
                try {
                    checkpoint.flushLocal();
                    failed.flush();
                } catch (IOException e) {
                    System.err.println("flush failed: " + e.getMessage());
                }
            }, 5, 5, TimeUnit.SECONDS);

            MigrationWorker worker = new MigrationWorker(
                    src, srcEnc, dst, a.srcBucket, a.dstBucket,
                    OVERSIZE_BYTES, BackoffPolicy.defaultKms(), Thread::sleep);

            Iterator<String> keys = openKeyIterator(a.keyListFile);
            WorkerPool.Summary summary = WorkerPool.run(
                    keys, worker::migrate, a.concurrency,
                    checkpoint, failed, metrics, stop::get);

            checkpoint.flushLocal();
            failed.flush();
            poller.shutdownNow();
            flusher.shutdownNow();

            System.err.printf("attempted=%d succeeded=%d failed=%d oversized=%d skipped=%d%n",
                    summary.attempted(), summary.succeeded(), summary.failed(),
                    summary.oversized(), summary.skipped());

            if (stop.get()) return 3;
            if (summary.failed() > 0 || summary.oversized() > 0) return 2;
            return 0;
        }
    }

    private static AmazonS3 buildS3(String region) {
        AmazonS3ClientBuilder b = AmazonS3ClientBuilder.standard();
        if (region != null) b.setRegion(region);
        return b.build();
    }

    @SuppressWarnings("deprecation")
    private static AmazonS3 buildEncryptionClient(String region, String kmsKeyId) {
        KMSEncryptionMaterialsProvider provider = new KMSEncryptionMaterialsProvider(kmsKeyId);
        CryptoConfiguration crypto = new CryptoConfiguration(CryptoMode.AuthenticatedEncryption);
        if (region != null) crypto.setAwsKmsRegion(com.amazonaws.regions.Region.getRegion(
                com.amazonaws.regions.Regions.fromName(region)));
        AmazonS3EncryptionClient client = (AmazonS3EncryptionClient)
                AmazonS3EncryptionClient.encryptionBuilder()
                        .withEncryptionMaterials(provider)
                        .withCryptoConfiguration(crypto)
                        .build();
        return client;
    }

    private static Iterator<String> openKeyIterator(Path keyListFile) throws IOException {
        BufferedReader reader;
        if (keyListFile == null) {
            reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        } else {
            reader = Files.newBufferedReader(keyListFile, StandardCharsets.UTF_8);
        }
        return new Iterator<>() {
            private String next;
            private boolean done;

            @Override
            public boolean hasNext() {
                if (done) return false;
                if (next != null) return true;
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.isEmpty()) {
                            next = line;
                            return true;
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                done = true;
                try { reader.close(); } catch (IOException ignore) {}
                return false;
            }

            @Override
            public String next() {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                String v = next;
                next = null;
                return v;
            }
        };
    }

    record Args(String srcBucket, String dstBucket, String kmsKeyId,
                int concurrency,
                Path checkpointFile, Path failedKeysFile, Path failedLogFile,
                String stopFlagKey,
                String region, Path keyListFile) {}

    static Args parseArgs(String[] argv) {
        Map<String, String> opts = new HashMap<>();
        Path positional = null;
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                if (i + 1 >= argv.length || argv[i + 1].startsWith("--")) {
                    opts.put(key, "true");
                } else {
                    opts.put(key, argv[++i]);
                }
            } else if (positional == null) {
                positional = Paths.get(a);
            } else {
                throw new IllegalArgumentException("unexpected positional: " + a);
            }
        }

        String srcBucket = required(opts, "src-bucket");
        String dstBucket = required(opts, "dst-bucket");
        String kmsKeyId = required(opts, "kms-key-id");
        int concurrency = Integer.parseInt(opts.getOrDefault("concurrency", "20"));
        Path ckpt = Paths.get(opts.getOrDefault("checkpoint", "./checkpoint.log"));
        Path failedKeys = Paths.get(opts.getOrDefault("failed-keys", "./failed.keys"));
        Path failedLog = Paths.get(opts.getOrDefault("failed-log", "./failed.log"));
        String stopFlag = opts.getOrDefault("stop-flag-key", STOP_FLAG_KEY_DEFAULT);
        String region = opts.get("region");
        return new Args(srcBucket, dstBucket, kmsKeyId, concurrency,
                ckpt, failedKeys, failedLog, stopFlag, region, positional);
    }

    private static String required(Map<String, String> opts, String key) {
        String v = opts.get(key);
        if (v == null) throw new IllegalArgumentException("--" + key + " is required");
        return v;
    }
}

# S3 CSE-KMS Migration Plan

## Table of Contents

1. [Background](#background)
2. [Timeline](#timeline)
3. [Tech Stack](#tech-stack)
4. [Directory Layout](#directory-layout)
5. [Key Algorithms](#key-algorithms)
6. [Maven Dependencies](#maven-dependencies)
7. [CLI Reference](#cli-reference)
8. [Checkpoint and Fault Tolerance](#checkpoint-and-fault-tolerance)
9. [Makefile Targets](#makefile-targets)
10. [Operational Runbook](#operational-runbook)
11. [Additional Features](#additional-features)
12. [Constraints TBD](#constraints-tbd)

## Background

The source S3 bucket contains about **10 million files** encrypted with **AWS S3 V1 Java SDK client-side encryption (CSE-KMS)**. The goal is to decrypt every file and write plaintext objects to a new destination bucket. New files are still written daily; writes stop at time T, and all files must be migrated by T+2.

The migration uses daily S3 Inventory manifests (CSV, no Athena) as the primary file list. Because S3 Inventory is **not guaranteed to be sorted**, daily deltas are computed by normalizing inventory rows, externally sorting them on disk, and then running a sorted set difference. This does not require Athena or a separately deployed database. Decryption uses the **AWS V1 Java SDK** (`AmazonS3EncryptionClient`) to avoid protocol reimplementation risk.

This plan migrates **current versions only**. Historical versions and delete markers are out of scope; destination objects are treated as new PUTs.

## Timeline

| Day | Action |
|-----|--------|
| Now | Create S3 Inventory configuration; first delivery can take up to 48 hours |
| T-14 or T-7 | **Full migration**: use that day's inventory as baseline and migrate all ~10M keys; start at T-14 if throughput testing makes T-7 risky |
| After full run | **Delta**: latest inventory minus the full-run baseline |
| T-3 through T+0 | Daily delta: current inventory minus previous day's inventory |
| T+1, T+2 | Final catch-up deltas after writes stop |
| T+2 | **Deadline**: all files migrated |

## Tech Stack

**Java 17 (LTS) + Maven**. Two fat JARs are built from one multi-module Maven project with `maven-shade-plugin`:

- `inventory-diff.jar`: computes daily delta key lists
- `migrate.jar`: decrypts and copies files

Java is used because it can directly call `AmazonS3EncryptionClient` from the V1 SDK. The SDK handles envelope unwrap, KMS decrypt, and AES object-body decryption exactly as the original encryption client did.

## Directory Layout

```text
s3-migration/
  plan.zh.md
  plan.en.md
  pom.xml
  Makefile
  inventory-diff/
    pom.xml
    src/main/java/com/migration/s3/inventorydiff/
      InventoryDiffMain.java
    src/test/java/com/migration/s3/inventorydiff/
      InventoryDiffMainTest.java
  migrate/
    pom.xml
    src/main/java/com/migration/s3/migrate/
      MigrateMain.java
    src/test/java/com/migration/s3/migrate/
      MigrateMainTest.java
  common/
    pom.xml
    src/main/java/com/migration/s3/
      inventory/
        Manifest.java
        ManifestLoader.java
        InventoryReader.java
        InventoryStream.java
      delta/
        ExternalSortDiff.java
      worker/
        MigrationWorker.java
        WorkerPool.java
      checkpoint/
        CheckpointLog.java
      metrics/
        MetricsEmitter.java
        CloudWatchEmitter.java
        NoopEmitter.java
    src/test/java/com/migration/s3/
      delta/ExternalSortDiffTest.java
      inventory/InventoryReaderTest.java
      checkpoint/CheckpointLogTest.java
      worker/MigrationWorkerTest.java
  scripts/
    setup-inventory.sh
    run-t7.sh
    run-daily.sh
    verify-listobjects-v2.py
```

## Key Algorithms

### Inventory Structure and Delta Diff

S3 Inventory does not guarantee key order, so two manifests cannot be diffed directly with a two-pointer merge. The correct flow is:

1. `InventoryStream` iterates all gzipped CSV parts listed in `manifest.json`.
2. Parse CSV fields and URL-decode the object key.
3. Configure Inventory as `Current version only`; historical versions and delete markers are not migrated.
4. Emit comparison record: `key<TAB>etag<TAB>size<TAB>lastModified`. This captures both new keys and same-key overwrites.
5. Externally sort on EC2 local NVMe/EBS scratch, for example `LC_ALL=C sort -S 24G --parallel=$(nproc) -T /mnt/scratch -u`.
6. Run a sorted set difference and output `current - baseline`.
7. Project the delta back to keys and deduplicate keys; if the same key changed multiple times, only the final current version needs migration.

This remains a no-Athena, no-separate-DB approach. Memory is bounded by `sort -S`; data spills to disk. Use about `-S 20G` on a 32GB EC2 instance and about `-S 45G` on a 64GB instance, leaving room for the JVM, page cache, and the OS.

### Delta Algorithm (External Sort)

```bash
normalize_inventory(baseline_manifest) > baseline.records
normalize_inventory(current_manifest)  > current.records

LC_ALL=C sort -S SORT_MEM -T SCRATCH -u baseline.records > baseline.sorted
LC_ALL=C sort -S SORT_MEM -T SCRATCH -u current.records  > current.sorted

comm -13 baseline.sorted current.sorted \
  | cut -f1 \
  | LC_ALL=C sort -S SORT_MEM -T SCRATCH -u \
  > delta.keys
```

Output: keys that exist in `current` but not in `baseline`, one key per line.

For a full run, omit `--baseline`; `inventory-diff` outputs the normalized and sorted key list from the current manifest.

**URL-encoded keys**: S3 Inventory URL-encodes keys that contain special characters. Decode with `URLDecoder.decode(key, StandardCharsets.UTF_8)` before comparison and output.

**Current-version scope**: The migration copies only the source bucket's current versions. If the source bucket has versioning enabled, historical versions and delete markers are out of scope. The destination bucket gets its own new version history through PUT operations.

### V1 CSE-KMS Decryption

`MigrationWorker` uses `AmazonS3EncryptionClient` from the V1 SDK to GET the object. The SDK transparently handles the envelope flow:

1. Read `x-amz-key-v2` and `x-amz-matdesc` from object user metadata.
2. Call `KMS.Decrypt` with the encrypted DEK and encryption context.
3. Decrypt the object body with AES-CBC or AES-GCM based on `x-amz-cek-alg`.
4. Return a plaintext `InputStream`.

The worker then uses a regular `AmazonS3Client` to `putObject` to the destination bucket without client-side encryption.

**Oversized file handling**: Before GET, issue a HEAD request to obtain `Content-Length`. If the object is `>= 5 GB`, skip it, append `key\tsize\ttimestamp` to `oversized.log`, increment the `keys.skipped` metric, and continue without treating it as a failure.

**Failure logging**: When any key fails during decryption or PUT, append `key\treason\ttimestamp` to `failed.log`. The total failure count is written to stderr on exit. `failed.log` can be fed directly as input to the next `migrate.jar` run for retries.

## Maven Dependencies

Defined in `common/pom.xml`:

```xml
<dependency>
  <groupId>com.amazonaws</groupId>
  <artifactId>aws-java-sdk-s3</artifactId>
  <version>1.12.750</version>
</dependency>
<dependency>
  <groupId>com.amazonaws</groupId>
  <artifactId>aws-java-sdk-kms</artifactId>
  <version>1.12.750</version>
</dependency>
<dependency>
  <groupId>software.amazon.awssdk</groupId>
  <artifactId>cloudwatch</artifactId>
  <version>2.26.0</version>
</dependency>
<dependency>
  <groupId>org.apache.commons</groupId>
  <artifactId>commons-csv</artifactId>
  <version>1.11.0</version>
</dependency>
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
  <version>2.17.1</version>
</dependency>
<dependency>
  <groupId>org.junit.jupiter</groupId>
  <artifactId>junit-jupiter</artifactId>
  <version>5.11.0</version>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.mockito</groupId>
  <artifactId>mockito-core</artifactId>
  <version>5.12.0</version>
  <scope>test</scope>
</dependency>
```

## CLI Reference

### `inventory-diff.jar`

```text
java -jar inventory-diff.jar [flags]

--baseline-manifest  Baseline manifest.json S3 URI; omit for full mode
--current-manifest   Current manifest.json S3 URI; required
--inventory-bucket   Bucket holding the CSV parts; required
--out                Output file path; default stdout
--scratch-dir        External sort temp directory; local NVMe/EBS recommended
--sort-mem           sort memory budget; 32GB EC2: 20G, 64GB EC2: 45G
--sort-parallel      sort parallelism; default vCPU count
--validate-checksums Validate MD5 of each CSV part before reading; default true
--region             AWS region
--profile            AWS credentials profile
--verbose
```

Exit codes: `0` success, `1` configuration error, `2` S3 error, `3` manifest/checksum error, `4` I/O error.

### `migrate.jar`

```text
java -jar migrate.jar [flags] [key-list-file]

--src-bucket        Encrypted source bucket; required
--dst-bucket        Plaintext destination bucket; required
--concurrency       Worker threads; default 20, use 50 for T-7
--checkpoint        Local checkpoint file; default ./checkpoint.log
--checkpoint-s3     S3 URI for checkpoint backup (default: s3://DST_BUCKET/migration-logs/<run>-checkpoint.log)
--oversized-log-s3  S3 URI for oversized-file log backup (default: s3://DST_BUCKET/migration-logs/<run>-oversized.log)
--failed-log-s3     S3 URI for failure log backup (default: s3://DST_BUCKET/migration-logs/<run>-failed.log)
--stop-flag-bucket  Bucket for the emergency stop flag; required
--stop-flag-key     Stop flag S3 key; default migration/STOP
--dry-run           Decrypt and discard; do not PUT to destination
--metrics           Emit CloudWatch metrics in namespace S3Migration
--verify-sample     Fraction of migrated keys to HEAD-verify; default 0.01
--log-every         Progress log interval in keys; default 1000
--oversized-log     Local path for oversized-file log (>= 5 GB); default ./oversized.log
--failed-log        Local path for per-key failure log; default ./failed.log
--region
--profile
```

Exit codes: `0` all migrated, `1` configuration error, `2` partial failure, `3` stop flag triggered, `4` checkpoint error.

The key list is read from the positional file argument or stdin.

## Checkpoint and Fault Tolerance

- Local append-only file: one completed S3 key per line.
- Load into an in-memory `HashSet<String>` on startup for O(1) `contains()` checks.
- Memory estimate: 10M keys × ~80 bytes average ≈ ~800 MB JVM heap; use `-Xmx2g`.
- Flush to local file every 100 completions or every 5 seconds.
- Sync to S3 every 10,000 completions or every 10 minutes.
- The checkpoint, `oversized.log`, and `failed.log` are all backed up to the destination bucket under the `migration-logs/` prefix to keep them separate from business objects:
  ```
  s3://DST_BUCKET/migration-logs/
    t7-checkpoint.log
    t7-oversized.log
    t7-failed.log
    delta-YYYY-MM-DD-checkpoint.log
    delta-YYYY-MM-DD-oversized.log
    delta-YYYY-MM-DD-failed.log
  ```
- On startup, download the checkpoint from S3 if the local file is absent.
- Emergency stop: `aws s3 cp /dev/null s3://STOP_BUCKET/migration/STOP`; workers poll every 1,000 tasks.
- **Note**: the `ListObjectsV2` reconciliation script must use `--dst-prefix` to exclude the `migration-logs/` prefix so log files do not appear in `extra-in-destination.txt`.

## Makefile Targets

| Target | Command | Description |
|--------|---------|-------------|
| `build` | `mvn package -DskipTests` | Build, skipping tests |
| `test` | `mvn verify` | Build and run all tests |
| `clean` | `mvn clean` | Clean build outputs |
| `release` | `mvn package -DskipTests -Prelease` | Produce Linux-runnable fat JARs |

## Operational Runbook

### Day 0

```bash
./scripts/setup-inventory.sh SRC_BUCKET INV_BUCKET migration/inventory REGION
# Inventory must be Current version only and include Key, ETag, Size, LastModified

aws s3 ls s3://INV_BUCKET/migration/inventory/
make release
```

### T-7 or T-14 Full Migration

```bash
./scripts/run-t7.sh
# Use --concurrency 50
# checkpoint-s3 = s3://OPS_BUCKET/migration/t7-checkpoint.log
```

On crash, rerun the same command. The checkpoint skips completed keys.

### Daily Incremental

```bash
./scripts/run-daily.sh YYYY-MM-DD-prev YYYY-MM-DD-today
```

### T+2 Final Verification

```bash
java -jar inventory-diff.jar \
  --baseline-manifest s3://INV_BUCKET/.../t-7/manifest.json \
  --current-manifest  s3://INV_BUCKET/.../t+2/manifest.json \
  --inventory-bucket INV_BUCKET | wc -l

sort t7-checkpoint.log delta-*.log | uniq | wc -l

java -jar migrate.jar --dry-run --src-bucket SRC --dst-bucket DST missing-keys.txt

aws s3 cp s3://DST_BUCKET/some-key /tmp/check && file /tmp/check
```

### Full Key Reconciliation with ListObjectsV2

After confirming there are no new writes at T, scan current key sets in the source and destination buckets, externally sort them, and run a set difference:

```bash
python3 scripts/verify-listobjects-v2.py \
  --src-bucket SRC_BUCKET \
  --dst-bucket DST_BUCKET \
  --workdir /mnt/scratch/verify \
  --sort-mem 20G \
  --sort-parallel "$(nproc)" \
  --region REGION
```

Outputs:

- `missing-in-destination.txt`: source current keys absent from destination; must be 0.
- `extra-in-destination.txt`: destination keys absent from source; usually for manual review.

Because this migration only handles current versions, `ListObjectsV2` reconciliation matches the migration scope. It verifies key coverage; for content verification, download a sample and compare size/hash.

## Additional Features

| # | Feature | Description |
|---|---------|-------------|
| 1 | Preflight dry-run | Run on 1,000 random keys before T-7 to confirm end-to-end decryption |
| 2 | Emergency stop flag | Checked every 1,000 tasks for graceful drain without a kill signal |
| 3 | Manifest availability polling | `ManifestLoader` retries with exponential backoff from 5 to 60 minutes |
| 4 | Verification sampling | HEAD-check random destination samples after the worker pool drains |
| 5 | CloudWatch metrics | `keys.attempted/succeeded/failed/skipped`, `bytes.decrypted`, `kms.calls`, `decrypt.duration_ms` |
| 6 | CSV MD5 validation | Detect corrupted inventory parts before processing |
| 7 | Oversized file log | HEAD before GET; objects >= 5 GB are written to `oversized.log` (key, size, timestamp) and counted as `keys.skipped` without interrupting the run |
| 8 | Structured failure log | Every failed key is appended to `failed.log` (key, reason, timestamp); the file can be fed directly as input to the next run for retries |

## Constraints TBD

- [ ] Source bucket name: `___________`
- [ ] Destination bucket name: `___________`
- [ ] Inventory bucket name: `___________`
- [ ] AWS region: `___________`
- [ ] KMS key ARN for IAM validation: `___________`
- [ ] EC2/ECS spec for running `migrate.jar`: `___________`
- [ ] Concurrency ceiling from KMS TPS quota: `___________`
- [ ] VPC Endpoint required: `___________`
- [ ] Log retention policy: `___________`
- [x] No Athena
- [x] No separately deployed DB for now; RocksDB/SQLite installability TBD
- [x] 32GB or 64GB EC2 is available for diff and verification jobs
- [x] One-time migration from V1 CSE-KMS; continue using AWS Java SDK V1 for decryption
- [x] If T-7 is risky, full migration can start at T-14
- [x] Migrate current versions only; destination objects are treated as new files
- [x] No new writes after T
- [ ] Other constraints: `___________`

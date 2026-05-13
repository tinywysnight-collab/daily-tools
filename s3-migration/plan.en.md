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

This plan migrates **current versions only**. Historical versions and delete markers are out of scope; destination objects are treated as new PUTs. **Source-side deletions are NOT propagated to the destination**: if a key existed in the baseline inventory but is absent from the current inventory, the destination keeps the old copy. Consequently, `extra-in-destination.txt` is an expected output, not a failure.

**Destination bucket configuration** (confirmed):

- **SSE-KMS** enabled (destination KMS key supplied by the business; distinct from the source KMS key).
- **Bucket Key** enabled (`BucketKeyEnabled=true`). S3 uses a short-lived bucket-level key to create object data keys. AWS documents up to 99% lower KMS request traffic; actual savings depend on requesters, request patterns, and key usage, and should be observed through CloudTrail / KMS metrics.
- **Versioning** enabled. To prevent buildup of noncurrent versions from crash retries, the destination bucket must carry a lifecycle rule: `NoncurrentVersionExpiration: 7 days` (revisit after acceptance).
- Object metadata to preserve on PUT: HTTP metadata (`Content-Type`, `Content-Encoding`, `Cache-Control`, `Content-Disposition`, `Expires`) and all `x-amz-meta-*`. Object **Tags are NOT preserved** (avoids 20M extra API calls). CSE envelope headers (`x-amz-key-v2`, `x-amz-iv`, `x-amz-matdesc`, `x-amz-cek-alg`, `x-amz-unencrypted-content-length`, `x-amz-tag-len`, `x-amz-wrap-alg`) must be stripped before PUT.

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
2. Parse CSV fields, URL-decode the object key, then percent-encode it before writing to any internal file (`%09` for tab, `%0A` for newline). All internal files (records, checkpoint, failed.keys, failed.log) use percent-encoded keys. Decode back to the raw key only when calling the S3 API.
3. Configure Inventory as `Current version only`; historical versions and delete markers are not migrated.
4. If the CSE envelope uses `.instruction` file storage mode, filter out `*.instruction` objects; they are encryption-material sidecar files, not business objects.
5. Emit comparison record: `percent-encoded-key<TAB>etag<TAB>size<TAB>lastModified`. This captures both new keys and same-key overwrites.
6. Externally sort on EC2 local NVMe/EBS scratch, for example `LC_ALL=C sort -S 24G --parallel=$(nproc) -T /mnt/scratch -u`.
7. Run a sorted set difference and output `current - baseline`.
8. Project the delta back to keys and deduplicate keys; if the same key changed multiple times, only the final current version needs migration.

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

**Key encoding convention**: S3 Inventory URL-encodes special characters (using `+` for spaces). Read with `URLDecoder.decode`, then write all internal files with RFC3986 percent-encoding. Spaces must be encoded as `%20`; do not use `application/x-www-form-urlencoded` style `+`. `cut -f1` on TAB-separated records is safe because keys never contain a literal tab. Decode back to the raw key only immediately before S3 API calls.

**Current-version scope**: The migration copies only the source bucket's current versions. If the source bucket has versioning enabled, historical versions and delete markers are out of scope. The destination bucket gets its own new version history through PUT operations.

### V1 CSE-KMS Decryption

`MigrationWorker` uses `AmazonS3EncryptionClient` from the V1 SDK to GET the object. The SDK transparently handles the envelope flow:

1. Read `x-amz-key-v2` and `x-amz-matdesc` from object user metadata.
2. Call `KMS.Decrypt` with the encrypted DEK and encryption context.
3. Decrypt the object body with AES-CBC or AES-GCM based on `x-amz-cek-alg`.
4. Return a plaintext `InputStream`.

After GET, read from the source object's `ObjectMetadata`:

- **Plaintext length** `x-amz-unencrypted-content-length`: use as PUT `Content-Length`. This must be set explicitly, otherwise the V1 SDK buffers the entire object in memory and high concurrency will OOM.
- **Business metadata**: HTTP metadata (`Content-Type`, `Content-Encoding`, `Cache-Control`, `Content-Disposition`, `Expires`) and all `x-amz-meta-*`. Strip CSE envelope headers (`x-amz-key-v2` / `x-amz-iv` / `x-amz-matdesc` / `x-amz-cek-alg` / `x-amz-unencrypted-content-length` / `x-amz-tag-len` / `x-amz-wrap-alg`) before passing to PUT.

The worker then uses a regular `AmazonS3Client` to `putObject` the plaintext stream and metadata to the destination bucket (SSE-KMS + Bucket Key apply automatically). Object Tags are not copied.

**Anomalous size (≥ 5 GB)**: The business confirms no objects ≥ 5 GB exist in the source bucket. If HEAD detects such an object, **treat it as an error**: write the key to `failed.keys`, record `reason=oversized` in `failed.log`, and exclude it from normal migration. The final ListObjectsV2 reconciliation will surface these keys in `missing-in-destination.txt`, forcing manual review and a per-object decision.

**Failure logging**: Two separate files:
- `failed.keys`: one percent-encoded key per line; feed directly to the next `migrate.jar` run for retries.
- `failed.log`: `percent-encoded-key\treason\ttimestamp` for debugging; not used as direct input.

**KMS throttling backoff**: Both source `Decrypt` and destination `GenerateDataKey` can return `ThrottlingException` / `KMSThrottlingException`. Workers apply exponential backoff (100ms → 200ms → ... → 5s, up to 6 attempts); persistent failures land in `failed.keys` for re-run.

The total failure count is written to stderr on exit.

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
--failed-keys-s3    S3 URI for failed key list backup (default: s3://DST_BUCKET/migration-logs/<run>-failed.keys)
--failed-log-s3     S3 URI for failure log backup (default: s3://DST_BUCKET/migration-logs/<run>-failed.log)
--stop-flag-key     Stop flag S3 key written to destination bucket; default migration-logs/STOP
--dry-run           Decrypt and discard; do not PUT to destination
--verify-sample     Fraction of migrated keys to HEAD-verify; default 0.01
--log-every         Progress log interval in keys; default 1000
--failed-keys       Local path for failed key list (percent-encoded keys, ready for retry); default ./failed.keys
--failed-log        Local TSV path for failure details; default ./failed.log
--region
--profile
```

Exit codes: `0` all migrated, `1` configuration error, `2` partial failure, `3` stop flag triggered, `4` checkpoint error.

The key list is read from the positional file argument or stdin; input files contain one percent-encoded key per line.

## Checkpoint and Fault Tolerance

- Local append-only file: one completed S3 key per line.
- Load into an in-memory `HashSet<String>` on startup for O(1) `contains()` checks.
- Memory estimate: 10M keys × ~80 bytes average ≈ ~800 MB JVM heap; use `-Xmx2g`.
- Flush to local file every 100 completions or every 5 seconds.
- Sync to S3 every 10,000 completions or every 10 minutes.
- The checkpoint and `failed.log` are backed up to the destination bucket under the `migration-logs/` prefix to keep them separate from business objects:
  ```
  s3://DST_BUCKET/migration-logs/
    t7-checkpoint.log
    t7-failed.log
    t7-failed.keys
    delta-YYYY-MM-DD-checkpoint.log
    delta-YYYY-MM-DD-failed.log
    delta-YYYY-MM-DD-failed.keys
  ```
- On startup, download the checkpoint from S3 if the local file is absent.
- Emergency stop: `aws s3 cp /dev/null s3://DST_BUCKET/migration-logs/STOP`; workers poll every 1,000 tasks.
- **Note**: the `ListObjectsV2` reconciliation script must use `--exclude-dst-prefix migration-logs/` to exclude the log prefix so log files do not appear in `extra-in-destination.txt`.

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

**Acceptance criteria**:

1. `ListObjectsV2` reconciliation: `missing-in-destination=0`.
2. `failed.keys` is empty (any leftovers require manual review, especially `reason=oversized` entries in `failed.log`, which need a per-object decision from the business).

Checkpoint counts and inventory diff line counts are for reference only (they include retries and overwrites and do not equal the current key set).

```bash
# 1. Full ListObjectsV2 reconciliation — exclude the migration-logs/ prefix
python3 scripts/verify-listobjects-v2.py \
  --src-bucket SRC_BUCKET \
  --dst-bucket DST_BUCKET \
  --exclude-dst-prefix migration-logs/ \
  --workdir /mnt/scratch/verify \
  --sort-mem 20G \
  --sort-parallel "$(nproc)" \
  --region REGION

# If the CSE envelope uses .instruction file mode, also add:
#   --exclude-src-suffix .instruction --exclude-dst-suffix .instruction

# 2. Re-run migration for any missing keys
java -jar migrate.jar --src-bucket SRC --dst-bucket DST \
  /mnt/scratch/verify/missing-in-destination.txt

# Repeat steps 1 and 2 until missing-in-destination=0

# 3. Spot-check plaintext in destination
aws s3 cp s3://DST_BUCKET/some-key /tmp/check && file /tmp/check
```

Outputs:

- `missing-in-destination.txt`: **must be 0**; otherwise re-run step 2 and reconcile again.
- `extra-in-destination.txt`: **expected to be non-empty**. Contents include: (a) keys deleted at the source during the migration window where deletions are not propagated; (b) pre-existing destination objects, if any. This file does not block acceptance, but archive it for audit.

Because this migration only handles current versions, `ListObjectsV2` reconciliation matches the migration scope exactly.

## Additional Features

| # | Feature | Description |
|---|---------|-------------|
| 1 | Preflight dry-run | Run on 1,000 random keys before T-7 to confirm end-to-end decryption |
| 2 | Emergency stop flag | Checked every 1,000 tasks for graceful drain without a kill signal |
| 3 | Manifest availability polling | `ManifestLoader` retries with exponential backoff from 5 to 60 minutes |
| 4 | Verification sampling | HEAD-check random destination samples after the worker pool drains |
| 5 | Progress logging | Final stderr summary `attempted/succeeded/failed/oversized/skipped`; observe KMS call volume and throttling via CloudTrail / KMS console |
| 6 | CSV MD5 validation | Detect corrupted inventory parts before processing |
| 7 | Anomalous size detection | HEAD ≥ 5 GB objects are written to `failed.keys`, with `reason=oversized` recorded in `failed.log`; the business has confirmed such objects should not exist in the source bucket |
| 8 | Structured failure log | `failed.keys` (percent-encoded key list for retry) + `failed.log` (percent-encoded-key, reason, timestamp for debugging) |
| 9 | KMS throttling backoff | `ThrottlingException` triggers exponential backoff 100ms→5s, up to 6 attempts; persistent failure lands in `failed.keys` |
| 10 | Metadata preservation | PUT carries source HTTP metadata and all `x-amz-meta-*`; CSE envelope headers are stripped; Object Tags are NOT copied |

## IAM Permissions

**Migration role** (runs `migrate.jar` and `inventory-diff.jar`):

- Source bucket: `s3:GetObject`, `s3:GetObjectVersion` (if needed)
- Destination bucket: `s3:PutObject`, `s3:GetObject` (HEAD sampling and checkpoint readback)
- Inventory bucket: `s3:GetObject`
- Source KMS key: `kms:Decrypt` (encryption context must match the object's `x-amz-matdesc`)
- Destination KMS key: `kms:GenerateDataKey`, `kms:Encrypt`
- `s3:ListBucket` is NOT required (migrate.jar does not call `ListObjectsV2`)

**Verification role** (runs `verify-listobjects-v2.py`):

- Source bucket + destination bucket: `s3:ListBucket`

## Constraints TBD

- [ ] Source bucket name: `___________`
- [ ] Destination bucket name: `___________`
- [ ] Inventory bucket name: `___________`
- [ ] AWS region: `___________`
- [ ] Source KMS key ARN: `___________`
- [ ] Destination KMS key ARN: `___________`
- [ ] CSE envelope storage mode: `object metadata` or `.instruction` file (if `.instruction`, inventory normalize and ListObjectsV2 reconciliation both filter `*.instruction`)
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
- [x] Destination bucket has SSE-KMS + Bucket Key + Versioning (with 7-day noncurrent version lifecycle)
- [x] Source-side deletions are NOT propagated; `extra-in-destination.txt` is an expected output
- [x] PUT preserves HTTP metadata and `x-amz-meta-*`; Object Tags are not preserved
- [x] Business confirms no objects ≥ 5 GB exist in the source; if detected, treat as an error
- [ ] Other constraints: `___________`

# S3 CSE-KMS 迁移方案 / S3 CSE-KMS Migration Plan

---

## 目录 / Table of Contents

1. [背景 / Background](#背景--background)
2. [时间线 / Timeline](#时间线--timeline)
3. [技术选型 / Tech Stack](#技术选型--tech-stack)
4. [目录结构 / Directory Layout](#目录结构--directory-layout)
5. [核心算法 / Key Algorithms](#核心算法--key-algorithms)
6. [Maven 依赖 / Maven Dependencies](#maven-依赖--maven-dependencies)
7. [命令行接口 / CLI Reference](#命令行接口--cli-reference)
8. [检查点与容错 / Checkpoint & Fault Tolerance](#检查点与容错--checkpoint--fault-tolerance)
9. [Makefile 目标 / Makefile Targets](#makefile-目标--makefile-targets)
10. [运维手册 / Operational Runbook](#运维手册--operational-runbook)
11. [附加功能 / Additional Features](#附加功能--additional-features)
12. [待补充约束 / Constraints TBD](#待补充约束--constraints-tbd)

---

## 背景 / Background

**中文**

源 S3 桶中约 **1000 万个文件**，使用 **AWS S3 V1 Java SDK 客户端加密（CSE-KMS）** 加密。目标：解密每个文件，将明文写入新的目标桶。每天仍有新文件写入，写入将在时间点 T（约 7 天后）停止；所有文件必须在 T+2 前完成迁移。

迁移使用每日 S3 Inventory 清单（CSV 格式，不使用 Athena）作为文件列表的权威来源。采用流式有序归并算法计算每日增量（O(1) 内存，两份清单在清单顺序下全局有序，无需数据库）。解密直接使用 **AWS V1 Java SDK**（`AmazonS3EncryptionClient`），彻底消除协议重新实现的风险——对于 1000 万文件而言这是关键要求。

**English**

~10 million files in a source S3 bucket encrypted with **AWS S3 V1 Java SDK client-side encryption (CSE-KMS)**. Goal: decrypt every file and write plaintext to a new destination bucket. New files are added daily; creation stops at time T (~7 days from now); all files must be in the destination bucket by T+2.

The migration uses daily S3 Inventory manifests (CSV, no Athena) as the authoritative file list. A streaming sorted-merge algorithm computes daily deltas with O(1) memory (both inventories are globally sorted across all CSV parts — no DB needed). Decryption uses the **actual AWS V1 Java SDK** (`AmazonS3EncryptionClient`) to eliminate any risk of protocol reimplementation errors — critical for 10M files.

---

## 时间线 / Timeline

| 天 / Day | 操作 / Action |
|----------|--------------|
| 现在 / Now | 创建 S3 Inventory 配置（首次交付最多需 48 小时）/ Create S3 Inventory config (up to 48 h for first delivery) |
| T-7 | **全量迁移**：以 T-7 清单为基线，迁移全部约 1000 万个 key（约需 3 天）/ **Full migration**: T-7 inventory as baseline; migrate all ~10 M keys (~3 days) |
| T-4 | 全量运行完成；**增量**：T-4 清单减去 T-7 基线 / Full run completes; **delta**: T-4 minus T-7 baseline |
| T-3 至 T+0 | 每日增量：当天清单减去前一天清单 / Daily delta: current inventory minus previous day's |
| T+1, T+2 | 文件写入停止后的最终补充增量 / Final catch-up deltas after file creation stops |
| T+2 | **截止日期**——所有文件完成迁移 / **Deadline** — all files migrated |

---

## 技术选型 / Tech Stack

**中文**

**Java 17 (LTS) + Maven**。使用 `maven-shade-plugin` 从一个多模块 Maven 项目构建两个 fat JAR：

- `inventory-diff.jar`：计算每日增量 key 列表
- `migrate.jar`：解密并复制文件

使用 Java 的原因：直接调用 `AmazonS3EncryptionClient`（V1 SDK），完全消除协议重新实现的风险；SDK 内部处理所有信封解封、KMS 解密和 AES 逻辑，与文件原始加密方式完全一致。

**English**

**Java 17 (LTS) + Maven**. Two fat JARs (via `maven-shade-plugin`) produced from one multi-module Maven project:

- `inventory-diff.jar` — computes daily delta key list
- `migrate.jar` — decrypts and copies files

Using Java eliminates any protocol reimplementation risk: `AmazonS3EncryptionClient` (V1 SDK) handles all envelope unwrap, KMS decrypt, and AES logic exactly as the files were originally encrypted.

---

## 目录结构 / Directory Layout

```
s3-migration/
  plan.md                               本文档 / this document
  pom.xml                               父 POM（多模块）/ parent POM (multi-module)
  Makefile                              封装 mvn 命令 / thin wrapper: mvn package, test, verify
  inventory-diff/
    pom.xml
    src/main/java/com/migration/s3/inventorydiff/
      InventoryDiffMain.java            CLI 入口 / CLI entrypoint
    src/test/java/com/migration/s3/inventorydiff/
      InventoryDiffMainTest.java
  migrate/
    pom.xml
    src/main/java/com/migration/s3/migrate/
      MigrateMain.java                  CLI 入口 / CLI entrypoint
    src/test/java/com/migration/s3/migrate/
      MigrateMainTest.java
  common/
    pom.xml
    src/main/java/com/migration/s3/
      inventory/
        Manifest.java                   manifest.json POJO（Jackson）
        ManifestLoader.java             下载 + MD5 校验 / download + MD5 checksum validate
        InventoryReader.java            流式读取单个 gzipped CSV part / streaming gzipped CSV (one part)
        InventoryStream.java            多 part 顺序流门面 / sequential multi-part stream facade
      delta/
        SortedMergeDiff.java            O(1) 双指针差分 / O(1) two-pointer diff
      worker/
        MigrationWorker.java            Callable: GET 解密 PUT / GET decrypt PUT
        WorkerPool.java                 ExecutorService + CompletionService
      checkpoint/
        CheckpointLog.java              追加日志 + S3 同步 / append-only log + S3 sync
      metrics/
        MetricsEmitter.java             接口 / interface
        CloudWatchEmitter.java          批量 PutMetricData / batched PutMetricData
        NoopEmitter.java                空实现 / no-op default
    src/test/java/com/migration/s3/
      delta/SortedMergeDiffTest.java
      inventory/InventoryReaderTest.java
      checkpoint/CheckpointLogTest.java
      worker/MigrationWorkerTest.java
  scripts/
    setup-inventory.sh                  创建每日 CSV inventory 配置 / AWS CLI: create daily CSV inventory config
    run-t7.sh                           T-7 全量迁移脚本 / T-7 full migration script
    run-daily.sh                        增量迁移脚本 / incremental migration script
```

---

## 核心算法 / Key Algorithms

### Inventory 结构（为何无需数据库）/ Inventory Structure (Why No DB)

**中文**

S3 Inventory 保证整份报告是一个**全局有序**的对象 key 序列，按清单顺序分布在多个 gzipped CSV part 中（part 1 的 key < part 2 的 key < …）。因此两份清单可以作为两个有序迭代器同时流式处理。

`InventoryStream` 按顺序遍历 `manifest.json` 中列出的 part，逐行流式读取每个 gzipped CSV 文件。每份清单产生一个有序 key 流，无需将任何数据加载到内存。

**English**

S3 Inventory guarantees the entire report is one **globally sorted** sequence of object keys, split across multiple gzipped CSV parts in manifest order (part 1 keys < part 2 keys < …). Two manifests can therefore be streamed as two sorted iterators simultaneously.

`InventoryStream` iterates the parts listed in `manifest.json` in order, streaming each gzipped CSV file row by row. This produces a single sorted key stream per manifest without loading anything into memory.

---

### 增量算法（O(1) 内存）/ Delta Algorithm (O(1) Memory)

```
b = baseline.next()
c = current.next()
while c != EOF:
  if b == EOF:
    emit c.key; c = current.next()
  else:
    cmp = c.key.compareTo(b.key)
    if cmp < 0:  c 是新增 key — 输出 c.key，推进 c  / c is NEW — emit c.key; advance c
    if cmp == 0: 未变化 — 同时推进两指针 / unchanged — advance both
    if cmp > 0:  从源桶删除的 key — 只推进 b / deleted from source — advance b only
```

输出 / Output：`current` 中存在但 `baseline` 中不存在的 key，每行一个。

T-7 全量运行时：调用 `inventory-diff` 时不传 `--baseline` 参数 → 输出清单中所有 key。

**URL 编码 key / URL-encoded keys**：S3 Inventory 会对含特殊字符的 key 进行 URL 编码。比较和输出前需用 `URLDecoder.decode(key, StandardCharsets.UTF_8)` 解码。

---

### V1 CSE-KMS 解密 / V1 CSE-KMS Decryption

**中文**

`MigrationWorker` 使用 `AmazonS3EncryptionClient`（V1 SDK）执行 GET 操作——SDK 透明处理完整的信封流程：

1. 从对象用户元数据中读取 `x-amz-key-v2`（base64 编码的 KMS 加密 DEK）和 `x-amz-matdesc`（KMS 上下文）
2. 使用加密的 DEK 和加密上下文调用 `KMS.Decrypt`
3. 根据 `x-amz-cek-alg` 使用 AES-CBC 或 AES-GCM 解密对象体
4. 返回明文的 `InputStream`

Worker 随后调用普通 `AmazonS3Client` 将对象 `putObject` 到目标桶（不加密）。

这用一次对成熟库的 `getObject` 调用替代了约 200 行手写加密代码。

**English**

`MigrationWorker` uses `AmazonS3EncryptionClient` (V1 SDK) to GET the object — the SDK transparently handles the full envelope:

1. Reads `x-amz-key-v2` (base64 KMS-encrypted DEK) and `x-amz-matdesc` (KMS context) from object user metadata
2. Calls `KMS.Decrypt` with the encrypted DEK and encryption context
3. Decrypts the object body with AES-CBC or AES-GCM based on `x-amz-cek-alg`
4. Returns a plain `InputStream` of the cleartext

The worker then calls a plain `AmazonS3Client` to `putObject` to the destination bucket (no encryption).

This replaces ~200 lines of hand-rolled crypto with a single `getObject` call to a well-tested library.

---

## Maven 依赖 / Maven Dependencies

`common/pom.xml` 中定义 / Defined in `common/pom.xml`:

```xml
<!-- V1 SDK：解密（AmazonS3EncryptionClient）/ V1 SDK for decryption -->
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

<!-- V2 SDK：CloudWatch 指标 / V2 SDK for CloudWatch metrics -->
<dependency>
  <groupId>software.amazon.awssdk</groupId>
  <artifactId>cloudwatch</artifactId>
  <version>2.26.0</version>
</dependency>

<!-- CSV 解析 / CSV parsing -->
<dependency>
  <groupId>org.apache.commons</groupId>
  <artifactId>commons-csv</artifactId>
  <version>1.11.0</version>
</dependency>

<!-- JSON（manifest.json 解析）/ JSON for manifest.json -->
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
  <version>2.17.1</version>
</dependency>

<!-- 测试 / Testing -->
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

---

## 命令行接口 / CLI Reference

### `inventory-diff.jar`

```
java -jar inventory-diff.jar [参数 / flags]

--baseline-manifest  基线 manifest.json 的 S3 URI（省略 = 全量模式）
                     S3 URI of baseline manifest.json (omit = full-inventory mode)
--current-manifest   当前 manifest.json 的 S3 URI（必填）
                     S3 URI of current manifest.json (required)
--inventory-bucket   存放 CSV parts 的桶（必填）/ Bucket holding the CSV parts (required)
--out                输出文件路径（默认: stdout）/ Output file path (default: stdout)
--validate-checksums 读取前校验每个 CSV part 的 MD5（默认: true）
                     Validate MD5 of each CSV part before reading (default: true)
--region             AWS 区域 / AWS region
--profile            AWS 凭证 profile / AWS credentials profile
--verbose
```

退出码 / Exit codes: `0`=成功, `1`=配置错误, `2`=S3 错误, `3`=清单/校验错误, `4`=I/O 错误

---

### `migrate.jar`

```
java -jar migrate.jar [参数 / flags] [key-list-file]

--src-bucket        加密源桶（必填）/ Encrypted source bucket (required)
--dst-bucket        明文目标桶（必填）/ Plaintext destination bucket (required)
--concurrency       并行 worker 线程数（默认: 20；T-7 使用 50）
                    Parallel worker threads (default: 20; use 50 for T-7)
--checkpoint        本地检查点文件（默认: ./checkpoint.log）
                    Local checkpoint file (default: ./checkpoint.log)
--checkpoint-s3     检查点 S3 备份 URI / S3 URI for checkpoint backup
--stop-flag-bucket  紧急停止标志所在桶 / Bucket containing the emergency stop object
--stop-flag-key     停止标志 S3 key（默认: migration/STOP）
                    S3 key for stop flag (default: migration/STOP)
--dry-run           解密但不 PUT 到目标桶 / Decrypt and discard; do not PUT to destination
--metrics           上报 CloudWatch 指标（命名空间: S3Migration）
                    Emit CloudWatch metrics (namespace: S3Migration)
--verify-sample     已迁移 key 的 HEAD 验证比例（默认: 0.01）
                    Fraction of migrated keys to HEAD-verify (default: 0.01)
--log-every         进度日志间隔（单位: key 数，默认: 1000）
                    Progress log interval in keys (default: 1000)
--region
--profile
```

退出码 / Exit codes: `0`=全部迁移完成, `1`=配置错误, `2`=部分失败（失败数输出到 stderr）, `3`=停止标志触发, `4`=检查点错误

key 列表从位置参数文件或 stdin 读取 / Key list is read from the positional file argument or stdin.

---

## 检查点与容错 / Checkpoint & Fault Tolerance

**中文**

- 本地**追加写入文件**：每行一个已完成的 S3 key
- 启动时加载到内存 `HashSet<String>`，实现 O(1) 的 `contains()` 查询
  - 内存估算：1000 万 key × 平均约 80 字节 ≈ ~800 MB JVM 堆；使用 `-Xmx2g`
- 每 100 次完成或每 5 秒（后台线程）刷新到本地文件
- 每 10,000 次完成或每 10 分钟同步到 S3
- 启动时：若本地文件不存在，从 S3 下载
- **紧急停止**：`aws s3 cp /dev/null s3://STOP_BUCKET/migration/STOP` — worker 每处理 1,000 个任务检查一次

**English**

- Local **append-only file**: one completed S3 key per line
- In-memory `HashSet<String>` loaded at startup for O(1) `contains()` check
  - Memory: 10 M keys × ~80 bytes avg = ~800 MB on JVM heap; use `-Xmx2g`
- Flush to local file every 100 completions and every 5 seconds (background thread)
- Sync to S3 every 10,000 completions or every 10 minutes
- On startup: if local file absent, download from S3
- **Emergency stop**: `aws s3 cp /dev/null s3://STOP_BUCKET/migration/STOP` — workers poll every 1,000 tasks

---

## Makefile 目标 / Makefile Targets

| 目标 / Target | 命令 / Command | 说明 / Description |
|--------------|---------------|-------------------|
| `build` | `mvn package -DskipTests` | 构建（跳过测试）/ Build, skip tests |
| `test` | `mvn verify` | 构建并运行所有测试 / Build and run all tests |
| `clean` | `mvn clean` | 清理构建产物 / Clean build artifacts |
| `release` | `mvn package -DskipTests -Prelease` | 生成可在 Linux 运行的 fat JAR / Produce linux-runnable fat JARs |

---

## 运维手册 / Operational Runbook

### 第 0 天（现在）/ Day 0 (Now)

```bash
# 创建每日 CSV inventory 配置 / Create daily CSV inventory config
./scripts/setup-inventory.sh SRC_BUCKET INV_BUCKET migration/inventory REGION

# 48 小时内确认清单已交付 / Confirm manifest delivery within 48 h
aws s3 ls s3://INV_BUCKET/migration/inventory/

# 构建 fat JAR / Build fat JARs
make release
```

---

### T-7（全量迁移）/ T-7 (Full Migration)

```bash
./scripts/run-t7.sh
# 使用 --concurrency 50，约需 3 天 / ~3 days at --concurrency 50
# checkpoint-s3 = s3://OPS_BUCKET/migration/t7-checkpoint.log
```

崩溃后重启 / On crash: re-run same command. Checkpoint skips completed keys.

---

### T-4 至 T+2（每日增量）/ T-4 through T+2 (Daily Incremental)

```bash
./scripts/run-daily.sh YYYY-MM-DD-prev YYYY-MM-DD-today
```

---

### T+2 最终核验 / T+2 Final Verification

```bash
# 1. 全量差分，统计预期总 key 数 / Full-universe diff: total expected keys
java -jar inventory-diff.jar \
  --baseline-manifest s3://INV_BUCKET/.../t-7/manifest.json \
  --current-manifest  s3://INV_BUCKET/.../t+2/manifest.json \
  --inventory-bucket INV_BUCKET | wc -l

# 2. 统计已迁移 key 数 / Count migrated keys
sort t7-checkpoint.log delta-*.log | uniq | wc -l

# 3. 对未确认 key 执行 dry-run / dry-run on unchecked keys
java -jar migrate.jar --dry-run --src-bucket SRC --dst-bucket DST missing-keys.txt

# 4. 抽查目标桶明文 / Spot-check plaintext in destination
aws s3 cp s3://DST_BUCKET/some-key /tmp/check && file /tmp/check
```

---

## 附加功能 / Additional Features

| # | 功能 / Feature | 说明 / Description |
|---|--------------|-------------------|
| 1 | **预检 dry-run** | T-7 前对 1,000 个随机 key 运行 dry-run，确认解密端到端可用 / Run on 1,000 random keys before T-7 to confirm decryption works end-to-end |
| 2 | **紧急停止标志** | 每处理 1,000 个任务检查一次，无需 kill 信号即可优雅排空 / Checked every 1,000 tasks — graceful drain without kill signal |
| 3 | **清单可用性轮询** | `ManifestLoader` 以指数退避（5→10→20→…→60 分钟）重试，避免清单尚未交付时直接失败 / Retries with exponential backoff (5→10→20…60 min max) before failing |
| 4 | **验证采样** | 线程池排空后对目标桶中随机样本执行 HEAD 检查 / HEAD-checks a random sample in destination after pool drains |
| 5 | **CloudWatch 指标** | `keys.attempted/succeeded/failed/skipped`、`bytes.decrypted`、`kms.calls`、`decrypt.duration_ms` — T-7 运行期间提供实时 ETA 可视化 / Provides real-time ETA visibility during T-7 run |
| 6 | **CSV MD5 校验** | 处理前检测损坏的 inventory part / Detect corrupted inventory parts before wasting processing time |

---

## 待补充约束 / Constraints TBD

> **请在此处补充你的约束条件，然后我们开始编码。**
> **Please add your constraints here before we start coding.**

- [ ] 源桶名 / Source bucket name: `___________`
- [ ] 目标桶名 / Destination bucket name: `___________`
- [ ] Inventory 桶名 / Inventory bucket name: `___________`
- [ ] AWS 区域 / AWS region: `___________`
- [ ] KMS key ARN（用于权限验证）/ KMS key ARN (for IAM policy validation): `___________`
- [ ] 运行 migrate.jar 的 EC2/ECS 规格 / EC2/ECS spec for running migrate.jar: `___________`
- [ ] 并发度上限（KMS TPS 配额）/ Concurrency ceiling (KMS TPS quota): `___________`
- [ ] 是否需要 VPC Endpoint / VPC Endpoint required: `___________`
- [ ] 日志保留策略 / Log retention policy: `___________`
- [ ] 其他约束 / Other constraints: `___________`

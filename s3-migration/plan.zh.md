# S3 CSE-KMS 迁移方案

## 目录

1. [背景](#背景)
2. [时间线](#时间线)
3. [技术选型](#技术选型)
4. [目录结构](#目录结构)
5. [核心算法](#核心算法)
6. [Maven 依赖](#maven-依赖)
7. [命令行接口](#命令行接口)
8. [检查点与容错](#检查点与容错)
9. [Makefile 目标](#makefile-目标)
10. [运维手册](#运维手册)
11. [附加功能](#附加功能)
12. [待补充约束](#待补充约束)

## 背景

源 S3 桶中约 **1000 万个文件**，使用 **AWS S3 V1 Java SDK 客户端加密（CSE-KMS）** 加密。目标：解密每个文件，将明文写入新的目标桶。每天仍有新文件写入，写入将在时间点 T（约 7 天后）停止；所有文件必须在 T+2 前完成迁移。

迁移使用每日 S3 Inventory 清单（CSV 格式，不使用 Athena）作为文件列表的主要来源。由于 S3 Inventory **不保证排序**，每日增量通过“规范化 Inventory → 磁盘外部排序 → 有序集合差分”计算，不依赖 Athena 或独立部署数据库。解密直接使用 **AWS V1 Java SDK**（`AmazonS3EncryptionClient`），彻底消除协议重新实现的风险。

本方案只迁移源桶的 **current version**。历史版本和 delete marker 不进入迁移范围；目标桶对象按新文件 PUT。**源端删除不传播到目标桶**：如果某个 key 在 baseline 出现、current 不再出现（已删除），目标桶保留旧拷贝。因此 `extra-in-destination.txt` 中的内容是预期产物，不作为失败项处理。

**目标桶配置**（已确认）：

- 启用 **SSE-KMS**（目标 KMS key 由业务方提供，与源 KMS key 不同）。
- 启用 **Bucket Key**（`BucketKeyEnabled=true`）。S3 会使用短期 bucket-level key 为对象生成数据密钥，AWS 官方说明最多可降低 99% 的 KMS request traffic；实际节省取决于 requester、请求模式和 key 使用情况，需通过 CloudTrail / KMS metrics 观察。
- 启用 **Versioning**。为避免崩溃重试产生的 noncurrent version 堆积，目标桶必须配 lifecycle rule：`NoncurrentVersionExpiration: 7 天`（迁移完成验收后可调整）。
- 业务对象元数据保留范围：HTTP 元数据（`Content-Type`、`Content-Encoding`、`Cache-Control`、`Content-Disposition`、`Expires`）与所有 `x-amz-meta-*`；Object **Tags 不保留**（避免 2000 万次额外 API 调用）。CSE envelope 头（`x-amz-key-v2`, `x-amz-iv`, `x-amz-matdesc`, `x-amz-cek-alg`, `x-amz-unencrypted-content-length`, `x-amz-tag-len`, `x-amz-wrap-alg`）在 PUT 时必须剥离。

## 时间线

| 天 | 操作 |
|----|------|
| 现在 | 创建 S3 Inventory 配置（首次交付最多需 48 小时） |
| T-14 或 T-7 | **全量迁移**：以该日清单为基线，迁移全部约 1000 万个 key；若吞吐压测显示 T-7 风险过高则提前到 T-14 |
| 全量完成后 | **增量**：最新清单减去全量基线 |
| T-3 至 T+0 | 每日增量：当天清单减去前一天清单 |
| T+1, T+2 | 文件写入停止后的最终补充增量 |
| T+2 | **截止日期**：所有文件完成迁移 |

## 技术选型

**Java 17 (LTS) + Maven**。使用 `maven-shade-plugin` 从一个多模块 Maven 项目构建两个 fat JAR：

- `inventory-diff.jar`：计算每日增量 key 列表
- `migrate.jar`：解密并复制文件

使用 Java 的原因：直接调用 `AmazonS3EncryptionClient`（V1 SDK），完全消除协议重新实现的风险；SDK 内部处理所有信封解封、KMS 解密和 AES 逻辑，与文件原始加密方式完全一致。

## 目录结构

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

## 核心算法

### Inventory 结构与增量差分

S3 Inventory 不保证对象 key 的输出顺序。因此不能直接对两个 manifest 做双指针归并。正确流程是：

1. `InventoryStream` 按 `manifest.json` 遍历所有 gzipped CSV part，逐行流式读取。
2. 解析 CSV 字段，URL decode key，再 percent-encode 后写入内部文件（`%09` 代替 tab，`%0A` 代替换行），确保含特殊字符的 key 不破坏 TSV/行分隔格式。所有内部文件（records、checkpoint、failed.keys、failed.log）均使用 percent-encoded key；仅在调用 S3 API 前 decode。
3. Inventory 配置为 `Current version only`，不迁移历史版本和 delete marker。
4. 若 CSE envelope 使用 `.instruction` 文件存储模式，过滤掉 `*.instruction` 对象；这些是加密材料旁路文件，不是业务对象。
5. 输出比较记录：`percent-encoded-key<TAB>etag<TAB>size<TAB>lastModified`。这样新增 key 和同名 overwrite 都会进入增量。
6. 使用 EC2 本地 NVMe/EBS scratch 目录运行外部排序，例如 `LC_ALL=C sort -S 24G --parallel=$(nproc) -T /mnt/scratch -u`。
7. 对两个已排序文件做集合差分，输出 `current - baseline`。
8. 将差分结果投影回 key，并对 key 去重；同一个 key 多次变化时只需要迁移最终 current version。

这仍然是“无 Athena、无独立 DB”的方案；内存由 `sort -S` 控制，数据落盘。32GB EC2 可用 `-S 20G`，64GB EC2 可用 `-S 45G`，同时预留 JVM、page cache 和系统内存。

### 增量算法（磁盘外排）

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

输出：`current` 中存在但 `baseline` 中不存在的 key，每行一个。

全量运行时：调用 `inventory-diff` 时不传 `--baseline` 参数，输出当前清单规范化并排序后的全部 key。

**Key 编码规范**：S3 Inventory 对特殊字符使用 URL 编码（`+` 表示空格）。读取后先用 `URLDecoder.decode` 还原原始 key，再按 RFC3986 percent-encoding 写入所有内部文件；空格必须编码为 `%20`，不要使用 `application/x-www-form-urlencoded` 风格的 `+`。`cut -f1` 取第一列时不会因 key 含 tab 而错位。调用 S3 API（`getObject`/`putObject`）前再 decode 回原始 key。

**当前版本限定**：本迁移只复制源桶当前版本。若源桶开启 versioning，历史版本和 delete marker 不进入迁移范围；目标桶按新 PUT 生成自己的版本历史。

### V1 CSE-KMS 解密

`MigrationWorker` 使用 `AmazonS3EncryptionClient`（V1 SDK）执行 GET 操作，SDK 透明处理完整的信封流程：

1. 从对象用户元数据中读取 `x-amz-key-v2`（base64 编码的 KMS 加密 DEK）和 `x-amz-matdesc`（KMS 上下文）
2. 使用加密的 DEK 和加密上下文调用 `KMS.Decrypt`
3. 根据 `x-amz-cek-alg` 使用 AES-CBC 或 AES-GCM 解密对象体
4. 返回明文的 `InputStream`

GET 完成后，从源对象的 `ObjectMetadata` 中读取：

- **明文长度** `x-amz-unencrypted-content-length`：作为 PUT 的 `Content-Length`。必须显式设置，否则 V1 SDK 会把整对象 buffer 到内存，并发情况下会 OOM。
- **业务元数据**：HTTP 元数据（`Content-Type`、`Content-Encoding`、`Cache-Control`、`Content-Disposition`、`Expires`）与所有 `x-amz-meta-*`。CSE envelope 头（`x-amz-key-v2`/`x-amz-iv`/`x-amz-matdesc`/`x-amz-cek-alg`/`x-amz-unencrypted-content-length`/`x-amz-tag-len`/`x-amz-wrap-alg`）逐字段剔除后传给 PUT。

Worker 随后调用普通 `AmazonS3Client` 将明文 stream + 业务元数据 `putObject` 到目标桶（目标桶启用 SSE-KMS + Bucket Key，由 S3 自动加密）。Object Tags 不复制。

**异常大小（≥ 5 GB）**：业务侧确认源桶不存在 ≥ 5 GB 的对象。如果 HEAD 阶段检测到此类对象，**视为异常**，写入 `failed.keys`，并在 `failed.log` 记录 `reason=oversized`；不进入正常迁移流程，需要人工介入决定单独处理或忽略。最终 ListObjectsV2 对账时这些 key 会自然出现在 `missing-in-destination.txt`，迫使人工确认。

**失败日志**：拆为两个文件：
- `failed.keys`：每行一个 percent-encoded key，可直接作为下次 `migrate.jar` 的输入用于重试。
- `failed.log`：`percent-encoded-key\treason\ttimestamp`，供排查用，不直接作为输入。

**KMS 限流退避**：源 `Decrypt` 与目标 `GenerateDataKey` 都可能返回 `ThrottlingException` / `KMSThrottlingException`。Worker 对该类异常执行指数退避（100ms → 200ms → ... → 5s，最多 6 次），仍失败则落入 `failed.keys`，下次重跑。

进程结束时将失败 key 数量输出到 stderr。

## Maven 依赖

`common/pom.xml` 中定义：

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

## 命令行接口

### `inventory-diff.jar`

```text
java -jar inventory-diff.jar [参数]

--baseline-manifest  基线 manifest.json 的 S3 URI（省略 = 全量模式）
--current-manifest   当前 manifest.json 的 S3 URI（必填）
--inventory-bucket   存放 CSV parts 的桶（必填）
--out                输出文件路径（默认: stdout）
--scratch-dir        外部排序临时目录（建议本地 NVMe/EBS）
--sort-mem           sort 内存预算（32GB EC2: 20G；64GB EC2: 45G）
--sort-parallel      sort 并行度（默认: vCPU 数）
--validate-checksums 读取前校验每个 CSV part 的 MD5（默认: true）
--region             AWS 区域
--profile            AWS 凭证 profile
--verbose
```

退出码：`0`=成功，`1`=配置错误，`2`=S3 错误，`3`=清单/校验错误，`4`=I/O 错误。

### `migrate.jar`

```text
java -jar migrate.jar [参数] [key-list-file]

--src-bucket        加密源桶（必填）
--dst-bucket        明文目标桶（必填）
--concurrency       并行 worker 线程数（默认: 20；T-7 使用 50）
--checkpoint        本地检查点文件（默认: ./checkpoint.log）
--checkpoint-s3     检查点 S3 备份 URI（默认: s3://DST_BUCKET/migration-logs/<run>-checkpoint.log）
--failed-keys-s3    失败 key 列表 S3 备份 URI（默认: s3://DST_BUCKET/migration-logs/<run>-failed.keys）
--failed-log-s3     失败日志 S3 备份 URI（默认: s3://DST_BUCKET/migration-logs/<run>-failed.log）
--stop-flag-key     停止标志 S3 key，写入目标桶（默认: migration-logs/STOP）
--dry-run           解密但不 PUT 到目标桶
--metrics           上报 CloudWatch 指标（命名空间: S3Migration）
--verify-sample     已迁移 key 的 HEAD 验证比例（默认: 0.01）
--log-every         进度日志间隔（单位: key 数，默认: 1000）
--failed-keys       失败 key 本地路径，percent-encoded key 列表，可直接重试（默认: ./failed.keys）
--failed-log        失败详情本地 TSV 路径（默认: ./failed.log）
--region
--profile
```

退出码：`0`=全部迁移完成，`1`=配置错误，`2`=部分失败，`3`=停止标志触发，`4`=检查点错误。

key 列表从位置参数文件或 stdin 读取；输入文件每行一个 percent-encoded key。

## 检查点与容错

- 本地追加写入文件：每行一个已完成的 S3 key
- 启动时加载到内存 `HashSet<String>`，实现 O(1) 的 `contains()` 查询
- 内存估算：1000 万 key × 平均约 80 字节 ≈ ~800 MB JVM 堆；使用 `-Xmx2g`
- 每 100 次完成或每 5 秒（后台线程）刷新到本地文件
- 每 10,000 次完成或每 10 分钟同步到 S3
- checkpoint 与 `failed.log` 均备份到目标桶的 `migration-logs/` 前缀下，与业务对象隔离：
  ```
  s3://DST_BUCKET/migration-logs/
    t7-checkpoint.log
    t7-failed.log
    t7-failed.keys
    delta-YYYY-MM-DD-checkpoint.log
    delta-YYYY-MM-DD-failed.log
    delta-YYYY-MM-DD-failed.keys
  ```
- 启动时：若本地文件不存在，从 S3 下载
- 紧急停止：`aws s3 cp /dev/null s3://DST_BUCKET/migration-logs/STOP`，worker 每处理 1,000 个任务检查一次
- **注意**：`ListObjectsV2` 对账时须通过 `--exclude-dst-prefix migration-logs/` 排除日志前缀，避免日志文件出现在 `extra-in-destination.txt`

## Makefile 目标

| 目标 | 命令 | 说明 |
|------|------|------|
| `build` | `mvn package -DskipTests` | 构建（跳过测试） |
| `test` | `mvn verify` | 构建并运行所有测试 |
| `clean` | `mvn clean` | 清理构建产物 |
| `release` | `mvn package -DskipTests -Prelease` | 生成可在 Linux 运行的 fat JAR |

## 运维手册

### 第 0 天（现在）

```bash
./scripts/setup-inventory.sh SRC_BUCKET INV_BUCKET migration/inventory REGION
# Inventory 必须配置为 Current version only，并包含 Key, ETag, Size, LastModified

aws s3 ls s3://INV_BUCKET/migration/inventory/
make release
```

### T-7 或 T-14（全量迁移）

```bash
./scripts/run-t7.sh
# 使用 --concurrency 50
# checkpoint-s3 = s3://OPS_BUCKET/migration/t7-checkpoint.log
```

崩溃后重跑同一命令，checkpoint 会跳过已完成 key。

### 每日增量

```bash
./scripts/run-daily.sh YYYY-MM-DD-prev YYYY-MM-DD-today
```

### T+2 最终核验

**验收标准**：

1. `ListObjectsV2` 对账 `missing-in-destination=0`。
2. `failed.keys` 为空（如有遗留必须人工 review；尤其是 `failed.log` 中 `reason=oversized` 的条目，业务方需逐个决策）。

checkpoint 计数和 inventory diff 行数仅供参考（含重试、overwrite 等因素，与"当前 key 集合"不等价），不作为验收依据。

```bash
# 1. ListObjectsV2 全量对账，排除 migration-logs/ 前缀
python3 scripts/verify-listobjects-v2.py \
  --src-bucket SRC_BUCKET \
  --dst-bucket DST_BUCKET \
  --exclude-dst-prefix migration-logs/ \
  --workdir /mnt/scratch/verify \
  --sort-mem 20G \
  --sort-parallel "$(nproc)" \
  --region REGION

# 如果 CSE envelope 使用 .instruction 文件模式，上面的对账命令需额外加：
#   --exclude-src-suffix .instruction --exclude-dst-suffix .instruction

# 2. 对 missing-in-destination.txt 中的 key 补跑迁移
java -jar migrate.jar --src-bucket SRC --dst-bucket DST \
  /mnt/scratch/verify/missing-in-destination.txt

# 补跑后重复步骤 1 和 2，直到 missing-in-destination=0

# 3. 抽查目标桶明文
aws s3 cp s3://DST_BUCKET/some-key /tmp/check && file /tmp/check
```

输出：

- `missing-in-destination.txt`：**必须为 0**，否则补跑步骤 2 并重新对账。
- `extra-in-destination.txt`：**预期非空**。其中包含：(a) 源端在迁移期间被删除、但目标桶未传播删除的 key；(b) 迁移前历史遗留的目标桶对象（若有）。该文件不作为验收阻塞项，但建议归档备查。

本迁移只处理当前版本，因此 `ListObjectsV2` 对账与迁移范围完全一致。

## 附加功能

| # | 功能 | 说明 |
|---|------|------|
| 1 | 预检 dry-run | T-7 前对 1,000 个随机 key 运行 dry-run，确认解密端到端可用 |
| 2 | 紧急停止标志 | 每处理 1,000 个任务检查一次，无需 kill 信号即可优雅排空 |
| 3 | 清单可用性轮询 | `ManifestLoader` 以指数退避（5→10→20→…→60 分钟）重试 |
| 4 | 验证采样 | 线程池排空后对目标桶中随机样本执行 HEAD 检查 |
| 5 | CloudWatch 指标 | `keys.attempted/succeeded/failed`、`bytes.decrypted`、`kms.src.decrypt.calls`（源 Decrypt 应用侧计数）、`s3.put.requests`、`decrypt.duration_ms`、`put.duration_ms`；目标 SSE-KMS 的实际 KMS 调用量通过 CloudTrail / KMS metrics 观测 |
| 6 | CSV MD5 校验 | 处理前检测损坏的 inventory part |
| 7 | 异常大小检测 | HEAD ≥ 5 GB 的对象写入 `failed.keys`，并在 `failed.log` 记录 `reason=oversized`；业务侧确认源桶不应存在此类对象 |
| 8 | 结构化失败日志 | `failed.keys`（percent-encoded key 列表，可直接重试）+ `failed.log`（percent-encoded-key、reason、timestamp，供排查） |
| 9 | KMS 限流退避 | `ThrottlingException` 指数退避 100ms→5s，最多 6 次，仍失败则进 `failed.keys` |
| 10 | 元数据保留 | PUT 时携带源对象 HTTP 元数据与所有 `x-amz-meta-*`；剥离 CSE envelope 头；不复制 Object Tags |

## IAM 权限

**迁移角色**（运行 `migrate.jar` 与 `inventory-diff.jar`）：

- 源桶：`s3:GetObject`、`s3:GetObjectVersion`（如需）
- 目标桶：`s3:PutObject`、`s3:GetObject`（用于 HEAD 抽检与 checkpoint 回读）
- Inventory 桶：`s3:GetObject`
- 源 KMS key：`kms:Decrypt`（加密上下文需与对象 `x-amz-matdesc` 匹配）
- 目标 KMS key：`kms:GenerateDataKey`、`kms:Encrypt`
- CloudWatch：`cloudwatch:PutMetricData`（namespace `S3Migration`）
- 不需要 `s3:ListBucket`（migrate.jar 不调用 `ListObjectsV2`）

**对账角色**（运行 `verify-listobjects-v2.py`）：

- 源桶 + 目标桶：`s3:ListBucket`

## 待补充约束

- [ ] 源桶名：`___________`
- [ ] 目标桶名：`___________`
- [ ] Inventory 桶名：`___________`
- [ ] AWS 区域：`___________`
- [ ] 源 KMS key ARN：`___________`
- [ ] 目标 KMS key ARN：`___________`
- [ ] CSE envelope 存储模式：`object metadata` 或 `.instruction` 文件（若为 `.instruction`，inventory normalize 与 ListObjectsV2 对账均过滤 `*.instruction`）
- [ ] 运行 migrate.jar 的 EC2/ECS 规格：`___________`
- [ ] 并发度上限（KMS TPS 配额）：`___________`
- [ ] 是否需要 VPC Endpoint：`___________`
- [ ] 日志保留策略：`___________`
- [x] 不使用 Athena
- [x] 暂不依赖独立部署 DB；RocksDB/SQLite 是否可安装待确认
- [x] 可使用 32GB 或 64GB EC2 跑 inventory diff 与 verify job
- [x] 一次性迁移，源对象为 V1 CSE-KMS，继续使用 AWS Java SDK V1 解密
- [x] 若 T-7 风险过高，可提前 T-14 开始全量迁移
- [x] 只迁移当前版本；目标桶对象按新文件处理
- [x] T 天不会再有文件写入
- [x] 目标桶启用 SSE-KMS + Bucket Key + Versioning（含 7 天 noncurrent version lifecycle）
- [x] 源端删除不传播到目标桶；`extra-in-destination.txt` 是预期产物
- [x] PUT 时保留 HTTP 元数据与 `x-amz-meta-*`；不保留 Object Tags
- [x] 业务侧确认源桶不存在 ≥ 5 GB 对象；若检测到视为异常
- [ ] 其他约束：`___________`

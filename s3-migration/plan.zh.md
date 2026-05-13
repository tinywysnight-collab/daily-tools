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

本方案只迁移源桶的 **current version**。历史版本和 delete marker 不进入迁移范围；目标桶对象按新文件 PUT。

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
2. 解析 CSV 字段，URL decode key，再 percent-encode 后写入内部文件（`%09` 代替 tab，`%0A` 代替换行），确保含特殊字符的 key 不破坏 TSV/行分隔格式。所有内部文件（records、checkpoint、failed.keys、oversized.log）均使用 percent-encoded key；仅在调用 S3 API 前 decode。
3. Inventory 配置为 `Current version only`，不迁移历史版本和 delete marker。
4. 输出比较记录：`percent-encoded-key<TAB>etag<TAB>size<TAB>lastModified`。这样新增 key 和同名 overwrite 都会进入增量。
5. 使用 EC2 本地 NVMe/EBS scratch 目录运行外部排序，例如 `LC_ALL=C sort -S 24G --parallel=$(nproc) -T /mnt/scratch -u`。
6. 对两个已排序文件做集合差分，输出 `current - baseline`。
7. 将差分结果投影回 key，并对 key 去重；同一个 key 多次变化时只需要迁移最终 current version。

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

**Key 编码规范**：S3 Inventory 对特殊字符使用 URL 编码（`+` 表示空格）。读取后先用 `URLDecoder.decode` 还原原始 key，再用 `URLEncoder.encode` / `%XX` percent-encode 后写入所有内部文件。`cut -f1` 取第一列时不会因 key 含 tab 而错位。调用 S3 API（`getObject`/`putObject`）前再 decode 回原始 key。

**当前版本限定**：本迁移只复制源桶当前版本。若源桶开启 versioning，历史版本和 delete marker 不进入迁移范围；目标桶按新 PUT 生成自己的版本历史。

### V1 CSE-KMS 解密

`MigrationWorker` 使用 `AmazonS3EncryptionClient`（V1 SDK）执行 GET 操作，SDK 透明处理完整的信封流程：

1. 从对象用户元数据中读取 `x-amz-key-v2`（base64 编码的 KMS 加密 DEK）和 `x-amz-matdesc`（KMS 上下文）
2. 使用加密的 DEK 和加密上下文调用 `KMS.Decrypt`
3. 根据 `x-amz-cek-alg` 使用 AES-CBC 或 AES-GCM 解密对象体
4. 返回明文的 `InputStream`

Worker 随后调用普通 `AmazonS3Client` 将对象 `putObject` 到目标桶（不做客户端加密）。

**大文件跳过**：GET 前先 HEAD 获取 `Content-Length`。若 `>= 5 GB`，跳过该对象，将 `percent-encoded-key\tsize\ttimestamp` 追加到 `oversized.log`，计入 `keys.skipped` 指标，不视为失败。大文件是**正式例外**：最终 ListObjectsV2 对账时须通过 `--oversized-keys` 传入 `oversized.keys`（从 `oversized.log` 提取的纯 key 列表），从期望集合中扣除，否则它们会出现在 `missing-in-destination.txt`。

**失败日志**：拆为两个文件：
- `failed.keys`：每行一个 percent-encoded key，可直接作为下次 `migrate.jar` 的输入用于重试。
- `failed.log`：`percent-encoded-key\treason\ttimestamp`，供排查用，不直接作为输入。

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
--oversized-log-s3  大文件日志 S3 备份 URI（默认: s3://DST_BUCKET/migration-logs/<run>-oversized.log）
--failed-log-s3     失败日志 S3 备份 URI（默认: s3://DST_BUCKET/migration-logs/<run>-failed.log）
--stop-flag-key     停止标志 S3 key，写入目标桶（默认: migration-logs/STOP）
--dry-run           解密但不 PUT 到目标桶
--metrics           上报 CloudWatch 指标（命名空间: S3Migration）
--verify-sample     已迁移 key 的 HEAD 验证比例（默认: 0.01）
--log-every         进度日志间隔（单位: key 数，默认: 1000）
--oversized-log     大文件（≥ 5 GB）本地 TSV 路径（默认: ./oversized.log）
--oversized-log-s3  大文件日志 S3 备份 URI
--failed-keys       失败 key 本地路径，纯 key 列表，可直接重试（默认: ./failed.keys）
--failed-log        失败详情本地 TSV 路径（默认: ./failed.log）
--failed-log-s3     失败日志 S3 备份 URI
--region
--profile
```

退出码：`0`=全部迁移完成，`1`=配置错误，`2`=部分失败，`3`=停止标志触发，`4`=检查点错误。

key 列表从位置参数文件或 stdin 读取。

## 检查点与容错

- 本地追加写入文件：每行一个已完成的 S3 key
- 启动时加载到内存 `HashSet<String>`，实现 O(1) 的 `contains()` 查询
- 内存估算：1000 万 key × 平均约 80 字节 ≈ ~800 MB JVM 堆；使用 `-Xmx2g`
- 每 100 次完成或每 5 秒（后台线程）刷新到本地文件
- 每 10,000 次完成或每 10 分钟同步到 S3
- checkpoint、`oversized.log`、`failed.log` 均备份到目标桶的 `migration-logs/` 前缀下，与业务对象隔离：
  ```
  s3://DST_BUCKET/migration-logs/
    t7-checkpoint.log
    t7-oversized.log
    t7-failed.log
    delta-YYYY-MM-DD-checkpoint.log
    delta-YYYY-MM-DD-oversized.log
    delta-YYYY-MM-DD-failed.log
  ```
- 启动时：若本地文件不存在，从 S3 下载
- 紧急停止：`aws s3 cp /dev/null s3://DST_BUCKET/migration-logs/STOP`，worker 每处理 1,000 个任务检查一次
- **注意**：`ListObjectsV2` 对账时须通过 `--dst-prefix` 排除 `migration-logs/` 前缀，避免日志文件出现在 `extra-in-destination.txt`

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

**唯一验收标准：`ListObjectsV2` 对账 `missing-in-destination=0`。**

checkpoint 计数和 inventory diff 行数仅供参考（含重试、overwrite 等因素，与"当前 key 集合"不等价），不作为验收依据。

```bash
# 1. 从 oversized.log 提取纯 key 列表（正式例外，对账时扣除）
cut -f1 s3://DST_BUCKET/migration-logs/t7-oversized.log > /tmp/oversized.keys
# 合并所有增量 oversized.log（如有）
for f in s3://DST_BUCKET/migration-logs/delta-*-oversized.log; do
  aws s3 cp "$f" - | cut -f1 >> /tmp/oversized.keys
done

# 2. ListObjectsV2 全量对账，排除 migration-logs/ 并扣除大文件例外
python3 scripts/verify-listobjects-v2.py \
  --src-bucket SRC_BUCKET \
  --dst-bucket DST_BUCKET \
  --exclude-dst-prefix migration-logs/ \
  --oversized-keys /tmp/oversized.keys \
  --workdir /mnt/scratch/verify \
  --sort-mem 20G \
  --sort-parallel "$(nproc)" \
  --region REGION

# 3. 对 missing-in-destination.txt 中的 key 补跑迁移
java -jar migrate.jar --src-bucket SRC --dst-bucket DST \
  /mnt/scratch/verify/missing-in-destination.txt

# 4. 抽查目标桶明文
aws s3 cp s3://DST_BUCKET/some-key /tmp/check && file /tmp/check
```

输出：

- `missing-in-destination.txt`：**必须为 0**，否则补跑步骤 3
- `extra-in-destination.txt`：目标桶多出的 key，通常用于人工确认

本迁移只处理当前版本，因此 `ListObjectsV2` 对账与迁移范围完全一致。

## 附加功能

| # | 功能 | 说明 |
|---|------|------|
| 1 | 预检 dry-run | T-7 前对 1,000 个随机 key 运行 dry-run，确认解密端到端可用 |
| 2 | 紧急停止标志 | 每处理 1,000 个任务检查一次，无需 kill 信号即可优雅排空 |
| 3 | 清单可用性轮询 | `ManifestLoader` 以指数退避（5→10→20→…→60 分钟）重试 |
| 4 | 验证采样 | 线程池排空后对目标桶中随机样本执行 HEAD 检查 |
| 5 | CloudWatch 指标 | `keys.attempted/succeeded/failed/skipped`、`bytes.decrypted`、`kms.calls`、`decrypt.duration_ms` |
| 6 | CSV MD5 校验 | 处理前检测损坏的 inventory part |
| 7 | 大文件跳过日志 | GET 前 HEAD 检查；≥ 5 GB 的对象写入 `oversized.log`（percent-encoded-key、size、timestamp），计入 `keys.skipped`，不中断主流程；最终对账须用 `--oversized-keys` 扣除 |
| 8 | 结构化失败日志 | `failed.keys`（纯 key 列表，可直接重试）+ `failed.log`（percent-encoded-key、reason、timestamp，供排查） |

## 待补充约束

- [ ] 源桶名：`___________`
- [ ] 目标桶名：`___________`
- [ ] Inventory 桶名：`___________`
- [ ] AWS 区域：`___________`
- [ ] KMS key ARN（用于权限验证）：`___________`
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
- [ ] 其他约束：`___________`

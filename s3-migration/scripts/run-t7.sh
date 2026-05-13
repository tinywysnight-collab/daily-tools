#!/usr/bin/env bash
# Full migration (T-7 / T-14): inventory-diff in full mode, then migrate every current key.
# Required env: SRC_BUCKET, DST_BUCKET, INV_BUCKET, KMS_KEY_ID, REGION, CURRENT_MANIFEST (s3:// URI)
# Optional env: CONCURRENCY (default 50), SCRATCH_DIR (default /mnt/scratch), SORT_MEM (default 20G),
#               FILTER_SUFFIX (default empty), METRICS (default true)
set -euo pipefail

: "${SRC_BUCKET:?}"; : "${DST_BUCKET:?}"; : "${INV_BUCKET:?}"; : "${KMS_KEY_ID:?}"
: "${REGION:?}"; : "${CURRENT_MANIFEST:?}"
CONCURRENCY=${CONCURRENCY:-50}
SCRATCH_DIR=${SCRATCH_DIR:-/mnt/scratch}
SORT_MEM=${SORT_MEM:-20G}
FILTER_SUFFIX=${FILTER_SUFFIX:-}
METRICS=${METRICS:-true}

RUN_TAG=t7-$(date -u +%Y%m%d)
WORK_DIR=${SCRATCH_DIR}/${RUN_TAG}
mkdir -p "${WORK_DIR}"

INV_DIFF_JAR=$(dirname "$0")/../inventory-diff/target/inventory-diff.jar
MIGRATE_JAR=$(dirname "$0")/../migrate/target/migrate.jar

java -Xmx4g -jar "${INV_DIFF_JAR}" \
  --current-manifest "${CURRENT_MANIFEST}" \
  --inventory-bucket "${INV_BUCKET}" \
  --out "${WORK_DIR}/baseline.keys" \
  --scratch-dir "${WORK_DIR}" \
  --sort-mem "${SORT_MEM}" \
  --filter-suffix "${FILTER_SUFFIX}" \
  --region "${REGION}"

METRICS_FLAG=""
[ "${METRICS}" = "true" ] && METRICS_FLAG="--metrics true"

java -Xmx2g -jar "${MIGRATE_JAR}" \
  --src-bucket "${SRC_BUCKET}" \
  --dst-bucket "${DST_BUCKET}" \
  --kms-key-id "${KMS_KEY_ID}" \
  --concurrency "${CONCURRENCY}" \
  --checkpoint "${WORK_DIR}/${RUN_TAG}-checkpoint.log" \
  --failed-keys "${WORK_DIR}/${RUN_TAG}-failed.keys" \
  --failed-log "${WORK_DIR}/${RUN_TAG}-failed.log" \
  ${METRICS_FLAG} \
  --region "${REGION}" \
  "${WORK_DIR}/baseline.keys"

# Sync operational artifacts to dst bucket for durability.
aws s3 cp "${WORK_DIR}/${RUN_TAG}-checkpoint.log" "s3://${DST_BUCKET}/migration-logs/${RUN_TAG}-checkpoint.log"
aws s3 cp "${WORK_DIR}/${RUN_TAG}-failed.keys"    "s3://${DST_BUCKET}/migration-logs/${RUN_TAG}-failed.keys"
aws s3 cp "${WORK_DIR}/${RUN_TAG}-failed.log"     "s3://${DST_BUCKET}/migration-logs/${RUN_TAG}-failed.log"

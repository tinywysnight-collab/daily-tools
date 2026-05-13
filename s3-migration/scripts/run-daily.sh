#!/usr/bin/env bash
# Daily delta migration: diff today's inventory against yesterday's, migrate the delta keys.
# Usage: run-daily.sh BASELINE_MANIFEST CURRENT_MANIFEST
# Required env: SRC_BUCKET, DST_BUCKET, INV_BUCKET, KMS_KEY_ID, REGION
# Optional env: CONCURRENCY (default 20), SCRATCH_DIR (default /mnt/scratch), SORT_MEM (default 20G),
#               FILTER_SUFFIX (default empty)
set -euo pipefail

BASELINE_MANIFEST=${1:?baseline manifest s3:// URI required}
CURRENT_MANIFEST=${2:?current manifest s3:// URI required}
: "${SRC_BUCKET:?}"; : "${DST_BUCKET:?}"; : "${INV_BUCKET:?}"; : "${KMS_KEY_ID:?}"; : "${REGION:?}"
CONCURRENCY=${CONCURRENCY:-20}
SCRATCH_DIR=${SCRATCH_DIR:-/mnt/scratch}
SORT_MEM=${SORT_MEM:-20G}
FILTER_SUFFIX=${FILTER_SUFFIX:-}

RUN_TAG=delta-$(date -u +%Y-%m-%d)
WORK_DIR=${SCRATCH_DIR}/${RUN_TAG}
mkdir -p "${WORK_DIR}"

INV_DIFF_JAR=$(dirname "$0")/../inventory-diff/target/inventory-diff.jar
MIGRATE_JAR=$(dirname "$0")/../migrate/target/migrate.jar

java -Xmx4g -jar "${INV_DIFF_JAR}" \
  --baseline-manifest "${BASELINE_MANIFEST}" \
  --current-manifest  "${CURRENT_MANIFEST}" \
  --inventory-bucket  "${INV_BUCKET}" \
  --out "${WORK_DIR}/delta.keys" \
  --scratch-dir "${WORK_DIR}" \
  --sort-mem "${SORT_MEM}" \
  --filter-suffix "${FILTER_SUFFIX}" \
  --region "${REGION}"

java -Xmx2g -jar "${MIGRATE_JAR}" \
  --src-bucket "${SRC_BUCKET}" \
  --dst-bucket "${DST_BUCKET}" \
  --kms-key-id "${KMS_KEY_ID}" \
  --concurrency "${CONCURRENCY}" \
  --checkpoint "${WORK_DIR}/${RUN_TAG}-checkpoint.log" \
  --failed-keys "${WORK_DIR}/${RUN_TAG}-failed.keys" \
  --failed-log "${WORK_DIR}/${RUN_TAG}-failed.log" \
  --region "${REGION}" \
  "${WORK_DIR}/delta.keys"

aws s3 cp "${WORK_DIR}/${RUN_TAG}-checkpoint.log" "s3://${DST_BUCKET}/migration-logs/${RUN_TAG}-checkpoint.log"
aws s3 cp "${WORK_DIR}/${RUN_TAG}-failed.keys"    "s3://${DST_BUCKET}/migration-logs/${RUN_TAG}-failed.keys"
aws s3 cp "${WORK_DIR}/${RUN_TAG}-failed.log"     "s3://${DST_BUCKET}/migration-logs/${RUN_TAG}-failed.log"

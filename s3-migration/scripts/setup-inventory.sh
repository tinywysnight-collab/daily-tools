#!/usr/bin/env bash
# Configure daily S3 Inventory on a source bucket. First delivery may take up to 48h.
# Usage: setup-inventory.sh SRC_BUCKET INV_BUCKET INV_PREFIX REGION [INVENTORY_ID]
set -euo pipefail

SRC_BUCKET=${1:?source bucket required}
INV_BUCKET=${2:?inventory bucket required}
INV_PREFIX=${3:?inventory prefix required}
REGION=${4:?region required}
INVENTORY_ID=${5:-migration-daily}

CONFIG=$(cat <<EOF
{
  "Destination": {
    "S3BucketDestination": {
      "Bucket": "arn:aws:s3:::${INV_BUCKET}",
      "Format": "CSV",
      "Prefix": "${INV_PREFIX}"
    }
  },
  "IsEnabled": true,
  "Id": "${INVENTORY_ID}",
  "IncludedObjectVersions": "Current",
  "Schedule": { "Frequency": "Daily" },
  "OptionalFields": ["Size", "LastModifiedDate", "ETag"]
}
EOF
)

aws s3api put-bucket-inventory-configuration \
    --region "${REGION}" \
    --bucket "${SRC_BUCKET}" \
    --id "${INVENTORY_ID}" \
    --inventory-configuration "${CONFIG}"

echo "Inventory '${INVENTORY_ID}' configured on s3://${SRC_BUCKET} -> s3://${INV_BUCKET}/${INV_PREFIX}/"
echo "First manifest may take up to 48 hours to appear at:"
echo "  s3://${INV_BUCKET}/${INV_PREFIX}/${SRC_BUCKET}/${INVENTORY_ID}/YYYY-MM-DDT00-00Z/manifest.json"

package com.migration.s3.inventory;

import java.util.List;

public record Manifest(
        String sourceBucket,
        String fileFormat,
        List<String> columns,
        List<InventoryFile> files
) {
    public record InventoryFile(String key, long size, String md5) {}
}

package com.migration.s3.delta;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExternalSortDiffTest {

    @TempDir
    Path tmp;

    private ExternalSortDiff diff;

    @BeforeEach
    void setUp() {
        diff = new ExternalSortDiff(tmp, "64M", 1);
    }

    private Path write(String name, String... lines) throws IOException {
        Path p = tmp.resolve(name);
        Files.writeString(p, String.join("\n", lines) + (lines.length == 0 ? "" : "\n"));
        return p;
    }

    @Test
    void sortRecordsSortsAndDedupes() throws IOException {
        Path in = write("in.txt", "b\t1", "a\t2", "b\t1", "a\t2", "c\t3");
        Path out = tmp.resolve("out.txt");

        diff.sortRecords(in, out);

        assertEquals(List.of("a\t2", "b\t1", "c\t3"), Files.readAllLines(out));
    }

    @Test
    void deltaKeysReturnsCurrentMinusBaseline() throws IOException {
        Path baseline = write("baseline.txt", "a\tE1\t1\tt", "b\tE2\t1\tt");
        Path current = write("current.txt", "a\tE1\t1\tt", "b\tE2_changed\t1\tt", "c\tE3\t1\tt");
        Path baselineSorted = tmp.resolve("baseline.sorted");
        Path currentSorted = tmp.resolve("current.sorted");
        diff.sortRecords(baseline, baselineSorted);
        diff.sortRecords(current, currentSorted);

        Path out = tmp.resolve("delta.keys");
        diff.deltaKeys(baselineSorted, currentSorted, out);

        // b is etag-changed; c is new — both keys appear.
        assertEquals(List.of("b", "c"), Files.readAllLines(out));
    }

    @Test
    void allKeysDedupesProjectedKeys() throws IOException {
        Path sorted = write("sorted.txt", "a\tE1\t1\tt", "a\tE2\t1\tt", "b\tE3\t1\tt");
        Path out = tmp.resolve("all.keys");

        diff.allKeys(sorted, out);

        assertEquals(List.of("a", "b"), Files.readAllLines(out));
    }
}

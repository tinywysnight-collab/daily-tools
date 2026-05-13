package com.migration.s3.delta;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ExternalSortDiff {

    private final Path scratchDir;
    private final String sortMem;
    private final int sortParallel;

    public ExternalSortDiff(Path scratchDir, String sortMem, int sortParallel) {
        this.scratchDir = scratchDir;
        this.sortMem = sortMem;
        this.sortParallel = sortParallel;
    }

    public void sortRecords(Path input, Path output) throws IOException {
        runPipeline(List.of(sortCmd("-u", "-o", output.toString(), input.toString())));
    }

    public void deltaKeys(Path baselineSorted, Path currentSorted, Path output) throws IOException {
        runPipeline(List.of(
                List.of("comm", "-13", baselineSorted.toString(), currentSorted.toString()),
                List.of("cut", "-f1"),
                sortCmd("-u", "-o", output.toString())
        ));
    }

    public void allKeys(Path sortedRecords, Path output) throws IOException {
        runPipeline(List.of(
                List.of("cut", "-f1", sortedRecords.toString()),
                sortCmd("-u", "-o", output.toString())
        ));
    }

    private List<String> sortCmd(String... tail) {
        List<String> cmd = new ArrayList<>();
        cmd.add("sort");
        cmd.add("-S");
        cmd.add(sortMem);
        cmd.add("-T");
        cmd.add(scratchDir.toString());
        if (sortParallel > 1) {
            cmd.add("--parallel=" + sortParallel);
        }
        for (String t : tail) cmd.add(t);
        return cmd;
    }

    private void runPipeline(List<List<String>> commands) throws IOException {
        List<ProcessBuilder> builders = new ArrayList<>();
        for (List<String> cmd : commands) {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            pb.environment().put("LC_ALL", "C");
            builders.add(pb);
        }
        List<Process> procs;
        try {
            procs = ProcessBuilder.startPipeline(builders);
        } catch (UnsupportedOperationException e) {
            throw new IOException("pipeline not supported on this platform", e);
        }
        try {
            for (int i = 0; i < procs.size(); i++) {
                Process p = procs.get(i);
                int code = p.waitFor();
                if (code != 0) {
                    String err = new String(p.getErrorStream().readAllBytes());
                    throw new IOException("command failed (exit " + code + "): "
                            + String.join(" ", commands.get(i)) + "\n" + err);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while running pipeline", e);
        }
    }
}

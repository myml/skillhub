package com.iflytek.skillhub.gitsync.git;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses {@code git ls-tree -r -l -z} output into {@link GitTreeEntry} values.
 *
 * <p>The long format is {@code <mode> SP <type> SP <sha> SP <size> TAB <path>}
 * with NUL separators, which keeps paths containing spaces, quotes or newlines
 * intact.
 */
final class GitTreeListing {

    private GitTreeListing() {
    }

    static List<GitTreeEntry> parse(byte[] output) {
        List<GitTreeEntry> entries = new ArrayList<>();
        String text = new String(output, StandardCharsets.UTF_8);
        for (String record : text.split("\0")) {
            if (record.isEmpty()) {
                continue;
            }
            int tab = record.indexOf('\t');
            if (tab <= 0) {
                continue;
            }
            String[] header = record.substring(0, tab).trim().split("\\s+");
            String path = record.substring(tab + 1);
            if (header.length < 3 || path.isEmpty()) {
                continue;
            }
            String type = header[1];
            String sha = header[2];
            long size = 0L;
            if (header.length >= 4) {
                try {
                    // Directories and submodule commits report "-".
                    size = Long.parseLong(header[3]);
                } catch (NumberFormatException ex) {
                    size = 0L;
                }
            }
            entries.add(new GitTreeEntry(path, type, sha, size));
        }
        return entries;
    }

    /** Environment carrying nothing: the mirror is read from the local disk. */
    static Map<String, String> localOnly() {
        return Map.of();
    }
}

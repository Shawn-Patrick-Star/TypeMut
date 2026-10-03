package org.fuzz.util;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.HashMap;

@Slf4j
public class LogGrouper {
    private static final Pattern ROLLED_LOG_SUFFIX = Pattern.compile(".*\\.log\\.(\\d+)$");

    public static void groupLogs(String inputPath, String outputPath) {
        processLogs(inputPath, outputPath, false);
    }

    public static void extractBugLogs(String inputPath, String outputPath) {
        processLogs(inputPath, outputPath, true);
    }

    public static IncrementalSession incremental(Path input, Path groupedOutput, Path bugsOutput) {
        return new IncrementalSession(input, groupedOutput, bugsOutput);
    }

    public static IncrementalSession incremental(Path input, Path groupedOutput) {
        return new IncrementalSession(input, groupedOutput, null);
    }

    public static void cleanupRollingBackups(String inputPath) {
        Path input = Paths.get(inputPath);
        Path parent = input.getParent();
        Path fileName = input.getFileName();
        if (parent == null || fileName == null || !Files.isDirectory(parent)) {
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent, fileName + ".*")) {
            for (Path candidate : stream) {
                if (isRolledLog(input, candidate)) {
                    Files.deleteIfExists(candidate);
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to clean old rolling log backups for " + inputPath + ": " + e.getMessage());
        }
    }

    private static void processLogs(String inputPath, String outputPath, boolean bugsOnly) {
        Path input = Paths.get(inputPath);
        Path output = Paths.get(outputPath);
        List<Path> inputs = resolveLogInputs(input);

        if (inputs.isEmpty()) {
            log.warn("Cannot translate logs. File not found: {}", inputPath);
            return;
        }

        try {
            Map<String, List<String>> groupMap = new LinkedHashMap<>();
            List<String> unmappedLogs = new ArrayList<>();
            Pattern pattern = Pattern.compile("\\[([^\\]]+)\\] - ");

            String currentGroup = null;
            for (Path logInput : inputs) {
                try (BufferedReader reader = Files.newBufferedReader(logInput, StandardCharsets.UTF_8)) {
                    String line;

                    while ((line = reader.readLine()) != null) {
                        java.util.regex.Matcher m = pattern.matcher(line);
                        if (m.find()) {
                            currentGroup = m.group(1);
                            groupMap.computeIfAbsent(currentGroup, k -> new ArrayList<>()).add(line);
                        } else if (line.matches("^\\[(INFO|DEBUG|WARN|ERROR|FATAL|TRACE)\\s*\\].*")) {
                            currentGroup = null;
                            unmappedLogs.add(line);
                        } else if (currentGroup != null) {
                            groupMap.get(currentGroup).add(line);
                        } else {
                            unmappedLogs.add(line);
                        }
                    }
                }
            }

            try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                
                if (!bugsOnly) {
                    for (String line : unmappedLogs) {
                        writer.write(line);
                        writer.newLine();
                    }
                }

                for (Map.Entry<String, List<String>> entry : groupMap.entrySet()) {
                    List<String> logs = entry.getValue();
                    boolean hasBug = logs.stream().anyMatch(l -> l.contains("BUG FOUND"));

                    if (!bugsOnly || hasBug) {
                        writer.write("\n======================================================\n");
                        writer.write("Group: " + entry.getKey() + (hasBug ? " [BUG]" : "") + "\n");
                        writer.write("======================================================\n");
                        for (String line : logs) {
                            writer.write(line);
                            writer.newLine();
                        }
                    }
                }
            }
            log.info("Log processing completed -> {}, mode: {}, input files: {}",
                    outputPath, bugsOnly ? "BUGS_ONLY" : "ALL", inputs.size());
        } catch (IOException e) {
            log.error("IO Exception during log processing: {}", e.getMessage());
        }
    }

    private static List<Path> resolveLogInputs(Path input) {
        List<Path> inputs = new ArrayList<>();
        Path parent = input.getParent();
        Path fileName = input.getFileName();
        if (parent != null && fileName != null && Files.isDirectory(parent)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent, fileName + ".*")) {
                for (Path candidate : stream) {
                    if (isRolledLog(input, candidate)) {
                        inputs.add(candidate);
                    }
                }
            } catch (IOException e) {
                log.warn("Cannot inspect rolled logs for {}: {}", input, e.getMessage());
            }
        }

        inputs.sort(Comparator.comparingInt(LogGrouper::rolledIndex).reversed());
        if (Files.exists(input)) {
            inputs.add(input);
        }
        return inputs;
    }

    private static boolean isRolledLog(Path baseLog, Path candidate) {
        String baseName = baseLog.getFileName().toString();
        String candidateName = candidate.getFileName().toString();
        if (!candidateName.startsWith(baseName + ".")) {
            return false;
        }
        return ROLLED_LOG_SUFFIX.matcher(candidateName).matches();
    }

    private static int rolledIndex(Path path) {
        java.util.regex.Matcher matcher = ROLLED_LOG_SUFFIX.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static final class IncrementalSession {
        private static final Pattern GROUP_PATTERN = Pattern.compile("\\[([^\\]]+)\\] - ");
        private static final Pattern LOG_LINE = Pattern.compile("^\\[(INFO|DEBUG|WARN|ERROR|FATAL|TRACE)\\s*\\].*");

        private final Path input;
        private final Path groupedOutput;
        private final Path bugsOutput;
        private final Map<String, Long> offsets = new HashMap<>();
        private final Map<String, List<String>> groupMap = new LinkedHashMap<>();
        private final List<String> unmappedLogs = new ArrayList<>();
        private String currentGroup;

        private IncrementalSession(Path input, Path groupedOutput, Path bugsOutput) {
            this.input = input.toAbsolutePath().normalize();
            this.groupedOutput = groupedOutput.toAbsolutePath().normalize();
            this.bugsOutput = bugsOutput == null ? null : bugsOutput.toAbsolutePath().normalize();
        }

        public synchronized void flush() throws IOException {
            for (Path logInput : resolveLogInputs(input)) readNewCompleteLines(logInput);
            write(groupedOutput, false);
            if (bugsOutput != null) write(bugsOutput, true);
        }

        private void readNewCompleteLines(Path file) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            String identity = attributes.fileKey() == null
                    ? file.toAbsolutePath().normalize().toString()
                    : attributes.fileKey().toString();
            long offset = offsets.getOrDefault(identity, 0L);
            long size = attributes.size();
            if (size < offset) offset = 0L;
            long remaining = size - offset;
            if (remaining <= 0L) return;
            if (remaining > Integer.MAX_VALUE) {
                throw new IOException("Incremental log chunk is too large: " + remaining);
            }

            ByteBuffer buffer = ByteBuffer.allocate((int) remaining);
            try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
                channel.position(offset);
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                                                            
                }
            }
            byte[] bytes = buffer.array();
            int lastNewline = -1;
            for (int i = bytes.length - 1; i >= 0; i--) {
                if (bytes[i] == '\n') { lastNewline = i; break; }
            }
            if (lastNewline < 0) return;

            String complete = new String(bytes, 0, lastNewline + 1, StandardCharsets.UTF_8);
            complete.lines().forEach(this::acceptLine);
            offsets.put(identity, offset + lastNewline + 1L);
        }

        private void acceptLine(String line) {
            java.util.regex.Matcher matcher = GROUP_PATTERN.matcher(line);
            if (matcher.find()) {
                currentGroup = matcher.group(1);
                groupMap.computeIfAbsent(currentGroup, ignored -> new ArrayList<>()).add(line);
            } else if (LOG_LINE.matcher(line).matches()) {
                currentGroup = null;
                unmappedLogs.add(line);
            } else if (currentGroup != null) {
                groupMap.get(currentGroup).add(line);
            } else {
                unmappedLogs.add(line);
            }
        }

        private void write(Path output, boolean bugsOnly) throws IOException {
            if (output.getParent() != null) Files.createDirectories(output.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                if (!bugsOnly) {
                    for (String line : unmappedLogs) {
                        writer.write(line);
                        writer.newLine();
                    }
                }
                for (Map.Entry<String, List<String>> entry : groupMap.entrySet()) {
                    boolean hasBug = entry.getValue().stream().anyMatch(line -> line.contains("BUG FOUND"));
                    if (bugsOnly && !hasBug) continue;
                    writer.write("\n======================================================\n");
                    writer.write("Group: " + entry.getKey() + (hasBug ? " [BUG]" : "") + "\n");
                    writer.write("======================================================\n");
                    for (String line : entry.getValue()) {
                        writer.write(line);
                        writer.newLine();
                    }
                }
            }
        }
    }
}

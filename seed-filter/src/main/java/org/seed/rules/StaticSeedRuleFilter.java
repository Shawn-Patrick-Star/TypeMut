package org.seed.rules;

import org.seed.model.RejectionReason;
import org.seed.model.Seed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class StaticSeedRuleFilter {

    private static final List<Rule> RULES = List.of(
            new Rule(
                    "time-api",
                    RejectionReason.STATIC_TIME,
                    Pattern.compile("\\b(Date|Calendar|DateFormat|SimpleDateFormat|TimeZone|LocalDate|LocalTime|LocalDateTime|ZonedDateTime|Instant|nanoTime|currentTimeMillis)\\b"                        
                )
            ),
            new Rule(
                    "external-java-process",
                    RejectionReason.STATIC_SYSTEM,
                    Pattern.compile(
                            "\\bProcessBuilder\\s*\\(\\s*\"java\"|"
                                    + "\\bRuntime\\s*\\.\\s*getRuntime\\s*\\(\\s*\\)\\s*\\.\\s*exec\\s*\\(\\s*\"java\""
                    )
            ),
            new Rule(
                "thread-api",
                RejectionReason.STATIC_THREAD,
                Pattern.compile(
                    "\\b(Thread|Runnable|Callable|ExecutorService|ThreadPoolExecutor|ScheduledExecutorService|" +
                    "ForkJoinPool|ForkJoinTask|CountDownLatch|CyclicBarrier|Semaphore|Phaser|Exchanger|" +
                    "Future|CompletableFuture|BlockingQueue|ConcurrentHashMap|ConcurrentLinkedQueue|" +
                    "CopyOnWriteArrayList|LockSupport|ReentrantLock|ReadWriteLock|StampedLock|Condition|ProcessBuilder|Currency)\\b|" +
                    "java\\.util\\.concurrent\\.|" +
                    "\\bRuntime\\s*\\.\\s*getRuntime\\s*\\("
                )
            ),            
            new Rule(
                    "random-api",
                    RejectionReason.STATIC_SYSTEM,
                    Pattern.compile("(?i)\\b(?:Random|ThreadLocalRandom|SplittableRandom)\\b|\\bMath\\s*\\.\\s*random\\s*\\("    
                )
            ),
            new Rule(
                    "properties-usage",
                    RejectionReason.STATIC_SYSTEM,
                    Pattern.compile("\\b(?:System|Properties)\\s*\\.\\s*(?:getProperty|setProperty)\\s*\\(|\\bjava\\.util\\.Properties\\b"
                )
            ),
            new Rule(
                    "java-compiler-api",
                    RejectionReason.STATIC_SYSTEM,
                    Pattern.compile(
                        "\\bjavax\\.tools\\.JavaCompiler\\b|" +
                        "\\bToolProvider\\s*\\.\\s*getSystemJavaCompiler\\s*\\(|" +
                        "\\bcompiler\\.lib\\."
                    )
            ),
            new Rule(
                    "unsafe-api",
                    RejectionReason.STATIC_SYSTEM,
                    Pattern.compile("\\b(?:sun\\.misc\\.|jdk\\.internal\\.misc\\.)?Unsafe\\b|\\bUnsafe\\s*\\.\\s*getUnsafe\\s*\\(|\\btheUnsafe\\b")),
            new Rule(
                    "unsupported-lib",
                    RejectionReason.STATIC_SYSTEM,
                    Pattern.compile(
                        "\\b(?:import\\s+)?(?:javafx\\.|com\\.sun\\.|sun\\.|" +
                        "jdk\\.internal\\.|jdk\\.nashorn\\.|java\\.lang\\.foreign\\.|javax\\.|" +
                        "java\\.net\\.|java\\.rmi\\.|java\\.security\\.|java\\.io\\.|java\\.awt\\.|" +
                        "java\\.nio\\.|java\\.text\\.|java\\.sql\\.|org\\.|java\\.util\\.Locale|MethodHandleProxies\\b)"
                    )
            )
        );

    public Optional<StaticRuleMatch> firstMatch(Seed seed) {
        for (Path file : seedFiles(seed)) {
            String content;
            try {
                content = stripJavaComments(Files.readString(file));
            } catch (IOException e) {
                continue;
            }
            for (Rule rule : RULES) {
                if (rule.pattern().matcher(content).find()) {
                    return Optional.of(new StaticRuleMatch(rule.name(), rule.reason(),
                            "Matched " + rule.name() + " in " + file.getFileName()));
                }
            }
        }
        return Optional.empty();
    }

    private List<Path> seedFiles(Seed seed) {
        return Stream.concat(Stream.of(seed.getMainFilePath()), seed.getCompanionFilePaths().stream())
                .filter(file -> !"FuzzerUtils.java".equals(file.getFileName().toString()))
                .toList();
    }

    private String stripJavaComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean escaped = false;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (c == '\n' || c == '\r') {
                    inLineComment = false;
                    out.append(c);
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                } else if (c == '\n' || c == '\r') {
                    out.append(c);
                }
                continue;
            }
            if (!inString && !inChar && c == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (!inString && !inChar && c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }

            out.append(c);
            if (escaped) {
                escaped = false;
                continue;
            }
            if ((inString || inChar) && c == '\\') {
                escaped = true;
                continue;
            }
            if (!inChar && c == '"') {
                inString = !inString;
            } else if (!inString && c == '\'') {
                inChar = !inChar;
            }
        }
        return out.toString();
    }

    private record Rule(String name, RejectionReason reason, Pattern pattern) {
    }

    public record StaticRuleMatch(String ruleName, RejectionReason reason, String detail) {
    }
}


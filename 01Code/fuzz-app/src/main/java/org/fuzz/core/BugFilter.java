package org.fuzz.core;

import lombok.extern.slf4j.Slf4j;
import org.difftest.model.DTResult;
import org.difftest.model.exec.ExecutionStatus;
import org.difftest.model.exec.RunTimeExecutionResult;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.difftest.model.exec.RunTimeExecutionResult.IssueType.EXCEPTION;

   
                                                                
  
                                                                                
                                                 
   
@Slf4j
public class BugFilter {

    public enum FilterCategory {
        KNOWN_FALSE_POSITIVE,
        MAIN_CLASS_NOT_FOUND
    }

    public record FilterMatch(String name, FilterCategory category) {
    }

    private static final String JAVAC8_HOTSPOT8_ID = "javac-8 -> hotspot-8";
    private static final String JAVAC8_OPENJ9_8_ID = "javac-8 -> openj9-8";
    private static final String ECJ_HOTSPOT8_ID = "ecj -> hotspot-8";
    private static final String ECJ_OPENJ9_8_ID = "ecj -> openj9-8";

    private static final List<FilterRule> FILTER_RULES = List.of(
            rule(" ", Map.of(
                    JAVAC8_HOTSPOT8_ID, expectExceptions(Map.of("java.lang.RuntimeException", 1L)),
                    ECJ_HOTSPOT8_ID,    expect(ExecutionStatus.SUCCESS),
                    JAVAC8_OPENJ9_8_ID, expect(ExecutionStatus.SUCCESS),
                    ECJ_OPENJ9_8_ID,    expect(ExecutionStatus.SUCCESS))),

            rule(" ", Map.of(
                    JAVAC8_HOTSPOT8_ID, expectExceptions(Map.of("java.lang.ClassNotFoundException", 250L)),
                    ECJ_HOTSPOT8_ID,    expectExceptions(Map.of("java.lang.ClassNotFoundException", 250L)),
                    JAVAC8_OPENJ9_8_ID, expectExceptions(Map.of("java.lang.ClassNotFoundException", 400L)),
                    ECJ_OPENJ9_8_ID,    expectExceptions(Map.of("java.lang.ClassNotFoundException", 400L)))),

            rule("openj9 reports JMX DumpOptions as write-only while hotspot succeeds", Map.of(
                    JAVAC8_HOTSPOT8_ID, expect(ExecutionStatus.SUCCESS),
                    ECJ_HOTSPOT8_ID,    expect(ExecutionStatus.SUCCESS),
                    JAVAC8_OPENJ9_8_ID, expectExceptions(Map.of("javax.management.AttributeNotFoundException", 1L)),
                    ECJ_OPENJ9_8_ID,    expectExceptions(Map.of("javax.management.AttributeNotFoundException", 1L))))
    );


    public boolean isFiltered(DTResult result) {
        return match(result).isPresent();
    }

    public Optional<FilterMatch> match(DTResult result) {
        if (result == null || result.getExecutionResults() == null) {
            return Optional.empty();
        }

        Map<String, RunTimeExecutionResult> resultsById = result.getExecutionResults().stream()
                .filter(RunTimeExecutionResult.class::isInstance)
                .map(RunTimeExecutionResult.class::cast)
                .collect(Collectors.toMap(
                        RunTimeExecutionResult::getId,
                        Function.identity(),
                        (first, ignored) -> first,
                        TreeMap::new));

        for (FilterRule rule : FILTER_RULES) {
            if (rule.matches(resultsById)) {
                log.info("[Filter] {}. Skipping.", rule.name());
                return Optional.of(new FilterMatch(rule.name(), rule.category()));
            }
        }

        return Optional.empty();
    }

    private static FilterRule rule(String name, Map<String, ExpectedRuntime> expectedRuntimes) {
        return rule(name, FilterCategory.KNOWN_FALSE_POSITIVE, expectedRuntimes);
    }

    private static FilterRule rule(String name, FilterCategory category, Map<String, ExpectedRuntime> expectedRuntimes) {
        return new FilterRule(name, category, expectedRuntimes);
    }

    private static ExpectedRuntime expect(ExecutionStatus status) {
        return new ExpectedRuntime(status, null);
    }

    private static ExpectedRuntime expectExceptions(Map<String, Long> exceptions) {
        return new ExpectedRuntime(
                ExecutionStatus.FAILURE,
                issueSignature(Map.of(EXCEPTION, exceptions)));
    }

    private record FilterRule(String name, FilterCategory category, Map<String, ExpectedRuntime> expectedRuntimes) {
        private boolean matches(Map<String, RunTimeExecutionResult> actualResults) {
            return expectedRuntimes.entrySet().stream()
                    .allMatch(entry -> {
                        RunTimeExecutionResult actual = actualResults.get(entry.getKey());
                        return actual != null && entry.getValue().matches(actual);
                    });
        }
    }

    private record ExpectedRuntime(
            ExecutionStatus status,
            Map<RunTimeExecutionResult.IssueType, Map<String, Long>> exactIssues) {

        private boolean matches(RunTimeExecutionResult actual) {
            if (actual.getStatus() != status) {
                return false;
            }
            return exactIssues == null || issueSignature(actual.getIssues()).equals(exactIssues);
        }
    }

    private static Map<RunTimeExecutionResult.IssueType, Map<String, Long>> issueSignature(
            Map<RunTimeExecutionResult.IssueType, Map<String, Long>> issues) {
        if (issues == null) {
            return Map.of();
        }

        EnumMap<RunTimeExecutionResult.IssueType, Map<String, Long>> signature =
                new EnumMap<>(RunTimeExecutionResult.IssueType.class);

        issues.forEach((type, details) -> {
            Map<String, Long> normalizedDetails = normalizeIssueDetails(details);
            if (!normalizedDetails.isEmpty()) {
                signature.put(type, normalizedDetails);
            }
        });

        return signature;
    }

    private static Map<String, Long> normalizeIssueDetails(Map<String, Long> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }

        return details.entrySet().stream()
                .filter(entry -> entry.getKey() != null)
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        Long::sum,
                        TreeMap::new));
    }
}

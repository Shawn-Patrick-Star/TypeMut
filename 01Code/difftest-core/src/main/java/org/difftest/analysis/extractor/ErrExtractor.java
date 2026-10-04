package org.difftest.analysis.extractor;

import org.difftest.model.exec.RunTimeExecutionResult;
import org.difftest.model.DTFailureReason;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

   
              
                                              
                                      
   
public class ErrExtractor {
    
    private static final Pattern EX_PATTERN = Pattern.compile(
        "\\b((?!Exception in thread)([a-z][a-z0-9_]*\\.)*([A-Z][a-zA-Z0-9_$]*)?(Exception|Error))\\b");

    public void extract(RunTimeExecutionResult result) {
        String stdout = result.getStdout();
        String stderr = result.getStderr();

                                         
        extractExceptionsFrom(stderr, result);
        extractExceptionsFrom(stdout, result);

                                
        extractCrashFrom(result);
        
        if (isMainClassNotFound(result)) {
            result.setFailureReason(DTFailureReason.MAIN_CLASS_NOT_FOUND);
        }
    }

    public static boolean isMainClassNotFound(RunTimeExecutionResult result) {
        if (result == null || result.getExitCode() == 0) {
            return false;
        }
        String output = result.getFullOutput();
        if (output == null || output.isBlank()) {
            return false;
        }
        return output.contains("Could not find or load main class")
                || output.contains("ClassNotFoundException");
    }

    private void extractExceptionsFrom(String text, RunTimeExecutionResult result) {
        if (text == null || text.isEmpty()) return;
        for (String line : text.split("\\R")) {
                                                         
            if (isStackFrameLine(line)) continue;

            Matcher matcher = EX_PATTERN.matcher(line);
            while (matcher.find()) {
                String exName = matcher.group(1);
                                               
                if (matcher.end() < line.length() && line.charAt(matcher.end()) == '.') continue;

                if (exName.endsWith("Error")) {
                    result.getErrors().merge(exName, 1L, Long::sum);
                } else {
                    result.getExceptions().merge(exName, 1L, Long::sum);
                }
            }
        }
    }

    private boolean isStackFrameLine(String line) {
        String trimmed = line == null ? "" : line.trim();
        return trimmed.startsWith("at ") || trimmed.startsWith("...");
    }

    private void extractCrashFrom(RunTimeExecutionResult result) {
        String stdout = result.getStdout() == null ? "" : result.getStdout();
        String stderr = result.getStderr() == null ? "" : result.getStderr();
        String combined = stdout + "\n" + stderr;

                                   
        boolean isOom = combined.contains("OutOfMemoryError");

        boolean hasCrashSignal =
                combined.contains("Segmentation fault") ||
                combined.contains("A fatal error has been detected") ||
                result.getExitCode() == 134 ||                   
                result.getExitCode() == 139;                     

                                                            
        boolean hasCoreDump = combined.contains("core dump") && !isOom;

        if (hasCrashSignal || hasCoreDump) {
            result.getCrashes().merge("RUNTIME_CRASH", 1L, Long::sum);
        }
    }
}

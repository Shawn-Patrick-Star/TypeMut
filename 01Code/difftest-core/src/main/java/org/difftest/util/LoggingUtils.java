package org.difftest.util;

import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.FileAppender;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.difftest.config.DTConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LoggingUtils {
    private LoggingUtils() {
    }

    public static void configureForCli() {
        String logLevel = System.getProperty("log.level");
        if (logLevel == null || logLevel.isBlank()) {
            logLevel = DTConfig.LOG_LEVEL;
            System.setProperty("log.level", logLevel);
        }

        LogManager.resetConfiguration();
        Logger rootLogger = Logger.getRootLogger();
        rootLogger.setLevel(Level.toLevel(logLevel, Level.INFO));

        PatternLayout layout = new PatternLayout("[%-5p] [%d{yyyy-MM-dd HH:mm:ss}] %m%n");
        ConsoleAppender consoleAppender = new ConsoleAppender(layout, ConsoleAppender.SYSTEM_ERR);
        consoleAppender.setName("difftest-console");
        consoleAppender.activateOptions();
        rootLogger.addAppender(consoleAppender);

        String logFile = System.getProperty("difftest.log_file");
        if (logFile == null) {
            logFile = DTConfig.LOG_FILE;
        }
        if (logFile == null || logFile.isBlank()) {
            return;
        }

        FileAppender fileAppender = createFileAppender(Path.of(logFile), "difftest-file", false);
        if (fileAppender != null) {
            rootLogger.addAppender(fileAppender);
        }
    }

    public static FileAppender createFileAppender(Path logFile, String name, boolean append) {
        try {
            Path logPath = logFile.toAbsolutePath().normalize();
            Path parent = logPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            PatternLayout layout = new PatternLayout("[%-5p] [%d{yyyy-MM-dd HH:mm:ss}] %m%n");
            FileAppender appender = new FileAppender(layout, logPath.toString(), append);
            appender.setName(name);
            appender.setEncoding("UTF-8");
            appender.activateOptions();
            return appender;
        } catch (IOException e) {
            System.err.println("Failed to initialize difftest log file: " + e.getMessage());
            return null;
        }
    }
}

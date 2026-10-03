package org.seed.util;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.FileAppender;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.seed.config.SeedFilterConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;

public final class LoggingUtils {
    private LoggingUtils() {
    }

    public static void configureForCli(SeedFilterConfig config) {
        String logLevel = System.getProperty("log.level");
        if (logLevel == null || logLevel.isBlank()) {
            logLevel = config.logLevel();
            System.setProperty("log.level", logLevel);
        }

        LogManager.resetConfiguration();
        Logger rootLogger = Logger.getRootLogger();
        rootLogger.setLevel(Level.toLevel(logLevel, Level.INFO));

        PatternLayout layout = new PatternLayout("[%-5p] [%d{yyyy-MM-dd HH:mm:ss}] %m%n");
        ConsoleAppender consoleAppender = new ConsoleAppender(layout, ConsoleAppender.SYSTEM_ERR);
        consoleAppender.setName("seed-filter-console");
        consoleAppender.setThreshold(Level.ERROR);
        consoleAppender.activateOptions();
        rootLogger.addAppender(consoleAppender);

        FileAppender fileAppender = createFileAppender(resolveLogFile(config), "seed-filter-file", false);
        if (fileAppender != null) {
            rootLogger.addAppender(fileAppender);
        }
    }

    public static void quietConsoleLogging(Logger rootLogger) {
        Enumeration<?> appenders = rootLogger.getAllAppenders();
        while (appenders.hasMoreElements()) {
            Object appender = appenders.nextElement();
            if (appender instanceof ConsoleAppender && appender instanceof AppenderSkeleton skeleton) {
                skeleton.setThreshold(Level.ERROR);
            }
        }
    }

    public static FileAppender createFileAppender(Path logFile, String name, boolean append) {
        if (logFile == null) {
            return null;
        }
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
            System.err.println("Failed to initialize seed-filter log file: " + e.getMessage());
            return null;
        }
    }

    private static Path resolveLogFile(SeedFilterConfig config) {
        String configured = System.getProperty("seedfilter.log_file");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return config.logFile();
    }
}

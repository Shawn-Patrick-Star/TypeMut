package org.fuzz.util;

import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.RollingFileAppender;
import org.fuzz.config.FuzzConfigLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class LoggingUtils {
    private static final String RUN_SUMMARY_LOGGER_NAME = "org.fuzz.run-summary";
    private static final String BUG_LOGGER_NAME = "org.fuzz.bugs";
    private static final String BUG_LOG_FILE = "app-bugs";

    private LoggingUtils() {
    }

    public static void configure(String defaultLogFile, String defaultLevel) {
        FuzzConfigLoader loader = FuzzConfigLoader.getInstance();
        boolean enabled = loader.getBoolean("log.enabled", true);
        configure(defaultLogFile, defaultLevel, enabled, loader);
    }

    static void configure(String defaultLogFile, String defaultLevel, boolean enabled) {
        configure(defaultLogFile, defaultLevel, enabled, FuzzConfigLoader.getInstance());
    }

    private static void configure(String defaultLogFile, String defaultLevel, boolean enabled,
            FuzzConfigLoader loader) {
        System.setProperty("fuzz.logging.disabled", Boolean.toString(!enabled));

        if (System.getProperty("log.dir") == null) {
            System.setProperty("log.dir", loader.getString("log.dir", "logs"));
        }
        if (System.getProperty("log.file") == null) {
            System.setProperty("log.file", defaultLogFile);
        }
        if (System.getProperty("log.level") == null) {
            System.setProperty("log.level", loader.getString("log.level", defaultLevel));
        }

        String logDir = System.getProperty("log.dir");
        LogGrouper.cleanupRollingBackups(Paths.get(logDir, defaultLogFile + ".log").toString());
        LogGrouper.cleanupRollingBackups(Paths.get(logDir, BUG_LOG_FILE + ".log").toString());

        LogManager.resetConfiguration();
        Logger rootLogger = Logger.getRootLogger();
        clearLogger(RUN_SUMMARY_LOGGER_NAME, true);
        configureDedicatedFileLogger(BUG_LOGGER_NAME, BUG_LOG_FILE);
        if (!enabled) {
            configureDedicatedFileLogger(RUN_SUMMARY_LOGGER_NAME, defaultLogFile);
            rootLogger.setLevel(Level.OFF);
            return;
        }

        rootLogger.setLevel(Level.toLevel(System.getProperty("log.level"), Level.INFO));
        rootLogger.addAppender(createConsoleAppender());
        RollingFileAppender fileAppender = createFileAppender();
        if (fileAppender != null) {
            rootLogger.addAppender(fileAppender);
        }
    }

    public static void logRunSummary(String message) {
        Logger.getLogger(RUN_SUMMARY_LOGGER_NAME).info(message);
    }

    public static void logBug(String message) {
        Logger.getLogger(BUG_LOGGER_NAME).error(message);
    }

    private static void configureDedicatedFileLogger(String loggerName, String fileName) {
        Logger logger = Logger.getLogger(loggerName);
        logger.removeAllAppenders();
        logger.setAdditivity(false);
        logger.setLevel(Level.INFO);
        RollingFileAppender appender = createFileAppender(fileName);
        if (appender != null) {
            logger.addAppender(appender);
        }
    }

    private static void clearLogger(String loggerName, boolean additive) {
        Logger logger = Logger.getLogger(loggerName);
        logger.removeAllAppenders();
        logger.setAdditivity(additive);
        logger.setLevel(null);
    }

    private static ConsoleAppender createConsoleAppender() {
        ColorPrint layout = new ColorPrint();
        layout.setConversionPattern("[%-5p] %X{fuzzCtx} - %m%n");
        ConsoleAppender appender = new ConsoleAppender(layout, ConsoleAppender.SYSTEM_OUT);
        appender.setName("console");
        appender.activateOptions();
        return appender;
    }

    private static RollingFileAppender createFileAppender() {
        return createFileAppender(System.getProperty("log.file"));
    }

    private static RollingFileAppender createFileAppender(String fileName) {
        try {
            Path logPath = Paths.get(System.getProperty("log.dir"), fileName + ".log")
                    .toAbsolutePath()
                    .normalize();
            Path parent = logPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            PatternLayout layout = new PatternLayout("[%-5p] [%d{yyyy-MM-dd HH:mm:ss}] %X{fuzzCtx} - %m%n");
            RollingFileAppender appender = new RollingFileAppender(layout, logPath.toString(), false);
            appender.setName("file");
            appender.setEncoding("UTF-8");
            appender.setMaxFileSize("128MB");
            appender.setMaxBackupIndex(5);
            appender.activateOptions();
            return appender;
        } catch (IOException e) {
            System.err.println("Failed to initialize fuzz log file: " + e.getMessage());
            return null;
        }
    }
}

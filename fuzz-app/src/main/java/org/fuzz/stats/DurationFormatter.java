package org.fuzz.stats;

import java.time.Duration;

final class DurationFormatter {
    private DurationFormatter() {
    }

    static String toDaysHoursMinutesSeconds(Duration duration) {
        long totalSeconds = Math.max(0L, duration.getSeconds());
        long days = totalSeconds / 86_400L;
        long hours = (totalSeconds % 86_400L) / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%dd %02dh %02dm %02ds", days, hours, minutes, seconds);
    }
}

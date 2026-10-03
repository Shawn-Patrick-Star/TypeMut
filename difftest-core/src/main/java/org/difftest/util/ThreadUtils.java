package org.difftest.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadUtils {

    public static ThreadFactory namedThreadFactory(String prefix) {
        return namedThreadFactory(prefix, false);
    }

    public static ThreadFactory namedThreadFactory(String prefix, boolean daemon) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName(prefix + "-" + counter.getAndIncrement());
            thread.setDaemon(daemon);
            return thread;
        };
    }
}

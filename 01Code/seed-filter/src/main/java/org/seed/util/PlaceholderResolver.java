package org.seed.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PlaceholderResolver {
    private static final Pattern PATTERN = Pattern.compile("\\$\\{([^}]+)}");
    private static final int MAX_DEPTH = 5;

    private PlaceholderResolver() {
    }

    public static void resolveAll(Properties properties) {
        boolean changed;
        int depth = 0;
        do {
            changed = false;
            List<String> keys = new ArrayList<>(properties.stringPropertyNames());
            for (String key : keys) {
                String value = properties.getProperty(key);
                String newValue = resolve(value, properties);
                if (value != null && !value.equals(newValue)) {
                    properties.setProperty(key, newValue);
                    changed = true;
                }

                if (key.contains("${")) {
                    String newKey = resolve(key, properties);
                    if (!key.equals(newKey)) {
                        properties.remove(key);
                        properties.setProperty(newKey, newValue != null ? newValue : "");
                        changed = true;
                    }
                }
            }
        } while (changed && ++depth < MAX_DEPTH);
    }

    private static String resolve(String input, Properties properties) {
        if (input == null || !input.contains("${")) {
            return input;
        }

        Matcher matcher = PATTERN.matcher(input);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String placeholder = matcher.group(1);
            String replacement = lookup(placeholder, properties);
            if (replacement == null) {
                matcher.appendReplacement(resolved, Matcher.quoteReplacement("${" + placeholder + "}"));
            } else {
                matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
            }
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private static String lookup(String placeholder, Properties properties) {
        String replacement = System.getProperty(placeholder);
        if (replacement != null) {
            return replacement;
        }

        replacement = properties.getProperty(placeholder);
        if (replacement != null) {
            return replacement;
        }

        String suffix = "." + placeholder;
        String matchedValue = null;
        for (String key : properties.stringPropertyNames()) {
            if (!key.endsWith(suffix)) {
                continue;
            }
            String value = properties.getProperty(key);
            if (matchedValue != null && !matchedValue.equals(value)) {
                return null;
            }
            matchedValue = value;
        }
        return matchedValue;
    }
}

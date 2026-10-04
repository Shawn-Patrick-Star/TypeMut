package org.seed.service;

import java.io.PrintStream;
import java.util.Objects;

final class ConsoleFrameRenderer {
    private static final String CLEAR_LINE = "\033[2K";

    private final PrintStream out;
    private int renderedLineCount = 0;

    ConsoleFrameRenderer(PrintStream out) {
        this.out = Objects.requireNonNull(out);
    }

    synchronized void printStatic(String frame) {
        out.print(frame);
        if (!frame.endsWith(System.lineSeparator()) && !frame.endsWith("\n")) {
            out.println();
        }
        out.flush();
    }

    synchronized void render(String frame) {
        String[] lines = frame.split("\\R", -1);
        int previousLineCount = renderedLineCount;

        moveCursorToFrameStart(previousLineCount);
        for (String line : lines) {
            out.print('\r');
            out.print(CLEAR_LINE);
            out.print(line);
            out.println();
        }
        for (int i = lines.length; i < previousLineCount; i++) {
            out.print('\r');
            out.print(CLEAR_LINE);
            out.println();
        }
        out.flush();
        renderedLineCount = Math.max(lines.length, previousLineCount);
    }

    private void moveCursorToFrameStart(int lineCount) {
        if (lineCount == 0) {
            return;
        }
        out.print('\r');
        out.print("\033[" + lineCount + "A");
    }
}

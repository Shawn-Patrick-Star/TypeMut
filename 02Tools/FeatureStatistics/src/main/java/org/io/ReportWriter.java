package org.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class ReportWriter {
    private final Gson gson;

    public ReportWriter() {
        this.gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    }

    public void write(Object report, File outputFile) {
        try (FileWriter writer = new FileWriter(outputFile)) {
            gson.toJson(report, writer);
            System.out.println("Generated: " + outputFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("Error writing JSON to: " + outputFile.getAbsolutePath());
            e.printStackTrace();
        }
    }
}
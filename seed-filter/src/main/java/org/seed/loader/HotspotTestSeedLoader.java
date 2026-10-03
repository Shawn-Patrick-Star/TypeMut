package org.seed.loader;

import lombok.extern.slf4j.Slf4j;
import org.seed.model.Seed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Slf4j
final class HotspotTestSeedLoader implements SeedLoader {

    private static final String TESTCASES_FILE = "testcases.txt";
    private static final String SRC_DIR = "src";

    @Override
    public String name() {
        return "hotspot-tests";
    }

    @Override
    public boolean supports(Path rootPath) {
        return Files.isRegularFile(rootPath.resolve(TESTCASES_FILE))
                && Files.isDirectory(rootPath.resolve(SRC_DIR));
    }

    @Override
    public List<Seed> load(Path rootPath) throws IOException {
        Path srcRoot = rootPath.resolve(SRC_DIR);
        Map<String, JavaSeedScanner.JavaSourceInfo> sourceIndex = buildSourceIndex(srcRoot);
        Set<String> testcases = readTestcases(rootPath.resolve(TESTCASES_FILE));

        List<Seed> seeds = new ArrayList<>();
        int missing = 0;
        for (String testcase : testcases) {
            String outerClassName = outerSimpleClassName(testcase);
            JavaSeedScanner.JavaSourceInfo sourceInfo = sourceIndex.get(outerClassName);
            if (sourceInfo == null) {
                missing++;
                continue;
            }

            String mainClassName = mainClassName(sourceInfo.packageName(), testcase);
            seeds.add(new Seed(
                    sourceInfo.sourceDir(),
                    sourceInfo.fileName(),
                    mainClassName,
                    sanitizeId(testcase),
                    0,
                    JavaSeedScanner.findReferencedSiblingJavaFiles(sourceInfo.sourceDir(), sourceInfo.fileName())));
        }

        if (missing > 0) {
            log.warn("[SeedLoader] {} HotSpot testcases did not have matching .java files", missing);
        }
        log.warn("[SeedLoader] HotSpot/JTReg @run arguments and @library classpaths are not modeled yet; "
                + "tests requiring extra VM args, program args, or libraries may be discarded by the pipeline.");
        return JavaSeedScanner.excludeOtherMainFilesFromCompanions(seeds);
    }

    private Map<String, JavaSeedScanner.JavaSourceInfo> buildSourceIndex(Path srcRoot) throws IOException {
        Map<String, JavaSeedScanner.JavaSourceInfo> index = new HashMap<>();
        try (Stream<Path> files = Files.walk(srcRoot)) {
            for (Path file : files.filter(JavaSeedScanner::isJavaFile).toList()) {
                JavaSeedScanner.inspect(file).ifPresent(info -> {
                    for (String className : info.declaredClassNames()) {
                        index.putIfAbsent(className, info);
                    }
                });
            }
        }
        return index;
    }

    private Set<String> readTestcases(Path testcasesFile) throws IOException {
        Set<String> testcases = new LinkedHashSet<>();
        for (String line : Files.readAllLines(testcasesFile)) {
            String testcase = line.strip();
            if (!testcase.isEmpty() && !testcase.startsWith("#")) {
                testcases.add(testcase);
            }
        }
        return testcases;
    }

    private String outerSimpleClassName(String testcase) {
        String className = testcase;
        int packageSeparator = className.lastIndexOf('.');
        if (packageSeparator >= 0) {
            className = className.substring(packageSeparator + 1);
        }
        int innerSeparator = className.indexOf('$');
        return innerSeparator >= 0 ? className.substring(0, innerSeparator) : className;
    }

    private String mainClassName(String packageName, String testcase) {
        if (testcase.indexOf('.') >= 0) {
            return testcase;
        }
        int innerSeparator = testcase.indexOf('$');
        if (innerSeparator >= 0) {
            String outerClassName = testcase.substring(0, innerSeparator);
            return JavaSeedScanner.qualify(packageName, outerClassName) + testcase.substring(innerSeparator);
        }
        return JavaSeedScanner.qualify(packageName, testcase);
    }

    private String sanitizeId(String testcase) {
        return testcase.replaceAll("[^A-Za-z0-9_$.-]", "_");
    }
}


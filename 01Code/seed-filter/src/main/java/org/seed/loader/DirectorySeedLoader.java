package org.seed.loader;

import lombok.extern.slf4j.Slf4j;
import org.seed.model.Seed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

                         
                   
                    
                   
                    
     
@Slf4j
final class DirectorySeedLoader implements SeedLoader {

    @Override
    public String name() {
        return "directory";
    }

    @Override
    public boolean supports(Path rootPath) {
        return Files.isDirectory(rootPath);
    }

    @Override
    public List<Seed> load(Path rootPath) throws IOException {
        List<Seed> seeds = new ArrayList<>();
        try (Stream<Path> subDirs = Files.list(rootPath)) {
            for (Path dir : subDirs.filter(Files::isDirectory).toList()) {
                try {
                    seeds.addAll(JavaSeedScanner.findMainSeedsInDirectory(dir));
                } catch (IOException e) {
                    log.warn("[SeedLoader] Failed to parse seed directory {}: {}", dir, e.getMessage());
                }
            }
        }
        return seeds;
    }
}


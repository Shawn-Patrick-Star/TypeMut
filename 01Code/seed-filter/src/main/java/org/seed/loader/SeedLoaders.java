package org.seed.loader;

import java.nio.file.Path;
import java.util.List;

public final class SeedLoaders {

    private static final List<SeedLoader> LOADERS = List.of(
            new HotspotTestSeedLoader(),
            new DirectorySeedLoader());

    private SeedLoaders() {
    }

    public static SeedLoader choose(Path rootPath) {
        return LOADERS.stream()
                .filter(loader -> loader.supports(rootPath))
                .findFirst()
                .orElse(new DirectorySeedLoader());
    }
}


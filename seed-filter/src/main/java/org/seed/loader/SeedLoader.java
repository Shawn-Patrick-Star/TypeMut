package org.seed.loader;

import org.seed.model.Seed;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface SeedLoader {

    String name();

    boolean supports(Path rootPath);

    List<Seed> load(Path rootPath) throws IOException;
}


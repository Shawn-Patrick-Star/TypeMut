package org.fuzz.core;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.fuzz.util.NameGenerator;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.factory.Factory;
import java.util.Random;

@Getter
@Slf4j
public class MutationContext {
    private final Factory factory;
    private final CtModel model;
    private final Random random;
    private final NameGenerator nameGenerator;

    public MutationContext(Launcher launcher) {
        this.factory = launcher.getFactory();

        try {
            this.model = launcher.buildModel();
        } catch (Exception e) {
            log.error("[Spoon Load FAILURE] Parse failed, skipping: {}", e.getMessage());
            throw e;
        }

        this.random = new Random();
        this.nameGenerator = new NameGenerator(this.random);
    }

                                        
    public MutationContext(Launcher launcher, long seed) {
        this.factory = launcher.getFactory();

        try {
            this.model = launcher.buildModel();
        } catch (Exception e) {
            log.error("[Spoon Load FAILURE] Parse failed, skipping: {}", e.getMessage());
            throw e;
        }
        this.random = new Random(seed);
        this.nameGenerator = new NameGenerator(this.random);
    }

}
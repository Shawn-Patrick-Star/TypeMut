package org.fuzz.seed;

import lombok.extern.slf4j.Slf4j;
import org.fuzz.config.FuzzConfig;
import org.seed.config.SeedFilterConfig;
import org.seed.loader.SeedLoader;
import org.seed.loader.SeedLoaders;
import org.seed.model.Seed;
import org.seed.model.SeedFilterResult;
import org.seed.service.SeedFilterService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

   
                                                                                   
   
@Slf4j
public class SeedPool {

    public record SeedClaim(Seed seed, int mutationCount, boolean exhausted) {
    }

    private final Map<Integer, List<Seed>> generationalBuckets = new HashMap<>();
    private final Random random = FuzzConfig.EXPERIMENTAL_MODE ? new Random(FuzzConfig.FIXED_RANDOM_SEED) : new Random();
    private final ReentrantLock lock = new ReentrantLock();
    private int totalSize = 0;

    public int getPoolSize() {
        return size();
    }

    public Map<Integer, Integer> getGenerationalBreakdown() {
        lock.lock();
        try {
            Map<Integer, Integer> breakdown = new TreeMap<>();
            generationalBuckets.forEach((gen, bucket) -> {
                if (!bucket.isEmpty()) {
                    breakdown.put(gen, bucket.size());
                }
            });
            return breakdown;
        } finally {
            lock.unlock();
        }
    }

    public boolean initializeFromDirectory(Path rootPath) {
        List<Seed> seeds;
        if (FuzzConfig.SEED_FILTER_ENABLED) {
            SeedFilterConfig config = SeedFilterConfig.defaults();
            SeedFilterResult result = new SeedFilterService(true).filter(
                    rootPath,
                    config.outputDir(),
                    config);
            seeds = result.acceptedSeeds();
            log.info("[SeedFilter] Seed filter accepted {} seeds and rejected {} seeds.",
                    result.acceptedCount(), result.rejectedCount());
        } else {
            SeedLoader loader = SeedLoaders.choose(rootPath);
            log.info("[SeedPool] Loading seeds from {} using {} loader", rootPath, loader.name());
            try {
                seeds = loader.load(rootPath);
            } catch (IOException e) {
                log.error("[SeedPool] Error loading seeds from {}", rootPath, e);
                return false;
            }
        }

        long total = seeds.size();
        long done = 0L;
        for (Seed seed : seeds) {
            add(seed);
            done++;
            if (FuzzConfig.LOG_ENABLED) renderProgress("[SeedPool] Loading", done, total);
        }
        if (FuzzConfig.LOG_ENABLED && total > 0) {
            renderProgress("[SeedPool] Loading", total, total);
            System.out.println();
        }

        if (isEmpty()) {
            log.error("[SeedPool] No valid seeds found in {}", rootPath);
            return false;
        }
        log.info("[SeedPool] Loaded {} seeds", size());
        return true;
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public int size() {
        lock.lock();
        try {
            return totalSize;
        } finally {
            lock.unlock();
        }
    }

    public void add(Seed seed) {
        lock.lock();
        try {
            generationalBuckets.computeIfAbsent(seed.getGeneration(), k -> new ArrayList<>()).add(seed);
            totalSize++;
        } finally {
            lock.unlock();
        }
    }

    public List<Seed> snapshot() {
        lock.lock();
        try {
            List<Seed> seeds = new ArrayList<>();
            generationalBuckets.values().forEach(seeds::addAll);
            return seeds;
        } finally {
            lock.unlock();
        }
    }

    public int removeIf(Predicate<Seed> predicate) {
        lock.lock();
        try {
            int removed = 0;
            List<Integer> emptyGenerations = new ArrayList<>();
            for (Map.Entry<Integer, List<Seed>> entry : generationalBuckets.entrySet()) {
                List<Seed> bucket = entry.getValue();
                int before = bucket.size();
                bucket.removeIf(predicate);
                int delta = before - bucket.size();
                removed += delta;
                totalSize -= delta;
                if (bucket.isEmpty()) {
                    emptyGenerations.add(entry.getKey());
                }
            }
            emptyGenerations.forEach(generationalBuckets::remove);
            return removed;
        } finally {
            lock.unlock();
        }
    }

       
                                 
       
       
                           
            
                       
                                
                                                                  
                                                                         
       
    public SeedClaim claimNextSeedForMutation(int maxMutationsPerSeed) {
        lock.lock();
        try {
            if (totalSize == 0) {
                return null;
            }

                                     
            List<Integer> activeGens = new ArrayList<>();
            generationalBuckets.forEach((gen, bucket) -> {
                if (!bucket.isEmpty() && gen < FuzzConfig.MAX_MUTATION_DEPTH) {
                    activeGens.add(gen);
                }
            });

            if (activeGens.isEmpty()) {
                return null;
            }

                                
                                         
            int totalWeight = 0;
            List<Integer> weights = new ArrayList<>();
            for (int gen : activeGens) {
                int weight = (gen == 0) ? Math.max(2, activeGens.size() / 2 + 1) : 1;
                weights.add(weight);
                totalWeight += weight;
            }

                           
            int selectedGen = activeGens.get(0);
            int randomWeight = random.nextInt(totalWeight);
            int currentSum = 0;
            for (int i = 0; i < activeGens.size(); i++) {
                currentSum += weights.get(i);
                if (randomWeight < currentSum) {
                    selectedGen = activeGens.get(i);
                    break;
                }
            }

            List<Seed> bucket = generationalBuckets.get(selectedGen);
            
                              
            int index = random.nextInt(bucket.size());
            Seed seed = bucket.get(index);

                        
            int mutationCount = seed.incrementMutationCount();
            boolean exhausted = maxMutationsPerSeed > 0 && mutationCount >= maxMutationsPerSeed;

            if (exhausted) {
                                     
                removeAt(bucket, index);
                totalSize--;
                                                
                if (bucket.isEmpty()) {
                    generationalBuckets.remove(selectedGen);
                }
            }

            return new SeedClaim(seed, mutationCount, exhausted);
        } finally {
            lock.unlock();
        }
    }

    public boolean remove(Seed seed) {
        lock.lock();
        try {
            List<Seed> bucket = generationalBuckets.get(seed.getGeneration());
            if (bucket == null) {
                return false;
            }
            int index = bucket.indexOf(seed);
            if (index < 0) {
                return false;
            }
            removeAt(bucket, index);
            totalSize--;
            return true;
        } finally {
            lock.unlock();
        }
    }

    private void removeAt(List<Seed> bucket, int index) {
        int lastIndex = bucket.size() - 1;
        if (index != lastIndex) {
            bucket.set(index, bucket.get(lastIndex));
        }
        bucket.remove(lastIndex);
    }

    private void renderProgress(String label, long done, long total) {
        if (total <= 0) {
            return;
        }
        int percent = (int) Math.min(100L, (done * 100L) / total);
        String line = String.format("\r%s %3d%% (%d/%d)          ", label, percent, done, total);
        System.out.print(line);
    }
}

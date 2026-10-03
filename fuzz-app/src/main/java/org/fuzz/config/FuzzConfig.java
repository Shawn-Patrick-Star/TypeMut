package org.fuzz.config;

import java.util.Collections;
import java.util.Map;
import java.nio.file.Paths;
import org.fuzz.operator.OperatorRegistry;

import lombok.extern.slf4j.Slf4j;
import org.difftest.config.DTConfig;

   
                   
                                      
   
@Slf4j
public class FuzzConfig {

    private static final FuzzConfigLoader loader = FuzzConfigLoader.getInstance();

                                               
    public static final String SEED_DIR = loader.getString("project.seed_dir", "./seeds");
    public static final String OUTPUT_DIR = loader.getString("project.output_dir", "./output");

                                                     
    public static final boolean EXPERIMENTAL_MODE = loader.getBoolean("FuzzingEngine.experimental_mode", false);
    public static final long FIXED_RANDOM_SEED = Long.parseLong(loader.getString("FuzzingEngine.fixed_random_seed", "12345"));

    public static final int MAX_FUZZING_ROUNDS = loader.getInt("FuzzingEngine.max_fuzzing_rounds", 1000);
    public static final int MAX_FUZZING_TIME_MINUTES = loader.getInt("FuzzingEngine.max_fuzzing_time_minutes", -1);
    public static final String TERMINATION_MODE = loader.getString("FuzzingEngine.termination_mode", "TIME");
    public static final int MAX_INTERVAL_TRIALS = loader.getInt("AbstractBlockOperator.max_interval_trials", 50);
    public static final int MAX_AST_DEPTH = loader.getInt("AbstractBlockOperator.max_ast_depth", 3);
    public static final int COMPUTATION_CHAIN_MIN_LENGTH = normalizeMinChainLength(
            loader.getInt("ComputationChainOP.min_chain_length", 3));
    public static final int COMPUTATION_CHAIN_MAX_LENGTH = normalizeMaxChainLength(
            COMPUTATION_CHAIN_MIN_LENGTH,
            loader.getInt("ComputationChainOP.max_chain_length", 6));
    public static final int MAX_MUTATIONS_PER_SEED = loader.getInt("FuzzingEngine.max_mutations_per_seed", 5);
    public static final int MAX_MUTATION_DEPTH = loader.getInt("FuzzingEngine.max_mutation_depth", 10);
    public static final boolean MUTATION_ENABLED = loader.getBoolean("FuzzingEngine.mutation_enabled", true);
    public static final boolean RANDOM_OPERATOR_WEIGHTS = loader.getBoolean("FuzzingEngine.random_operator_weights", false);
    public static final String LOG_LEVEL = loader.getString("log.level", "INFO");
    public static final String LOG_DIR = loader.getString("log.dir", "logs");
    public static final boolean LOG_ENABLED = loader.getBoolean("log.enabled", true);

    public static final boolean SEED_FILTER_ENABLED =
            loader.getBoolean("FuzzingEngine.seed_filter_enabled", true);

                                               
    public static boolean REPRODUCE_MODE = false;
    public static final String REPRODUCER_MODE = loader.getString("BugReproducer.mode", "ALL");
    public static final String REPRODUCER_BUG_PATH = loader.getString("BugReproducer.bugPath", "fuzz_output/bugs");
    public static final boolean REPRODUCER_MOVE_BUG = loader.getBoolean("BugReproducer.moveBug", false);

                                                        
        public static final int INITIAL_WORKERS = DTConfig.INITIAL_WORKERS;
        public static final int WORKER_UPPER_BOUND = DTConfig.WORKER_UPPER_BOUND;
    public static final Map<String, Double> OPERATOR_WEIGHTS;

    static {
        OPERATOR_WEIGHTS = Collections.unmodifiableMap(loader.loadOperatorWeights());
    }

    public static void printConfigSummary(OperatorRegistry registry) {
        log.info(buildConfigSummary(registry));
    }

    public static String buildConfigSummary(OperatorRegistry registry) {
        StringBuilder summary = new StringBuilder();

        summary.append("\n==================================================================\n");
        summary.append("                 Fuzzer Configuration Summary                     \n");
        summary.append("==================================================================\n");
        summary.append(String.format(" [Config]      Fuzz Config      : %s\n",
                System.getProperty(FuzzCliOptions.FUZZ_CONFIG_PROPERTY, "fuzz.yaml")));
        summary.append(String.format(" [Config]      Filter Config    : %s\n",
                System.getProperty(FuzzCliOptions.FILTER_CONFIG_PROPERTY, "seed-filter.yaml")));
        summary.append(String.format(" [Config]      Difftest Config  : %s\n",
                System.getProperty(FuzzCliOptions.DIFFTEST_CONFIG_PROPERTY, "difftest.yaml")));

        summary.append(String.format(" [Environment] OS               : %s\n", System.getProperty("os.name")));
        summary.append(String.format(" [Environment] Seed Dir         : %s\n", SEED_DIR));
        summary.append(String.format(" [Environment] Output Dir       : %s\n", OUTPUT_DIR));
        summary.append(String.format(" [Log]         Level            : %s\n", LOG_LEVEL));
        summary.append(String.format(" [Log]         Dir              : %s\n", LOG_DIR));
        summary.append(String.format(" [Log]         Enabled          : %s\n", LOG_ENABLED));


                        
        summary.append(String.format(" [DT]          Compiler Diff    : %s\n", DTConfig.USE_COMPILER_DT));
        summary.append(String.format(" [DT]          JVM Diff         : %s\n", DTConfig.USE_JVM_DT));
        
        summary.append(String.format(" [Strategy]    Experimental Mode: %s\n", EXPERIMENTAL_MODE));
        if (EXPERIMENTAL_MODE) {
            summary.append(String.format(" [Strategy]    Fixed Random Seed: %d\n", FIXED_RANDOM_SEED));
        }
        summary.append(String.format(" [Strategy]    Termination Mode : %s (%d %s)\n",
                TERMINATION_MODE,
                "TIME".equalsIgnoreCase(TERMINATION_MODE) ? MAX_FUZZING_TIME_MINUTES : MAX_FUZZING_ROUNDS,
                "TIME".equalsIgnoreCase(TERMINATION_MODE) ? "minutes" : "rounds"));

        summary.append(String.format(" [Strategy]    Timeout          : %d seconds\n", DTConfig.TIMEOUT_SECONDS));
        summary.append(String.format(" [Strategy]    Mutation Enabled : %s\n", MUTATION_ENABLED));
        summary.append(String.format(" [Strategy]    Max Mut per Seed : %d\n", MAX_MUTATIONS_PER_SEED));
        summary.append(String.format(" [Strategy]    Max Mut Depth    : %d\n", MAX_MUTATION_DEPTH));
        summary.append(String.format(" [Strategy]    Chain Length     : %d-%d\n",
                COMPUTATION_CHAIN_MIN_LENGTH, COMPUTATION_CHAIN_MAX_LENGTH));
                                                                                                    

                                                                                                        
                                                                                                        


        summary.append(String.format(" [Resources]   Physical Cores   : %d\n", DTConfig.PHYSICAL_CORES));
        summary.append(String.format(" [Resources]   Workers          : initial=%d, max=%d\n",
                INITIAL_WORKERS, WORKER_UPPER_BOUND));
		
        summary.append(String.format(" [Targets]     Active Compilers : %s\n",
                DTConfig.COMPILERS.stream().map(c -> c.getId()).reduce((a, b) -> a + ", " + b).orElse("None")));
        summary.append(String.format(" [Targets]     Active JVMs      : %s\n",
                DTConfig.JVMS.stream().map(j -> j.getId()).reduce((a, b) -> a + ", " + b).orElse("None")));
        summary.append("========================= Operator Weights ========================\n");
        if (!MUTATION_ENABLED) {
            summary.append(" [MutOP]   Mutation disabled; seeds are differential-tested as-is.\n");
        } else {
            Map<String, Double> effectiveWeights = RANDOM_OPERATOR_WEIGHTS
                    ? registry.getEffectiveWeights()
                    : OPERATOR_WEIGHTS;
            for (Map.Entry<String, Double> entry : effectiveWeights.entrySet()) {
                summary.append(String.format(" [MutOP]   %-21s: %.2f\n", entry.getKey().replace("Operator", ""),
                        entry.getValue()));
            }
        }

        summary.append("==================================================================");
        return summary.toString();
    }

    public static String logPath(String fileName) {
        return Paths.get(LOG_DIR, fileName).toString();
    }

    static int normalizeMinChainLength(int configuredValue) {
        return Math.max(1, configuredValue);
    }

    static int normalizeMaxChainLength(int minimum, int configuredValue) {
        return Math.max(minimum, configuredValue);
    }
}

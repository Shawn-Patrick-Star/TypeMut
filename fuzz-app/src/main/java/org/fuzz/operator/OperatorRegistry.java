package org.fuzz.operator;

import lombok.extern.slf4j.Slf4j;
import org.fuzz.config.FuzzConfig;
import org.fuzz.core.MutationContext;
import org.fuzz.operator.impl.ContextWeavingOperator;
import org.fuzz.operator.impl.PolymorphismOperator;
import org.fuzz.operator.impl.VectorizationTriggerOperator;
import org.fuzz.operator.impl.block.IfInjectionOperator;
import org.fuzz.operator.impl.block.LoopInjectionOperator;
import org.fuzz.operator.impl.block.SwitchInjectionOperator;
import org.fuzz.operator.impl.block.TryCatchInjectionOperator;
import org.fuzz.operator.impl.computation.ComputationChainOP;
import org.fuzz.operator.impl.method.MethodToMethodHandleOperator;
import org.fuzz.operator.impl.method.MethodToReflectionOperator;
import org.fuzz.operator.impl.typeCasting.*;

import java.util.*;

@Slf4j
public class OperatorRegistry {
    private final List<MutOP> operators = new ArrayList<>();
    private final Map<String, MutOP> operatorMap = new HashMap<>();

    public OperatorRegistry() {
        init();
    }

       
                                
       
    private void init() {
        register(new PrimitiveConversionOperator());                              
        register(new BoxedConversionOperator());                      
        register(new ReferenceConversionOperator());                                       

        register(new MethodToReflectionOperator());                               
        register(new MethodToMethodHandleOperator());

        register(new VectorizationTriggerOperator());
        register(new PolymorphismOperator());

               
        register(new ComputationChainOP());
        register(new LoopInjectionOperator());
        register(new IfInjectionOperator());
        register(new SwitchInjectionOperator());
        register(new TryCatchInjectionOperator());
        register(new ContextWeavingOperator());

                   
        loadWeights();
    }

    private void loadWeights() {
        Map<String, Double> configWeights = FuzzConfig.OPERATOR_WEIGHTS;

        if (FuzzConfig.RANDOM_OPERATOR_WEIGHTS) {
                                          
            Random rng = new Random();
            for (MutOP op : operators) {
                if (op instanceof AbstractMutationOperator<?> absOp) {
                    absOp.setWeight(0.01 + rng.nextDouble() * 1.99);
                }
            }
            log.info("[OperatorRegistry] Random weight mode enabled.");
        } else {
                             
            for (MutOP op : operators) {
                if (op instanceof AbstractMutationOperator<?> absOp) {
                    double weight = configWeights.getOrDefault(op.getName(), 1.0);
                    if (weight <= 0) weight = 0.001;
                    absOp.setWeight(weight);
                }
            }
        }
    }

    public void register(MutOP operator) {
        if (operatorMap.containsKey(operator.getName())) {
            log.warn("Duplicate operator name detected: {}", operator.getName());
            return;
        }
        operators.add(operator);
        operatorMap.put(operator.getName(), operator);
    }

    public List<MutOP> getAllOperators() {
        return Collections.unmodifiableList(operators);
    }

    public Map<String, Double> getEffectiveWeights() {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (MutOP op : operators) {
            weights.put(op.getName(), op.getWeight());
        }
        return weights;
    }

       
               
       
    public MutOP getRandomOperator(Random random) {
        if (operators.isEmpty()) {
            throw new IllegalStateException("No operators registered.");
        }
        return operators.get(random.nextInt(operators.size()));
    }

       
                  
      
                                                      
                                
                                             
       
    public MutOP applyMutationByName(String operatorName, MutationContext context) {
        MutOP operator = operatorMap.get(operatorName);

        if (operator == null) {
            log.error("[Mutation Error] Operator '{}' not found. Available operators: {}",
                    operatorName, operatorMap.keySet());
            return null;
        }

        log.debug("[Mutation] Target specific operator: {}", operator.getName());

               
        if (operator.apply(context)) {
            log.info("[Mutation Success] Operator: {}", operator.getName());
            return operator;
        } else {
            log.warn("[Mutation FAILURE] Operator '{}' could not be applied to the current context.", operator.getName());
            return null;
        }
    }

       
                                                              
      
                           
                                            
       
    public MutOP applyRandomMutation(MutationContext context) {
        if (Boolean.TRUE) {
            return applyRandomMutationStable(context);
        }
                                            
                                                   
                                                       
        List<MutOP> candidates = operators.stream()
                .filter(op -> !op.getName().equals("ContextWeaving"))
                .sorted((a, b) -> {
                    double scoreA = Math.pow(context.getRandom().nextDouble(), 1.0 / a.getWeight());
                    double scoreB = Math.pow(context.getRandom().nextDouble(), 1.0 / b.getWeight());
                    return Double.compare(scoreB, scoreA);              
                })
                .toList();

                  
        for (int i = 0; i < candidates.size(); i++) {
            MutOP operator = candidates.get(i);
            log.debug("[Mutation] Weighted attempt {}: Trying {} (Weight: {})...", 
                    i + 1, operator.getName(), operator.getWeight());

            if (operator.apply(context)) {
                log.info("[Mutation Success] Operator: {} (Weight: {})", operator.getName(), operator.getWeight());

                                                             
                if (context.getRandom().nextInt(10) < 3) {
                    MutOP weaver = operatorMap.get("ContextWeaving");
                    if (weaver != null && weaver.apply(context)) {
                        log.info("[Mutation] ContextWeaving applied as overlay");
                    }
                }
                return operator;
            }
        }

        log.warn("[Mutation FAILURE] Tried all {} weighted operators, none applicable.", candidates.size());
        return null;
    }

    public MutOP applyRandomMutationStable(MutationContext context) {
        Map<MutOP, Double> scores = new IdentityHashMap<>();
        List<MutOP> candidates = operators.stream()
                .filter(op -> !op.getName().equals("ContextWeaving"))
                .peek(op -> scores.put(op, Math.pow(context.getRandom().nextDouble(), 1.0 / op.getWeight())))
                .sorted(Comparator.comparingDouble((MutOP op) -> scores.getOrDefault(op, 0.0)).reversed())
                .toList();

        for (int i = 0; i < candidates.size(); i++) {
            MutOP operator = candidates.get(i);
            log.debug("[Mutation] Weighted attempt {}: Trying {} (Weight: {})...",
                    i + 1, operator.getName(), operator.getWeight());

            if (operator.apply(context)) {
                log.info("[Mutation Success] Operator: {} (Weight: {})", operator.getName(), operator.getWeight());

                if (context.getRandom().nextInt(10) < 3) {
                    MutOP weaver = operatorMap.get("ContextWeaving");
                    if (weaver != null && weaver.apply(context)) {
                        log.info("[Mutation] ContextWeaving applied as overlay");
                    }
                }
                return operator;
            }
        }

        log.warn("[Mutation FAILURE] Tried all {} weighted operators, none applicable.", candidates.size());
        return null;
    }
}

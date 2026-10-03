package org.fuzz.operator.impl.computation;

import lombok.extern.slf4j.Slf4j;
import org.fuzz.config.FuzzConfig;
import org.fuzz.core.MutationContext;
import org.fuzz.operator.AbstractMutationOperator;
import org.fuzz.selector.PrimitiveVariableReadSelector;
import spoon.reflect.code.CtCodeSnippetExpression;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtTypeReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

                                                                                    
@Slf4j
public class ComputationChainOP extends AbstractMutationOperator<CtVariableRead<?>> {

    private static final int MIN_OPERATIONS_PER_STEP = 2;
    private static final int MAX_OPERATIONS_PER_STEP = 4;
    private static final int CONTEXT_OPERAND_PERCENT = 60;

    private final int minChainLength;
    private final int maxChainLength;

    public ComputationChainOP() {
        this(FuzzConfig.COMPUTATION_CHAIN_MIN_LENGTH, FuzzConfig.COMPUTATION_CHAIN_MAX_LENGTH);
    }

    ComputationChainOP(int minChainLength, int maxChainLength) {
        super(new PrimitiveVariableReadSelector());
        this.minChainLength = Math.max(1, minChainLength);
        this.maxChainLength = Math.max(this.minChainLength, maxChainLength);
    }

    @Override
    public String getName() {
        return "ComputationChainOP";
    }

    @Override
    protected boolean attemptMutate(CtVariableRead<?> candidate, MutationContext context) {
        PrimitiveVariableReadSelector.InsertionPoint insertionPoint =
                PrimitiveVariableReadSelector.findInsertionPoint(candidate);
        CtTypeReference<?> originalType = candidate.getType();
        if (insertionPoint == null || !PrimitiveVariableReadSelector.isSupportedPrimitive(originalType)) {
            return false;
        }

        try {
            Factory factory = context.getFactory();
            Random random = context.getRandom();
            Carrier carrier = selectCarrier(originalType, factory, random);
            ValueDomain domain = domainOf(originalType);
            List<Operand> partners = collectPartners(candidate, insertionPoint, domain);
            int chainLength = minChainLength + random.nextInt(maxChainLength - minChainLength + 1);

            List<CtStatement> chainStatements = new ArrayList<>(chainLength + 1);
            String previousExpression = castTo(carrier.typeName(), candidate.toString());
            for (int step = 0; step < chainLength; step++) {
                String variableName = context.getNameGenerator().generate("fuzz_chain_t");
                String stepExpression = buildStep(previousExpression, domain, carrier, partners, random);
                chainStatements.add(createLocalVariable(factory, carrier.type(), variableName, stepExpression));
                previousExpression = variableName;
            }

            String resultName = context.getNameGenerator().generate("fuzz_chain_r");
            String resultExpression = castTo(sourceTypeName(originalType), previousExpression);
            CtLocalVariable<?> resultVariable = createLocalVariable(
                    factory, originalType.clone(), resultName, resultExpression);
            chainStatements.add(resultVariable);

            if (!insertAndReplace(candidate, insertionPoint, chainStatements, resultVariable, factory)) {
                return false;
            }
            log.debug("[Mutation {}] inserted {} steps before line {} ({} -> {} -> {})",
                    getName(), chainLength, lineOf(insertionPoint.anchor()),
                    originalType.getSimpleName(), carrier.typeName(), originalType.getSimpleName());
            return true;
        } catch (RuntimeException e) {
            log.debug("[Mutation {}] failed to build computation chain: {}", getName(), e.getMessage());
            return false;
        }
    }

    private List<Operand> collectPartners(
            CtVariableRead<?> candidate,
            PrimitiveVariableReadSelector.InsertionPoint insertionPoint,
            ValueDomain domain) {
        List<Operand> partners = new ArrayList<>();
        CtVariable<?> selectedDeclaration = candidate.getVariable() == null
                ? null
                : candidate.getVariable().getDeclaration();

                                                                                        
                                                                                       
                                                                     
        partners.add(new Operand(candidate.toString()));

        CtMethod<?> method = candidate.getParent(CtMethod.class);
        if (method != null) {
            for (CtParameter<?> parameter : method.getParameters()) {
                addPartner(partners, parameter, selectedDeclaration, domain);
            }
        }

        List<CtStatement> statements = insertionPoint.block().getStatements();
        for (int i = 0; i < insertionPoint.index(); i++) {
            if (statements.get(i) instanceof CtLocalVariable<?> local
                    && local.getDefaultExpression() != null) {
                addPartner(partners, local, selectedDeclaration, domain);
            }
        }
        return partners;
    }

    private void addPartner(
            List<Operand> partners,
            CtVariable<?> variable,
            CtVariable<?> selectedDeclaration,
            ValueDomain domain) {
        if (variable == selectedDeclaration || variable.getType() == null) {
            return;
        }
        if (domainOf(variable.getType()) == domain) {
            partners.add(new Operand(variable.getSimpleName()));
        }
    }

    private String buildStep(
            String previous,
            ValueDomain domain,
            Carrier carrier,
            List<Operand> partners,
            Random random) {
        int operationCount = MIN_OPERATIONS_PER_STEP
                + random.nextInt(MAX_OPERATIONS_PER_STEP - MIN_OPERATIONS_PER_STEP + 1);
        String expression = previous;
        boolean usedContextOperand = false;

        for (int operation = 0; operation < operationCount; operation++) {
            boolean forceContextOperand = !partners.isEmpty()
                    && !usedContextOperand
                    && operation == operationCount - 1;
            StepFragment fragment = switch (domain) {
                case INTEGRAL -> buildIntegralOperation(
                        expression, carrier, partners, random, forceContextOperand);
                case FLOATING -> buildFloatingOperation(
                        expression, carrier, partners, random, forceContextOperand);
                case BOOLEAN -> buildBooleanOperation(
                        expression, partners, random, forceContextOperand);
                case UNSUPPORTED -> throw new IllegalArgumentException("Unsupported primitive domain");
            };
            expression = fragment.expression();
            usedContextOperand |= fragment.usesContextOperand();
        }
        return expression;
    }

    private StepFragment buildIntegralOperation(
            String previous,
            Carrier carrier,
            List<Operand> partners,
            Random random,
            boolean forceContextOperand) {
        IntegralStep kind = IntegralStep.values()[random.nextInt(IntegralStep.values().length)];
        if (forceContextOperand && kind == IntegralStep.UNARY) {
            kind = random.nextBoolean() ? IntegralStep.ARITHMETIC : IntegralStep.BITWISE;
        }
        return switch (kind) {
            case ARITHMETIC -> {
                OperandChoice operand = randomOperand(partners, carrier, random, forceContextOperand);
                yield new StepFragment(
                        binary(previous, choose(random, "+", "-", "*"), operand.expression()),
                        operand.contextOperand());
            }
            case BITWISE -> {
                OperandChoice operand = randomOperand(partners, carrier, random, forceContextOperand);
                yield new StepFragment(
                        binary(previous, choose(random, "^", "&", "|"), operand.expression()),
                        operand.contextOperand());
            }
            case UNARY -> new StepFragment(
                    random.nextBoolean() ? "(~(" + previous + "))" : "(-(" + previous + "))",
                    false);
            case SHIFT -> {
                OperandChoice distance = shiftDistance(partners, carrier, random, forceContextOperand);
                yield new StepFragment(
                        binary(previous, choose(random, "<<", ">>", ">>>"), distance.expression()),
                        distance.contextOperand());
            }
            case COMPARE -> {
                OperandChoice operand = randomOperand(partners, carrier, random, forceContextOperand);
                String comparison = binary(previous, choose(random, "<", "<=", ">", ">=", "==", "!="),
                        operand.expression());
                String constant = numericLiteral(carrier, random);
                yield new StepFragment("((" + comparison + ") ? "
                        + binary(previous, "^", constant) + " : "
                        + binary(previous, "+", constant) + ")", operand.contextOperand());
            }
        };
    }

    private StepFragment buildFloatingOperation(
            String previous,
            Carrier carrier,
            List<Operand> partners,
            Random random,
            boolean forceContextOperand) {
        FloatingStep kind = FloatingStep.values()[random.nextInt(FloatingStep.values().length)];
        if (forceContextOperand && kind == FloatingStep.UNARY) {
            kind = FloatingStep.ARITHMETIC;
        }
        return switch (kind) {
            case ARITHMETIC -> {
                OperandChoice operand = randomOperand(partners, carrier, random, forceContextOperand);
                yield new StepFragment(
                        binary(previous, choose(random, "+", "-", "*"), operand.expression()),
                        operand.contextOperand());
            }
            case UNARY -> new StepFragment("(-(" + previous + "))", false);
            case COMPARE -> {
                OperandChoice operand = randomOperand(partners, carrier, random, forceContextOperand);
                String comparison = binary(previous, choose(random, "<", "<=", ">", ">=", "==", "!="),
                        operand.expression());
                yield new StepFragment("((" + comparison + ") ? (-(" + previous + ")) : "
                        + binary(previous, "+", numericLiteral(carrier, random)) + ")",
                        operand.contextOperand());
            }
        };
    }

    private StepFragment buildBooleanOperation(
            String previous,
            List<Operand> partners,
            Random random,
            boolean forceContextOperand) {
        BooleanStep kind = BooleanStep.values()[random.nextInt(BooleanStep.values().length)];
        if (forceContextOperand && kind == BooleanStep.UNARY) {
            kind = BooleanStep.LOGICAL;
        }
        return switch (kind) {
            case LOGICAL -> {
                OperandChoice operand = randomBooleanOperand(partners, random, forceContextOperand);
                yield new StepFragment(
                        binary(previous, choose(random, "^", "&", "|"), operand.expression()),
                        operand.contextOperand());
            }
            case UNARY -> new StepFragment("(!(" + previous + "))", false);
            case COMPARE -> {
                OperandChoice operand = randomBooleanOperand(partners, random, forceContextOperand);
                String comparison = binary(previous, choose(random, "==", "!="), operand.expression());
                yield new StepFragment("((" + comparison + ") ? (!(" + previous + ")) : "
                        + binary(previous, "^", operand.expression()) + ")",
                        operand.contextOperand());
            }
        };
    }

    private OperandChoice randomOperand(
            List<Operand> partners,
            Carrier carrier,
            Random random,
            boolean forceContextOperand) {
        if (shouldUseContextOperand(partners, random, forceContextOperand)) {
            Operand partner = partners.get(random.nextInt(partners.size()));
            return new OperandChoice(castTo(carrier.typeName(), partner.name()), true);
        }
        return new OperandChoice(numericLiteral(carrier, random), false);
    }

    private OperandChoice randomBooleanOperand(
            List<Operand> partners,
            Random random,
            boolean forceContextOperand) {
        if (shouldUseContextOperand(partners, random, forceContextOperand)) {
            return new OperandChoice(partners.get(random.nextInt(partners.size())).name(), true);
        }
        return new OperandChoice(Boolean.toString(random.nextBoolean()), false);
    }

    private OperandChoice shiftDistance(
            List<Operand> partners,
            Carrier carrier,
            Random random,
            boolean forceContextOperand) {
        int mask = "long".equals(carrier.typeName()) ? 63 : 31;
        if (shouldUseContextOperand(partners, random, forceContextOperand)) {
            Operand partner = partners.get(random.nextInt(partners.size()));
            return new OperandChoice("(((int) (" + partner.name() + ")) & " + mask + ")", true);
        }
        return new OperandChoice(Integer.toString(1 + random.nextInt(mask)), false);
    }

    private boolean shouldUseContextOperand(
            List<Operand> partners,
            Random random,
            boolean forceContextOperand) {
        return !partners.isEmpty()
                && (forceContextOperand || random.nextInt(100) < CONTEXT_OPERAND_PERCENT);
    }

    private String numericLiteral(Carrier carrier, Random random) {
        int value = 1 + random.nextInt(63);
        if (random.nextBoolean()) {
            value = -value;
        }
        return switch (carrier.typeName()) {
            case "long" -> value + "L";
            case "float" -> value + ".0F";
            case "double" -> value + ".0D";
            default -> Integer.toString(value);
        };
    }

    private Carrier selectCarrier(CtTypeReference<?> originalType, Factory factory, Random random) {
        return switch (originalType.getSimpleName()) {
            case "byte", "short", "char", "int" -> random.nextBoolean()
                    ? new Carrier(factory.Type().integerPrimitiveType(), "int")
                    : new Carrier(factory.Type().longPrimitiveType(), "long");
            case "long" -> new Carrier(factory.Type().longPrimitiveType(), "long");
            case "float" -> random.nextBoolean()
                    ? new Carrier(factory.Type().floatPrimitiveType(), "float")
                    : new Carrier(factory.Type().doublePrimitiveType(), "double");
            case "double" -> new Carrier(factory.Type().doublePrimitiveType(), "double");
            case "boolean" -> new Carrier(factory.Type().booleanPrimitiveType(), "boolean");
            default -> throw new IllegalArgumentException("Unsupported primitive type: " + originalType);
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private CtLocalVariable<?> createLocalVariable(
            Factory factory,
            CtTypeReference<?> type,
            String name,
            String initializerCode) {
        CtCodeSnippetExpression initializer = factory.Code().createCodeSnippetExpression(initializerCode);
        initializer.setType(type.clone());
        return factory.Code().createLocalVariable((CtTypeReference) type.clone(), name, initializer);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean insertAndReplace(
            CtVariableRead<?> candidate,
            PrimitiveVariableReadSelector.InsertionPoint insertionPoint,
            List<CtStatement> statements,
            CtLocalVariable<?> resultVariable,
            Factory factory) {
        List<CtStatement> inserted = new ArrayList<>();
        try {
            int index = insertionPoint.index();
            for (CtStatement statement : statements) {
                insertionPoint.block().addStatement(index++, statement);
                inserted.add(statement);
            }
            CtVariableAccess replacement = factory.Code().createVariableRead(resultVariable.getReference(), false);
            candidate.replace(replacement);
            return true;
        } catch (RuntimeException e) {
            for (int i = inserted.size() - 1; i >= 0; i--) {
                try {
                    inserted.get(i).delete();
                } catch (RuntimeException ignored) {
                                                                                                             
                }
            }
            log.debug("[Mutation {}] failed to insert computation chain: {}", getName(), e.getMessage());
            return false;
        }
    }

    private String sourceTypeName(CtTypeReference<?> type) {
        return type.getQualifiedName();
    }

    private String castTo(String typeName, String expression) {
        return "((" + typeName + ") (" + expression + "))";
    }

    private String binary(String left, String operator, String right) {
        return "((" + left + ") " + operator + " (" + right + "))";
    }

    private String choose(Random random, String... values) {
        return values[random.nextInt(values.length)];
    }

    private ValueDomain domainOf(CtTypeReference<?> type) {
        if (type == null || !type.isPrimitive()) {
            return ValueDomain.UNSUPPORTED;
        }
        return switch (type.getSimpleName()) {
            case "byte", "short", "char", "int", "long" -> ValueDomain.INTEGRAL;
            case "float", "double" -> ValueDomain.FLOATING;
            case "boolean" -> ValueDomain.BOOLEAN;
            default -> ValueDomain.UNSUPPORTED;
        };
    }

    private int lineOf(CtStatement statement) {
        return statement.getPosition() != null && statement.getPosition().isValidPosition()
                ? statement.getPosition().getLine()
                : -1;
    }

    private enum ValueDomain {
        INTEGRAL,
        FLOATING,
        BOOLEAN,
        UNSUPPORTED
    }

    private enum IntegralStep {
        ARITHMETIC,
        BITWISE,
        UNARY,
        SHIFT,
        COMPARE
    }

    private enum FloatingStep {
        ARITHMETIC,
        UNARY,
        COMPARE
    }

    private enum BooleanStep {
        LOGICAL,
        UNARY,
        COMPARE
    }

    private record Carrier(CtTypeReference<?> type, String typeName) {
    }

    private record Operand(String name) {
    }

    private record OperandChoice(String expression, boolean contextOperand) {
    }

    private record StepFragment(String expression, boolean usesContextOperand) {
    }
}

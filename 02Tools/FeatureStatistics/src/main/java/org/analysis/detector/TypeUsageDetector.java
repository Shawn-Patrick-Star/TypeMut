package org.analysis.detector;

import org.ASTfeature.SourceCodeFeature;
import org.model.FeatureOccurrence;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtNewArray;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtWildcardReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TypeUsageDetector implements FeatureDetector {

    private static final Map<String, SourceCodeFeature> PRIMITIVE_MAP = new HashMap<>();
    static {
        PRIMITIVE_MAP.put("byte", SourceCodeFeature.byteType);
        PRIMITIVE_MAP.put("boolean", SourceCodeFeature.booleanType);
        PRIMITIVE_MAP.put("char", SourceCodeFeature.charType);
        PRIMITIVE_MAP.put("int", SourceCodeFeature.integerType);
        PRIMITIVE_MAP.put("float", SourceCodeFeature.floatType);
        PRIMITIVE_MAP.put("double", SourceCodeFeature.doubleType);
        PRIMITIVE_MAP.put("long", SourceCodeFeature.longType);
        PRIMITIVE_MAP.put("short", SourceCodeFeature.shortType);
    }

    private static final Set<String> WRAPPER_NAMES = new HashSet<>(Arrays.asList(
            "Integer", "Boolean", "Byte", "Character",
            "Double", "Float", "Long", "Short"
    ));

    @Override
    public SourceCodeFeature getTargetFeature() {
        return SourceCodeFeature.integerType;
    }

    @Override
    public List<FeatureOccurrence> detect(CtType<?> type) {
        List<FeatureOccurrence> results = new ArrayList<>();

        /*
         * Do NOT scan every CtTypeReference in the model. Spoon attaches inferred
         * type references to literals, comparisons, arithmetic expressions, method
         * calls, etc., which previously made int/boolean almost universal.
         *
         * Instead, visit AST contexts where Java source explicitly names a type:
         * variable/field/parameter declarations, method return types, explicit
         * casts, constructor/new-array syntax, and explicit type accesses.
         */
        for (CtVariable<?> variable : type.getElements(new TypeFilter<>(CtVariable.class))) {
            if (hasSourcePosition(variable) && !isInferredVariable(variable)) {
                recordType(variable.getType(), variable, results, 0);
            }
        }

        for (CtMethod<?> method : type.getElements(new TypeFilter<>(CtMethod.class))) {
            if (hasSourcePosition(method)) {
                recordType(method.getType(), method, results, 0);
            }
        }

        for (CtExpression<?> expression : type.getElements(new TypeFilter<>(CtExpression.class))) {
            if (!hasSourcePosition(expression)) {
                continue;
            }
            List<CtTypeReference<?>> casts = expression.getTypeCasts();
            if (casts == null) {
                continue;
            }
            for (CtTypeReference<?> castType : casts) {
                if (castType != null && !castType.isImplicit()) {
                    recordType(castType, expression, results, 0);
                }
            }
        }

        for (CtConstructorCall<?> constructorCall : type.getElements(new TypeFilter<>(CtConstructorCall.class))) {
            if (hasSourcePosition(constructorCall)) {
                recordType(constructorCall.getType(), constructorCall, results, 0);
            }
        }

        for (CtNewArray<?> newArray : type.getElements(new TypeFilter<>(CtNewArray.class))) {
            if (hasSourcePosition(newArray)) {
                recordType(newArray.getType(), newArray, results, 0);
            }
        }

        for (CtTypeAccess<?> typeAccess : type.getElements(new TypeFilter<>(CtTypeAccess.class))) {
            if (hasSourcePosition(typeAccess) && !typeAccess.isImplicit()) {
                recordType(typeAccess.getAccessedType(), typeAccess, results, 0);
            }
        }

        // nullType is syntax-driven: only an actual source null literal counts.
        for (CtLiteral<?> literal : type.getElements(new TypeFilter<>(CtLiteral.class))) {
            if (literal.getValue() == null && hasSourcePosition(literal)) {
                results.add(new FeatureOccurrence(SourceCodeFeature.nullType, findMeaningfulStatement(literal)));
            }
        }

        return results;
    }

    private boolean isInferredVariable(CtVariable<?> variable) {
        if (variable instanceof CtLocalVariable) {
            return ((CtLocalVariable<?>) variable).isInferred();
        }
        if (variable instanceof CtParameter) {
            return ((CtParameter<?>) variable).isInferred();
        }
        return false;
    }

    private void recordType(CtTypeReference<?> ref,
                            CtElement origin,
                            List<FeatureOccurrence> results,
                            int depth) {
        if (ref == null || depth > 8) {
            return;
        }

        String name = ref.getSimpleName();
        SourceCodeFeature primitiveFeature = PRIMITIVE_MAP.get(name);
        if (primitiveFeature != null) {
            results.add(new FeatureOccurrence(SourceCodeFeature.primitiveType, findMeaningfulStatement(origin)));
            results.add(new FeatureOccurrence(primitiveFeature, findMeaningfulStatement(origin)));
        }

        if (WRAPPER_NAMES.contains(name)) {
            results.add(new FeatureOccurrence(SourceCodeFeature.wrapperType, findMeaningfulStatement(origin)));
        }

        // int[] explicitly contains the primitive element type; similarly,
        // List<Integer> explicitly contains a wrapper type argument.
        if (ref instanceof CtArrayTypeReference) {
            recordType(((CtArrayTypeReference<?>) ref).getComponentType(), origin, results, depth + 1);
        }

        if (ref.getActualTypeArguments() != null) {
            for (CtTypeReference<?> arg : ref.getActualTypeArguments()) {
                recordType(arg, origin, results, depth + 1);
            }
        }

        if (ref instanceof CtWildcardReference) {
            CtTypeReference<?> bound = ((CtWildcardReference) ref).getBoundingType();
            if (bound != null && !bound.isImplicit()) {
                recordType(bound, origin, results, depth + 1);
            }
        }
    }
}

package org.analysis.detector;

import org.ASTfeature.SourceCodeFeature;
import org.model.FeatureOccurrence;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtTypePattern;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtFormalTypeDeclarer;
import spoon.reflect.declaration.CtInterface;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtModifiable;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypeParameter;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.reference.CtTypeParameterReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtWildcardReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class TypeSystemFeatureDetector implements FeatureDetector {

    @Override
    public SourceCodeFeature getTargetFeature() {
        return SourceCodeFeature.genericType;
    }

    @Override
    public List<FeatureOccurrence> detect(CtType<?> type) {
        List<FeatureOccurrence> results = new ArrayList<>();

        detectTypeDeclarations(type, results);
        if (detectGenerics(type, results)) {
            results.add(new FeatureOccurrence(SourceCodeFeature.genericType, type));
        }
        detectTypeRelatedExpressions(type, results);

        return results;
    }

    private void detectTypeDeclarations(CtType<?> rootType, List<FeatureOccurrence> results) {
        for (CtType<?> type : collectTypes(rootType)) {
            if (type != rootType) {
                results.add(new FeatureOccurrence(SourceCodeFeature.nestedType, type));
            }
            if (type instanceof CtInterface) {
                results.add(new FeatureOccurrence(SourceCodeFeature.interfaceType, type));
            }
            if (type instanceof CtModifiable &&
                    ((CtModifiable) type).getModifiers().contains(ModifierKind.ABSTRACT)) {
                results.add(new FeatureOccurrence(SourceCodeFeature.abstractType, type));
            }
            if (type instanceof CtEnum) {
                results.add(new FeatureOccurrence(SourceCodeFeature.enumType, type));
            }
        }
    }

    private List<CtType<?>> collectTypes(CtType<?> rootType) {
        List<CtType<?>> allTypes = new ArrayList<>();
        allTypes.add(rootType);
        allTypes.addAll(rootType.getElements(new TypeFilter<>(CtType.class)));
        return allTypes;
    }

    private boolean detectGenerics(CtType<?> rootType, List<FeatureOccurrence> results) {
        boolean hasGenericFeature = false;

        // Formal type parameters are explicit declarations.
        for (CtTypeParameter parameter : rootType.getElements(new TypeFilter<>(CtTypeParameter.class))) {
            if (!hasSourcePosition(parameter)) {
                continue;
            }
            results.add(new FeatureOccurrence(SourceCodeFeature.typeParameter, parameter));
            hasGenericFeature = true;
            if (hasBounds(parameter)) {
                results.add(new FeatureOccurrence(SourceCodeFeature.boundedTypeParameter, parameter));
            }

            CtTypeReference<?> superclass = parameter.getSuperclass();
            if (superclass != null && !"java.lang.Object".equals(superclass.getQualifiedName())) {
                hasGenericFeature |= recordGenericType(superclass, parameter, results, 0);
            }
            Set<CtTypeReference<?>> interfaces = parameter.getSuperInterfaces();
            if (interfaces != null) {
                for (CtTypeReference<?> iface : interfaces) {
                    hasGenericFeature |= recordGenericType(iface, parameter, results, 0);
                }
            }
        }

        for (CtMethod<?> method : rootType.getElements(new TypeFilter<>(CtMethod.class))) {
            if (!hasSourcePosition(method)) {
                continue;
            }
            if (hasFormalTypeParameters(method)) {
                results.add(new FeatureOccurrence(SourceCodeFeature.genericMethod, method));
                hasGenericFeature = true;
            }
            hasGenericFeature |= recordGenericType(method.getType(), method, results, 0);
        }

        // Variable/field/parameter declarations are explicit type-bearing source
        // contexts. Skip Java `var` and inferred lambda parameters.
        for (CtVariable<?> variable : rootType.getElements(new TypeFilter<>(CtVariable.class))) {
            if (!hasSourcePosition(variable) || isInferredVariable(variable)) {
                continue;
            }
            hasGenericFeature |= recordGenericType(variable.getType(), variable, results, 0);
        }

        // Extends / implements clauses are explicit type syntax.
        for (CtType<?> type : collectTypes(rootType)) {
            CtTypeReference<?> superclass = type.getSuperclass();
            if (superclass != null && !"java.lang.Object".equals(superclass.getQualifiedName())) {
                hasGenericFeature |= recordGenericType(superclass, type, results, 0);
            }
            Set<CtTypeReference<?>> interfaces = type.getSuperInterfaces();
            if (interfaces != null) {
                for (CtTypeReference<?> iface : interfaces) {
                    hasGenericFeature |= recordGenericType(iface, type, results, 0);
                }
            }
        }

        // Explicit casts and constructor type syntax may also contain generics.
        for (CtExpression<?> expression : rootType.getElements(new TypeFilter<>(CtExpression.class))) {
            if (!hasSourcePosition(expression)) {
                continue;
            }
            List<CtTypeReference<?>> casts = expression.getTypeCasts();
            if (casts == null) {
                continue;
            }
            for (CtTypeReference<?> castType : casts) {
                if (castType != null && !castType.isImplicit()) {
                    hasGenericFeature |= recordGenericType(castType, expression, results, 0);
                }
            }
        }

        for (CtConstructorCall<?> constructorCall : rootType.getElements(new TypeFilter<>(CtConstructorCall.class))) {
            if (hasSourcePosition(constructorCall)) {
                hasGenericFeature |= recordGenericType(constructorCall.getType(), constructorCall, results, 0);
            }
        }

        return hasGenericFeature;
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

    private boolean hasFormalTypeParameters(CtFormalTypeDeclarer declarer) {
        return declarer.getFormalCtTypeParameters() != null &&
                !declarer.getFormalCtTypeParameters().isEmpty();
    }

    private boolean hasBounds(CtTypeParameter parameter) {
        CtTypeReference<?> superclass = parameter.getSuperclass();
        if (superclass != null && !"java.lang.Object".equals(superclass.getQualifiedName())) {
            return true;
        }
        return parameter.getSuperInterfaces() != null && !parameter.getSuperInterfaces().isEmpty();
    }

    /**
     * Inspect a type reference that came from an explicit source context. This is
     * intentionally recursive so List<? extends T> records parameterization,
     * wildcard use, bounds, and type-parameter use without scanning unrelated
     * inferred references elsewhere in the Spoon model.
     */
    private boolean recordGenericType(CtTypeReference<?> reference,
                                      CtElement origin,
                                      List<FeatureOccurrence> results,
                                      int depth) {
        if (reference == null || depth > 10) {
            return false;
        }

        boolean hasGenericFeature = false;
        CtElement target = findMeaningfulStatement(origin);

        if (reference instanceof CtWildcardReference) {
            results.add(new FeatureOccurrence(SourceCodeFeature.wildcardType, target));
            hasGenericFeature = true;

            CtTypeReference<?> bound = ((CtWildcardReference) reference).getBoundingType();
            if (bound != null && !bound.isImplicit()) {
                results.add(new FeatureOccurrence(SourceCodeFeature.boundedWildcardType, target));
                hasGenericFeature = true;
                hasGenericFeature |= recordGenericType(bound, origin, results, depth + 1);
            }
        } else if (reference instanceof CtTypeParameterReference) {
            results.add(new FeatureOccurrence(SourceCodeFeature.typeParameter, target));
            hasGenericFeature = true;
        }

        List<CtTypeReference<?>> args = reference.getActualTypeArguments();
        if (args != null && !args.isEmpty()) {
            results.add(new FeatureOccurrence(SourceCodeFeature.parameterizedType, target));
            hasGenericFeature = true;
            for (CtTypeReference<?> arg : args) {
                hasGenericFeature |= recordGenericType(arg, origin, results, depth + 1);
            }
        }

        return hasGenericFeature;
    }

    private void detectTypeRelatedExpressions(CtType<?> type, List<FeatureOccurrence> results) {
        for (CtFieldAccess<?> access : type.getElements(new TypeFilter<>(CtFieldAccess.class))) {
            if (hasSourcePosition(access) && access.getVariable() != null &&
                    "class".equals(access.getVariable().getSimpleName())) {
                results.add(new FeatureOccurrence(SourceCodeFeature.classLiteral, findMeaningfulStatement(access)));
            }
        }

        for (CtTypePattern pattern : type.getElements(new TypeFilter<>(CtTypePattern.class))) {
            if (hasSourcePosition(pattern)) {
                results.add(new FeatureOccurrence(SourceCodeFeature.instanceofOperator, findMeaningfulStatement(pattern)));
            }
        }
    }
}

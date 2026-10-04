package org.analysis.detector;

import org.ASTfeature.SourceCodeFeature;
import org.model.FeatureOccurrence;
import spoon.reflect.code.CtArrayAccess;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtNewArray;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtWildcardReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.List;

public class ArrayDetector implements FeatureDetector {

    @Override
    public SourceCodeFeature getTargetFeature() {
        return SourceCodeFeature.arrayType;
    }

    @Override
    public List<FeatureOccurrence> detect(CtType<?> type) {
        List<FeatureOccurrence> results = new ArrayList<>();

        /*
         * Do not scan every CtArrayTypeReference in the Spoon model: inferred
         * expression/variable types can also create array references. Instead,
         * inspect AST contexts where source explicitly names a type.
         */
        for (CtVariable<?> variable : type.getElements(new TypeFilter<>(CtVariable.class))) {
            if (!hasSourcePosition(variable) ||
                    isInferredVariable(variable) ||
                    isMainStringArrayParameter(variable)) {
                continue;
            }
            recordArrayType(variable.getType(), variable, results, 0);
        }

        for (CtMethod<?> method : type.getElements(new TypeFilter<>(CtMethod.class))) {
            if (hasSourcePosition(method)) {
                recordArrayType(method.getType(), method, results, 0);
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
                    recordArrayType(castType, expression, results, 0);
                }
            }
        }

        for (CtNewArray<?> newArray : type.getElements(new TypeFilter<>(CtNewArray.class))) {
            if (hasSourcePosition(newArray)) {
                recordArrayType(newArray.getType(), newArray, results, 0);
            }
        }

        for (CtConstructorCall<?> constructorCall : type.getElements(new TypeFilter<>(CtConstructorCall.class))) {
            if (hasSourcePosition(constructorCall)) {
                recordArrayType(constructorCall.getType(), constructorCall, results, 0);
            }
        }

        for (CtTypeAccess<?> typeAccess : type.getElements(new TypeFilter<>(CtTypeAccess.class))) {
            if (hasSourcePosition(typeAccess) && !typeAccess.isImplicit()) {
                recordArrayType(typeAccess.getAccessedType(), typeAccess, results, 0);
            }
        }

        // arrayAccess is a concrete syntactic feature: actual a[i] load/store.
        for (CtArrayAccess<?, ?> access : type.getElements(new TypeFilter<>(CtArrayAccess.class))) {
            if (hasSourcePosition(access)) {
                CtElement target = findMeaningfulStatement(access);
                results.add(new FeatureOccurrence(SourceCodeFeature.arrayAccess, target));
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

    private void recordArrayType(CtTypeReference<?> ref,
                                 CtElement origin,
                                 List<FeatureOccurrence> results,
                                 int depth) {
        if (ref == null || depth > 8) {
            return;
        }

        if (ref instanceof CtArrayTypeReference) {
            results.add(new FeatureOccurrence(
                    SourceCodeFeature.arrayType,
                    findMeaningfulStatement(origin)
            ));
            // The main prevalence statistic is binary per case, so array dimensions
            // are not separate features. Still recurse into generic arguments/bounds.
        }

        if (ref.getActualTypeArguments() != null) {
            for (CtTypeReference<?> arg : ref.getActualTypeArguments()) {
                recordArrayType(arg, origin, results, depth + 1);
            }
        }

        if (ref instanceof CtWildcardReference) {
            CtTypeReference<?> bound = ((CtWildcardReference) ref).getBoundingType();
            if (bound != null && !bound.isImplicit()) {
                recordArrayType(bound, origin, results, depth + 1);
            }
        }
    }

    private boolean isMainStringArrayParameter(CtVariable<?> variable) {
        if (!(variable instanceof CtParameter)) {
            return false;
        }
        CtParameter<?> parameter = (CtParameter<?>) variable;
        if (!(parameter.getType() instanceof CtArrayTypeReference)) {
            return false;
        }

        CtMethod<?> method;
        try {
            method = parameter.getParent(CtMethod.class);
        } catch (Exception ignored) {
            return false;
        }
        if (method == null || !"main".equals(method.getSimpleName()) ||
                !method.getModifiers().contains(ModifierKind.STATIC) ||
                method.getParameters().size() != 1) {
            return false;
        }

        CtTypeReference<?> component = ((CtArrayTypeReference<?>) parameter.getType()).getComponentType();
        while (component instanceof CtArrayTypeReference) {
            component = ((CtArrayTypeReference<?>) component).getComponentType();
        }
        if (component == null) {
            return false;
        }

        String qName = component.getQualifiedName();
        return "java.lang.String".equals(qName) || "String".equals(component.getSimpleName());
    }
}

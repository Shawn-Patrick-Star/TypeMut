package org.analysis.detector;

import org.ASTfeature.SourceCodeFeature;
import org.model.FeatureOccurrence;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtReturn;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtInterface;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TypeCastingDetector implements FeatureDetector {

    private static final Set<String> BROAD_CATEGORIES = new HashSet<>(Arrays.asList(
            "Interface", "CustomClass", "Exception"
    ));

    private static final Set<String> WRAPPERS = new HashSet<>(Arrays.asList(
            "java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Float",
            "java.lang.Short", "java.lang.Byte", "java.lang.Character", "java.lang.Boolean"
    ));

    @Override
    public SourceCodeFeature getTargetFeature() {
        return SourceCodeFeature.TypeCast;
    }

    @Override
    public List<FeatureOccurrence> detect(CtType<?> type) {
        List<FeatureOccurrence> results = new ArrayList<>();
        detectExplicitTypeCasts(type, results);
        detectImplicitTypeConversions(type, results);
        results.addAll(new BoxingUnboxingDetector().detect(type));
        return results;
    }

    /** Explicit Java cast syntax, e.g. (Foo) obj or (int) value. */
    private void detectExplicitTypeCasts(CtType<?> type, List<FeatureOccurrence> results) {
        for (CtExpression<?> expression : type.getElements(new TypeFilter<>(CtExpression.class))) {
            if (!hasSourcePosition(expression)) {
                continue;
            }

            List<CtTypeReference<?>> casts = expression.getTypeCasts();
            if (casts == null || casts.isEmpty()) {
                continue;
            }

            for (CtTypeReference<?> castType : casts) {
                // Parsed source casts are non-implicit. Do not require the child
                // type-reference object itself to own a full SourcePosition because
                // Spoon versions differ in how cast target positions are attached.
                if (castType == null || castType.isImplicit()) {
                    continue;
                }

                CtElement targetStatement = findMeaningfulStatement(expression);
                CtTypeReference<?> sourceType = expression.getType();
                String detailInfo = "explicit:" + getDetailInfo(
                        normalizeType(sourceType),
                        normalizeType(castType)
                );

                results.add(new FeatureOccurrence(
                        SourceCodeFeature.TypeCast,
                        targetStatement,
                        detailInfo
                ));

                recordConversionKind(results, targetStatement, sourceType, castType, detailInfo);
            }
        }
    }

    /**
     * Conversion implied by an actual source assignment/initializer/return
     * context, rather than by merely observing two inferred AST types somewhere.
     */
    private void detectImplicitTypeConversions(CtType<?> type, List<FeatureOccurrence> results) {
        for (CtLocalVariable<?> variable : type.getElements(new TypeFilter<>(CtLocalVariable.class))) {
            if (!hasSourcePosition(variable)) {
                continue;
            }
            CtExpression<?> assignment = variable.getDefaultExpression();
            if (assignment == null || hasExplicitCast(assignment)) {
                continue;
            }
            recordImplicitConversion(results, variable, assignment.getType(), variable.getType());
        }

        for (CtAssignment<?, ?> assignment : type.getElements(new TypeFilter<>(CtAssignment.class))) {
            if (!hasSourcePosition(assignment)) {
                continue;
            }
            CtExpression<?> rhs = assignment.getAssignment();
            CtExpression<?> lhs = assignment.getAssigned();
            if (rhs == null || lhs == null || hasExplicitCast(rhs)) {
                continue;
            }
            recordImplicitConversion(results, assignment, rhs.getType(), lhs.getType());
        }

        for (CtReturn<?> returnStatement : type.getElements(new TypeFilter<>(CtReturn.class))) {
            if (!hasSourcePosition(returnStatement)) {
                continue;
            }
            CtExpression<?> returnedExpression = returnStatement.getReturnedExpression();
            CtMethod<?> method = returnStatement.getParent(CtMethod.class);
            if (returnedExpression == null || method == null || hasExplicitCast(returnedExpression)) {
                continue;
            }
            recordImplicitConversion(results, returnStatement, returnedExpression.getType(), method.getType());
        }
    }

    private boolean hasExplicitCast(CtExpression<?> expression) {
        List<CtTypeReference<?>> casts = expression.getTypeCasts();
        if (casts == null || casts.isEmpty()) {
            return false;
        }
        for (CtTypeReference<?> cast : casts) {
            if (cast != null && !cast.isImplicit()) {
                return true;
            }
        }
        return false;
    }

    private void recordImplicitConversion(List<FeatureOccurrence> results,
                                          CtElement location,
                                          CtTypeReference<?> sourceTypeRef,
                                          CtTypeReference<?> targetTypeRef) {
        if (sourceTypeRef == null || targetTypeRef == null) {
            return;
        }

        String sourceQName = getSafeQualifiedName(sourceTypeRef);
        String targetQName = getSafeQualifiedName(targetTypeRef);
        if (sourceQName == null || targetQName == null ||
                sourceQName.isEmpty() || targetQName.isEmpty() ||
                sourceQName.equals(targetQName) ||
                "<nulltype>".equals(sourceQName) || "null".equals(sourceQName)) {
            return;
        }

        CtElement targetStatement = findMeaningfulStatement(location);
        String detailInfo = "implicit:"
                + getDetailInfo(normalizeType(sourceTypeRef), normalizeType(targetTypeRef));
        results.add(new FeatureOccurrence(
                SourceCodeFeature.TypeCast,
                targetStatement,
                detailInfo
        ));

        recordConversionKind(results, targetStatement, sourceTypeRef, targetTypeRef, detailInfo);
    }

    private void recordConversionKind(List<FeatureOccurrence> results,
                                      CtElement targetStatement,
                                      CtTypeReference<?> sourceTypeRef,
                                      CtTypeReference<?> targetTypeRef,
                                      String detailInfo) {
        if (sourceTypeRef == null || targetTypeRef == null) {
            return;
        }

        boolean sourcePrimitive = sourceTypeRef.isPrimitive();
        boolean targetPrimitive = targetTypeRef.isPrimitive();
        String sourceQName = getSafeQualifiedName(sourceTypeRef);
        String targetQName = getSafeQualifiedName(targetTypeRef);

        if (sourcePrimitive && targetPrimitive && !sourceQName.equals(targetQName)) {
            results.add(new FeatureOccurrence(
                    SourceCodeFeature.primitiveConversion,
                    targetStatement,
                    detailInfo
            ));
        }

    }

    private static String getDetailInfo(String sourceType, String targetType) {
        if (sourceType.equals(targetType) && BROAD_CATEGORIES.contains(sourceType)) {
            return sourceType + "A->" + targetType + "B";
        }
        return sourceType + "->" + targetType;
    }

    private String getSafeQualifiedName(CtTypeReference<?> ref) {
        if (ref == null) {
            return null;
        }
        try {
            String qName = ref.getQualifiedName();
            return qName != null ? qName : ref.getSimpleName();
        } catch (Exception e) {
            return ref.getSimpleName();
        }
    }

    public static String normalizeType(CtTypeReference<?> typeRef) {
        if (typeRef == null) {
            return "Unknown";
        }

        if (typeRef.isPrimitive()) {
            return typeRef.getSimpleName();
        }

        if (typeRef instanceof CtArrayTypeReference) {
            return typeRef.getSimpleName();
        }

        String qName = typeRef.getQualifiedName();
        if (WRAPPERS.contains(qName)) {
            return typeRef.getSimpleName();
        }
        if ("java.lang.String".equals(qName)) {
            return "String";
        }
        if ("java.lang.Object".equals(qName)) {
            return "Object";
        }

        try {
            CtType<?> declaration = typeRef.getTypeDeclaration();
            if (declaration != null) {
                if (declaration instanceof CtInterface) {
                    return "Interface";
                }
                if (declaration instanceof CtClass) {
                    if (isException(typeRef)) {
                        return "Exception";
                    }
                    return "CustomClass";
                }
            }
        } catch (Exception ignored) {
        }

        if (typeRef.getSimpleName().endsWith("Exception") || typeRef.getSimpleName().endsWith("Error")) {
            return "Exception";
        }

        return "CustomClass";
    }

    private static boolean isException(CtTypeReference<?> ref) {
        return ref.getSimpleName().endsWith("Exception");
    }
}

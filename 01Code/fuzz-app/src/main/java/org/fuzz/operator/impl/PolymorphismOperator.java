package org.fuzz.operator.impl;

import lombok.extern.slf4j.Slf4j;
import org.fuzz.core.MutationContext;
import org.fuzz.operator.AbstractMutationOperator;
import org.fuzz.selector.ConstructorCallSelector;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtCodeSnippetStatement;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtNamedElement;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.reference.CtTypeReference;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

   
                                                                
   
@Slf4j
public class PolymorphismOperator extends AbstractMutationOperator<CtConstructorCall<?>> {

    private static final String POLY_FLAG_FIELD = "_Fuzz_Poly_Flag";

    public PolymorphismOperator() {
        super(new ConstructorCallSelector());
    }

    @Override
    public String getName() {
        return "PolymorphismOperator";
    }

    @Override
    protected boolean attemptMutate(CtConstructorCall<?> candidate, MutationContext context) {
        CtTypeReference<?> targetTypeRef = candidate.getType();
        CtType<?> targetClassDecl = targetTypeRef.getTypeDeclaration();
        if (targetClassDecl == null) {
            return false;
        }

        CtType<?> hostType = candidate.getParent(CtType.class);
        if (hostType == null || hostType.isAnnotationType()) {
            return false;
        }

        String subClassName = "_Poly_Sub_" + targetTypeRef.getSimpleName();
        if (!ensureSubclassExists(hostType, targetClassDecl, subClassName, context)) {
            return false;
        }

        ensurePolyFlagField(hostType, context);

        String uid = context.getNameGenerator().generate("poly_obj");
        String parentTypeName = getCorrectSourceClassName(targetTypeRef);
        String argsStr = candidate.getArguments().stream()
                .map(CtElement::toString)
                .collect(Collectors.joining(", "));

        StringBuilder snippet = new StringBuilder();
        snippet.append(parentTypeName).append(" ").append(uid).append("; ");
        snippet.append("if (").append(POLY_FLAG_FIELD).append(") { ");
        snippet.append(uid).append(" = new ").append(subClassName).append("(").append(argsStr).append("); ");
        snippet.append("} else { ");
        snippet.append(uid).append(" = new ").append(parentTypeName).append("(").append(argsStr).append("); ");
        snippet.append("}");

        CtElement parent = candidate.getParent();
        if (parent instanceof spoon.reflect.code.CtBlock || parent instanceof spoon.reflect.code.CtCase) {
            CtCodeSnippetStatement stmt = context.getFactory().Code().createCodeSnippetStatement(snippet.toString());
            candidate.replace(stmt);
        } else if (parent instanceof spoon.reflect.code.CtLocalVariable<?> varDecl) {
            String varName = varDecl.getSimpleName();
            StringBuilder fullSnippet = new StringBuilder();
            fullSnippet.append(parentTypeName).append(" ").append(varName).append("; ");
            fullSnippet.append("if (").append(POLY_FLAG_FIELD).append(") { ");
            fullSnippet.append(varName).append(" = new ").append(subClassName).append("(").append(argsStr).append("); ");
            fullSnippet.append("} else { ");
            fullSnippet.append(varName).append(" = new ").append(parentTypeName).append("(").append(argsStr).append("); ");
            fullSnippet.append("}");
            varDecl.replace(context.getFactory().Code().createCodeSnippetStatement(fullSnippet.toString()));
        } else {
            CtConstructorCall<?> newCall = context.getFactory().Core().createConstructorCall();
            newCall.setType(context.getFactory().Type().createReference(subClassName));
            newCall.setArguments(candidate.getArguments());
            candidate.replace(newCall);
        }

        log.debug("[Mutation] Polymorphism: {} -> {} with volatile branch", targetTypeRef.getSimpleName(), subClassName);
        return true;
    }

    private boolean ensureSubclassExists(CtType<?> hostType, CtType<?> parentClass, String subClassName, MutationContext context) {
        if (hostType.getNestedType(subClassName) != null) {
            return true;
        }

        boolean isInterface = parentClass.isInterface();
        CtClass<Object> subClass = context.getFactory().Core().createClass();
        subClass.setSimpleName(subClassName);
        subClass.setModifiers(EnumSet.of(ModifierKind.PRIVATE, ModifierKind.STATIC));

        if (isInterface) {
            subClass.addSuperInterface(parentClass.getReference());
            CtConstructor<Object> defaultCons = context.getFactory().Core().createConstructor();
            defaultCons.setBody(context.getFactory().Core().createBlock());
            defaultCons.setModifiers(EnumSet.of(ModifierKind.PUBLIC));
            subClass.addConstructor(defaultCons);
        } else {
            subClass.setSuperclass(parentClass.getReference());
            if (!copyVisibleConstructors(subClass, parentClass, context)) {
                return false;
            }
        }

        for (CtMethod<?> abstractMethod : collectAbstractMethods(parentClass)) {
            CtMethod<?> implementation = buildDefaultImplementationMethod(abstractMethod, context);
            if (implementation == null) {
                return false;
            }
            subClass.addMethod(implementation);
        }

        CtMethod<?> overrideTarget = selectMethodToOverride(parentClass, context);
        if (overrideTarget != null && !isMethodAlreadyImplemented(subClass, overrideTarget)) {
            CtMethod<?> overriddenMethod = buildOverridingMethod(overrideTarget, context);
            if (overriddenMethod != null) {
                subClass.addMethod(overriddenMethod);
            }
        }

        hostType.addNestedType(subClass);
        return true;
    }

    private boolean copyVisibleConstructors(CtClass<Object> subClass, CtType<?> parentClass, MutationContext context) {
        Set<? extends CtConstructor<?>> constructors = ((CtClass<?>) parentClass).getConstructors();
        if (constructors.isEmpty()) {
            CtConstructor<Object> defaultCons = context.getFactory().Core().createConstructor();
            defaultCons.setBody(context.getFactory().Core().createBlock());
            defaultCons.setModifiers(EnumSet.of(ModifierKind.PUBLIC));
            subClass.addConstructor(defaultCons);
            return true;
        }

        boolean hasValidConstructor = false;
        for (CtConstructor<?> parentCons : constructors) {
            if (parentCons.isPrivate()) {
                continue;
            }

            CtConstructor<Object> subCons = context.getFactory().Core().createConstructor();
            subCons.setBody(context.getFactory().Core().createBlock());
            subCons.setModifiers(EnumSet.of(ModifierKind.PUBLIC));

            List<CtExpression<?>> superArgs = new ArrayList<>();
            for (CtParameter<?> p : parentCons.getParameters()) {
                CtParameter<?> newP = context.getFactory().Core().createParameter();
                newP.setSimpleName(p.getSimpleName());
                newP.setType(p.getType());
                subCons.addParameter(newP);
                superArgs.add(context.getFactory().Code().createVariableRead(newP.getReference(), false));
            }

            CtBlock<?> body = context.getFactory().Core().createBlock();
            body.addStatement(context.getFactory().Code().createCodeSnippetStatement(
                    "super(" + superArgs.stream().map(Object::toString).collect(Collectors.joining(", ")) + ")"
            ));
            subCons.setBody(body);
            subClass.addConstructor(subCons);
            hasValidConstructor = true;
        }
        return hasValidConstructor;
    }

    private List<CtMethod<?>> collectAbstractMethods(CtType<?> typeDecl) {
        Map<String, CtMethod<?>> methodsBySignature = new LinkedHashMap<>();
        collectAbstractMethodsRecursive(typeDecl, methodsBySignature);
        return new ArrayList<>(methodsBySignature.values());
    }

    private void collectAbstractMethodsRecursive(CtType<?> typeDecl, Map<String, CtMethod<?>> methodsBySignature) {
        if (typeDecl == null) {
            return;
        }

        if (typeDecl instanceof CtClass<?> ctClass && ctClass.getSuperclass() != null) {
            collectAbstractMethodsRecursive(ctClass.getSuperclass().getTypeDeclaration(), methodsBySignature);
        }
        for (CtTypeReference<?> superInterface : typeDecl.getSuperInterfaces()) {
            collectAbstractMethodsRecursive(superInterface.getTypeDeclaration(), methodsBySignature);
        }

        for (CtMethod<?> method : typeDecl.getMethods()) {
            if (method.isStatic() || method.isPrivate()) {
                continue;
            }
            String signature = buildMethodSignature(method);
            if (method.isAbstract()) {
                methodsBySignature.put(signature, method);
            } else {
                methodsBySignature.remove(signature);
            }
        }
    }

    private CtMethod<?> selectMethodToOverride(CtType<?> typeDecl, MutationContext context) {
        List<CtMethod<?>> candidates = typeDecl.getMethods().stream()
                .filter(m -> !m.isFinal() && !m.isStatic() && !m.isAbstract())
                .filter(m -> m.isPublic() || m.isProtected())
                .filter(m -> !m.getSimpleName().equals("getClass"))
                .filter(m -> !m.getSimpleName().equals("wait"))
                .filter(m -> !m.getSimpleName().equals("notify"))
                .filter(m -> !m.getSimpleName().equals("notifyAll"))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(context.getRandom().nextInt(candidates.size()));
    }

    private CtMethod<?> buildDefaultImplementationMethod(CtMethod<?> source, MutationContext context) {
        try {
            CtMethod<?> implementation = createBaseMethodSignature(source, context);
            boolean isVoid = "void".equals(source.getType().getQualifiedName());
            StringBuilder body = new StringBuilder();
            body.append("if ").append(buildNeverTrueReferenceGuard("this")).append(" { ");
            if (!isVoid) {
                body.append("return ").append(getDefaultValue(source.getType())).append("; ");
            } else {
                body.append("return; ");
            }
            body.append("} ");
            if (!isVoid) {
                body.append("return ").append(getDefaultValue(source.getType())).append(";");
            }

            CtBlock<?> block = context.getFactory().Core().createBlock();
            block.addStatement(context.getFactory().Code().createCodeSnippetStatement(stripTrailingSemicolons(body.toString())));
            implementation.setBody(block);
            return implementation;
        } catch (Exception e) {
            log.debug("[Polymorphism] Failed to build default implementation: {}", e.getMessage());
            return null;
        }
    }

    private CtMethod<?> buildOverridingMethod(CtMethod<?> source, MutationContext context) {
        try {
            CtMethod<?> override = createBaseMethodSignature(source, context);
            boolean isVoid = "void".equals(source.getType().getQualifiedName());
            String args = source.getParameters().stream()
                    .map(CtNamedElement::getSimpleName)
                    .collect(Collectors.joining(", "));

            StringBuilder body = new StringBuilder();
            body.append("if ").append(buildNeverTrueReferenceGuard("this")).append(" { ");
            if (isVoid) {
                body.append("return; ");
            } else {
                body.append("return ").append(getDefaultValue(source.getType())).append("; ");
            }
            body.append("} ");
            if (!isVoid) {
                body.append("return ");
            }
            body.append("super.").append(source.getSimpleName()).append("(").append(args).append(");");

            CtBlock<?> block = context.getFactory().Core().createBlock();
            block.addStatement(context.getFactory().Code().createCodeSnippetStatement(stripTrailingSemicolons(body.toString())));
            override.setBody(block);
            return override;
        } catch (Exception e) {
            log.debug("[Polymorphism] Failed to build override method: {}", e.getMessage());
            return null;
        }
    }

    private CtMethod<?> createBaseMethodSignature(CtMethod<?> source, MutationContext context) {
        CtMethod<?> method = context.getFactory().Core().createMethod();
        method.setSimpleName(source.getSimpleName());
        method.setType(source.getType());
        method.setModifiers(source.isPublic()
                ? EnumSet.of(ModifierKind.PUBLIC)
                : EnumSet.of(ModifierKind.PROTECTED));

        for (CtParameter<?> p : source.getParameters()) {
            CtParameter<?> newP = context.getFactory().Core().createParameter();
            newP.setSimpleName(p.getSimpleName());
            newP.setType(p.getType());
            method.addParameter(newP);
        }

        for (CtTypeReference<? extends Throwable> t : source.getThrownTypes()) {
            method.addThrownType(t.clone());
        }

        method.addAnnotation(context.getFactory().createAnnotation(
                context.getFactory().createCtTypeReference(Override.class)));
        return method;
    }

    private boolean isMethodAlreadyImplemented(CtClass<?> subClass, CtMethod<?> method) {
        return subClass.getMethods().stream()
                .map(this::buildMethodSignature)
                .anyMatch(buildMethodSignature(method)::equals);
    }

    private String buildMethodSignature(CtMethod<?> method) {
        return method.getSimpleName() + "(" + method.getParameters().stream()
                .map(parameter -> getCorrectSourceClassName(parameter.getType()))
                .collect(Collectors.joining(",")) + ")";
    }

    private String stripTrailingSemicolons(String code) {
        String trimmed = code == null ? "" : code.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private void ensurePolyFlagField(CtType<?> hostType, MutationContext context) {
        if (hostType.getField(POLY_FLAG_FIELD) != null) {
            return;
        }

        try {
            var field = context.getFactory().Core().<Boolean>createField();
            field.setSimpleName(POLY_FLAG_FIELD);
            field.setType(context.getFactory().Type().booleanPrimitiveType());
            field.setModifiers(EnumSet.of(ModifierKind.STATIC, ModifierKind.VOLATILE));
            field.setDefaultExpression(context.getFactory().Code().createLiteral(true));
            hostType.addFieldAtTop(field);
        } catch (Exception e) {
            log.debug("[Polymorphism] Failed to inject volatile flag: {}", e.getMessage());
        }
    }

    private String getDefaultValue(CtTypeReference<?> type) {
        if (type == null) {
            return "null";
        }
        if (type.isPrimitive()) {
            return switch (type.getSimpleName()) {
                case "int", "byte", "short", "char" -> "0";
                case "long" -> "0L";
                case "float" -> "0.0f";
                case "double" -> "0.0";
                case "boolean" -> "false";
                default -> "0";
            };
        }
        return "null";
    }
}

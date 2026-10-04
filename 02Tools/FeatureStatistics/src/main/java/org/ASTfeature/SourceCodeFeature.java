package org.ASTfeature;

public enum SourceCodeFeature {
    // Statement and control-flow shape.
    assignmentStmt,
    ifStmt,
    invocationStmt,
    switchStmt,
    tryCatchStmt,
    loop,

    // Operators and expression shape.
    arithmeticOperator,
    shiftOperator,
    compareOperator,
    logicalOperator,
    unaryOperator,
    instanceofOperator,

    // Basic and built-in type references.
    booleanType,
    byteType,
    charType,
    primitiveType,
    integerType,
    floatType,
    doubleType,
    longType,
    shortType,
    nullType,

    // Boxed primitive types (aggregate only; individual wrappers are not distinguished).
    wrapperType,

    // Array-related source structure.
    arrayType,
    arrayAccess,

    // Generic type-system usage.
    genericType,
    parameterizedType,
    typeParameter,
    boundedTypeParameter,
    wildcardType,
    boundedWildcardType,
    genericMethod,

    // Type declaration and abstraction style.
    interfaceType,
    abstractType,
    enumType,
    nestedType,
    hierarchy,

    // Functional style.
    lambda,

    // Synchronization context.
    synchronizedAccess,

    // JVM runtime type mechanisms.
    classLiteral,
    reflection,
    reflectiveMethodInvocation,
    methodHandle,
    customClassLoader,

    // Type conversions. TypeCast is the aggregate source-level conversion
    // feature; its detail uses explicit:/implicit: to preserve the source form.
    // primitiveConversion / boxingUnboxing describe orthogonal conversion kinds
    // and may co-occur with TypeCast for the same source construct.
    TypeCast,
    primitiveConversion,
    boxingUnboxing
}

/*
 * Thrifty
 *
 * Copyright (c) Microsoft Corporation
 *
 * All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * THIS CODE IS PROVIDED ON AN  *AS IS* BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED, INCLUDING
 * WITHOUT LIMITATION ANY IMPLIED WARRANTIES OR CONDITIONS OF TITLE,
 * FITNESS FOR A PARTICULAR PURPOSE, MERCHANTABLITY OR NON-INFRINGEMENT.
 *
 * See the Apache Version 2.0 License for specific language governing permissions and limitations under the License.
 */
package com.microsoft.thrifty.gen;

import com.google.common.base.Strings;
import com.microsoft.thrifty.TType;
import com.microsoft.thrifty.ThriftException;
import com.microsoft.thrifty.schema.BuiltinType;
import com.microsoft.thrifty.schema.EnumType;
import com.microsoft.thrifty.schema.Field;
import com.microsoft.thrifty.schema.ListType;
import com.microsoft.thrifty.schema.MapType;
import com.microsoft.thrifty.schema.NamespaceScope;
import com.microsoft.thrifty.schema.ServiceMethod;
import com.microsoft.thrifty.schema.ServiceType;
import com.microsoft.thrifty.schema.SetType;
import com.microsoft.thrifty.schema.StructType;
import com.microsoft.thrifty.schema.ThriftType;
import com.microsoft.thrifty.schema.TypedefType;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.NameAllocator;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.lang.model.element.Modifier;

final class ServiceBuilder {
    private final TypeResolver typeResolver;
    private final ConstantBuilder constantBuilder;
    private final FieldNamer fieldNamer;

    ServiceBuilder(TypeResolver typeResolver, ConstantBuilder constantBuilder, FieldNamer fieldNamer) {
        this.typeResolver = typeResolver;
        this.constantBuilder = constantBuilder;
        this.fieldNamer = fieldNamer;
    }

    TypeSpec buildServiceInterface(ServiceType service) {
        TypeSpec.Builder serviceSpec = TypeSpec.interfaceBuilder(service.name())
                .addModifiers(Modifier.PUBLIC);

        if (!Strings.isNullOrEmpty(service.documentation())) {
            serviceSpec.addJavadoc(service.documentation());
        }

        if (service.isDeprecated()) {
            serviceSpec.addAnnotation(AnnotationSpec.builder(Deprecated.class).build());
        }

        if (service.extendsService() != null) {
            ThriftType superType = service.extendsService().getTrueType();
            TypeName superTypeName = typeResolver.getJavaClass(superType);
            serviceSpec.addSuperinterface(superTypeName);
        }

        for (ServiceMethod method : service.methods()) {
            if (!method.annotations().containsKey("whitelisted")) {
                continue;
            }
            NameAllocator allocator = new NameAllocator();
            int tag = 0;

            MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(method.name())
                    .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT);

            if (method.hasJavadoc()) {
                methodBuilder.addJavadoc(method.documentation());
            }

            for (Field field : method.parameters()) {
                String fieldName = fieldNamer.getName(field);
                String name = allocator.newName(fieldName, ++tag);
                ThriftType paramType = field.type().getTrueType();
                TypeName paramTypeName = typeResolver.getJavaClass(paramType);

                methodBuilder.addParameter(paramTypeName, name);

            }
            methodBuilder.addAnnotation(retrofit("/uber", RestMethodResolver.GET));

            ThriftType returnType = method.returnType();
            TypeName returnTypeName;
            if (returnType.equals(BuiltinType.VOID)) {
                returnTypeName = TypeName.VOID.box();
            } else {
                returnTypeName = typeResolver.getJavaClass(returnType.getTrueType());
            }

            TypeName callbackInterfaceName = ParameterizedTypeName.get(
                    TypeNames.SINGLE_CALLBACK, returnTypeName);

            methodBuilder.returns(callbackInterfaceName);

            serviceSpec.addMethod(methodBuilder.build());
        }

        return serviceSpec.build();
    }

    TypeSpec buildService(ServiceType service, TypeSpec serviceInterface) {
        String packageName = service.getNamespaceFor(NamespaceScope.JAVA);
        TypeName interfaceTypeName = ClassName.get(packageName, serviceInterface.name);
        TypeSpec.Builder builder = TypeSpec.classBuilder(service.name() + "Client")
                .addModifiers(Modifier.PUBLIC)
                .addSuperinterface(interfaceTypeName);

        if (service.extendsService() != null) {
            ThriftType type = service.extendsService();
            String typeName = type.name() + "Client";
            String ns = ((ServiceType) type).getNamespaceFor(NamespaceScope.JAVA);
            TypeName javaClass = ClassName.get(ns, typeName);
            builder.superclass(javaClass);
        } else {
            builder.superclass(TypeNames.SERVICE_CLIENT_BASE);
        }

        builder.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(TypeNames.PROTOCOL, "protocol")
                .addParameter(TypeNames.SERVICE_CLIENT_LISTENER, "listener")
                .addStatement("super(protocol, listener)")
                .build());

        int i = 0;
        for (MethodSpec methodSpec : serviceInterface.methodSpecs) {
            ServiceMethod serviceMethod = service.methods().get(i++);
            TypeSpec call = buildCallSpec(serviceMethod);
            builder.addType(call);

            MethodSpec.Builder meth = MethodSpec.methodBuilder(methodSpec.name)
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .addParameters(methodSpec.parameters)
                    .addExceptions(methodSpec.exceptions);

            CodeBlock.Builder body = CodeBlock.builder()
                    .add("$[this.enqueue(new $N(", call);

            boolean first = true;
            for (ParameterSpec parameter : methodSpec.parameters) {
                if (first) {
                    body.add("$N", parameter.name);
                    first = false;
                } else {
                    body.add(", $N", parameter.name);
                }
            }

            body.add("));\n$]");

            meth.addCode(body.build());

            builder.addMethod(meth.build());
        }

        return builder.build();
    }

    private TypeSpec buildCallSpec(ServiceMethod method) {
        String name = method.name();
        if (Character.isLowerCase(name.charAt(0))) {
            if (name.length() > 1) {
                name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            } else {
                name = name.toUpperCase(Locale.US);
            }

            name += "Call";
        }

        ThriftType returnType = method.returnType();
        TypeName returnTypeName = returnType.equals(BuiltinType.VOID)
                ? TypeName.VOID.box()
                : typeResolver.getJavaClass(returnType.getTrueType());
        TypeName callbackTypeName = ParameterizedTypeName.get(TypeNames.SERVICE_CALLBACK, returnTypeName);
        TypeName superclass = ParameterizedTypeName.get(TypeNames.SERVICE_METHOD_CALL, returnTypeName);

        boolean hasReturnType = !returnTypeName.equals(TypeName.VOID.box());

        TypeSpec.Builder callBuilder = TypeSpec.classBuilder(name)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .superclass(superclass);

        // Set up fields
        for (Field field : method.parameters()) {
            TypeName javaType = typeResolver.getJavaClass(field.type().getTrueType());

            callBuilder.addField(FieldSpec.builder(javaType, fieldNamer.getName(field))
                    .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                    .build());
        }

        // Ctor
        callBuilder.addMethod(buildCallCtor(method, callbackTypeName));

        // Send
        callBuilder.addMethod(buildSendMethod(method));

        // Receive
        callBuilder.addMethod(buildReceiveMethod(method, hasReturnType));

        return callBuilder.build();
    }

    private MethodSpec buildCallCtor(ServiceMethod method, TypeName callbackTypeName) {
        NameAllocator allocator = new NameAllocator();
        AtomicInteger scope = new AtomicInteger(0);
        MethodSpec.Builder ctor = MethodSpec.constructorBuilder()
                .addStatement(
                        "super($S, $T.$L, callback)",
                        method.name(),
                        TypeNames.TMESSAGE_TYPE,
                        method.oneWay() ? "ONEWAY" : "CALL");

        for (Field field : method.parameters()) {
            String fieldName = fieldNamer.getName(field);
            TypeName javaType = typeResolver.getJavaClass(field.type().getTrueType());

            ctor.addParameter(javaType, fieldName);

            if (field.required() && field.defaultValue() == null) {
                ctor.addStatement("if ($L == null) throw new NullPointerException($S)", fieldName, fieldName);
                ctor.addStatement("this.$1L = $1L", fieldName);
            } else if (field.defaultValue() != null) {
                ctor.beginControlFlow("if ($L != null)", fieldName);
                ctor.addStatement("this.$1L = $1L", fieldName);
                ctor.nextControlFlow("else");

                CodeBlock.Builder init = CodeBlock.builder();
                constantBuilder.generateFieldInitializer(
                        init,
                        allocator,
                        scope,
                        "this." + fieldName,
                        field.type().getTrueType(),
                        field.defaultValue(),
                        false);
                ctor.addCode(init.build());

                ctor.endControlFlow();
            } else {
                ctor.addStatement("this.$1L = $1L", fieldName);
            }
        }

        ctor.addParameter(callbackTypeName, "callback");

        return ctor.build();
    }

    private MethodSpec buildSendMethod(ServiceMethod method) {
        MethodSpec.Builder send = MethodSpec.methodBuilder("send")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .addParameter(TypeNames.PROTOCOL, "protocol")
                .addException(TypeNames.IO_EXCEPTION);

        send.addStatement("protocol.writeStructBegin($S)", "args");

        for (Field field : method.parameters()) {
            String fieldName = fieldNamer.getName(field);
            boolean optional = !field.required();
            final ThriftType tt = field.type().getTrueType();
            byte typeCode = typeResolver.getTypeCode(tt);

            // Enums are written/read as i32
            if (typeCode == TType.ENUM) {
                typeCode = TType.I32;
            }

            if (optional) {
                send.beginControlFlow("if (this.$L != null)", fieldName);
            }

            send.addStatement("protocol.writeFieldBegin($S, $L, $T.$L)",
                    field.name(), // send the Thrift name, not the fieldNamer output
                    field.id(),
                    TypeNames.TTYPE,
                    TypeNames.getTypeCodeName(typeCode));

            tt.accept(new GenerateWriterVisitor(typeResolver, send, "protocol", "this", fieldName));

            send.addStatement("protocol.writeFieldEnd()");

            if (optional) {
                send.endControlFlow();
            }
        }

        send.addStatement("protocol.writeFieldStop()");
        send.addStatement("protocol.writeStructEnd()");

        return send.build();
    }

    private MethodSpec buildReceiveMethod(ServiceMethod method, boolean hasReturnType) {
        final MethodSpec.Builder recv = MethodSpec.methodBuilder("receive")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PROTECTED)
                .addParameter(TypeNames.PROTOCOL, "protocol")
                .addParameter(TypeNames.MESSAGE_METADATA, "metadata")
                .addException(TypeNames.EXCEPTION);

        if (hasReturnType) {
            TypeName retTypeName = typeResolver.getJavaClass(method.returnType().getTrueType());
            recv.returns(retTypeName);
            recv.addStatement("$T result = null", retTypeName);
        } else {
            recv.returns(TypeName.VOID.box());
        }

        for (Field field : method.exceptions()) {
            String fieldName = fieldNamer.getName(field);
            TypeName exceptionTypeName = typeResolver.getJavaClass(field.type().getTrueType());
            recv.addStatement("$T $L = null", exceptionTypeName, fieldName);
        }

        recv.addStatement("protocol.readStructBegin()")
                .beginControlFlow("while (true)")
                .addStatement("$T field = protocol.readFieldBegin()", TypeNames.FIELD_METADATA)
                .beginControlFlow("if (field.typeId == $T.STOP)", TypeNames.TTYPE)
                .addStatement("break")
                .endControlFlow()
                .beginControlFlow("switch (field.fieldId)");

        if (hasReturnType) {
            ThriftType type = method.returnType().getTrueType();
            recv.beginControlFlow("case 0:");

            new GenerateReaderVisitor(typeResolver, recv, "result", type) {
                @Override
                protected void useReadValue(String localName) {
                    recv.addStatement("result = $N", localName);
                }
            }.generate();

            recv.endControlFlow();
            recv.addStatement("break");
        }

        for (Field field : method.exceptions()) {
            final String fieldName = fieldNamer.getName(field);
            recv.beginControlFlow("case $L:", field.id());

            new GenerateReaderVisitor(typeResolver, recv, fieldName, field.type().getTrueType()) {
                @Override
                protected void useReadValue(String localName) {
                    recv.addStatement("$N = $N", fieldName, localName);
                }
            }.generate();

            recv.endControlFlow();
            recv.addStatement("break");
        }

        recv.addStatement("default: $T.skip(protocol, field.typeId); break", TypeNames.PROTO_UTIL);
        recv.endControlFlow(); // end switch
        recv.addStatement("protocol.readFieldEnd()");
        recv.endControlFlow(); // end while
        recv.addStatement("protocol.readStructEnd()");

        boolean isInControlFlow = false;
        if (hasReturnType) {
            recv.beginControlFlow("if (result != null)");
            recv.addStatement("return result");
            isInControlFlow = true;
        }

        for (Field field : method.exceptions()) {
            String fieldName = fieldNamer.getName(field);
            if (isInControlFlow) {
                recv.nextControlFlow("else if ($L != null)", fieldName);
            } else {
                recv.beginControlFlow("if ($L != null)", fieldName);
                isInControlFlow = true;
            }
            recv.addStatement("throw $L", fieldName);
        }

        if (isInControlFlow) {
            recv.nextControlFlow("else");
        }

        if (hasReturnType) {
            // In this branch, no return type was received, nor were
            // any declared exceptions received.  This is a failure.
            recv.addStatement(
                    "throw new $T($T.$L, $S)",
                    TypeNames.THRIFT_EXCEPTION,
                    TypeNames.THRIFT_EXCEPTION_KIND,
                    ThriftException.Kind.MISSING_RESULT.name(),
                    "Missing result");
        } else {
            // No return is expected, and no exceptions were received.
            // Success!
            recv.addStatement("return null");
        }

        if (isInControlFlow) {
            recv.endControlFlow();
        }

        return recv.build();
    }

    private AnnotationSpec retrofit(String url, RestMethodResolver restMethodResolver) {
        AnnotationSpec.Builder retro = AnnotationSpec.builder(restMethodResolver.getRetrofitClass());
        retro.addMember("value", "$S", url);
        return retro.build();
    }

    void populateRequestType(ServiceType service, Set<TypeName> requestTypeNames, Set<TypeName> whitelistedTypeNames) {
        for (ServiceMethod method : service.methods()) {
            if (!method.annotations().containsKey("whitelisted")) {
                continue;
            }
            for (Field field : method.parameters()) {
                ThriftType paramType = field.type().getTrueType();
                if (paramType instanceof StructType) {
                    requestTypes((StructType) paramType, requestTypeNames);
                }
            }
            ThriftType returnType = method.returnType().getTrueType();
            if (returnType instanceof StructType) {
                reachableNode((StructType) returnType, whitelistedTypeNames);
            } else if (returnType instanceof ListType) {
                ListType listType = (ListType) returnType;
                ThriftType elementType = listType.elementType().getTrueType();
                if (elementType instanceof StructType) {
                    reachableNode((StructType) elementType, whitelistedTypeNames);
                }
            }
        }
    }

    private void requestTypes(StructType structType, Set<TypeName> typeNames) {
        TypeName paramTypeName = typeResolver.getJavaClass(structType);
        Logger.getGlobal().info(paramTypeName.toString());
        if (typeNames.contains(paramTypeName)) {
            return;
        }
        typeNames.add(paramTypeName);
        for (Field field : structType.fields()) {
            ThriftType thriftType = field.type().getTrueType();
            if (thriftType instanceof StructType) {
                requestTypes((StructType) thriftType, typeNames);
            }
        }
    }

    private void reachableNode(StructType structType, Set<TypeName> typeNames) {
        TypeName paramTypeName = typeResolver.getJavaClass(structType);
        Logger.getGlobal().info(paramTypeName.toString());
        if (typeNames.contains(paramTypeName)) {
            return;
        }
        typeNames.add(paramTypeName);
        for (Field field : structType.fields()) {
            ThriftType thriftType = field.type().getTrueType();
            if (thriftType instanceof StructType) {
                reachableNode((StructType) thriftType, typeNames);
            }
        }
    }

    public void reachableVisitorExternal(ServiceType serviceType, Set<TypeName> requestTypeNames, Set<TypeName> whitelistedTypeNames) {
        reachableVisitor(serviceType, whitelistedTypeNames, false);
        reachableVisitor(serviceType, requestTypeNames, true);
    }

    private Set<TypeName> reachableVisitor(ThriftType thriftType, Set<TypeName> typeNames, boolean requestTypes) {
        TypeName paramTypeName = typeResolver.getJavaClass(thriftType);
        Logger.getGlobal().info(paramTypeName.toString());
        if (typeNames.contains(paramTypeName)) {
            return Collections.emptySet();
        }
        typeNames.add(paramTypeName);
        return thriftType.accept(new ThriftType.Visitor<Set<TypeName>>() {
            @Override public Set<TypeName> visitVoid(BuiltinType voidType) {
                return Collections.emptySet();
            }

            @Override public Set<TypeName> visitBool(BuiltinType boolType) {
                return Collections.emptySet();
            }

            @Override public Set<TypeName> visitByte(BuiltinType byteType) {
                return Collections.emptySet();
            }

            @Override public Set<TypeName> visitI16(BuiltinType i16Type) {
                return Collections.emptySet();
            }

            @Override public Set<TypeName> visitI32(BuiltinType i32Type) {
                return Collections.emptySet();
            }

            @Override public Set<TypeName> visitI64(BuiltinType i64Type) {
                return Collections.emptySet();
            }

            @Override public Set<TypeName> visitDouble(BuiltinType doubleType) {
                return Collections.emptySet();
            }

            @Override public Set<TypeName> visitString(BuiltinType stringType) {
                return Collections.emptySet();
            }

            @Override public Set<TypeName> visitBinary(BuiltinType binaryType) {
                return Collections.emptySet();
            }

            @Override public Set<TypeName> visitEnum(EnumType enumType) {
                return Collections.emptySet();
            }

            @Override public Set<TypeName> visitList(ListType listType) {
                ThriftType elementType = listType.elementType().getTrueType();
                return reachableVisitor(elementType, typeNames, requestTypes);
            }

            @Override public Set<TypeName> visitSet(SetType setType) {
                ThriftType elementType = setType.elementType().getTrueType();
                return reachableVisitor(elementType, typeNames, requestTypes);
            }

            @Override public Set<TypeName> visitMap(MapType mapType) {
                Set<TypeName> typeNameSet = new HashSet<>();
                ThriftType keyType = mapType.keyType().getTrueType();
                ThriftType valueType = mapType.valueType().getTrueType();
                typeNameSet.addAll(reachableVisitor(keyType, typeNames, requestTypes));
                typeNameSet.addAll(reachableVisitor(valueType, typeNames, requestTypes));
                return typeNameSet;
            }

            @Override public Set<TypeName> visitStruct(StructType structType) {
                Set<TypeName> typeNameSet = new HashSet<>();
                for (Field field : structType.fields()) {
                    ThriftType thriftType = field.type().getTrueType();
                    typeNameSet.addAll(reachableVisitor(thriftType, typeNames, requestTypes));
                }
                return typeNameSet;
            }

            @Override public Set<TypeName> visitTypedef(TypedefType typedefType) {
                return Collections.emptySet();
            }

            @Override public Set<TypeName> visitService(ServiceType serviceType) {
                Set<TypeName> typeNameSet = new HashSet<>();
                for (ServiceMethod method : serviceType.methods()) {
                    if (!method.annotations().containsKey("whitelisted")) {
                        continue;
                    }
                    for (Field field : method.parameters()) {
                        ThriftType paramType = field.type().getTrueType();
                        typeNameSet.addAll(reachableVisitor(paramType, typeNames, requestTypes));
                    }
                    if (requestTypes) {
                        continue;
                    }
                    ThriftType returnType = method.returnType().getTrueType();
                    typeNameSet.addAll(reachableVisitor(returnType, typeNames, false));
                }
                return typeNameSet;
            }
        });
    }
}

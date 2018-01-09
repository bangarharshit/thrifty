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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.microsoft.thrifty.Obfuscated;
import com.microsoft.thrifty.Redacted;
import com.microsoft.thrifty.ThriftField;
import com.microsoft.thrifty.compiler.spi.TypeProcessor;
import com.microsoft.thrifty.schema.BuiltinType;
import com.microsoft.thrifty.schema.Constant;
import com.microsoft.thrifty.schema.EnumMember;
import com.microsoft.thrifty.schema.EnumType;
import com.microsoft.thrifty.schema.Field;
import com.microsoft.thrifty.schema.FieldNamingPolicy;
import com.microsoft.thrifty.schema.ListType;
import com.microsoft.thrifty.schema.Location;
import com.microsoft.thrifty.schema.MapType;
import com.microsoft.thrifty.schema.NamespaceScope;
import com.microsoft.thrifty.schema.Schema;
import com.microsoft.thrifty.schema.ServiceType;
import com.microsoft.thrifty.schema.SetType;
import com.microsoft.thrifty.schema.StructType;
import com.microsoft.thrifty.schema.ThriftType;
import com.microsoft.thrifty.schema.TypedefType;
import com.microsoft.thrifty.schema.UserType;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.NameAllocator;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import javax.lang.model.element.Modifier;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

public final class ThriftyCodeGenerator {
    private static final String FILE_COMMENT =
            "Automatically generated by the Thrifty compiler; do not edit!\n"
            + "Generated on: ";

    public static final String ADAPTER_FIELDNAME = "ADAPTER";

    private static final DateTimeFormatter DATE_FORMATTER =
            ISODateTimeFormat.dateTime().withZoneUTC();

    private final TypeResolver typeResolver = new TypeResolver();
    private final Schema schema;
    private final FieldNamer fieldNamer;
    private final ConstantBuilder constantBuilder;
    private final ServiceBuilder serviceBuilder;
    private TypeProcessor typeProcessor;
    private boolean emitAndroidAnnotations;
    private boolean emitParcelable;
    private boolean emitFileComment = true;

    public ThriftyCodeGenerator(Schema schema) {
        this(schema, FieldNamingPolicy.DEFAULT);
    }

    public ThriftyCodeGenerator(Schema schema, FieldNamingPolicy namingPolicy) {
        this(
                schema,
                namingPolicy,
                ClassName.get(ArrayList.class),
                ClassName.get(HashSet.class),
                ClassName.get(HashMap.class));
    }

    private ThriftyCodeGenerator(
            Schema schema,
            FieldNamingPolicy namingPolicy,
            ClassName listClassName,
            ClassName setClassName,
            ClassName mapClassName) {

        Preconditions.checkNotNull(schema, "schema");
        Preconditions.checkNotNull(namingPolicy, "namingPolicy");
        Preconditions.checkNotNull(listClassName, "listClassName");
        Preconditions.checkNotNull(setClassName, "setClassName");
        Preconditions.checkNotNull(mapClassName, "mapClassName");

        this.schema = schema;
        this.fieldNamer = new FieldNamer(namingPolicy);
        typeResolver.setListClass(listClassName);
        typeResolver.setSetClass(setClassName);
        typeResolver.setMapClass(mapClassName);

        constantBuilder = new ConstantBuilder(typeResolver, schema);
        serviceBuilder = new ServiceBuilder(typeResolver, constantBuilder, fieldNamer);
    }

    public ThriftyCodeGenerator withListType(String listClassName) {
        typeResolver.setListClass(ClassName.bestGuess(listClassName));
        return this;
    }

    public ThriftyCodeGenerator withSetType(String setClassName) {
        typeResolver.setSetClass(ClassName.bestGuess(setClassName));
        return this;
    }

    public ThriftyCodeGenerator withMapType(String mapClassName) {
        typeResolver.setMapClass(ClassName.bestGuess(mapClassName));
        return this;
    }

    public ThriftyCodeGenerator emitAndroidAnnotations(boolean shouldEmit) {
        emitAndroidAnnotations = shouldEmit;
        return this;
    }

    public ThriftyCodeGenerator emitParcelable(boolean emitParcelable) {
        this.emitParcelable = emitParcelable;
        return this;
    }

    public ThriftyCodeGenerator emitFileComment(boolean emitFileComment) {
        this.emitFileComment = emitFileComment;
        return this;
    }

    public ThriftyCodeGenerator usingTypeProcessor(TypeProcessor typeProcessor) {
        this.typeProcessor = typeProcessor;
        return this;
    }

    public void generate(final Path directory) throws IOException {
        generate(file -> {
            if (file != null) {
                file.writeTo(directory);
            }
        });
    }

    public void generate(final File directory) throws IOException {
        generate(file -> {
            if (file != null) {
                file.writeTo(directory);
            }
        });
    }

    public void generate(final Appendable appendable) throws IOException {
        generate(file -> {
            if (file != null) {
                file.writeTo(appendable);
            }
        });
    }

    public ImmutableList<JavaFile> generateTypes() {
        ImmutableList.Builder<JavaFile> generatedTypes = ImmutableList.builder();

        for (EnumType type : schema.enums()) {
            TypeSpec spec = buildEnum(type);
            JavaFile file = assembleJavaFile(type, spec);
            if (file != null) {
                generatedTypes.add(file);
            }
        }

        for (StructType type : schema.structs()) {
            TypeSpec spec = buildStruct(type);
            JavaFile file = assembleJavaFile(type, spec);
            if (file != null) {
                generatedTypes.add(file);
            }
        }

        for (StructType type : schema.exceptions()) {
            TypeSpec spec = buildStruct(type);
            JavaFile file = assembleJavaFile(type, spec);
            if (file != null) {
                generatedTypes.add(file);
            }
        }

        for (StructType type : schema.unions()) {
            TypeSpec spec = buildStruct(type);
            JavaFile file = assembleJavaFile(type, spec);
            if (file != null) {
                generatedTypes.add(file);
            }
        }

        Multimap<String, Constant> constantsByPackage = HashMultimap.create();
        for (Constant constant : schema.constants()) {
            constantsByPackage.put(constant.getNamespaceFor(NamespaceScope.JAVA), constant);
        }

        for (Map.Entry<String, Collection<Constant>> entry : constantsByPackage.asMap().entrySet()) {
            String packageName = entry.getKey();
            Collection<Constant> values = entry.getValue();
            TypeSpec spec = buildConst(values);
            JavaFile file = assembleJavaFile(packageName, spec);
            if (file != null) {
                generatedTypes.add(file);
            }
        }

        for (ServiceType type : schema.services()) {
            TypeSpec spec = serviceBuilder.buildServiceInterface(type);
            JavaFile file = assembleJavaFile(type, spec);
            if (file == null) {
                continue;
            }

            generatedTypes.add(file);
        }

        return generatedTypes.build();
    }

    private interface FileWriter {
        void write(@Nullable JavaFile file) throws IOException;
    }

    private void generate(FileWriter writer) throws IOException {
        for (JavaFile file : generateTypes()) {
            writer.write(file);
        }
    }

    @Nullable
    private JavaFile assembleJavaFile(UserType named, TypeSpec spec) {
        String packageName = named.getNamespaceFor(NamespaceScope.JAVA);
        if (Strings.isNullOrEmpty(packageName)) {
            throw new IllegalArgumentException("A Java package name must be given for java code generation");
        }

        return assembleJavaFile(packageName, spec, named.location());
    }

    @Nullable
    private JavaFile assembleJavaFile(String packageName, TypeSpec spec) {
        return assembleJavaFile(packageName, spec, null);
    }

    @Nullable
    private JavaFile assembleJavaFile(String packageName, TypeSpec spec, Location location) {
        if (typeProcessor != null) {
            spec = typeProcessor.process(spec);
            if (spec == null) {
                return null;
            }
        }

        JavaFile.Builder file = JavaFile.builder(packageName, spec)
                .skipJavaLangImports(true);

        if (emitFileComment) {
            file.addFileComment(FILE_COMMENT + DATE_FORMATTER.print(System.currentTimeMillis()));

            if (location != null) {
                file.addFileComment("\nSource: $L", location);
            }
        }

        return file.build();
    }

    @VisibleForTesting
    @SuppressWarnings("WeakerAccess")
    TypeSpec buildStruct(StructType type) {
        String packageName = type.getNamespaceFor(NamespaceScope.JAVA);
        ClassName structTypeName = ClassName.get(packageName, type.name());
        ClassName builderTypeName = structTypeName.nestedClass("Builder");

        TypeSpec.Builder structBuilder = TypeSpec.classBuilder(type.name())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

        if (type.hasJavadoc()) {
            structBuilder.addJavadoc("$L", type.documentation());
        }

        if (type.isException()) {
            structBuilder.superclass(Exception.class);
        }

        if (type.isDeprecated()) {
            structBuilder.addAnnotation(AnnotationSpec.builder(Deprecated.class).build());
        }

        TypeSpec builderSpec = builderFor(type, structTypeName, builderTypeName);

        if (emitParcelable) {
            generateParcelable(type, structTypeName, structBuilder);
        }

        structBuilder.addType(builderSpec);

        MethodSpec.Builder ctor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameter(builderTypeName, "builder");

        for (Field field : type.fields()) {

            String name = fieldNamer.getName(field);
            ThriftType fieldType = field.type();
            ThriftType trueType = fieldType.getTrueType();
            TypeName fieldTypeName = typeResolver.getJavaClass(trueType);

            // Define field
            FieldSpec.Builder fieldBuilder = FieldSpec.builder(fieldTypeName, name)
                    .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

            if (emitAndroidAnnotations) {
                ClassName anno = field.required() ? TypeNames.NOT_NULL : TypeNames.NULLABLE;
                fieldBuilder.addAnnotation(anno);
            }

            if (field.hasJavadoc()) {
                fieldBuilder = fieldBuilder.addJavadoc("$L", field.documentation());
            }

            if (field.isRedacted()) {
                fieldBuilder = fieldBuilder.addAnnotation(AnnotationSpec.builder(Redacted.class).build());
            }

            if (field.isObfuscated()) {
                fieldBuilder = fieldBuilder.addAnnotation(AnnotationSpec.builder(Obfuscated.class).build());
            }

            if (field.isDeprecated()) {
                fieldBuilder = fieldBuilder.addAnnotation(AnnotationSpec.builder(Deprecated.class).build());
            }

            structBuilder.addField(fieldBuilder.build());

            // Update the struct ctor

            CodeBlock.Builder assignment = CodeBlock.builder().add("$[this.$N = ", name);

            if (trueType.isList()) {
                if (!field.required()) {
                    assignment.add("builder.$N == null ? null : ", name);
                }
                assignment.add("$T.unmodifiableList(builder.$N)",
                        TypeNames.COLLECTIONS, name);
            } else if (trueType.isSet()) {
                if (!field.required()) {
                    assignment.add("builder.$N == null ? null : ", name);
                }
                assignment.add("$T.unmodifiableSet(builder.$N)",
                        TypeNames.COLLECTIONS, name);
            } else if (trueType.isMap()) {
                if (!field.required()) {
                    assignment.add("builder.$N == null ? null : ", name);
                }
                assignment.add("$T.unmodifiableMap(builder.$N)",
                        TypeNames.COLLECTIONS, name);
            } else {
                assignment.add("builder.$N", name);
            }

            ctor.addCode(assignment.add(";\n$]").build());
        }

        structBuilder.addMethod(ctor.build());
        structBuilder.addMethod(buildEqualsFor(type));
        structBuilder.addMethod(buildHashCodeFor(type));

        return structBuilder.build();
    }

    private void generateParcelable(StructType structType, ClassName structName, TypeSpec.Builder structBuilder) {
        structBuilder.addSuperinterface(TypeNames.PARCELABLE);

        structBuilder.addField(FieldSpec.builder(ClassLoader.class, "CLASS_LOADER")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.class.getClassLoader()", structName)
                .build());

        ParameterizedTypeName creatorType = ParameterizedTypeName.get(TypeNames.PARCELABLE_CREATOR, structName);
        TypeSpec creator = TypeSpec.anonymousClassBuilder("")
                .addSuperinterface(creatorType)
                .addMethod(MethodSpec.methodBuilder("createFromParcel")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(structName)
                        .addParameter(TypeNames.PARCEL, "source")
                        .addStatement("return new $T(source)", structName)
                        .build())
                .addMethod(MethodSpec.methodBuilder("newArray")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(ArrayTypeName.of(structName))
                        .addParameter(int.class, "size")
                        .addStatement("return new $T[size]", structName)
                        .build())
                .build();

        MethodSpec.Builder parcelCtor = MethodSpec.constructorBuilder()
                .addParameter(TypeNames.PARCEL, "in")
                .addModifiers(Modifier.PRIVATE);

        MethodSpec.Builder parcelWriter = MethodSpec.methodBuilder("writeToParcel")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(TypeNames.PARCEL, "dest")
                .addParameter(int.class, "flags");

        for (Field field : structType.fields()) {
            String name = fieldNamer.getName(field);
            TypeName fieldType = typeResolver.getJavaClass(field.type().getTrueType());
            parcelCtor.addStatement("this.$N = ($T) in.readValue(CLASS_LOADER)", name, fieldType);

            parcelWriter.addStatement("dest.writeValue(this.$N)", name);
        }

        FieldSpec creatorField = FieldSpec.builder(creatorType, "CREATOR")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$L", creator)
                .build();

        structBuilder
                .addField(creatorField)
                .addMethod(MethodSpec.methodBuilder("describeContents")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(int.class)
                        .addStatement("return 0")
                        .build())
                .addMethod(parcelCtor.build())
                .addMethod(parcelWriter.build());

    }

    private TypeSpec builderFor(
            StructType structType,
            ClassName structClassName,
            ClassName builderClassName) {
        TypeSpec.Builder builder = TypeSpec.classBuilder("Builder")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);

        MethodSpec.Builder buildMethodBuilder = MethodSpec.methodBuilder("build")
                .returns(structClassName)
                .addModifiers(Modifier.PUBLIC);

        MethodSpec.Builder copyCtor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(structClassName, "struct");

        MethodSpec.Builder defaultCtor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC);

        if (structType.isUnion()) {
            buildMethodBuilder.addStatement("int setFields = 0");
        }

        // Add fields to the struct and set them in the ctor
        NameAllocator allocator = new NameAllocator();
        for (Field field : structType.fields()) {
            String name = fieldNamer.getName(field);
            allocator.newName(name, name);
        }

        AtomicInteger tempNameId = new AtomicInteger(0); // used for generating unique names of temporary values
        for (Field field : structType.fields()) {
            ThriftType fieldType = field.type().getTrueType();
            TypeName javaTypeName = typeResolver.getJavaClass(fieldType);
            String fieldName = fieldNamer.getName(field);
            FieldSpec.Builder f = FieldSpec.builder(javaTypeName, fieldName, Modifier.PRIVATE);

            if (field.hasJavadoc()) {
                f.addJavadoc("$L", field.documentation());
            }

            if (field.defaultValue() != null) {
                CodeBlock.Builder initializer = CodeBlock.builder();
                constantBuilder.generateFieldInitializer(
                        initializer,
                        allocator,
                        tempNameId,
                        "this." + fieldName,
                        fieldType.getTrueType(),
                        field.defaultValue(),
                        false);
                defaultCtor.addCode(initializer.build());

            }

            builder.addField(f.build());

            MethodSpec.Builder setterBuilder = MethodSpec.methodBuilder(fieldName)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(builderClassName)
                    .addParameter(javaTypeName, fieldName);

            if (field.required()) {
                setterBuilder.beginControlFlow("if ($N == null)", fieldName);
                setterBuilder.addStatement(
                        "throw new $T(\"Required field '$L' cannot be null\")",
                        NullPointerException.class,
                        fieldName);
                setterBuilder.endControlFlow();
            }

            setterBuilder
                    .addStatement("this.$N = $N", fieldName, fieldName)
                    .addStatement("return this");

            builder.addMethod(setterBuilder.build());

            if (structType.isUnion()) {
                buildMethodBuilder
                        .addStatement("if (this.$N != null) ++setFields", fieldName);
            } else {
                if (field.required()) {
                    buildMethodBuilder.beginControlFlow("if (this.$N == null)", fieldName);
                    buildMethodBuilder.addStatement(
                            "throw new $T($S)",
                            ClassName.get(IllegalStateException.class),
                            "Required field '" + fieldName + "' is missing");
                    buildMethodBuilder.endControlFlow();
                }
            }

            copyCtor.addStatement("this.$N = $N.$N", fieldName, "struct", fieldName);
        }

        if (structType.isUnion()) {
            buildMethodBuilder
                    .beginControlFlow("if (setFields != 1)")
                    .addStatement(
                            "throw new $T($S + setFields + $S)",
                            ClassName.get(IllegalStateException.class),
                            "Invalid union; ",
                            " field(s) were set")
                    .endControlFlow();
        }

        buildMethodBuilder.addStatement("return new $T(this)", structClassName);
        builder.addMethod(defaultCtor.build());
        builder.addMethod(copyCtor.build());
        builder.addMethod(buildMethodBuilder.build());

        return builder.build();
    }

    private MethodSpec buildEqualsFor(StructType struct) {
        MethodSpec.Builder equals = MethodSpec.methodBuilder("equals")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .addParameter(Object.class, "other")
                .addStatement("if (this == other) return true")
                .addStatement("if (other == null) return false");


        if (struct.fields().size() > 0) {
            equals.addStatement("if (!(other instanceof $L)) return false", struct.name());
            equals.addStatement("$1L that = ($1L) other", struct.name());
        }

        boolean isFirst = true;
        Set<String> warningsToSuppress = new LinkedHashSet<>();
        for (Field field : struct.fields()) {
            ThriftType type = field.type().getTrueType();
            String fieldName = fieldNamer.getName(field);

            if (isFirst) {
                equals.addCode("$[return ");
                isFirst = false;
            } else {
                equals.addCode("\n&& ");
            }

            if (field.required()) {
                equals.addCode("(this.$1N == that.$1N || this.$1N.equals(that.$1N))", fieldName);
            } else {
                equals.addCode("(this.$1N == that.$1N || (this.$1N != null && this.$1N.equals(that.$1N)))",
                        fieldName);
            }

            if (type.isBuiltin() && ((BuiltinType) type).isNumeric()) {
                warningsToSuppress.add("NumberEquality");
            }

            if (type.equals(BuiltinType.STRING)) {
                warningsToSuppress.add("StringEquality");
            }
        }

        if (warningsToSuppress.size() > 0) {
            equals.addAnnotation(suppressWarnings(warningsToSuppress));
        }

        if (struct.fields().size() > 0) {
            equals.addCode(";\n$]");
        } else {
            equals.addStatement("return other instanceof $L", struct.name());
        }

        return equals.build();
    }

    private AnnotationSpec suppressWarnings(Collection<String> warnings) {
        AnnotationSpec.Builder anno = AnnotationSpec.builder(SuppressWarnings.class);

        if (warnings.isEmpty()) {
            throw new IllegalArgumentException("No warnings present - compiler error?");
        }

        if (warnings.size() == 1) {
            anno.addMember("value", "$S", Iterables.get(warnings, 0));
        } else {
            StringBuilder sb = new StringBuilder("{");
            for (String warning : warnings) {
                sb.append("\"");
                sb.append(warning);
                sb.append("\", ");
            }
            sb.setLength(sb.length() - 2);
            sb.append("}");

            anno.addMember("value", "$L", sb.toString());
        }

        return anno.build();
    }

    private MethodSpec buildHashCodeFor(StructType struct) {
        MethodSpec.Builder hashCode = MethodSpec.methodBuilder("hashCode")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(int.class)
                .addStatement("int code = 16777619");

        for (Field field : struct.fields()) {
            String fieldName = fieldNamer.getName(field);

            if (field.required()) {
                hashCode.addStatement("code ^= this.$N.hashCode()", fieldName);
            } else {
                hashCode.addStatement("code ^= (this.$1N == null) ? 0 : this.$1N.hashCode()", fieldName);
            }
            hashCode.addStatement("code *= 0x811c9dc5");
        }

        hashCode.addStatement("return code");
        return hashCode.build();
    }

    /**
     * Builds a #toString() method for the given struct.
     *
     * <p>The goal is to produce a method that performs as few string
     * concatenations as possible.  To do so, we identify what would be
     * consecutive constant strings (i.e. field name followed by '='),
     * collapsing them into "chunks", then using the chunks to generate
     * the actual code.
     *
     * <p>This approach, while more complicated to implement than naive
     * StringBuilder usage, produces more-efficient and "more pleasing" code.
     * Simple structs (e.g. one with only one field, which is redacted) end up
     * with simple constants like {@code return "Foo{ssn=&lt;REDACTED&gt;}";}.
     */
    private MethodSpec buildToStringFor(StructType struct) {
        class Chunk {
            private final String format;
            private final Object[] args;

            private Chunk(String format, Object ...args) {
                this.format = format;
                this.args = args;
            }
        }

        MethodSpec.Builder toString = MethodSpec.methodBuilder("toString")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class);

        List<Chunk> chunks = new ArrayList<>();

        StringBuilder sb = new StringBuilder(struct.name()).append("{");
        boolean appendedOneField = false;
        for (Field field : struct.fields()) {
            String fieldName = fieldNamer.getName(field);

            if (appendedOneField) {
                sb.append(", ");
            } else {
                appendedOneField = true;
            }

            sb.append(fieldName).append("=");

            if (field.isRedacted()) {
                sb.append("<REDACTED>");
            } else if (field.isObfuscated()) {
                chunks.add(new Chunk("$S", sb.toString()));
                sb.setLength(0);

                Chunk chunk;
                ThriftType fieldType = field.type().getTrueType();
                if (fieldType.isList() || fieldType.isSet()) {
                    String type;
                    String elementType;
                    if (fieldType.isList()) {
                        type = "list";
                        elementType = ((ListType) fieldType).elementType().name();
                    } else {
                        type = "set";
                        elementType = ((SetType) fieldType).elementType().name();
                    }

                    chunk = new Chunk(
                            "$T.summarizeCollection(this.$L, $S, $S)",
                            TypeNames.OBFUSCATION_UTIL,
                            fieldName,
                            type,
                            elementType);
                } else if (fieldType.isMap()) {
                    MapType mapType = (MapType) fieldType;
                    String keyType = mapType.keyType().name();
                    String valueType = mapType.valueType().name();

                    chunk = new Chunk(
                            "$T.summarizeMap(this.$L, $S, $S)",
                            TypeNames.OBFUSCATION_UTIL,
                            fieldName,
                            keyType,
                            valueType);
                } else {
                    chunk = new Chunk("$T.hash(this.$L)", TypeNames.OBFUSCATION_UTIL, fieldName);
                }

                chunks.add(chunk);
            } else {
                chunks.add(new Chunk("$S", sb.toString()));
                chunks.add(new Chunk("this.$L", fieldName));

                sb.setLength(0);
            }
        }

        sb.append("}");
        chunks.add(new Chunk("$S", sb.toString()));

        CodeBlock.Builder block = CodeBlock.builder();
        boolean firstChunk = true;
        for (Chunk chunk : chunks) {
            if (firstChunk) {
                block.add("$[return ");
                firstChunk = false;
            } else {
                block.add(" + ");
            }

            block.add(chunk.format, chunk.args);
        }

        block.add(";$]\n");

        toString.addCode(block.build());

        return toString.build();
    }

    @VisibleForTesting
    @SuppressWarnings("WeakerAccess")
    TypeSpec buildConst(Collection<Constant> constants) {
        TypeSpec.Builder builder = TypeSpec.classBuilder("Constants")
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addMethod(MethodSpec.constructorBuilder()
                        .addModifiers(Modifier.PRIVATE)
                        .addCode("// no instances\n")
                        .build());

        final NameAllocator allocator = new NameAllocator();
        allocator.newName("Constants", "Constants");

        final AtomicInteger scope = new AtomicInteger(0); // used for temporaries in const collections
        final CodeBlock.Builder staticInit = CodeBlock.builder();
        final AtomicBoolean hasStaticInit = new AtomicBoolean(false);

        for (final Constant constant : constants) {
            final ThriftType type = constant.type().getTrueType();

            TypeName javaType = typeResolver.getJavaClass(type);

            // Primitive-typed const fields should be unboxed, but be careful -
            // while strings are builtin, they are *not* primitive!
            if (type.isBuiltin() && !type.equals(BuiltinType.STRING)) {
                javaType = javaType.unbox();
            }

            final FieldSpec.Builder field = FieldSpec.builder(javaType, constant.name())
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);

            if (constant.hasJavadoc()) {
                field.addJavadoc("$L", constant.documentation() + "\n\nGenerated from: " + constant.location() + "\n");
            }

            if (constant.isDeprecated()) {
                field.addAnnotation(AnnotationSpec.builder(Deprecated.class).build());
            }

            type.accept(new SimpleVisitor<Void>() {
                @Override
                public Void visitBuiltin(ThriftType builtinType) {
                    field.initializer(constantBuilder.renderConstValue(null, allocator, scope, type, constant.value()));
                    return null;
                }

                @Override
                public Void visitEnum(EnumType userType) {
                    field.initializer(constantBuilder.renderConstValue(null, allocator, scope, type, constant.value()));
                    return null;
                }

                @Override
                public Void visitList(ListType listType) {
                    if (constant.value().getAsList().isEmpty()) {
                        field.initializer("$T.emptyList()", TypeNames.COLLECTIONS);
                        return null;
                    }
                    initCollection("list", "unmodifiableList");
                    return null;
                }

                @Override
                public Void visitSet(SetType setType) {
                    if (constant.value().getAsList().isEmpty()) {
                        field.initializer("$T.emptySet()", TypeNames.COLLECTIONS);
                        return null;
                    }
                    initCollection("set", "unmodifiableSet");
                    return null;
                }

                @Override
                public Void visitMap(MapType mapType) {
                    if (constant.value().getAsMap().isEmpty()) {
                        field.initializer("$T.emptyMap()", TypeNames.COLLECTIONS);
                        return null;
                    }
                    initCollection("map", "unmodifiableMap");
                    return null;
                }

                private void initCollection(String tempName, String unmodifiableMethod) {
                    tempName += scope.incrementAndGet();
                    constantBuilder.generateFieldInitializer(
                            staticInit,
                            allocator,
                            scope,
                            tempName,
                            type,
                            constant.value(),
                            true);
                    staticInit.addStatement("$N = $T.$L($N)",
                            constant.name(),
                            TypeNames.COLLECTIONS,
                            unmodifiableMethod,
                            tempName);

                    hasStaticInit.set(true);
                }

                @Override
                public Void visitStruct(StructType userType) {
                    throw new UnsupportedOperationException("Struct-type constants are not supported");
                }

                @Override
                public Void visitTypedef(TypedefType typedefType) {
                    throw new AssertionError("Typedefs should have been resolved before now");
                }

                @Override
                public Void visitService(ServiceType serviceType) {
                    throw new AssertionError("Services cannot be constant values");
                }
            });

            builder.addField(field.build());
        }

        if (hasStaticInit.get()) {
            builder.addStaticBlock(staticInit.build());
        }

        return builder.build();
    }

    private static AnnotationSpec fieldAnnotation(Field field) {
        AnnotationSpec.Builder ann = AnnotationSpec.builder(ThriftField.class)
                .addMember("fieldId", "$L", field.id());

        if (field.required()) {
            ann.addMember("isRequired", "$L", field.required());
        }

        if (field.optional()) {
            ann.addMember("isOptional", "$L", field.optional());
        }

        String typedef = field.typedefName();
        if (!Strings.isNullOrEmpty(typedef)) {
            ann = ann.addMember("typedefName", "$S", typedef);
        }

        return ann.build();
    }

    @VisibleForTesting
    @SuppressWarnings("WeakerAccess")
    TypeSpec buildEnum(EnumType type) {
        ClassName enumClassName = ClassName.get(
                type.getNamespaceFor(NamespaceScope.JAVA),
                type.name());

        TypeSpec.Builder builder = TypeSpec.enumBuilder(type.name())
                .addModifiers(Modifier.PUBLIC)
                .addField(int.class, "value", Modifier.PUBLIC, Modifier.FINAL)
                .addMethod(MethodSpec.constructorBuilder()
                        .addParameter(int.class, "value")
                        .addStatement("this.$N = $N", "value", "value")
                        .build());

        if (type.hasJavadoc()) {
            builder.addJavadoc("$L", type.documentation());
        }

        if (type.isDeprecated()) {
            builder.addAnnotation(AnnotationSpec.builder(Deprecated.class).build());
        }

        MethodSpec.Builder fromCodeMethod = MethodSpec.methodBuilder("findByValue")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(enumClassName)
                .addParameter(int.class, "value")
                .beginControlFlow("switch (value)");

        for (EnumMember member : type.members()) {
            String name = member.name();

            int value = member.value();

            TypeSpec.Builder memberBuilder = TypeSpec.anonymousClassBuilder("$L", value);
            if (member.hasJavadoc()) {
                memberBuilder.addJavadoc("$L", member.documentation());
            }

            if (member.isDeprecated()) {
                memberBuilder.addAnnotation(AnnotationSpec.builder(Deprecated.class).build());
            }

            builder.addEnumConstant(name, memberBuilder.build());

            fromCodeMethod.addStatement("case $L: return $N", value, name);
        }

        fromCodeMethod
                .addStatement("default: return null")
                .endControlFlow();

        builder.addMethod(fromCodeMethod.build());

        return builder.build();
    }
}

package com.apollographql.apollo.compiler

import com.apollographql.apollo.compiler.ClassNames.parameterizedGuavaOptional
import com.apollographql.apollo.compiler.ClassNames.parameterizedInputType
import com.apollographql.apollo.compiler.ClassNames.parameterizedJavaOptional
import com.apollographql.apollo.compiler.ClassNames.parameterizedOptional
import com.apollographql.apollo.compiler.ir.CodeGenerationContext
import com.apollographql.apollo.compiler.ir.ScalarType
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.TypeName

class JavaTypeResolver(
    private val context: CodeGenerationContext,
    private val packageName: String,
    private val deprecated: Boolean = false
) {
  fun resolve(
      typeName: String,
      isOptional: Boolean = !typeName.endsWith("!"),
      nullableValueType: NullableValueType? = null,
      supportsAnnotations: Boolean = true
  ): TypeName {
    val normalizedTypeName = typeName.removeSuffix("!")
    val isList = normalizedTypeName.startsWith('[') && normalizedTypeName.endsWith(']')
    val customScalarType = context.customTypeMap[normalizedTypeName]
    val javaType = when {
      isList -> ClassNames.parameterizedListOf(
          resolve(
              typeName = normalizedTypeName.removeSurrounding("[", "]"),
              isOptional = false,
              supportsAnnotations = false
          )
      )
      normalizedTypeName == ScalarType.STRING.name -> ClassNames.STRING
      normalizedTypeName == ScalarType.INT.name -> if (isOptional) TypeName.INT.box() else TypeName.INT
      normalizedTypeName == ScalarType.BOOLEAN.name -> if (isOptional) TypeName.BOOLEAN.box() else TypeName.BOOLEAN
      normalizedTypeName == ScalarType.FLOAT.name -> if (isOptional) TypeName.DOUBLE.box() else TypeName.DOUBLE
      customScalarType != null -> customScalarType.toJavaType()
      else -> ClassName.get(packageName, normalizedTypeName.capitalize())
    }

    return when {
      !supportsAnnotations -> {
        // Adding an annotation to a primitive type will crash in javapoet's TypeName.box()
        // The underlying issue is that the annotation should be on the field name and not on the type name but it is a more
        // complex issue. Don't add the annotation for now if the type is used in a context where annotations do not make sense.
        // See https://github.com/apollographql/apollo-android/issues/2660
        // See https://github.com/apollographql/apollo-android/issues/2246
        javaType
      }
      javaType.isPrimitive -> {
        javaType.let { if (deprecated) it.annotated(Annotations.DEPRECATED) else it }
      }
      isOptional -> {
        when (nullableValueType ?: context.nullableValueType) {
          NullableValueType.APOLLO_OPTIONAL -> parameterizedOptional(javaType)
          NullableValueType.GUAVA_OPTIONAL -> parameterizedGuavaOptional(javaType)
          NullableValueType.JAVA_OPTIONAL -> parameterizedJavaOptional(javaType)
          NullableValueType.INPUT_TYPE -> parameterizedInputType(javaType)
          else -> javaType.annotated(Annotations.NULLABLE)
        }.let {
          if (deprecated) it.annotated(Annotations.DEPRECATED) else it
        }
      }
      else -> {
        javaType.annotated(Annotations.NONNULL).let {
          if (deprecated) it.annotated(Annotations.DEPRECATED) else it
        }
      }
    }
  }
}
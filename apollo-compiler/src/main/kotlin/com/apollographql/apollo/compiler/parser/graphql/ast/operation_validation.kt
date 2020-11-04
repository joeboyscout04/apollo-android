package com.apollographql.apollo.compiler.parser.graphql.ast

import com.apollographql.apollo.compiler.parser.error.ParseException
import com.apollographql.apollo.compiler.parser.introspection.IntrospectionSchema
import com.apollographql.apollo.compiler.parser.introspection.asGraphQLType
import com.apollographql.apollo.compiler.parser.introspection.isAssignableFrom

private class ExecutableDocumentValidator(val schema: IntrospectionSchema) {
  fun validateAsExecutable(document: GQLDocument): GQLDocument {
    document.validateNotExecutable()
    document.validateOperations()
    return document
  }


  private fun GQLField.validate(operation: GQLOperationDefinition, typeInScope: IntrospectionSchema.Type) {
    if (name.startsWith("__")) {
      // Builtin types are always valid
      return
    }

    val fieldsInScope = when (typeInScope) {
      is IntrospectionSchema.Type.Object -> typeInScope.fields
      is IntrospectionSchema.Type.Interface -> typeInScope.fields
      else -> throw ParseException(
          message = "Can't query `$name` on type `${typeInScope.name}`. `${typeInScope.name}` is not one of the expected types: `INTERFACE`, `OBJECT`.",
          sourceLocation = sourceLocation
      )
    }

    val field = fieldsInScope?.firstOrNull { it.name == name }

    if (field == null) {
      throw ParseException(
          message = "Can't query `$name` on type `${typeInScope.name}`",
          sourceLocation = sourceLocation
      )
    }
    arguments.validate(operation, field)

    val fieldType = schema[field.type.rawType.name] ?: throw ParseException(
        message = "Unknown type `${field.type.rawType.name}`",
        sourceLocation = sourceLocation
    )

    if (fieldType.kind != IntrospectionSchema.Kind.SCALAR) {
      if (selectionSet == null) {
        throw ParseException(
            message = "Field `$name` of type `${typeInScope.name}` must have a selection of sub-fields",
            sourceLocation = sourceLocation
        )
      }
      selectionSet.validate(operation, fieldType)
    }
  }


  /**
   * This is not in the specification per-se but in our use case, that will help catch some cases when users mistake
   * graphql operations for schemas
   */
  private fun GQLDocument.validateNotExecutable() {
    definitions.firstOrNull { it !is GQLOperationDefinition || it !is GQLFragmentDefinition }
        ?.let {
          throw ParseException("Found an non-executable definition.", it.sourceLocation)
        }
  }

  private fun GQLDocument.validateOperations() {
    definitions.filterIsInstance<GQLOperationDefinition>().forEach {
      it.validate()
    }
  }

  private fun GQLOperationDefinition.validate() {
    if (name.isNullOrBlank()) {
      throw ParseException(
          message = "Apollo does not support anonymous operations",
          sourceLocation = sourceLocation
      )
    }

    val operationRoot = when (operationType) {
      "query" -> schema.queryType
      "mutation" -> schema.mutationType
      "subscription" -> schema.subscriptionType
      else -> throw ParseException(
          message = "Unknown operation type `$operationType`",
          sourceLocation = sourceLocation
      )
    }

    val schemaType = schema[operationRoot] ?: throw ParseException(
        message = "Can't resolve root for `$operationType` operation type",
        sourceLocation = sourceLocation
    )

    selectionSet.validate(this, schemaType)
  }

  private fun GQLSelectionSet.validate(operation: GQLOperationDefinition, typeInScope: IntrospectionSchema.Type) {
    if (selections.isEmpty()) {
      // This will never happen from parsing documents but is kept for reference and to catch bad document modifications
      throw ParseException(
          message = "Selection of type `${typeInScope.name}` must have a selection of sub-fields",
          sourceLocation = sourceLocation
      )
    }

    selections.forEach {
      when (it) {
        is GQLField -> it.validate(operation, typeInScope)
        is GQLInlineFragment -> it.validate(typeInScope)
        is GQLFragmentSpread -> it.validate(typeInScope)
      }
    }
  }

  private fun IntrospectionSchema.TypeRef.prettyName(): String {
    return when (kind) {
      IntrospectionSchema.Kind.LIST -> "[${ofType!!.prettyName()}]"
      IntrospectionSchema.Kind.NON_NULL -> "${ofType!!.prettyName()}!"
      else -> name!!
    }
  }

  private fun validateInputValue(operation: GQLOperationDefinition, value: GQLValue, typeRef: IntrospectionSchema.TypeRef) {
    when (value) {
      is GQLIntValue -> {
        if (typeRef.name != "Int") {
          throw ParseException("Expected `${typeRef.prettyName()}` and got Int value `${value.value}` instead", value.sourceLocation)
        }
      }
      is GQLFloatValue -> {
        if (typeRef.name != "Float") {
          throw ParseException("Expected `${typeRef.prettyName()}` and got Float value `${value.value}` instead", value.sourceLocation)
        }
      }
      is GQLStringValue -> {
        if (typeRef.name != "String") {
          throw ParseException("Expected `${typeRef.prettyName()}` and got String value `${value.value}` instead", value.sourceLocation)
        }
      }
      is GQLBooleanValue -> {
        if (typeRef.name != "Boolean") {
          throw ParseException("Expected `${typeRef.prettyName()}` and got Boolean value `${value.value}` instead", value.sourceLocation)
        }
      }
      is GQLEnumValue -> {
        if (typeRef.kind != IntrospectionSchema.Kind.ENUM) {
          throw ParseException("Expected `${typeRef.prettyName()}` and got Enum value `${value.value}` instead", value.sourceLocation)
        }
      }
      is GQLNullValue -> {
        if (typeRef.kind == IntrospectionSchema.Kind.NON_NULL) {
          throw ParseException("Expected `${typeRef.prettyName()}` and got null value instead", value.sourceLocation)
        }
      }
      is GQLListValue -> {
        if (typeRef.kind != IntrospectionSchema.Kind.LIST) {
          throw ParseException("Expected `${typeRef.prettyName()}` and got non-list value instead", value.sourceLocation)
        }
        value.values.forEach {
          validateInputValue(operation, it, typeRef.ofType!!)
        }
      }
      is GQLObjectValue -> {
        if (typeRef.kind != IntrospectionSchema.Kind.OBJECT) {
          throw ParseException("Expected `${typeRef.prettyName()}` and got non-object value instead", value.sourceLocation)
        }

        value.fields.forEach {gqlField ->
          val fieldTypeRef = (schema[typeRef.name!!] as? IntrospectionSchema.Type.InputObject)?.inputFields
              ?.firstOrNull { it.name == gqlField.name }
              ?.type
          if (fieldTypeRef == null) {
            throw ParseException("Cannot find type for field ${gqlField.name}", gqlField.sourceLocation)
          }
          validateInputValue(operation, gqlField.value, fieldTypeRef)
        }
      }
      is GQLVariableValue -> {
        val variableDefinition = operation.variableDefinitions.firstOrNull { it.name == value.name }
        if (variableDefinition == null) {
          throw ParseException("Variable `${value.name}` is not defined by operation `${operation.name}`")
        }
        val variableTypeRef = variableDefinition.type.toTypeRef()
        if (!typeRef.isAssignableFrom(other = variableTypeRef, schema = schema)) {
          throw ParseException(
              "Variable `${value.name}` of type `${variableTypeRef.asGraphQLType()}` used in position expecting type `${typeRef.asGraphQLType()}`"
          )
        }
      }
    }
  }

  private fun GQLArgument.validate(operation: GQLOperationDefinition, field: IntrospectionSchema.Field) {
    val schemaArgument = field.args.firstOrNull { it.name == name }
    if (schemaArgument == null) {
      throw ParseException("Unknown argument `$name` on field `${field.name}`", sourceLocation)
    }

    // 5.6.2 Input Object Field Names
    validateInputValue(operation, value, schemaArgument.type)
  }

  private fun List<GQLArgument>.validate(operation: GQLOperationDefinition, field: IntrospectionSchema.Field) {
    // 5.4.2 Argument Uniqueness
    groupBy { it.name }.filter { it.value.size > 1 }.toList().firstOrNull()?.let {
      throw ParseException("Argument `${it.first}` is defined multiple times", sourceLocation = it.second.first().sourceLocation)
    }

    forEach {
      it.validate(operation, field)
    }
  }

  private fun GQLType.toTypeRef(): IntrospectionSchema.TypeRef {
    return when (this) {
      is GQLNonNullType -> {
        IntrospectionSchema.TypeRef(
            kind = IntrospectionSchema.Kind.NON_NULL,
            name = "", // why "" and not null ?
            ofType = type.toTypeRef()
        )
      }
      is GQLListType -> {
        IntrospectionSchema.TypeRef(
            kind = IntrospectionSchema.Kind.LIST,
            name = "", // why "" and not null ?
            ofType = type.toTypeRef())
      }
      is GQLNamedType -> {
        val typeDefinition = schema[name] ?: throw ParseException(
            message = "Undefined GraphQL schema type `$name`",
            sourceLocation = sourceLocation
        )
        IntrospectionSchema.TypeRef(
            kind = typeDefinition.kind,
            name = name,
            ofType = null
        )
      }
    }
  }
}


fun GQLDocument.validateAsExecutable(schema: IntrospectionSchema) = ExecutableDocumentValidator(schema).validateAsExecutable(this)

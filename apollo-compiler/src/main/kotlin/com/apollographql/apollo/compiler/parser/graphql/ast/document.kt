package com.apollographql.apollo.compiler.parser.graphql.ast

import com.apollographql.apollo.compiler.parser.antlr.GraphQLLexer
import com.apollographql.apollo.compiler.parser.antlr.GraphQLParser
import com.apollographql.apollo.compiler.parser.error.DocumentParseException
import com.apollographql.apollo.compiler.parser.error.ParseException
import com.apollographql.apollo.compiler.parser.introspection.IntrospectionSchema
import okio.Buffer
import okio.BufferedSink
import okio.buffer
import okio.sink
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.atn.PredictionMode
import java.io.File
import java.io.InputStream

fun GQLDocument.withBuiltinTypes(): GQLDocument {
  val buildInsInputStream = javaClass.getResourceAsStream("/builtins.sdl")
  return copy(
      definitions = definitions + GQLDocument.parseInternal(buildInsInputStream).definitions
  )
}

fun GQLDocument.withoutBuiltinTypes(): GQLDocument {
  return copy(
      definitions = definitions.filter {
        when ((it as? GQLNamed)?.name) {
          "Int", "Float", "String", "ID", "Boolean", "__Schema",
          "__Type", "__Field", "__InputValue", "__EnumValue", "__TypeKind",
          "__Directive", "__DirectiveLocation" -> false
          else -> true
        }
      }
  )
}

/**
 * Plain parsing, without validation or adding the builtin types
 */
private fun GQLDocument.Companion.parseInternal(inputStream: InputStream, filePath: String = "(source)"): GQLDocument {

  val parser = GraphQLParser(
      CommonTokenStream(
          GraphQLLexer(
              CharStreams.fromStream(inputStream)
          )
      )
  )

  return parser.apply {
    removeErrorListeners()
    interpreter.predictionMode = PredictionMode.SLL
    addErrorListener(
        object : BaseErrorListener() {
          override fun syntaxError(
              recognizer: Recognizer<*, *>?,
              offendingSymbol: Any?,
              line: Int,
              position: Int,
              msg: String?,
              e: RecognitionException?
          ) {
            throw ParseException(
                message = "Unsupported token `${(offendingSymbol as? Token)?.text ?: offendingSymbol.toString()}`",
                sourceLocation = com.apollographql.apollo.compiler.ir.SourceLocation(
                    line = line,
                    position = position
                )
            )
          }
        }
    )
  }.document()
      .also {
        parser.checkEOF(it)
      }
      .parse()
}

private fun GraphQLParser.checkEOF(documentContext: GraphQLParser.DocumentContext) {
  val documentStopToken = documentContext.getStop()
  val allTokens = (tokenStream as CommonTokenStream).tokens
  if (documentStopToken != null && !allTokens.isNullOrEmpty()) {
    val lastToken = allTokens[allTokens.size - 1]
    val eof = lastToken.type == Token.EOF
    val sameChannel = lastToken.channel == documentStopToken.channel
    if (!eof && lastToken.tokenIndex > documentStopToken.tokenIndex && sameChannel) {
      throw ParseException(
          message = "Unsupported token `${lastToken.text}`",
          token = lastToken
      )
    }
  }
}

fun GQLDocument.Companion.parseAsSchema(document: String) = GQLDocument.parseAsSchema(document.byteInputStream())

fun GQLDocument.Companion.parseAsSchema(file: File) = file.inputStream().use {
  GQLDocument.parseAsSchema(it, file.absolutePath)
}

fun GQLDocument.Companion.parseAsSchema(inputStream: InputStream, filePath: String = "(source)"): GQLDocument {
  // Validation as to be done before adding the built in types else validation fail on names starting with '__'
  // This means that it's impossible to add type extension on built in types at the moment
  return try {
    parseInternal(inputStream, filePath)
        .mergeTypeExtensions()
        .validateAsSchema()
        .withBuiltinTypes()
  } catch (e: ParseException) {
    throw DocumentParseException(
        parseException = e,
        filePath = filePath
    )
  }
}

fun GQLDocument.Companion.parseAsExecutable(inputStream: InputStream, introspectionSchema: IntrospectionSchema, filePath: String = "(source)"): GQLDocument {
  return try {
    parseInternal(inputStream, filePath)
        .validateAsExecutable(introspectionSchema)
  } catch (e: ParseException) {
    throw DocumentParseException(
        parseException = e,
        filePath = filePath
    )
  }
}


private fun String.withIndents(): String {
  var indent = 0
  return lines().joinToString(separator = "\n") { line ->
    if (line.endsWith("}")) indent -= 2
    (" ".repeat(indent) + line).also {
      if (line.endsWith("{")) indent += 2
    }
  }
}

fun GQLDocument.toBufferedSink(bufferedSink: BufferedSink) {
  // TODO("stream the indents")
  val buffer = Buffer()
  withoutBuiltinTypes().write(buffer)
  val pretty = buffer.readUtf8().withIndents()
  bufferedSink.writeUtf8(pretty)
}

fun GQLDocument.toUtf8(): String {
  val buffer = Buffer()
  toBufferedSink(buffer)
  return buffer.readUtf8()
}

fun GQLDocument.toFile(file: File) = file.outputStream().sink().buffer().use {
  toBufferedSink(it)
}
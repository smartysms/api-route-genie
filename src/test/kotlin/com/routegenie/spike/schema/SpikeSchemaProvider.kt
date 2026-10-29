package com.routegenie.spike.schema

import com.intellij.json.JsonFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType
import com.jetbrains.jsonSchema.impl.JsonSchemaVersion

/** Spike only: the schema of one request body, shown for exactly the body files that [appliesTo] accepts. */
class SpikeSchemaFactory(private val schemaFile: VirtualFile, private val appliesTo: (VirtualFile) -> Boolean) : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(SpikeSchemaProvider(schemaFile, appliesTo))
}

class SpikeSchemaProvider(private val schemaFile: VirtualFile, private val appliesTo: (VirtualFile) -> Boolean) : JsonSchemaFileProvider {
    override fun getName(): String = "spike-request-body"
    override fun isAvailable(file: VirtualFile): Boolean = appliesTo(file)
    override fun getSchemaFile(): VirtualFile = schemaFile
    override fun getSchemaType(): SchemaType = SchemaType.userSchema
    override fun getSchemaVersion(): JsonSchemaVersion = JsonSchemaVersion.SCHEMA_7
}

object SpikeSchemas {
    val SCHEMA = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "type": "object",
          "properties": {
            "name": {"type": "string"},
            "age": {"type": "integer", "minimum": 0}
          },
          "required": ["name"],
          "additionalProperties": false
        }
    """.trimIndent()

    /** In-memory schema document: no file on disk. */
    fun schemaFile(): VirtualFile = LightVirtualFile("spike-schema.json", JsonFileType.INSTANCE, SCHEMA)
}

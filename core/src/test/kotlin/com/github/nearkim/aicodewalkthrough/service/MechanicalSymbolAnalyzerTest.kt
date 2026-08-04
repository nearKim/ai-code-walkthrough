package com.github.nearkim.aicodewalkthrough.service

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MechanicalSymbolAnalyzerTest {

    @Test
    fun `python AST inventory exposes code owners methods state and ranges before mapping`() = runBlocking {
        val root = Files.createTempDirectory("python-symbols")
        Files.writeString(root.resolve("pyproject.toml"), "[project]\nname = \"sample\"\n")
        Files.createDirectories(root.resolve("sample"))
        Files.writeString(
            root.resolve("sample/service.py"),
            """
            from sample.port import Port

            class Runner(Port):
                mode = "safe"

                def __init__(self):
                    self.results = []

                def run(self, item):
                    self.results.append(item)
                    return item

            def build_runner():
                return Runner()
            """.trimIndent(),
        )
        Files.createDirectories(root.resolve("tests"))
        Files.writeString(root.resolve("tests/test_service.py"), "class HiddenTestHelper: pass\n")

        val analysisRoot = Files.createTempDirectory("python-symbol-cache")
        val inventory = MechanicalSymbolAnalyzer.analyze(root, Json, analysisRoot)

        assertNotNull(inventory)
        assertEquals("python_stdlib_ast", inventory?.tool)
        val modules = inventory!!.payload.getValue("modules").jsonArray
        assertEquals(listOf("sample/service.py"), modules.map { it.jsonObject.getValue("p").jsonPrimitive.content })
        val module = modules.single().jsonObject
        assertEquals("sample.port", module.getValue("i").jsonArray.single().jsonPrimitive.content)
        val runner = module.getValue("c").jsonArray.single().jsonObject
        assertEquals("Runner", runner.getValue("n").jsonPrimitive.content)
        assertEquals(listOf("3", "11"), runner.getValue("r").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("Port"), runner.getValue("b").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(
            setOf("mode", "results"),
            runner.getValue("s").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        assertEquals(
            listOf("__init__", "run"),
            runner.getValue("m").jsonArray.map { it.jsonObject.getValue("n").jsonPrimitive.content },
        )
        assertEquals(
            listOf("9", "11"),
            runner.getValue("m").jsonArray.last().jsonObject
                .getValue("r").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("build_runner"),
            module.getValue("f").jsonArray.map { it.jsonObject.getValue("n").jsonPrimitive.content },
        )
        assertFalse(inventory.payload.getValue("truncated").jsonPrimitive.content.toBoolean())
        val architecture = inventory.payload.getValue("architecture").jsonObject
        val component = architecture.getValue("components").jsonArray.single().jsonObject
        assertEquals("sample", component.getValue("name").jsonPrimitive.content)
        val responsibility = component.getValue("responsibilities").jsonArray
            .map { it.jsonObject }
            .first { item -> item.getValue("evidence").jsonArray.any { evidence ->
                evidence.jsonObject.getValue("kind").jsonPrimitive.content == "class"
            } }
        assertTrue(responsibility.getValue("description").jsonPrimitive.content.contains("Owns methods"))
        assertTrue(
            responsibility.getValue("evidence").jsonArray
                .map { it.jsonObject.getValue("kind").jsonPrimitive.content }
                .containsAll(listOf("class", "method")),
        )
    }

    @Test
    fun `persisted inventory is reused only while the Python source fingerprint matches`() = runBlocking {
        val root = Files.createTempDirectory("python-symbol-persistence")
        val analysisRoot = Files.createTempDirectory("python-symbol-persistence-cache")
        Files.writeString(root.resolve("app.py"), "def run() -> int:\n    return 1\n")

        val first = MechanicalSymbolAnalyzer.analyze(root, Json, analysisRoot)!!
        MechanicalSymbolAnalyzer.enrich(
            inventory = first,
            projectRoot = root,
            steps = emptyList(),
            semanticToolResults = listOf(ProviderToolResult("find_symbol", "{\"name_path\":\"run\"}", "app.py:1-2")),
            json = Json,
            analysisRoot = analysisRoot,
        )
        val second = MechanicalSymbolAnalyzer.analyze(root, Json, analysisRoot)!!

        assertFalse(first.cacheHit)
        assertTrue(second.cacheHit)
        assertEquals(
            "find_symbol",
            second.payload.getValue("semantic_tool_results").jsonArray.single().jsonObject
                .getValue("tool").jsonPrimitive.content,
        )
        assertEquals(
            first.payload.getValue("source_fingerprint"),
            second.payload.getValue("source_fingerprint"),
        )
        assertTrue(
            first.payload.getValue("modules").jsonArray.single().jsonObject
                .getValue("f").jsonArray.single().jsonObject
                .getValue("z").jsonPrimitive.content.toBoolean(),
        )

        Files.writeString(root.resolve("app.py"), "def run() -> int:\n    return 2\n")
        val changed = MechanicalSymbolAnalyzer.analyze(root, Json, analysisRoot)!!

        assertFalse(changed.cacheHit)
        assertNotEquals(
            first.payload.getValue("source_fingerprint"),
            changed.payload.getValue("source_fingerprint"),
        )
    }
}

package dev.deeptelar.telar.openai

import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.agent.EmbeddingModelException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration

class OpenAiEmbeddingModelTest {
    private val sent = mutableListOf<HttpRequestData>()

    private fun client(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpClient =
        HttpClient(
            MockEngine { request ->
                sent += request
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )

    private fun sentBody() = Json.parseToJsonElement((sent.single().body as TextContent).text)

    @Test
    fun `sends the texts and returns their vectors in their order`() =
        runTest {
            // The server may send the vectors in another order: their index says which text each is for.
            val reply =
                """
                {"object": "list", "model": "text-embedding-3-small", "usage": {"prompt_tokens": 4, "total_tokens": 4},
                 "data": [
                   {"object": "embedding", "index": 1, "embedding": [0.5, -1]},
                   {"object": "embedding", "index": 0, "embedding": [1, 0.25]}
                 ]}
                """.trimIndent()
            val model =
                OpenAiEmbeddingModel(
                    client(reply),
                    apiKey = "test-key",
                    dimensions = 2,
                    headers =
                        mapOf(
                            "OpenAI-Organization" to "org-1",
                        ),
                )

            val vectors = model.embed(listOf("fish", "Porto"))

            assertEquals(listOf(listOf(1f, 0.25f), listOf(0.5f, -1f)), vectors.map { it.toList() })
            val request = sent.single()
            assertEquals("https://api.openai.com/v1/embeddings", request.url.toString())
            assertEquals("Bearer test-key", request.headers["Authorization"])
            assertEquals("org-1", request.headers["OpenAI-Organization"])
            assertEquals(
                Json.parseToJsonElement("""{"model": "text-embedding-3-small", "input": ["fish", "Porto"], "dimensions": 2}"""),
                sentBody(),
            )
            assertEquals("https://api.openai.com/v1 text-embedding-3-small dimensions=2", model.id)
        }

    @Test
    fun `ollama needs no key and no texts need no request`() =
        runTest {
            val model = OpenAiEmbeddingModel.ollama(client("""{"data": [{"embedding": [1]}]}"""), model = "nomic-embed-text")

            assertTrue(model.embed(emptyList()).isEmpty())
            assertTrue(sent.isEmpty())
            assertEquals(listOf(listOf(1f)), model.embed(listOf("fish")).map { it.toList() })
            assertEquals("http://localhost:11434/v1/embeddings", sent.single().url.toString())
            assertEquals(null, sent.single().headers["Authorization"])
            assertEquals("http://localhost:11434/v1 nomic-embed-text", model.id)
        }

    @Test
    fun `an error of the API is told with its message`() =
        runTest {
            val model =
                OpenAiEmbeddingModel(
                    client(
                        """{"error": {"message": "Incorrect API key provided", "type": "invalid_request_error"}}""",
                        HttpStatusCode.Unauthorized,
                    ),
                    apiKey = "wrong",
                )

            val failure = assertFailsWith<EmbeddingModelException> { model.embed(listOf("fish")) }
            assertEquals("API error 401 (invalid_request_error): Incorrect API key provided", failure.message)
        }

    @Test
    fun `a response without a vector for each text is told`() =
        runTest {
            for (reply in listOf(
                "not json",
                """{"data": "none"}""",
                """{"data": [{"index": 0, "embedding": [1]}]}""",
                """{"data": [{"index": 0, "embedding": ["a"]}, {"index": 1}]}""",
                """{"data": [{"index": 5, "embedding": [1]}, {"index": 1, "embedding": [1]}]}""",
            )) {
                sent.clear()
                val model = OpenAiEmbeddingModel(client(reply), apiKey = null)
                assertFailsWith<EmbeddingModelException>(reply) { model.embed(listOf("fish", "Porto")) }
            }
        }

    @Test
    fun `a server that cannot be reached is told and a cancellation goes through`() =
        runTest {
            val unreachable =
                OpenAiEmbeddingModel(HttpClient(MockEngine { throw IllegalStateException("Connection refused") }), apiKey = null)
            val failure = assertFailsWith<EmbeddingModelException> { unreachable.embed(listOf("fish")) }
            assertTrue("Connection refused" in failure.message!!)

            val offline = OpenAiEmbeddingModel(HttpClient(MockEngine { throw Error("Fail to fetch") }), apiKey = null, timeout = null)
            assertFailsWith<EmbeddingModelException> { offline.embed(listOf("fish")) }

            val broken = OpenAiEmbeddingModel(HttpClient(MockEngine { throw AssertionError("Out of memory") }), apiKey = null)
            assertFailsWith<AssertionError> { broken.embed(listOf("fish")) }

            val cancelling = OpenAiEmbeddingModel(HttpClient(MockEngine { throw CancellationException("Stopped") }), apiKey = null)
            assertFailsWith<CancellationException> { cancelling.embed(listOf("fish")) }
        }

    @Test
    fun `the settings are checked`() {
        val client = HttpClient(MockEngine { respond("") })
        assertFailsWith<GraphValidationException> { OpenAiEmbeddingModel(client, apiKey = null, dimensions = 0) }
        assertFailsWith<GraphValidationException> { OpenAiEmbeddingModel(client, apiKey = null, timeout = Duration.ZERO) }
    }
}

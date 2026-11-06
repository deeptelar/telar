package dev.deeptelar.telar.agent

import dev.deeptelar.telar.TelarException

/**
 * A model that turns texts into vectors, so that texts that mean the same have vectors that point
 * the same way. [SemanticStore] uses one to find what a query is about. It is the interface for
 * every provider, as [ChatModel] is for chat: `OpenAiEmbeddingModel` of `telar-openai` reaches
 * OpenAI, Ollama, Gemini and every server with the embeddings API of OpenAI.
 *
 * A test that needs no provider implements it with a few lines, for example with a vector that
 * counts the words of a list in each text.
 */
public interface EmbeddingModel {
    /**
     * Names the model and the settings that change its vectors, such as `text-embedding-3-small`
     * with 512 dimensions. Vectors of two models cannot be compared, so a [SemanticStore] makes the
     * vectors of its items again when this changes.
     */
    public val id: String

    /**
     * Returns a vector for each of [texts], in the same order. All vectors of one model have the
     * same length.
     *
     * @throws EmbeddingModelException when the provider cannot be reached or returns an error.
     */
    public suspend fun embed(texts: List<String>): List<FloatArray>
}

/** A call to an [EmbeddingModel] failed: the provider could not be reached, or it returned an error. */
public class EmbeddingModelException(
    message: String,
    cause: Throwable? = null,
) : TelarException(message, cause)

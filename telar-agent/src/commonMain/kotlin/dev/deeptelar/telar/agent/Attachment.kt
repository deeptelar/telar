package dev.deeptelar.telar.agent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.io.encoding.Base64

/**
 * A file that goes to a model with a message, such as the photo of a receipt or a contract as a PDF.
 *
 * Create one from the bytes of the file with [Attachment.of], or from an address that the model's
 * API downloads itself with [Attachment.url], and send it with a [ChatMessage.User]:
 *
 * ```kotlin
 * val question = ChatMessage.User(
 *     "What does this letter ask me to do?",
 *     attachments = listOf(Attachment.of(photo, "image/jpeg")),
 * )
 * ```
 *
 * A tool returns one with [attach].
 *
 * The model classes send images (`image/jpeg`, `image/png`, `image/gif` and `image/webp`) and PDFs
 * (`application/pdf`); each says which of them it takes from an address. A file of another type
 * fails before the request, with a [ChatModelException].
 *
 * An attachment is part of its message, so a checkpoint of a state that holds the message holds
 * the file too, after every step. For a large file, keep its path or its address in the state and
 * attach it in the node that calls the model.
 *
 * @property mediaType the media type of the file, such as `image/png` or `application/pdf`.
 */
@Serializable
public sealed interface Attachment {
    public val mediaType: String

    /**
     * The file itself.
     *
     * @property base64 the bytes of the file in Base64. [Attachment.of] creates one from the bytes.
     */
    @Serializable
    @SerialName("data")
    public data class Data(
        val base64: String,
        override val mediaType: String,
    ) : Attachment {
        /** Returns the bytes of the file. */
        public fun bytes(): ByteArray = Base64.decode(base64)

        // The whole file would fill a log.
        override fun toString(): String = "Data(mediaType=$mediaType, ${base64.length} characters of Base64)"
    }

    /**
     * A file that the model's API downloads from [url].
     *
     * @property url the address of the file, which the API of the model must be able to reach.
     */
    @Serializable
    @SerialName("url")
    public data class Url(
        val url: String,
        override val mediaType: String,
    ) : Attachment

    /** Creates attachments. */
    public companion object {
        /** Returns the file with the bytes [bytes] and the media type [mediaType]. */
        public fun of(bytes: ByteArray, mediaType: String): Data = Data(Base64.encode(bytes), mediaType)

        /** Returns the file at [url], with the media type [mediaType]. */
        public fun url(url: String, mediaType: String): Url = Url(url, mediaType)
    }
}

/**
 * Adds [attachment] to the result of the tool call that runs this code, so the model gets the file
 * with the text the tool returns: the screenshot that a browser tool took, for example.
 *
 * ```kotlin
 * val screenshot = Tool<Page>("screenshot", "Takes a screenshot of a web page.") { page ->
 *     attach(Attachment.of(browser.screenshot(page.url), "image/png"))
 *     "The screenshot of ${page.url}."
 * }
 * ```
 *
 * Call it from a [Tool] or a [StateTool]. A tool that fails returns no attachments. It does nothing
 * when no tool call runs the code, as when a test calls [Tool.execute] itself: a test that checks
 * the files of a tool runs it with [List.execute], whose results have them.
 */
public suspend fun attach(attachment: Attachment) {
    currentCoroutineContext()[ToolAttachments]?.add(attachment)
}

/** Collects what one tool call passes to [attach]. */
internal class ToolAttachments : AbstractCoroutineContextElement(Key) {
    private val lock = Mutex()
    private val added = mutableListOf<Attachment>()

    suspend fun add(attachment: Attachment) {
        lock.withLock { added += attachment }
    }

    suspend fun all(): List<Attachment> = lock.withLock { added.toList() }

    companion object Key : CoroutineContext.Key<ToolAttachments>
}

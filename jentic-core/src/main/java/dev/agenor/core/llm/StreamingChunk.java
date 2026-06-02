package dev.agenor.core.llm;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a chunk of content in a streaming LLM response.
 *
 * <p>When using streaming mode, the LLM response is delivered incrementally
 * as a series of chunks. Each chunk contains a portion of the generated text
 * and metadata about the streaming process.
 *
 * <p>Example usage:
 * <pre>{@code
 * provider.chatStream(request, chunk -> {
 *     if (chunk.hasContent()) {
 *         System.out.print(chunk.content());
 *     }
 *     if (chunk.isLast()) {
 *         System.out.println("\n[Streaming complete]");
 *     }
 * });
 * }</pre>
 *
 * @param id unique identifier for the streaming session or chunk
 * @param model the model name generating the response
 * @param content the incremental textual content in this chunk
 * @param finishReason the reason why generation finished (null if not finished)
 * @param index the position of this chunk in the stream (0-indexed)
 * @param created timestamp when the chunk was created
 *
 * @since 0.3.0
 */
public record StreamingChunk(
    String id,
    String model,
    String content,
    String finishReason,
    int index,
    Instant created
) {

    /**
     * Compact constructor with validation.
     */
    public StreamingChunk {
        Objects.requireNonNull(id, "ID cannot be null");
        Objects.requireNonNull(model, "Model cannot be null");

        if (index < 0) {
            throw new IllegalArgumentException("Index cannot be negative");
        }

        if (created == null) {
            created = Instant.now();
        }
    }

    /**
     * Create a streaming chunk.
     *
     * @param id the chunk/response ID
     * @param model the model generating the response
     * @param content the content in this chunk
     * @param finishReason the finish reason (null if not finished)
     * @param index the chunk index in the stream
     * @return a new streaming chunk
     */
    public static StreamingChunk of(String id, String model, String content,
                                   String finishReason, int index) {
        return new StreamingChunk(id, model, content, finishReason, index, Instant.now());
    }

    /**
     * Create a streaming chunk with just content.
     *
     * @param id the chunk/response ID
     * @param model the model name
     * @param content the content
     * @param index the chunk index
     * @return a new streaming chunk
     */
    public static StreamingChunk of(String id, String model, String content, int index) {
        return of(id, model, content, null, index);
    }

    /**
     * Check if this chunk contains content.
     *
     * @return true if content is present and non-empty
     */
    public boolean hasContent() {
        return content != null && !content.isEmpty();
    }

    /**
     * Check if this is the last chunk in the stream.
     *
     * <p>A chunk is considered last if it has a finish reason, indicating
     * the stream is complete.
     *
     * @return true if this is the last chunk
     */
    public boolean isLast() {
        return finishReason != null && !finishReason.isEmpty();
    }

    /**
     * Check if the stream was stopped normally.
     *
     * @return true if finish reason is "stop"
     */
    public boolean isComplete() {
        return "stop".equals(finishReason);
    }

    /**
     * Check if the stream was truncated due to length limits.
     *
     * @return true if finish reason is "length"
     */
    public boolean wasTruncated() {
        return "length".equals(finishReason);
    }

    /**
     * Check if the stream was stopped due to content filtering.
     *
     * @return true if finish reason is "content_filter"
     */
    public boolean wasFiltered() {
        return "content_filter".equals(finishReason);
    }

    /**
     * Get the content or empty string if no content.
     *
     * @return the content, or empty string
     */
    public String getContentOrEmpty() {
        return content != null ? content : "";
    }

    /**
     * Get the finish reason or empty string if none.
     *
     * @return the finish reason, or empty string
     */
    public String getFinishReasonOrEmpty() {
        return finishReason != null ? finishReason : "";
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("StreamingChunk{");
        sb.append("index=").append(index);
        if (hasContent()) {
            String truncated = content.length() > 20
                ? content.substring(0, 20) + "..."
                : content;
            sb.append(", content='").append(truncated).append('\'');
        }
        if (isLast()) {
            sb.append(", finishReason='").append(finishReason).append('\'');
        }
        sb.append('}');
        return sb.toString();
    }
}

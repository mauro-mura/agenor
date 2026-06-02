package dev.agenor.core.llm;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class StreamingChunkTest {

    @Test
    void shouldCreateStreamingChunkWithAllParameters() {
        // Given
        String id = "chunk_123";
        String model = "gpt-4";
        String content = "Hello";
        String finishReason = "stop";
        int index = 5;
        Instant created = Instant.now();

        // When
        var chunk = new StreamingChunk(id, model, content, finishReason, index, created);

        // Then
        assertEquals(id, chunk.id());
        assertEquals(model, chunk.model());
        assertEquals(content, chunk.content());
        assertEquals(finishReason, chunk.finishReason());
        assertEquals(index, chunk.index());
        assertEquals(created, chunk.created());
    }

    @Test
    void shouldCreateWithFactoryMethod() {
        // Given
        String id = "chunk_456";
        String model = "claude-3";
        String content = "World";
        String finishReason = null;
        int index = 3;

        // When
        var chunk = StreamingChunk.of(id, model, content, finishReason, index);

        // Then
        assertEquals(id, chunk.id());
        assertEquals(model, chunk.model());
        assertEquals(content, chunk.content());
        assertNull(chunk.finishReason());
        assertEquals(index, chunk.index());
        assertNotNull(chunk.created());
    }

    @Test
    void shouldCreateWithSimplifiedFactoryMethod() {
        // Given
        String id = "chunk_789";
        String model = "gpt-3.5-turbo";
        String content = "Test";
        int index = 1;

        // When
        var chunk = StreamingChunk.of(id, model, content, index);

        // Then
        assertEquals(id, chunk.id());
        assertEquals(model, chunk.model());
        assertEquals(content, chunk.content());
        assertNull(chunk.finishReason());
        assertEquals(index, chunk.index());
    }

    @Test
    void shouldThrowExceptionWhenIdIsNull() {
        // When/Then
        assertThrows(NullPointerException.class, () ->
            new StreamingChunk(null, "model", "content", null, 0, Instant.now())
        );
    }

    @Test
    void shouldThrowExceptionWhenModelIsNull() {
        // When/Then
        assertThrows(NullPointerException.class, () ->
            new StreamingChunk("id", null, "content", null, 0, Instant.now())
        );
    }

    @Test
    void shouldThrowExceptionWhenIndexIsNegative() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () ->
            new StreamingChunk("id", "model", "content", null, -1, Instant.now())
        );
    }

    @Test
    void shouldDefaultCreatedToNow() {
        // Given
        Instant before = Instant.now();

        // When
        var chunk = new StreamingChunk("id", "model", "content", null, 0, null);

        // Then
        assertNotNull(chunk.created());
        assertTrue(chunk.created().isAfter(before.minusSeconds(1)));
    }

    @Test
    void shouldCheckHasContent() {
        // Given
        var withContent = StreamingChunk.of("id", "model", "Hello", 0);
        var withEmptyContent = StreamingChunk.of("id", "model", "", 1);
        var withNullContent = StreamingChunk.of("id", "model", null, 2);

        // Then
        assertTrue(withContent.hasContent());
        assertFalse(withEmptyContent.hasContent());
        assertFalse(withNullContent.hasContent());
    }

    @Test
    void shouldCheckIsLast() {
        // Given
        var lastChunk = StreamingChunk.of("id", "model", "Final", "stop", 5);
        var notLastChunk = StreamingChunk.of("id", "model", "Continue", null, 3);
        var emptyFinishReason = StreamingChunk.of("id", "model", "Text", "", 4);

        // Then
        assertTrue(lastChunk.isLast());
        assertFalse(notLastChunk.isLast());
        assertFalse(emptyFinishReason.isLast());
    }

    @Test
    void shouldCheckIsComplete() {
        // Given
        var complete = StreamingChunk.of("id", "model", "Done", "stop", 10);
        var notComplete = StreamingChunk.of("id", "model", "Text", "length", 5);
        var noFinishReason = StreamingChunk.of("id", "model", "Text", null, 3);

        // Then
        assertTrue(complete.isComplete());
        assertFalse(notComplete.isComplete());
        assertFalse(noFinishReason.isComplete());
    }

    @Test
    void shouldCheckWasTruncated() {
        // Given
        var truncated = StreamingChunk.of("id", "model", "...", "length", 8);
        var notTruncated = StreamingChunk.of("id", "model", "Done", "stop", 10);
        var noFinishReason = StreamingChunk.of("id", "model", "Text", null, 3);

        // Then
        assertTrue(truncated.wasTruncated());
        assertFalse(notTruncated.wasTruncated());
        assertFalse(noFinishReason.wasTruncated());
    }

    @Test
    void shouldCheckWasFiltered() {
        // Given
        var filtered = StreamingChunk.of("id", "model", "", "content_filter", 5);
        var notFiltered = StreamingChunk.of("id", "model", "Text", "stop", 6);
        var noFinishReason = StreamingChunk.of("id", "model", "Text", null, 3);

        // Then
        assertTrue(filtered.wasFiltered());
        assertFalse(notFiltered.wasFiltered());
        assertFalse(noFinishReason.wasFiltered());
    }

    @Test
    void shouldGetContentOrEmpty() {
        // Given
        var withContent = StreamingChunk.of("id", "model", "Hello", 0);
        var withNull = StreamingChunk.of("id", "model", null, 1);

        // Then
        assertEquals("Hello", withContent.getContentOrEmpty());
        assertEquals("", withNull.getContentOrEmpty());
    }

    @Test
    void shouldGetFinishReasonOrEmpty() {
        // Given
        var withReason = StreamingChunk.of("id", "model", "Text", "stop", 5);
        var withNull = StreamingChunk.of("id", "model", "Text", null, 3);

        // Then
        assertEquals("stop", withReason.getFinishReasonOrEmpty());
        assertEquals("", withNull.getFinishReasonOrEmpty());
    }

    @Test
    void shouldGenerateToStringWithContent() {
        // Given
        var chunk = StreamingChunk.of("id", "model", "Hello world", 3);

        // When
        String str = chunk.toString();

        // Then
        assertTrue(str.contains("index=3"));
        assertTrue(str.contains("content="));
        assertTrue(str.contains("Hello world"));
    }

    @Test
    void shouldGenerateToStringWithTruncatedLongContent() {
        // Given
        String longContent = "A".repeat(50);
        var chunk = StreamingChunk.of("id", "model", longContent, 2);

        // When
        String str = chunk.toString();

        // Then
        assertTrue(str.contains("index=2"));
        assertTrue(str.contains("..."));
        assertFalse(str.contains(longContent));
    }

    @Test
    void shouldGenerateToStringWithFinishReason() {
        // Given
        var chunk = StreamingChunk.of("id", "model", "Final", "stop", 10);

        // When
        String str = chunk.toString();

        // Then
        assertTrue(str.contains("index=10"));
        assertTrue(str.contains("finishReason='stop'"));
    }

    @Test
    void shouldGenerateToStringWithoutContentWhenNull() {
        // Given
        var chunk = StreamingChunk.of("id", "model", null, 1);

        // When
        String str = chunk.toString();

        // Then
        assertTrue(str.contains("index=1"));
        assertFalse(str.contains("content="));
    }

    @Test
    void shouldBeEqualWhenSameValues() {
        // Given
        Instant time = Instant.now();
        var chunk1 = new StreamingChunk("id", "model", "text", "stop", 5, time);
        var chunk2 = new StreamingChunk("id", "model", "text", "stop", 5, time);

        // Then
        assertEquals(chunk1, chunk2);
        assertEquals(chunk1.hashCode(), chunk2.hashCode());
    }

    @Test
    void shouldNotBeEqualWhenDifferentValues() {
        // Given
        var chunk1 = StreamingChunk.of("id1", "model", "text", 5);
        var chunk2 = StreamingChunk.of("id2", "model", "text", 5);

        // Then
        assertNotEquals(chunk1, chunk2);
    }

    @Test
    void shouldHandleZeroIndex() {
        // Given/When
        var chunk = StreamingChunk.of("id", "model", "First chunk", 0);

        // Then
        assertEquals(0, chunk.index());
        assertDoesNotThrow(() -> chunk.toString());
    }

    @Test
    void shouldHandleEmptyStringContent() {
        // Given/When
        var chunk = StreamingChunk.of("id", "model", "", 1);

        // Then
        assertFalse(chunk.hasContent());
        assertEquals("", chunk.getContentOrEmpty());
    }

    @Test
    void shouldHandleMultipleFinishReasons() {
        // Given
        var stopChunk = StreamingChunk.of("id", "model", "text", "stop", 1);
        var lengthChunk = StreamingChunk.of("id", "model", "text", "length", 2);
        var filterChunk = StreamingChunk.of("id", "model", "text", "content_filter", 3);
        var functionChunk = StreamingChunk.of("id", "model", "text", "function_call", 4);

        // Then
        assertTrue(stopChunk.isComplete());
        assertTrue(lengthChunk.wasTruncated());
        assertTrue(filterChunk.wasFiltered());
        assertFalse(functionChunk.isComplete());
        assertFalse(functionChunk.wasTruncated());
        assertFalse(functionChunk.wasFiltered());
    }

    @Test
    void shouldHandleWhitespaceContent() {
        // Given
        var chunk = StreamingChunk.of("id", "model", "   ", 1);

        // Then
        assertTrue(chunk.hasContent());
        assertEquals("   ", chunk.getContentOrEmpty());
    }

    @Test
    void shouldWorkInStreamingScenario() {
        // Given - simulating a streaming response
        var chunk0 = StreamingChunk.of("stream_1", "gpt-4", "The", 0);
        var chunk1 = StreamingChunk.of("stream_1", "gpt-4", " weather", 1);
        var chunk2 = StreamingChunk.of("stream_1", "gpt-4", " is", 2);
        var chunk3 = StreamingChunk.of("stream_1", "gpt-4", " sunny", 3);
        var chunk4 = StreamingChunk.of("stream_1", "gpt-4", "", "stop", 4);

        // Then
        assertTrue(chunk0.hasContent());
        assertTrue(chunk1.hasContent());
        assertTrue(chunk2.hasContent());
        assertTrue(chunk3.hasContent());
        assertFalse(chunk4.hasContent());

        assertFalse(chunk0.isLast());
        assertFalse(chunk1.isLast());
        assertFalse(chunk2.isLast());
        assertFalse(chunk3.isLast());
        assertTrue(chunk4.isLast());
        assertTrue(chunk4.isComplete());
    }
}

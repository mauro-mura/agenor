package dev.agenor.core.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolResultTest {

    @Test
    void constructor_validArgs_createsRecord() {
        var result = new McpToolResult("hello", false);

        assertThat(result.content()).isEqualTo("hello");
        assertThat(result.isError()).isFalse();
    }

    @Test
    void constructor_nullContent_normalizesToEmptyString() {
        var result = new McpToolResult(null, false);

        assertThat(result.content()).isEqualTo("");
    }

    @Test
    void success_factory_setsIsErrorFalse() {
        var result = McpToolResult.success("output");

        assertThat(result.content()).isEqualTo("output");
        assertThat(result.isError()).isFalse();
    }

    @Test
    void error_factory_setsIsErrorTrue() {
        var result = McpToolResult.error("something went wrong");

        assertThat(result.content()).isEqualTo("something went wrong");
        assertThat(result.isError()).isTrue();
    }

    @Test
    void recordEquality_sameValues_areEqual() {
        var a = McpToolResult.success("out");
        var b = McpToolResult.success("out");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void recordEquality_differentError_areNotEqual() {
        var a = McpToolResult.success("out");
        var b = McpToolResult.error("out");

        assertThat(a).isNotEqualTo(b);
    }
}

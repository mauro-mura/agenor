package dev.jentic.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void constructor_validArgs_createsRecord() {
        var tool = new McpTool("read_file", "Reads a file", null);

        assertThat(tool.name()).isEqualTo("read_file");
        assertThat(tool.description()).isEqualTo("Reads a file");
        assertThat(tool.inputSchema()).isNull();
    }

    @Test
    void constructor_withInputSchema_storesJsonNode() throws Exception {
        JsonNode schema = MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}");

        var tool = new McpTool("read_file", "Reads a file", schema);

        assertThat(tool.inputSchema()).isNotNull();
        assertThat(tool.inputSchema().get("type").asText()).isEqualTo("object");
    }

    @Test
    void constructor_nullName_throwsIllegalArgument() {
        assertThatThrownBy(() -> new McpTool(null, "desc", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void constructor_blankName_throwsIllegalArgument() {
        assertThatThrownBy(() -> new McpTool("  ", "desc", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void recordEquality_sameValues_areEqual() throws Exception {
        JsonNode schema = MAPPER.readTree("{\"type\":\"object\"}");
        var a = new McpTool("tool", "desc", schema);
        var b = new McpTool("tool", "desc", schema);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void recordEquality_differentName_areNotEqual() {
        var a = new McpTool("tool_a", "desc", null);
        var b = new McpTool("tool_b", "desc", null);

        assertThat(a).isNotEqualTo(b);
    }
}
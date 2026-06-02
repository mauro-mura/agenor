package dev.agenor.tools.cli.output;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TableFormatterTest {

    private TableFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new TableFormatter();
    }

    @Test
    void shouldRenderEmptyTable() {
        String result = formatter.render();
        assertThat(result).isEmpty();
    }

    @Test
    void shouldRenderTableWithHeaders() {
        formatter.addHeader("ID", "NAME", "STATUS");
        formatter.addRow("1", "Agent-1", "RUNNING");
        formatter.addRow("2", "Agent-2", "STOPPED");

        String result = formatter.render();

        assertThat(result).contains("ID");
        assertThat(result).contains("NAME");
        assertThat(result).contains("STATUS");
        assertThat(result).contains("Agent-1");
        assertThat(result).contains("Agent-2");
        assertThat(result).contains("RUNNING");
        assertThat(result).contains("STOPPED");
    }

    @Test
    void shouldHandleNullValues() {
        formatter.addHeader("COL1", "COL2");
        formatter.addRow("value", null);

        String result = formatter.render();

        assertThat(result).contains("value");
        assertThat(result).doesNotContain("null");
    }

    @Test
    void shouldAdjustColumnWidths() {
        formatter.addHeader("SHORT", "VERY_LONG_HEADER");
        formatter.addRow("a", "b");
        formatter.addRow("much_longer_value", "x");

        String result = formatter.render();

        // Should accommodate the longest value
        assertThat(result).contains("much_longer_value");
    }

    @Test
    void shouldClearTable() {
        formatter.addHeader("A", "B");
        formatter.addRow("1", "2");
        formatter.clear();

        String result = formatter.render();
        assertThat(result).isEmpty();
    }

    @Test
    void shouldRenderKeyValue() {
        String result = TableFormatter.keyValue(
                "Name", "Test Agent",
                "Status", "RUNNING",
                "ID", "agent-123"
        );

        assertThat(result).contains("Name:");
        assertThat(result).contains("Test Agent");
        assertThat(result).contains("Status:");
        assertThat(result).contains("RUNNING");
    }
}

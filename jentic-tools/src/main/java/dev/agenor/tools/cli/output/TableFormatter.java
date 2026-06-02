package dev.agenor.tools.cli.output;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple ASCII table formatter for CLI output.
 */
public class TableFormatter {

    private final List<String[]> rows = new ArrayList<>();
    private String[] headers;
    private int[] columnWidths;

    public TableFormatter addHeader(String... columns) {
        this.headers = columns;
        return this;
    }

    public TableFormatter addRow(String... values) {
        rows.add(values);
        return this;
    }

    public String render() {
        if (headers == null || headers.length == 0) {
            return "";
        }

        calculateColumnWidths();

        StringBuilder sb = new StringBuilder();

        // Header separator
        sb.append(renderSeparator());

        // Header row
        sb.append(renderRow(headers));
        sb.append(renderSeparator());

        // Data rows
        for (String[] row : rows) {
            sb.append(renderRow(row));
        }

        // Footer separator
        sb.append(renderSeparator());

        return sb.toString();
    }

    private void calculateColumnWidths() {
        int cols = headers.length;
        columnWidths = new int[cols];

        // Start with header widths
        for (int i = 0; i < cols; i++) {
            columnWidths[i] = headers[i].length();
        }

        // Check data widths
        for (String[] row : rows) {
            for (int i = 0; i < Math.min(cols, row.length); i++) {
                String val = row[i] != null ? row[i] : "";
                columnWidths[i] = Math.max(columnWidths[i], val.length());
            }
        }

        // Add padding
        for (int i = 0; i < cols; i++) {
            columnWidths[i] += 2; // 1 space padding on each side
        }
    }

    private String renderSeparator() {
        StringBuilder sb = new StringBuilder("+");
        for (int width : columnWidths) {
            sb.append("-".repeat(width));
            sb.append("+");
        }
        sb.append("\n");
        return sb.toString();
    }

    private String renderRow(String[] values) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < columnWidths.length; i++) {
            String val = (i < values.length && values[i] != null) ? values[i] : "";
            int padding = columnWidths[i] - val.length() - 1;
            sb.append(" ");
            sb.append(val);
            sb.append(" ".repeat(Math.max(0, padding)));
            sb.append("|");
        }
        sb.append("\n");
        return sb.toString();
    }

    public void clear() {
        rows.clear();
        headers = null;
        columnWidths = null;
    }

    /**
     * Render a simple key-value list.
     */
    public static String keyValue(String... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("Pairs must have even length");
        }

        int maxKeyLen = 0;
        for (int i = 0; i < pairs.length; i += 2) {
            maxKeyLen = Math.max(maxKeyLen, pairs[i].length());
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            String key = pairs[i];
            String value = pairs[i + 1];
            sb.append(String.format("%-" + (maxKeyLen + 1) + "s %s%n", key + ":", value));
        }

        return sb.toString();
    }
}

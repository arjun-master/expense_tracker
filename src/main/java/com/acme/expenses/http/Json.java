package com.acme.expenses.http;

import com.acme.expenses.model.Expense;
import com.acme.expenses.service.ReportSummary;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    private Json() {
    }

    public static Map<String, String> parseObject(String json) {
        Parser parser = new Parser(json == null ? "" : json);
        return parser.parseObject();
    }

    public static String expenseList(List<Expense> expenses) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"expenses\":[");
        appendExpenses(builder, expenses);
        builder.append("]}");
        return builder.toString();
    }

    public static String expense(Expense expense) {
        StringBuilder builder = new StringBuilder();
        appendExpense(builder, expense);
        return builder.toString();
    }

    public static String summary(ReportSummary summary) {
        StringBuilder builder = new StringBuilder();
        builder.append('{');
        field(builder, "total", money(summary.total())).append(',');
        field(builder, "count", Integer.toString(summary.count())).append(',');
        field(builder, "average", money(summary.average())).append(',');
        builder.append("\"byCategory\":[");
        for (int i = 0; i < summary.byCategory().size(); i++) {
            ReportSummary.CategoryBreakdown item = summary.byCategory().get(i);
            if (i > 0) {
                builder.append(',');
            }
            builder.append('{');
            stringField(builder, "category", item.category()).append(',');
            field(builder, "total", money(item.total())).append(',');
            field(builder, "count", Integer.toString(item.count())).append(',');
            field(builder, "percentage", BigDecimal.valueOf(item.percentage()).toPlainString());
            builder.append('}');
        }
        builder.append("],\"byMonth\":[");
        for (int i = 0; i < summary.byMonth().size(); i++) {
            ReportSummary.MonthBreakdown item = summary.byMonth().get(i);
            if (i > 0) {
                builder.append(',');
            }
            builder.append('{');
            stringField(builder, "month", item.month()).append(',');
            field(builder, "total", money(item.total())).append(',');
            field(builder, "count", Integer.toString(item.count()));
            builder.append('}');
        }
        builder.append("],\"recent\":[");
        appendExpenses(builder, summary.recent());
        builder.append("]}");
        return builder.toString();
    }

    public static String error(String message) {
        return "{\"error\":" + quote(message) + "}";
    }

    private static void appendExpenses(StringBuilder builder, List<Expense> expenses) {
        for (int i = 0; i < expenses.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            appendExpense(builder, expenses.get(i));
        }
    }

    private static void appendExpense(StringBuilder builder, Expense expense) {
        builder.append('{');
        stringField(builder, "id", expense.id()).append(',');
        stringField(builder, "date", expense.date().toString()).append(',');
        stringField(builder, "category", expense.category()).append(',');
        stringField(builder, "merchant", expense.merchant()).append(',');
        field(builder, "amount", money(expense.amount())).append(',');
        stringField(builder, "notes", expense.notes()).append(',');
        stringField(builder, "createdAt", expense.createdAt().toString());
        builder.append('}');
    }

    private static StringBuilder stringField(StringBuilder builder, String name, String value) {
        return field(builder, name, quote(value));
    }

    private static StringBuilder field(StringBuilder builder, String name, String value) {
        return builder.append('"').append(name).append("\":").append(value);
    }

    private static String money(BigDecimal value) {
        return value.stripTrailingZeros().scale() < 0
                ? value.setScale(0).toPlainString()
                : value.toPlainString();
    }

    private static String quote(String value) {
        StringBuilder builder = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (character < 0x20) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }

    private static final class Parser {
        private final String text;
        private int position;

        private Parser(String text) {
            this.text = text;
        }

        private Map<String, String> parseObject() {
            Map<String, String> values = new LinkedHashMap<>();
            skipWhitespace();
            expect('{');
            skipWhitespace();
            if (peek('}')) {
                position++;
                return values;
            }
            while (position < text.length()) {
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                String value = parseValue();
                values.put(key, value);
                skipWhitespace();
                if (peek(',')) {
                    position++;
                    skipWhitespace();
                    continue;
                }
                expect('}');
                skipWhitespace();
                if (position != text.length()) {
                    throw new IllegalArgumentException("Unexpected data after JSON object");
                }
                return values;
            }
            throw new IllegalArgumentException("Unterminated JSON object");
        }

        private String parseValue() {
            if (peek('"')) {
                return parseString();
            }
            int start = position;
            while (position < text.length()) {
                char character = text.charAt(position);
                if (character == ',' || character == '}' || Character.isWhitespace(character)) {
                    break;
                }
                position++;
            }
            if (start == position) {
                throw new IllegalArgumentException("Missing JSON value");
            }
            String value = text.substring(start, position);
            return "null".equals(value) ? "" : value;
        }

        private String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (position < text.length()) {
                char character = text.charAt(position++);
                if (character == '"') {
                    return builder.toString();
                }
                if (character != '\\') {
                    builder.append(character);
                    continue;
                }
                if (position >= text.length()) {
                    throw new IllegalArgumentException("Invalid JSON escape");
                }
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    case '/' -> builder.append('/');
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> {
                        if (position + 4 > text.length()) {
                            throw new IllegalArgumentException("Invalid unicode escape");
                        }
                        String hex = text.substring(position, position + 4);
                        builder.append((char) Integer.parseInt(hex, 16));
                        position += 4;
                    }
                    default -> throw new IllegalArgumentException("Unsupported JSON escape");
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        private boolean peek(char expected) {
            return position < text.length() && text.charAt(position) == expected;
        }

        private void expect(char expected) {
            if (!peek(expected)) {
                throw new IllegalArgumentException("Expected '" + expected + "'");
            }
            position++;
        }
    }
}

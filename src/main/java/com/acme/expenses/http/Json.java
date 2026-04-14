package com.acme.expenses.http;

import com.acme.expenses.model.Expense;
import com.acme.expenses.service.ReportSummary;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    private Json() {
    }

    public static Map<String, String> parseObject(String json) {
        return new Parser(json).parseObject();
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
        String text = value == null ? "" : value;
        StringBuilder builder = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
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
            this.text = text == null ? "" : text;
        }

        private Map<String, String> parseObject() {
            Map<String, String> values = new LinkedHashMap<>();
            skipWhitespace();
            expect('{');
            skipWhitespace();
            if (peek('}')) {
                position++;
                ensureFullyConsumed();
                return values;
            }
            while (true) {
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
                ensureFullyConsumed();
                return values;
            }
        }

        private String parseValue() {
            if (peek('"')) {
                return parseString();
            }
            if (peek('{') || peek('[')) {
                return parseStructuredValue();
            }
            if (matchLiteral("null")) {
                return null;
            }
            if (matchLiteral("true")) {
                return "true";
            }
            if (matchLiteral("false")) {
                return "false";
            }
            int start = position;
            while (position < text.length() && !isValueDelimiter(text.charAt(position))) {
                position++;
            }
            if (start == position) {
                throw new IllegalArgumentException("Missing JSON value");
            }
            return text.substring(start, position);
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
                        try {
                            builder.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException exception) {
                            throw new IllegalArgumentException("Invalid unicode escape", exception);
                        }
                        position += 4;
                    }
                    default -> throw new IllegalArgumentException("Unsupported JSON escape");
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private String parseStructuredValue() {
            int start = position;
            ArrayDeque<Character> expectedClosers = new ArrayDeque<>();
            expectedClosers.push(matchingClose(text.charAt(position++)));
            boolean inString = false;
            while (position < text.length()) {
                char character = text.charAt(position++);
                if (inString) {
                    if (character == '\\') {
                        if (position >= text.length()) {
                            throw new IllegalArgumentException("Invalid JSON escape");
                        }
                        char escaped = text.charAt(position++);
                        if (escaped == 'u') {
                            if (position + 4 > text.length()) {
                                throw new IllegalArgumentException("Invalid unicode escape");
                            }
                            position += 4;
                        }
                    } else if (character == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (character == '"') {
                    inString = true;
                    continue;
                }
                if (character == '{' || character == '[') {
                    expectedClosers.push(matchingClose(character));
                    continue;
                }
                if (character == '}' || character == ']') {
                    if (expectedClosers.isEmpty() || character != expectedClosers.pop()) {
                        throw new IllegalArgumentException("Mismatched JSON structure");
                    }
                    if (expectedClosers.isEmpty()) {
                        return text.substring(start, position);
                    }
                }
            }
            throw new IllegalArgumentException("Unterminated JSON value");
        }

        private boolean matchLiteral(String literal) {
            if (!text.regionMatches(position, literal, 0, literal.length())) {
                return false;
            }
            int end = position + literal.length();
            if (end < text.length() && !isValueDelimiter(text.charAt(end))) {
                return false;
            }
            position = end;
            return true;
        }

        private void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        private void ensureFullyConsumed() {
            if (position != text.length()) {
                throw new IllegalArgumentException("Unexpected data after JSON object");
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

        private boolean isValueDelimiter(char character) {
            return character == ',' || character == '}' || character == ']' || Character.isWhitespace(character);
        }

        private char matchingClose(char character) {
            return switch (character) {
                case '{' -> '}';
                case '[' -> ']';
                default -> throw new IllegalArgumentException("Unsupported JSON structure");
            };
        }
    }
}

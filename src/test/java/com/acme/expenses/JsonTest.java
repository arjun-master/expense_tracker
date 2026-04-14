package com.acme.expenses;

import com.acme.expenses.http.Json;

import java.util.Map;

final class JsonTest {
    private JsonTest() {
    }

    static void parsesNullValuesAndNestedStructures() {
        Map<String, String> values = Json.parseObject("""
                {"date":"2026-04-13","amount":42.50,"notes":null,"meta":{"nested":[1,{"x":"y"}]},"flag":true}
                """);

        Assertions.equals("2026-04-13", values.get("date"), "String values should parse");
        Assertions.equals("42.50", values.get("amount"), "Numeric values should parse");
        Assertions.equals(null, values.get("notes"), "Null values should be preserved");
        Assertions.equals("{\"nested\":[1,{\"x\":\"y\"}]}", values.get("meta"), "Nested objects should be preserved");
        Assertions.equals("true", values.get("flag"), "Boolean values should parse");

        Assertions.equals("{\"error\":\"\"}", Json.error(null), "Null error messages should serialize safely");
        Assertions.throwsType(IllegalArgumentException.class, () -> Json.parseObject(null),
                "Null JSON input should fail cleanly");
    }
}

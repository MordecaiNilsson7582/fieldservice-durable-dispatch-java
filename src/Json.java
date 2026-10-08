import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Json {
    private Json() { }
    static Map<String, Object> object(Map<?, ?> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        input.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }
    static String write(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            List<String> parts = new ArrayList<>();
            map.forEach((key, item) -> parts.add(write(String.valueOf(key)) + ":" + write(item)));
            return "{" + String.join(",", parts) + "}";
        }
        if (value instanceof Iterable<?> items) {
            List<String> parts = new ArrayList<>();
            items.forEach(item -> parts.add(write(item)));
            return "[" + String.join(",", parts) + "]";
        }
        throw new IllegalArgumentException("Unsupported JSON value");
    }
    static Object read(String input) {
        Parser parser = new Parser(input);
        Object value = parser.value();
        parser.space();
        if (parser.position != input.length()) throw new IllegalArgumentException("Trailing JSON content");
        return value;
    }
    private static final class Parser {
        final String text;
        int position;
        Parser(String text) { this.text = text; }
        void space() { while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++; }
        char peek() { space(); if (position >= text.length()) throw new IllegalArgumentException("Incomplete JSON"); return text.charAt(position); }
        void expect(char c) { if (peek() != c) throw new IllegalArgumentException("Invalid JSON"); position++; }
        Object value() {
            char c = peek();
            if (c == '"') return string();
            if (c == '{') {
                position++;
                Map<String, Object> map = new LinkedHashMap<>();
                if (peek() == '}') { position++; return map; }
                do {
                    String key = string(); expect(':'); map.put(key, value());
                    char separator = peek(); position++;
                    if (separator == '}') return map;
                    if (separator != ',') throw new IllegalArgumentException("Invalid object");
                } while (true);
            }
            if (c == '[') {
                position++;
                List<Object> list = new ArrayList<>();
                if (peek() == ']') { position++; return list; }
                do {
                    list.add(value());
                    char separator = peek(); position++;
                    if (separator == ']') return list;
                    if (separator != ',') throw new IllegalArgumentException("Invalid array");
                } while (true);
            }
            for (String literal : List.of("true", "false", "null"))
                if (text.startsWith(literal, position)) { position += literal.length(); return literal.equals("null") ? null : Boolean.valueOf(literal); }
            int start = position;
            while (position < text.length() && "-+0123456789.eE".indexOf(text.charAt(position)) >= 0) position++;
            if (start == position) throw new IllegalArgumentException("Invalid JSON value");
            return Double.valueOf(text.substring(start, position));
        }
        String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (position < text.length()) {
                char c = text.charAt(position++);
                if (c == '"') return out.toString();
                if (c == '\\') {
                    if (position >= text.length()) throw new IllegalArgumentException("Invalid escape");
                    char next = text.charAt(position++);
                    if (next == 'u') {
                        if (position + 4 > text.length()) throw new IllegalArgumentException("Invalid unicode escape");
                        out.append((char) Integer.parseInt(text.substring(position, position + 4), 16)); position += 4;
                    } else out.append(switch (next) { case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; case 'b' -> '\b'; case 'f' -> '\f'; case '"' -> '"'; case '\\' -> '\\'; case '/' -> '/'; default -> throw new IllegalArgumentException("Invalid escape"); });
                } else out.append(c);
            }
            throw new IllegalArgumentException("Unterminated string");
        }
    }
}

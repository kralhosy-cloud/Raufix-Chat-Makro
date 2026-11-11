package dev.chatmacro.studio.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A tiny, dependency-free JSON model, parser and writer.
 *
 * <p>Minecraft happens to ship Gson, but the persistence format is the one part
 * of this mod that must never silently lose player data, and the authoring
 * environment could not confirm which Gson version 1.21.11 puts on the runtime
 * classpath. Keeping the codec self-contained removes that risk entirely: no
 * shaded-copy conflicts, no version surprises, and it is fully unit-tested.</p>
 *
 * <p>The parser is deliberately strict about the things that bite configuration
 * files: nesting depth, document size, control characters, unpaired surrogates
 * and duplicate object keys.</p>
 */
public final class Json {

    public static final int MAX_DEPTH = 64;
    public static final int MAX_INPUT_CHARS = 8 * 1024 * 1024;

    private Json() {
    }

    // ---------------------------------------------------------------- model

    public sealed interface Value permits Obj, Arr, Str, Num, Bool, Nil {
        default Obj asObj() { throw new JsonException("expected object, found " + kindName()); }
        default Arr asArr() { throw new JsonException("expected array, found " + kindName()); }
        default Str asStr() { throw new JsonException("expected string, found " + kindName()); }
        default Num asNum() { throw new JsonException("expected number, found " + kindName()); }
        default Bool asBool() { throw new JsonException("expected boolean, found " + kindName()); }
        String kindName();
    }

    /** Object with insertion-ordered keys; duplicate keys are rejected. */
    public record Obj(LinkedHashMap<String, Value> members) implements Value {
        public static Obj of() { return new Obj(new LinkedHashMap<>()); }
        @Override public Obj asObj() { return this; }
        public Obj with(String key, Value value) {
            if (members.containsKey(key)) throw new JsonException("duplicate key: " + key);
            members.put(Objects.requireNonNull(key), Objects.requireNonNull(value));
            return this;
        }
        public Value get(String key) { return members.get(key); }
        public boolean has(String key) { return members.containsKey(key); }
        public String getString(String key, String fallback) {
            Value v = members.get(key);
            return v instanceof Str s ? s.value() : fallback;
        }
        public long getLong(String key, long fallback) {
            Value v = members.get(key);
            return v instanceof Num n ? n.value() : fallback;
        }
        public boolean getBoolean(String key, boolean fallback) {
            Value v = members.get(key);
            return v instanceof Bool b ? b.value() : fallback;
        }
        public Arr getArr(String key) {
            Value v = members.get(key);
            return v instanceof Arr a ? a : Arr.EMPTY;
        }
        public Obj getObj(String key) {
            Value v = members.get(key);
            return v instanceof Obj o ? o : null;
        }
        @Override public String kindName() { return "object"; }
    }

    public record Arr(List<Value> elements) implements Value {
        public static final Arr EMPTY = new Arr(List.of());
        @Override public Arr asArr() { return this; }
        public static Arr of() { return new Arr(new ArrayList<>()); }
        public Arr add(Value value) {
            elements.add(Objects.requireNonNull(value));
            return this;
        }
        public int size() { return elements.size(); }
        public Value get(int index) { return elements.get(index); }
        @Override public String kindName() { return "array"; }
    }

    public record Str(String value) implements Value {
        public static final Str EMPTY = new Str("");
        @Override public Str asStr() { return this; }
        @Override public String kindName() { return "string"; }
    }

    public record Num(long value) implements Value {
        @Override public Num asNum() { return this; }
        @Override public String kindName() { return "number"; }
    }

    public record Bool(boolean value) implements Value {
        public static final Bool TRUE = new Bool(true);
        public static final Bool FALSE = new Bool(false);
        public static Bool of(boolean b) { return b ? TRUE : FALSE; }
        @Override public Bool asBool() { return this; }
        @Override public String kindName() { return "boolean"; }
    }

    public record Nil() implements Value {
        public static final Nil INSTANCE = new Nil();
        @Override public String kindName() { return "null"; }
    }

    public static Str str(String s) { return new Str(Objects.requireNonNull(s)); }
    public static Num num(long n) { return new Num(n); }
    public static Bool bool(boolean b) { return Bool.of(b); }
    public static Nil nil() { return Nil.INSTANCE; }

    /** Thrown for malformed input and for schema violations. */
    public static final class JsonException extends RuntimeException {
        public JsonException(String message) { super(message); }
        public JsonException(String message, Throwable cause) { super(message, cause); }
    }

    // -------------------------------------------------------------- parser

    public static Value parse(String input) {
        return parse(input, MAX_INPUT_CHARS);
    }

    public static Value parse(String input, int maxChars) {
        Objects.requireNonNull(input, "input");
        if (input.length() > maxChars) {
            throw new JsonException("document too large: " + input.length() + " chars");
        }
        Parser p = new Parser(input);
        Value v = p.parseValue(0);
        p.skipWhitespace();
        if (!p.atEnd()) throw new JsonException("trailing content at offset " + p.pos);
        return v;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        boolean atEnd() { return pos >= s.length(); }

        void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else return;
            }
        }

        Value parseValue(int depth) {
            if (depth > MAX_DEPTH) throw new JsonException("nesting too deep at offset " + pos);
            skipWhitespace();
            if (atEnd()) throw new JsonException("unexpected end of document");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObject(depth + 1);
                case '[' -> parseArray(depth + 1);
                case '"' -> new Str(parseString());
                case 't' -> parseLiteral("true", Bool.TRUE);
                case 'f' -> parseLiteral("false", Bool.FALSE);
                case 'n' -> parseLiteral("null", Nil.INSTANCE);
                default -> parseNumber();
            };
        }

        Value parseLiteral(String literal, Value value) {
            if (!s.startsWith(literal, pos)) throw new JsonException("bad literal at offset " + pos);
            pos += literal.length();
            return value;
        }

        Obj parseObject(int depth) {
            pos++; // '{'
            Obj obj = Obj.of();
            skipWhitespace();
            if (!atEnd() && s.charAt(pos) == '}') { pos++; return obj; }
            while (true) {
                skipWhitespace();
                if (atEnd()) throw new JsonException("unterminated object");
                if (s.charAt(pos) != '"') throw new JsonException("object key must be a string at offset " + pos);
                String key = parseString();
                skipWhitespace();
                if (atEnd() || s.charAt(pos) != ':') throw new JsonException("expected ':' at offset " + pos);
                pos++;
                Value value = parseValue(depth);
                if (obj.has(key)) throw new JsonException("duplicate key '" + key + "' at offset " + pos);
                obj.members.put(key, value);
                skipWhitespace();
                if (atEnd()) throw new JsonException("unterminated object");
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return obj; }
                throw new JsonException("expected ',' or '}' at offset " + pos);
            }
        }

        Arr parseArray(int depth) {
            pos++; // '['
            Arr arr = Arr.of();
            skipWhitespace();
            if (!atEnd() && s.charAt(pos) == ']') { pos++; return arr; }
            while (true) {
                arr.add(parseValue(depth));
                skipWhitespace();
                if (atEnd()) throw new JsonException("unterminated array");
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return arr; }
                throw new JsonException("expected ',' or ']' at offset " + pos);
            }
        }

        String parseString() {
            pos++; // opening quote
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) throw new JsonException("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return out.toString();
                if (c < 0x20) throw new JsonException("raw control character in string at offset " + (pos - 1));
                if (c != '\\') { out.append(c); continue; }
                if (atEnd()) throw new JsonException("unterminated escape");
                char e = s.charAt(pos++);
                switch (e) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        int cp = parseHex4();
                        if (Character.isHighSurrogate((char) cp)) {
                            if (pos + 1 < s.length() && s.charAt(pos) == '\\' && s.charAt(pos + 1) == 'u') {
                                pos += 2;
                                int low = parseHex4();
                                if (!Character.isLowSurrogate((char) low)) {
                                    throw new JsonException("unpaired high surrogate at offset " + pos);
                                }
                                out.appendCodePoint(Character.toCodePoint((char) cp, (char) low));
                            } else {
                                throw new JsonException("unpaired high surrogate at offset " + pos);
                            }
                        } else if (Character.isLowSurrogate((char) cp)) {
                            throw new JsonException("unpaired low surrogate at offset " + pos);
                        } else {
                            out.append((char) cp);
                        }
                    }
                    default -> throw new JsonException("invalid escape '\\" + e + "' at offset " + (pos - 1));
                }
            }
        }

        int parseHex4() {
            if (pos + 4 > s.length()) throw new JsonException("truncated \\u escape");
            String hex = s.substring(pos, pos + 4);
            pos += 4;
            try {
                return Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new JsonException("invalid \\u escape: " + hex);
            }
        }

        Num parseNumber() {
            int start = pos;
            if (!atEnd() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) pos++;
            int digits = 0;
            while (!atEnd() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') { pos++; digits++; }
            if (digits == 0) throw new JsonException("invalid number at offset " + start);
            String text = s.substring(start, pos);
            try {
                return new Num(Long.parseLong(text));
            } catch (NumberFormatException e) {
                throw new JsonException("number out of range at offset " + start + ": " + text);
            }
        }
    }

    // -------------------------------------------------------------- writer

    public static String write(Value value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value, 0, true);
        sb.append('\n');
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Value value, int indent, boolean pretty) {
        switch (value) {
            case Obj obj -> {
                if (obj.members.isEmpty()) { sb.append("{}"); return; }
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, Value> e : obj.members.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    newline(sb, indent + 1, pretty);
                    writeString(sb, e.getKey());
                    sb.append(':');
                    if (pretty) sb.append(' ');
                    writeValue(sb, e.getValue(), indent + 1, pretty);
                }
                newline(sb, indent, pretty);
                sb.append('}');
            }
            case Arr arr -> {
                if (arr.elements.isEmpty()) { sb.append("[]"); return; }
                sb.append('[');
                boolean first = true;
                for (Value element : arr.elements) {
                    if (!first) sb.append(',');
                    first = false;
                    newline(sb, indent + 1, pretty);
                    writeValue(sb, element, indent + 1, pretty);
                }
                newline(sb, indent, pretty);
                sb.append(']');
            }
            case Str str -> writeString(sb, str.value());
            case Num num -> sb.append(num.value());
            case Bool bool -> sb.append(bool.value());
            case Nil nil -> sb.append("null");
        }
    }

    private static void newline(StringBuilder sb, int indent, boolean pretty) {
        if (!pretty) return;
        sb.append('\n');
        for (int i = 0; i < indent; i++) sb.append("  ");
    }

    /** Escapes so the output is valid JSON and safe for control characters. */
    static void writeString(StringBuilder sb, String text) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x7F) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** Convenience for callers that only need a read-only view. */
    public static Map<String, Value> membersOf(Obj obj) {
        return Collections.unmodifiableMap(obj.members);
    }
}

package io.github.yudigaga.lightfreeze.tags;

import java.util.HashSet;
import java.util.Set;

// Android's JSON parser accepts comments, duplicate keys and other non-JSON input.
final class TagJsonSyntax {
    private final String input;
    private int cursor;
    private TagJsonSyntax(String input) { this.input = input; }

    static void check(String input) {
        TagJsonSyntax parser = new TagJsonSyntax(input);
        parser.value(0);
        parser.space();
        if (parser.cursor != input.length()) throw invalid();
    }

    private void value(int depth) {
        if (depth > 8) throw new IllegalArgumentException("标签配置嵌套过深");
        space();
        if (cursor == input.length()) throw invalid();
        char ch = input.charAt(cursor);
        if (ch == '{') {
            cursor++;
            Set<String> keys = new HashSet<>();
            if (take('}')) return;
            do {
                space();
                if (cursor == input.length() || input.charAt(cursor) != '"') throw invalid();
                if (!keys.add(string())) throw new IllegalArgumentException("标签配置存在重复字段");
                if (!take(':')) throw invalid();
                value(depth + 1);
                if (take('}')) return;
            } while (take(','));
            throw invalid();
        }
        if (ch == '[') {
            cursor++;
            if (take(']')) return;
            do {
                value(depth + 1);
                if (take(']')) return;
            } while (take(','));
            throw invalid();
        }
        if (ch == '"') { string(); return; }
        for (String literal : new String[]{"true", "false", "null"}) {
            if (input.startsWith(literal, cursor)) { cursor += literal.length(); return; }
        }
        if (ch == '-') cursor++;
        if (cursor == input.length()) throw invalid();
        if (input.charAt(cursor) == '0') cursor++;
        else {
            if (input.charAt(cursor) < '1' || input.charAt(cursor) > '9') throw invalid();
            digits();
        }
        if (cursor < input.length() && input.charAt(cursor) == '.') { cursor++; requireDigits(); }
        if (cursor < input.length() && (input.charAt(cursor) == 'e' || input.charAt(cursor) == 'E')) {
            cursor++;
            if (cursor < input.length() && (input.charAt(cursor) == '+' || input.charAt(cursor) == '-')) cursor++;
            requireDigits();
        }
    }

    private String string() {
        cursor++;
        StringBuilder result = new StringBuilder();
        while (cursor < input.length()) {
            char ch = input.charAt(cursor++);
            if (ch == '"') return result.toString();
            if (ch < 0x20) throw invalid();
            if (ch == '\\') {
                if (cursor == input.length()) throw invalid();
                char escape = input.charAt(cursor++);
                switch (escape) {
                    case '"', '\\', '/' -> ch = escape;
                    case 'b' -> ch = '\b';
                    case 'f' -> ch = '\f';
                    case 'n' -> ch = '\n';
                    case 'r' -> ch = '\r';
                    case 't' -> ch = '\t';
                    case 'u' -> {
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            if (cursor == input.length()) throw invalid();
                            char hex = input.charAt(cursor++);
                            int digit = hex <= 127 ? Character.digit(hex, 16) : -1;
                            if (digit < 0) throw invalid();
                            code = code * 16 + digit;
                        }
                        ch = (char) code;
                    }
                    default -> throw invalid();
                }
            }
            result.append(ch);
        }
        throw invalid();
    }

    private void requireDigits() {
        int start = cursor;
        digits();
        if (cursor == start) throw invalid();
    }
    private void digits() {
        while (cursor < input.length() && input.charAt(cursor) >= '0' && input.charAt(cursor) <= '9') cursor++;
    }
    private boolean take(char ch) {
        space();
        if (cursor < input.length() && input.charAt(cursor) == ch) { cursor++; return true; }
        return false;
    }
    private void space() {
        while (cursor < input.length()) {
            char ch = input.charAt(cursor);
            if (ch != ' ' && ch != '\t' && ch != '\r' && ch != '\n') return;
            cursor++;
        }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("标签配置不是有效的 JSON"); }
}

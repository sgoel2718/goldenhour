package com.goldenhour.infra;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON writer and recursive-descent parser (objects, arrays, strings, numbers, booleans, null).
 * The core deliberately has zero third-party dependencies; this is ~120 lines instead of a 2 MB library.
 */
public final class Json {
    private Json() {}

    public static String write(Object o) { StringBuilder sb = new StringBuilder(); write(o, sb); return sb.toString(); }

    @SuppressWarnings("unchecked")
    private static void write(Object o, StringBuilder sb) {
        if (o == null) sb.append("null");
        else if (o instanceof String s) quote(s, sb);
        else if (o instanceof Double d) sb.append(d.isNaN() || d.isInfinite() ? "null" : (d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf(d.longValue()) : trim(String.format(java.util.Locale.ROOT, "%.6f", d))));
        else if (o instanceof Float f) write((double) f, sb);
        else if (o instanceof Number || o instanceof Boolean) sb.append(o);
        else if (o instanceof Enum<?> e) quote(e.name(), sb);
        else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(String.valueOf(e.getKey()), sb); sb.append(':'); write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (o instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object x : c) { if (!first) sb.append(','); first = false; write(x, sb); }
            sb.append(']');
        } else if (o instanceof double[] a) { List<Object> l = new ArrayList<>(); for (double x : a) l.add(x); write(l, sb); }
        else if (o instanceof int[] a) { List<Object> l = new ArrayList<>(); for (int x : a) l.add(x); write(l, sb); }
        else quote(o.toString(), sb);
    }

    private static String trim(String s) {
        int e = s.length();
        while (s.charAt(e - 1) == '0') e--;
        return s.substring(0, s.charAt(e - 1) == '.' ? e - 1 : e);
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        sb.append('"');
    }

    public static Object parse(String s) {
        int[] i = {0};
        Object v = value(s, i);
        ws(s, i);
        if (i[0] != s.length()) throw new IllegalArgumentException("trailing characters at " + i[0]);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String s) {
        Object o = parse(s);
        if (!(o instanceof Map)) throw new IllegalArgumentException("expected a JSON object");
        return (Map<String, Object>) o;
    }

    private static void ws(String s, int[] i) { while (i[0] < s.length() && Character.isWhitespace(s.charAt(i[0]))) i[0]++; }

    private static Object value(String s, int[] i) {
        ws(s, i);
        if (i[0] >= s.length()) throw new IllegalArgumentException("unexpected end");
        char c = s.charAt(i[0]);
        if (c == '{') {
            Map<String, Object> m = new LinkedHashMap<>();
            i[0]++; ws(s, i);
            if (s.charAt(i[0]) == '}') { i[0]++; return m; }
            while (true) {
                ws(s, i);
                String k = string(s, i);
                ws(s, i);
                expect(s, i, ':');
                m.put(k, value(s, i));
                ws(s, i);
                if (s.charAt(i[0]) == ',') { i[0]++; continue; }
                expect(s, i, '}');
                return m;
            }
        }
        if (c == '[') {
            List<Object> l = new ArrayList<>();
            i[0]++; ws(s, i);
            if (s.charAt(i[0]) == ']') { i[0]++; return l; }
            while (true) {
                l.add(value(s, i));
                ws(s, i);
                if (s.charAt(i[0]) == ',') { i[0]++; continue; }
                expect(s, i, ']');
                return l;
            }
        }
        if (c == '"') return string(s, i);
        if (s.startsWith("true", i[0])) { i[0] += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i[0])) { i[0] += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i[0])) { i[0] += 4; return null; }
        int st = i[0];
        while (i[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(i[0])) >= 0) i[0]++;
        if (st == i[0]) throw new IllegalArgumentException("unexpected '" + c + "' at " + st);
        return Double.parseDouble(s.substring(st, i[0]));
    }

    private static String string(String s, int[] i) {
        expect(s, i, '"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(i[0]++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char e = s.charAt(i[0]++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i[0], i[0] + 4), 16)); i[0] += 4; }
                    default -> sb.append(e);
                }
            } else sb.append(c);
        }
    }

    private static void expect(String s, int[] i, char c) {
        if (i[0] >= s.length() || s.charAt(i[0]) != c) throw new IllegalArgumentException("expected '" + c + "' at " + i[0]);
        i[0]++;
    }
}

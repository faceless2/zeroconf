package com.bfo.zeroconf;

import java.nio.*;
import java.util.*;

class Stringify {

    static Object parse(String in) {
        return parse(CharBuffer.wrap(in));
    }

    /**
     * A quick single-method JSON parser, intended to parse input which is expected to be valid.
     * Does not exacly match the JSON parsing rules for numbers.
     */
    static Object parse(CharBuffer in) {
        int tell = in.position();
        try {
            char c;
            while ((c=in.get()) == ' ' || c == '\n' || c == '\r' || c == '\t') {
                tell++;
            }
            Object out;
            if (c == '{') {
                Map<String,Object> m = new LinkedHashMap<String,Object>();
                while ((c=in.get()) == ' ' || c == '\n' || c == '\r' || c == '\t');
                if (c != '}') {
                    in.position(in.position() - 1);
                    do {
                        String key = (String)parse(in);
                        while ((c=in.get()) == ' ' || c == '\n' || c == '\r' || c == '\t');
                        if (c == ':') {
                            m.put((String)key, parse(in));
                            tell = in.position();
                        } else {
                            throw new UnsupportedOperationException("expecting colon");
                        }
                        while ((c=in.get()) == ' ' || c == '\n' || c == '\r' || c == '\t');
                        if (c != ',' && c != '}') {
                            throw new UnsupportedOperationException("expecting comma or end-map");
                        }
                    } while (c != '}');
                }
                out = m;
            } else if (c == '[') {
                List<Object> l = new ArrayList<Object>();
                while ((c=in.get()) == ' ' || c == '\n' || c == '\r' || c == '\t');
                if (c != ']') {
                    in.position(in.position() - 1);
                    do {
                        l.add(parse(in));
                        tell = in.position();
                        while ((c=in.get()) == ' ' || c == '\n' || c == '\r' || c == '\t');
                        if (c != ',' && c != ']') {
                            throw new UnsupportedOperationException("expecting comma or end-list");
                        }
                    } while (c != ']');
                }
                out = l;
            } else if (c == '"') {
                StringBuilder sb = new StringBuilder();
                while ((c=in.get()) != '"') {
                    if (c == '\\') {
                        c = in.get();
                        switch (c) {
                            case 'n': c = '\n'; break;
                            case 'r': c = '\r'; break;
                            case 't': c = '\t'; break;
                            case 'b': c = '\b'; break;
                            case 'f': c = '\f'; break;
                            case 'u': c = (char)Integer.parseInt(in.subSequence(0, 4).toString(), 16); in.position(in.position() + 4); break;
                        }
                    }
                    sb.append(c);
                }
                out = sb.toString();
            } else if (c == 't' && in.get() == 'r' && in.get() == 'u' && in.get() == 'e') {
                out = Boolean.TRUE;
            } else if (c == 'f' && in.get() == 'a' && in.get() == 'l' && in.get() == 's' && in.get() == 'e') {
                out = Boolean.FALSE;
            } else if (c == 'n' && in.get() == 'u' && in.get() == 'l' && in.get() == 'l') {
                out = null;
            } else if (c == '-' || (c >= '0' && c <= '9')) {
                StringBuilder sb = new StringBuilder();
                sb.append(c);
                while (in.hasRemaining()) {
                    if ((c=in.get()) == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) {
                        sb.append(c);
                    } else {
                        in.position(in.position() - 1);
                        break;
                    }
                }
                String s = sb.toString();
                try {
                    Long l = Long.parseLong(s);
                    if (l.longValue() == l.intValue()) {        // This can't be done with a ternary due to unboxing confusion
                        out = Integer.valueOf(l.intValue());
                    } else {
                        out = l;
                    }
                } catch (Exception e) {
                    try {
                        out = Double.parseDouble(s);
                    } catch (Exception e2) {
                        throw new UnsupportedOperationException("invalid number: " + s);
                    }
                }
            } else {
                throw new UnsupportedOperationException("invalid " + (c >= ' ' && c < 0x80 ? "'" + ((char)c) + "'" : "U+" + Integer.toHexString(c)));
            }
            return out;
        } catch (BufferUnderflowException e) {
            throw (IllegalArgumentException)new IllegalArgumentException("Parse failed: unexpected EOF").initCause(e);
        } catch (ClassCastException e) {
            in.position(tell);
            throw new IllegalArgumentException("Parse failed at " + in.position() + ": expected string");
        } catch (UnsupportedOperationException e) {
            in.position(tell);
            throw new IllegalArgumentException("Parse failed at " + in.position() + ": " + e.getMessage());
        }
    }

    static byte[] parseHex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i=0;i<b.length;i++) {
            b[i] = (byte)Integer.parseInt(s.substring(i*2, i*2 + 2), 16);
        }
        return b;
    }

    /**
     * A quick single-method JSON writer, intended to write Maps/Lists/Strings/Numbers/Booleans
     */
    static String toString(Object o) {
        if (o == null) {
            return null;
        } else if (o instanceof CharSequence) {
            CharSequence s = (CharSequence)o;
            StringBuilder sb = new StringBuilder();
            sb.append('"');
            for (int i=0;i<s.length();i++) {
                char c = s.charAt(i);
                if (c == '\n') {
                    sb.append("\\n");
                } else if (c == '\r') {
                    sb.append("\\r");
                } else if (c == '\t') {
                    sb.append("\\t");
                } else if (c == '"') {
                    sb.append("\\\"");
                } else if (c == '\\') {
                    sb.append("\\\\");
                } else if (Character.isISOControl(c)) {     // 0x00-0x1F, 0x80-0x9F
                    String t = Integer.toHexString(c);
                    sb.append("\\u");
                    for (int j=t.length();j<4;j++) {
                        sb.append('0');
                    }
                    sb.append(t);
                } else {
                    sb.append(c);
                }
            }
            sb.append('"');
            return sb.toString();
        } else if (o instanceof Map) {
            Map<?,?> m = (Map<?,?>)o;
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?,?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(toString((String)e.getKey()));
                sb.append(':');
                sb.append(toString(e.getValue()));
                first = false;
            }
            sb.append('}');
            return sb.toString();
        } else if (o instanceof List) {
            List<?> l = (List<?>)o;
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            boolean first = true;
            for (Object p : l) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(toString(p));
                first = false;
            }
            sb.append(']');
            return sb.toString();
        } else if (o instanceof Number || o instanceof Boolean) {
            return o.toString();
        } else {
            return o.toString();        // Any special processing would go here
        }
    }

}

package com.bfo.zeroconf;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.net.*;

/**
 * A single DNS record - a Packet has multiple Records.
 */
final class Record {

    static final int TTL_A = 120;
    static final int TTL_PTR = 28800;
    static final int TTL_SRV = 120;
    static final int TTL_TXT = 4500;

    static final int TYPE_A     = 0x01;
    static final int TYPE_CNAME = 0x05; // Not used by dns-sd
    static final int TYPE_PTR   = 0x0C;
    static final int TYPE_TXT   = 0x10;
    static final int TYPE_AAAA  = 0x1C;
    static final int TYPE_SRV   = 0x21;
    static final int TYPE_NSEC  = 0x2F; // Not used by dns-sd
    static final int TYPE_ANY   = 0xFF;
    private static final int CLAZZ = 0x8001;

    private final int type, clazz;
    private final String name;
    private final Object data;
    private int ttl;

    /**
     * @param type the type
     * @param clazz the class - seems to have no real impact
     * @param ttl the ttl in seconds
     * @param name the name
     * @param data any object, or null for questions
     */
    private Record(int type, int clazz, int ttl, String name, Object data) {
        this.type = type;
        this.clazz = clazz;
        this.ttl = ttl;
        this.name = name;
        if (name == null) {
            throw new IllegalArgumentException("Null name");
        }
        this.data = data;
        if (data == null) {
        } else if ((type == TYPE_A || type == TYPE_AAAA) && !(data instanceof InetAddress)) {
            throw new IllegalArgumentException("Data is a "+data.getClass().getName() + " not InetAddress");
        } else if (type == TYPE_PTR && !(data instanceof String)) {
            throw new IllegalArgumentException("Data is a "+data.getClass().getName() + " not String");
        } else if (type == TYPE_SRV && !(data instanceof SrvData)) {
            throw new IllegalArgumentException("Data is a "+data.getClass().getName() + " not SrvData");
        }
    }

    int getTTL() {
        return ttl;
    }

    void setTTL(int ttl) {
        this.ttl = ttl;
    }

    String getName() {
        return name;
    }

    int getType() {
        return type;
    }

    InetAddress getAddress() {
        return data instanceof InetAddress ? (InetAddress)data : null;
    }

    int getSrvPriority() {
        return data instanceof SrvData ? ((SrvData)data).priority : 0;
    }

    int getSrvWeight() {
        return data instanceof SrvData ? ((SrvData)data).weight : 0;
    }

    int getSrvPort() {
        return data instanceof SrvData ? ((SrvData)data).port : 0;
    }

    String getSrvHost() {
        return data instanceof SrvData ? ((SrvData)data).host : null;
    }

    String getPtrValue() {
        return data instanceof String ? (String)data : null;
    }

    @SuppressWarnings("unchecked")
    Map<String,String> getText() {
        return data instanceof Map ? (Map<String,String>)data : null;
    }

    //----------------------------------------------------
    // Static creation methods
    //----------------------------------------------------

    /**
     * Parse the output of Stringify.parse(record.toString()) back into a Record.
     */
    static Record parse(Map<String,Object> m) {
        int type;
        if (m.get("type") instanceof String) {
            switch ((String)m.get("type")) {
                case "ptr": type = TYPE_PTR; break;
                case "txt": type = TYPE_TXT; break;
                case "cname": type = TYPE_CNAME; break;
                case "nsec": type = TYPE_NSEC; break;
                case "a": type = TYPE_A; break;
                case "aaaa": type = TYPE_AAAA; break;
                case "srv": type = TYPE_SRV; break;
                case "any": type = TYPE_ANY; break;
                default: throw new IllegalArgumentException("Invalid type \"" + m.get("type") + "\"");
            }
        } else {
            type = ((Integer)m.get("type")).intValue();
        }
        int clazz = ((Integer)m.get("class")).intValue();
        int ttl = ((Integer)m.get("ttl")).intValue();
        String name = (String)m.get("name");
        Object data = null;
        if (type == TYPE_PTR) {
            data = m.get("value");
        } else if (type == TYPE_A || type == TYPE_AAAA) {
            try {
                data = InetAddress.getByName((String)m.get("address"));
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Invalid address \"" + m.get("address") + "\"", e);
            }
        } else if (type == TYPE_TXT) {
            data = m.get("data");
        } else if (type == TYPE_SRV) {
            if (m.get("host") != null) {
                String host = (String)m.get("host");
                int port = (Integer)m.get("port");
                int priority = (Integer)m.get("priority");
                int weight = (Integer)m.get("weight");
                data = new SrvData(priority, weight, port, host);
            }
        } else if (m.get("bytes") instanceof String) {
            data = Stringify.parseHex((String)m.get("bytes"));
        }
        return new Record(type, clazz, ttl, name, data);
    }

    /**
     * Create a new Question
     * @param type the type
     * @param name the name
     */
    static Record newQuestion(int type, String name) {
        return new Record(type, CLAZZ, 0, name, null);
    }

    /**
     * Create a new A or AAAA record
     * @param name the name
     * @param address the address
     */
    static Record newAddress(int ttl, String name, InetAddress address) {
        if (name == null) {
            throw new IllegalArgumentException("name is null");
        } else if (address instanceof Inet4Address) {
            return new Record(TYPE_A, CLAZZ, ttl, name, address);
        } else if (address instanceof Inet6Address) {
            return new Record(TYPE_AAAA, CLAZZ, ttl, name, address);
        } else {
            throw new IllegalArgumentException("address invalid");
        }
    }

    static Record newPtr(int ttl, String name, String value) {
        if (name == null || value == null) {
            throw new IllegalArgumentException("name or value is null");
        }
        return new Record(TYPE_PTR, CLAZZ, ttl, name, value);
    }

    static Record newSrv(int ttl, String name, String host, int port, int weight, int priority) {
        if (name == null || host == null || port < 1 || port > 65535) {
            throw new IllegalArgumentException("name, host or port is invalid");
        }
        return new Record(TYPE_SRV, CLAZZ, ttl, name, new SrvData(priority, weight, port, host));
    }

    static Record newTxt(int ttl, String name, Map<String,String> map) {
        if (name == null || map == null) {
            throw new IllegalArgumentException("name or map is invalid");
        }
        return new Record(TYPE_TXT, CLAZZ, ttl, name, map);
    }

    //----------------------------------------------------
    // Static methods for reading/writing
    //----------------------------------------------------

    static Record readAnswer(ByteBuffer in) {
//        System.out.println("RECORD: " + Packet.dump(in));
        int tell = in.position();
        try {
            String name = readName(in);
            int type = in.getShort() & 0xFFFF;
            int clazz = in.getShort() & 0xFFFF;
            int ttl = in.getInt();
            int len = in.getShort() & 0xFFFF;
            Object data;
            if (type == TYPE_PTR) {
                data = readName(in);
            } else if (type == TYPE_SRV) {
                int priority = in.getShort() & 0xffff;
                int weight = in.getShort() & 0xffff;
                int port = in.getShort() & 0xffff;
                String host = readName(in);
                data = new SrvData(priority, weight, port, host);
            } else if (type == TYPE_A || type == TYPE_AAAA) {
                byte[] buf = new byte[len];
                in.get(buf);
                data = InetAddress.getByAddress(buf);
            } else if (type == TYPE_TXT) {
                Map<String,String> map = new LinkedHashMap<String,String>();
                int end = in.position() + len;
                while (in.position() < end) {
                    String value = readString(in);
                    if (value.length() > 0) {
                        int ix = value.indexOf("=");
                        if (ix > 0) {
                            map.put(value.substring(0, ix), value.substring(ix + 1));
                        } else {
                            map.put(value, null);    // ???
                        }
                    }
                }
                data = Collections.<String,String>unmodifiableMap(map);
            } else {
//                System.out.println("UNKNOWN TYPE " + type+" len="+len);
                byte[] buf = new byte[len];
                in.get(buf);
                data = buf;
            }
            Record r =  new Record(type, clazz, ttl, name, data);
            return r;
        } catch (Exception e) {
            ((Buffer)in).position(tell);
            throw (RuntimeException)new RuntimeException("Failed reading record " + Packet.dump(in)).initCause(e);
        }
    }

    static Record readQuestion(ByteBuffer in) {
//        System.out.println("RECORD: " + Packet.dump(in));
        int tell = in.position();
        try {
            String name = readName(in);
            int type = in.getShort() & 0xFFFF;
            int clazz = in.getShort() & 0xFFFF;
            return new Record(type, clazz, 0, name, null);
        } catch (Exception e) {
            ((Buffer)in).position(tell);
            throw (RuntimeException)new RuntimeException("Failed reading record " + Packet.dump(in)).initCause(e);
        }
    }

    void write(ByteBuffer out) {
        final int pos1 = out.position();
        out.put(writeName(getName()));
        out.putShort((short)type);
        out.putShort((short)clazz);
        if (data != null) {
            out.putInt(ttl);
            int pos = out.position();
            out.putShort((short)0);
            if (type == TYPE_PTR) {
                out.put(writeName(getPtrValue()));
            } else if (type == TYPE_SRV) {
                out.putShort((short)getSrvPriority());
                out.putShort((short)getSrvWeight());
                out.putShort((short)getSrvPort());
                out.put(writeName(getSrvHost()));
            } else if (type == TYPE_A) {
                out.put(((Inet4Address)getAddress()).getAddress());
            } else if (type == TYPE_AAAA) {
                out.put(((Inet6Address)getAddress()).getAddress());
            } else if (type == TYPE_TXT) {
                if (getText().isEmpty()) {
                    out.put((byte)0);
                } else {
                    for (Map.Entry<String,String> e : getText().entrySet()) {
                        String value = e.getKey()+"="+e.getValue();
                        byte[] b = value.getBytes(StandardCharsets.UTF_8);
                        out.put((byte)b.length);
                        out.put(b);
                    }
                }
            } else if (data instanceof byte[]) {
                out.put((byte[])data);
            }
            if (out.position() > pos + 2) {
                int len = out.position() - pos - 2;
                out.putShort(pos, (short)len);
            } else {
                ((Buffer)out).position(pos);
            }

            /*
            String s1 = toString();
            int pos2 = out.position();
            int oldlimit = out.limit();
            out.limit(pos2);
            out.position(pos1);
            System.out.println(this);
            System.out.println(Packet.dump(out));
            Record r = readAnswer(out);
            String s2 = r.toString();
            if (!s1.equals(s2) || out.position() != pos2) {
                throw new Error("Should be " + s1 + "@"+pos2+" got " + s2+"@"+out.position());
            }
            out.limit(oldlimit);
            */
        }
    }

    private static byte[] writeName(String name) {
        ByteBuffer buf = ByteBuffer.allocate(name.length() * 2);
        int len = name.length();
        int start = 0;
        for (int i=0;i<=len;i++) {
            char c = i == len ? '.' : name.charAt(i);
            if (c == '.') {
                byte[] b = name.substring(start, i).getBytes(StandardCharsets.UTF_8);
                if (b.length >= 0x40) {
                    throw new UnsupportedOperationException("Not implemented yet");
                }
                buf.put((byte)b.length);
                buf.put(b);
                start = i + 1;
            }
        }
        buf.put((byte)0);
        byte[] out = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, out, 0, out.length);
        String s = readName(ByteBuffer.wrap(out, 0, out.length));
        if (!s.equals(name)) {
            throw new IllegalStateException("Wrong name: " + Stringify.toString(name) + " != " + Stringify.toString(s));
        }
        return out;
    }

    private static byte[] writeString(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length > 255) {
            throw new UnsupportedOperationException("String too long (" + b.length +" bytes)");
        }
        byte[] out = new byte[b.length + 1];
        out[0] = (byte)b.length;
        System.arraycopy(b, 0, out, 1, b.length);
        return out;
    }

    private static String readString(ByteBuffer in) {
//        System.out.println("STRING: " + Packet.dump(in));
        int len = in.get() & 0xFF;
        if (len == 0) {
            return "";
        } else {
            String s = new String(in.array(), in.position(), len, StandardCharsets.UTF_8);
            ((Buffer)in).position(in.position() + len);
            return s;
        }
    }

    private static String readName(ByteBuffer in) {
//        System.out.println("STRINGLIST: " + Packet.dump(in));
        StringBuilder sb = new StringBuilder();
        int len;
        int end = -1;
        while ((len = (in.get()&0xFF)) > 0) {
            if (len < 0x40) {
                if (sb.length() > 0) {
                    sb.append('.');
                }
                sb.append(new String(in.array(), in.position(), len, StandardCharsets.UTF_8));
                ((Buffer)in).position(in.position() + len);
            } else {
                int off = ((len & 0x3F) << 8) | (in.get() & 0xFF);       // Offset from start of packet
                if (end < 0) {
                    end = in.position();
                }
                ((Buffer)in).position(off);
            }
        }
        if (end >= 0) {
            ((Buffer)in).position(end);
        }
//        System.out.println("STRINGLIST OUT: " + Stringify.toString(sb.toString()));
        return sb.toString();
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"type\":");
        switch (type) {
             case TYPE_A:       sb.append("\"a\""); break;
             case TYPE_AAAA:    sb.append("\"aaaa\""); break;
             case TYPE_PTR:     sb.append("\"ptr\""); break;
             case TYPE_SRV:     sb.append("\"srv\""); break;
             case TYPE_TXT:     sb.append("\"txt\""); break;
             case TYPE_ANY:     sb.append("\"any\""); break;
             case TYPE_CNAME:   sb.append("\"cname\""); break;
             case TYPE_NSEC:    sb.append("\"nsec\""); break;
             default:           sb.append(Integer.toString(type));
        }
        sb.append(",\"name\":");
        sb.append(Stringify.toString(getName()));
        sb.append(",\"class\":");
        sb.append(clazz);
        sb.append(",\"ttl\":");
        sb.append(ttl);
        if (data != null) {
            int len = sb.length();
            if (type == TYPE_A || type == TYPE_AAAA) {
                sb.append(",\"address\":");
                try {
                    sb.append(Stringify.toString(InetAddress.getByAddress(getAddress().getAddress()).getHostAddress())); // Remove extra data from tostring
                } catch (Exception e) {
                    sb.append(Stringify.toString(getAddress().getHostAddress()));
                }
                len = 0;
            } else if (type == TYPE_PTR) {
                sb.append(",\"value\":");
                sb.append(Stringify.toString(getPtrValue()));
                len = 0;
            } else if (type == TYPE_SRV) {
                sb.append(",\"host\":");
                sb.append(Stringify.toString(getSrvHost()));
                sb.append(",\"port\":" + getSrvPort() + ",\"priority\":" + getSrvPriority() + ",\"weight\":" + getSrvWeight());
                len = 0;
            } else if (type == TYPE_TXT) {
                sb.append(",\"data\":{");
                boolean first = true;
                for (Map.Entry<String,String> e : getText().entrySet()) {
                    if (first) {
                        first = false;
                    } else {
                        sb.append(',');
                    }
                    sb.append(Stringify.toString(e.getKey()));
                    sb.append(':');
                    sb.append(Stringify.toString(e.getValue()));
                }
                sb.append("}");
                len = 0;
            } else {
                byte[] d = (byte[])data;
                sb.append(",\"bytes\":\"");
                for (int i=0;i<d.length;i++) {
                    int x = d[i] & 0xff;
                    if (x < 0x10) {
                        sb.append('0');
                    }
                    sb.append(Integer.toHexString(x));
                }
                sb.append('"');
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static class SrvData {
        final int priority, weight, port;
        final String host;
        SrvData(int priority, int weight, int port, String host) {
            this.priority = priority;
            this.weight = weight;
            this.port = port;
            this.host = host;
            if (host == null) {
                throw new IllegalArgumentException("Host is null");
            }
        }
    }

    /*
    public static void main(String[] args) throws Exception {
        for (String s : args) {
            System.out.println(Service.splitFQDN(s));
        }
    }
    */

}

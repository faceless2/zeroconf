package org.captainunlikely.zeroconf;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.net.*;

/**
 * Base class for a DNS record. Written entirely without the benefit of specifications.
 */
final class Record {

    static final int TYPE_A     = 0x01;
    static final int TYPE_CNAME = 0x05;
    static final int TYPE_PTR   = 0x0C;
    static final int TYPE_TXT   = 0x10;
    static final int TYPE_AAAA  = 0x1C;
    static final int TYPE_SRV   = 0x21;
    static final int TYPE_NSEC  = 0x2F;
    static final int TYPE_ANY   = 0xFF;
    private static final int CLAZZ = 0x8001;

    private final int type, clazz;
    private final String name;
    private final Object data;
    private int ttl;

    /**
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
            throw new Error(data.toString()+" "+data.getClass().getName());
        } else if (type == TYPE_PTR && !(data instanceof String)) {
            throw new Error(data.toString()+" "+data.getClass().getName());
        } else if (type == TYPE_SRV && !(data instanceof SrvData)) {
            throw new Error(data.toString()+" "+data.getClass().getName());
        }
        if (type == TYPE_PTR && data instanceof String && ((String)data).startsWith("...")) throw new Error(data.toString());
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

    boolean isUnicastQuery() {
        return (clazz & 0x80) != 0;
    }

    //----------------------------------------------------

    static Record newQuestion(int type, String name) {
        return new Record(type, CLAZZ, 0, name, null);
    }

    static Record newAddress(String name, InetAddress address) {
        if (name == null) {
            throw new IllegalArgumentException("name is null");
        } else if (address instanceof Inet4Address) {
            return new Record(TYPE_A, CLAZZ, 120, name, address);
        } else if (address instanceof Inet6Address) {
            return new Record(TYPE_AAAA, CLAZZ, 120, name, address);
        } else {
            throw new IllegalArgumentException("address invalid");
        }
    }

    static Record newPtr(String name, String value) {
        if (name == null || value == null) {
            throw new IllegalArgumentException("name or value is null");
        }
        return new Record(TYPE_PTR, CLAZZ, 28800, name, value);
    }

    static Record newSrv(String name, String host, int port, int weight, int priority) {
        if (name == null || host == null || port < 1 || port > 65535) {
            throw new IllegalArgumentException("name, host or port is invalid");
        }
        return new Record(TYPE_SRV, CLAZZ, 120, name, new SrvData(priority, weight, port, host));
    }

    static Record newTxt(String name, Map<String,String> map) {
        if (name == null || map == null || map.isEmpty()) {
            throw new IllegalArgumentException("name or map is invalid");
        }
        return new Record(TYPE_TXT, CLAZZ, 4500, name, map);
    }

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
                data = map;
            } else {
//                System.out.println("UNKNOWN TYPE " + type+" len="+len);
                byte[] buf = new byte[len];
                in.get(buf);
                data = buf;
            }
            Record r =  new Record(type, clazz, ttl, name, data);
            return r;
        } catch (Exception e) {
            in.position(tell);
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
            in.position(tell);
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
                for (Map.Entry<String,String> e : getText().entrySet()) {
                    String value = e.getKey()+"="+e.getValue();
                    byte[] b = value.getBytes(StandardCharsets.UTF_8);
                    out.put((byte)b.length);
                    out.put(b);
                }
            } else if (data instanceof byte[]) {
                out.put((byte[])data);
            }
            if (out.position() > pos + 2) {
                int len = out.position() - pos - 2;
                out.putShort(pos, (short)len);
            } else {
                out.position(pos);
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
        if (!s.equals(name)) throw new Error(Service.quote(name)+" "+Service.quote(s));
        return out;
    }

    private static byte[] writeString(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length > 255) {
            throw new UnsupportedOperationException("Not implemented yet");
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
        } else if (true || len < 0x40) {
            String s = new String(in.array(), in.position(), len, StandardCharsets.UTF_8);
            in.position(in.position() + len);
            return s;
        } else {
            int off = ((len & 0x3F) << 8) | (in.get() & 0xFF);       // Offset from start of packet
            len = in.get(off++) & 0xFF;
            String s = new String(in.array(), off, len, StandardCharsets.UTF_8);
            return s;
        }
//        return readName(in);
    }

    private static String readName(ByteBuffer in) {
        // See https://courses.cs.duke.edu/fall16/compsci356/DNS/DNS-primer.pdf
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
                in.position(in.position() + len);
            } else {
                int off = ((len & 0x3F) << 8) | (in.get() & 0xFF);       // Offset from start of packet
                if (end < 0) {
                    end = in.position();
                }
                in.position(off);
            }
        }
        if (end >= 0) {
            in.position(end);
        }
//        System.out.println("STRINGLIST OUT: " + Service.quote(sb.toString()));
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
        sb.append(Service.quote(getName()));
        sb.append(",\"class\":");
        sb.append(clazz);
        sb.append(",\"ttl\":");
        sb.append(ttl);
        if (data != null) {
            int len = sb.length();
            if (type == TYPE_A || type == TYPE_AAAA) {
                sb.append(",\"address\":");
                try {
                    sb.append(Service.quote(InetAddress.getByAddress(getAddress().getAddress()).toString())); // Remove extra data from tostring
                } catch (Exception e) {
                    sb.append(Service.quote(getAddress().toString()));
                }
                len = 0;
            } else if (type == TYPE_PTR) {
                sb.append(",\"value\":");
                sb.append(Service.quote(getPtrValue()));
                len = 0;
            } else if (type == TYPE_SRV) {
                sb.append(",\"host\":");
                sb.append(Service.quote(getSrvHost()));
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
                    sb.append(Service.quote(e.getKey()));
                    sb.append(':');
                    sb.append(Service.quote(e.getValue()));
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
            if (host == null) throw new Error();
        }
    }

    public static void main(String[] args) throws Exception {
        for (String s : args) {
            System.out.println(Service.splitFQDN(s));
        }
    }

}

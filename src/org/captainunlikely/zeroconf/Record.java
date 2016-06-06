package org.captainunlikely.zeroconf;

import java.nio.*;
import java.util.*;

/**
 * Base class for a DNS record. Written entirely without the benefit of specifications.
 */
class Record {

    static final int TYPE_A     = 0x01;
    static final int TYPE_PTR   = 0x0C;
    static final int TYPE_CNAME = 0x05;
    static final int TYPE_TXT   = 0x10;
    static final int TYPE_AAAA  = 0x1C;
    static final int TYPE_SRV   = 0x21;
    static final int TYPE_NSEC  = 0x2F;
    static final int TYPE_ANY   = 0xFF;

    private final int type;
    private String name;
    private int clazz;
    protected int ttl;
    private byte[] data;

    Record(int type) {
        this.type = type & 0xFFFF;
        setTTL(4500);
        this.clazz = 1;
    }

    String getName() {
        return name;
    }

    int getType() {
        return type;
    }

    Record setName(String name) {
        this.name = name;
        return this;
    }

    Record setTTL(int ttl) {
        this.ttl = ttl;
        return this;
    }

    protected static int writeName(String name, ByteBuffer out, Packet packet) {
        int len = name.length();
        int start = 0;
        for (int i=0;i<=len;i++) {
            char c = i == len ? '.' : name.charAt(i);
            if (c == '.') {
                out.put((byte)(i - start));
                for (int j=start;j<i;j++) {
                    out.put((byte)name.charAt(j));
                }
                start = i+1;
            }
        }
        out.put((byte)0);
        return name.length() + 2;
    }

    protected static String readName(ByteBuffer in) {
        StringBuilder sb = new StringBuilder();
        int len;
        while ((len = (in.get()&0xFF)) > 0) {
            if (len < 0x40) {
                if (sb.length() > 0) {
                    sb.append('.');
                }
                while (len-- > 0) {
                    sb.append((char)(in.get()&0xFF));
                }
            } else {
                int off = ((len & 0x3F)<<8) | (in.get()&0xFF);       // Offset from start of packet
                len = (in.get(off++)&0xFF);
                while (len-- > 0) {
                    sb.append((char)(in.get(off++)&0xFF));
                }
                break;
            }
        }
        return sb.toString();
    }

    protected void readData(int len, ByteBuffer in) {
        data = new byte[len];
        in.get(data);
    }

    protected int writeData(ByteBuffer out, Packet packet) {
        if (data != null) {
            out.put(data);
            return data.length;
        } else {
            return -1;
        }
    }

    static Record readAnswer(ByteBuffer in) {
        String name = readName(in);
        int type = in.getShort() & 0xFFFF;
        Record record = getInstance(type);
        record.setName(name);
        record.clazz = in.getShort() & 0xFFFF;
        record.ttl = in.getInt();
        int len = in.getShort() & 0xFFFF;
        record.readData(len, in);
        return record;
    }

    static Record readQuestion(ByteBuffer in) {
        String name = readName(in);
        int type = in.getShort() & 0xFFFF;
        Record record = getInstance(type);
        record.setName(name);
        record.clazz = in.getShort() & 0xFFFF;
        return record;
    }

    boolean isUnicastQuery() {
        return (clazz & 0x80) != 0;
    }

    public String toString() {
        return "{type:0x"+Integer.toHexString(type)+", name:\""+getName()+"\", data:"+data+"}";
    }

    private static Record getInstance(int type) {
        switch(type) {
            case TYPE_A:    return new RecordA();
            case TYPE_AAAA: return new RecordAAAA();
            case TYPE_SRV:  return new RecordSRV();
            case TYPE_PTR:  return new RecordPTR();
            case TYPE_TXT:  return new RecordTXT();
            case TYPE_ANY:  return new RecordANY();
//                case TYPE_NSEC: return new RecordNSEC();
            default: return new Record(type);
        }
    }

    void write(ByteBuffer out, Packet packet) {
        writeName(name, out, packet);
        out.putShort((short)type);
        out.putShort((short)clazz);
        out.putInt(ttl);
        int pos = out.position();
        out.putShort((short)0);
        int len = writeData(out, packet);
        if (len > 0) {
            out.putShort(pos, (short)len);
        } else {
            out.position(pos);
        }
    }

}

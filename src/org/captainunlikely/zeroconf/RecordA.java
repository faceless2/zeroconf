package org.captainunlikely.zeroconf;

import java.io.*;
import java.nio.*;
import java.net.*;

class RecordA extends Record {

    private byte[] address;

    RecordA() {
        super(TYPE_A);
    }

    RecordA(String name, Inet4Address address) {
        this();
        setName(name);
        setTTL(120);
        this.address = address.getAddress();
    }

    @Override protected void readData(int len, ByteBuffer in) {
        address = new byte[len];
        in.get(address);
    }

    @Override protected int writeData(ByteBuffer out, Packet packet) {
        if (address != null) {
            out.put(address);
            return address.length;
        } else {
            return -1;
        }
    }

    public Inet4Address getAddress() {
        try {
            return address == null ? null : (Inet4Address)InetAddress.getByAddress(address);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String toString() {
        return "{type:a, name:\""+getName()+"\", address:\""+getAddress()+"\"}";
    }
}


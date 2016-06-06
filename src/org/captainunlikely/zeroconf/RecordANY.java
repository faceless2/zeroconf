package org.captainunlikely.zeroconf;

import java.io.*;
import java.nio.*;
import java.net.*;

class RecordANY extends Record {

    RecordANY() {
        super(TYPE_ANY);
    }

    RecordANY(String name) {
        this();
        setName(name);
    }

    @Override protected void readData(int len, ByteBuffer in) {
        throw new IllegalStateException();
    }

    @Override protected int writeData(ByteBuffer out, Packet packet) {
        return -1;
    }

    public String toString() {
        return "{type:any, name:\""+getName()+"\"}";
    }
}


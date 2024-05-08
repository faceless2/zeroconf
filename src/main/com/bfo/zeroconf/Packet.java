package com.bfo.zeroconf;

import java.net.*;
import java.util.*;
import java.nio.*;

/**
 * A Service Dicovery Packet. This class is only of interest to developers.
 */
public class Packet {

    private final int id;
    private final int flags;
    private final List<Record> questions, answers, authorities, additionals;
    private InetSocketAddress address;

    private static final int FLAG_RESPONSE = 15;
    private static final int FLAG_AA       = 10;


    /**
     * Create a question packet
     * @param question the question record
     */
    Packet(Record question) {
        this.id = 0;
        this.flags = 0;
        this.questions = Collections.<Record>singletonList(question);
        this.answers = this.additionals = this.authorities = Collections.<Record>emptyList();
        this.address = null;
    }

    /**
     * Create a response packet
     * @param question the packet we're responding to
     * @param answers the answer records
     * @param additionals the additionals records
     */
    Packet(Packet question, List<Record> answers, List<Record> additionals) {
        this.id = question.id;
        if (additionals == null) {
            additionals = Collections.<Record>emptyList();
        }
        this.answers = answers;
        this.additionals = additionals;
        this.questions = this.authorities = Collections.<Record>emptyList();
        this.flags = (1<<FLAG_RESPONSE) | (1<<FLAG_AA);
    }

    /**
     * Create an announcement packet
     * @param service the service we're announcing
     */
    Packet(Service service) {
        String fqdn = service.getFQDN();
        String domain = service.getType() + service.getDomain();

        List<Record> answers = new ArrayList<Record>();
        List<Record> additionals = new ArrayList<Record>();
        answers.add(Record.newPtr(domain, fqdn));
        answers.add(Record.newSrv(fqdn, service.getHost(), service.getPort(), 0, 0));
        if (!service.getText().isEmpty()) {
            additionals.add(Record.newTxt(fqdn, service.getText()));
        }
        for (InetAddress address : service.getAddresses()) {
            additionals.add(Record.newAddress(service.getHost(), address));
        }

        this.id = 0;
        this.flags = (1<<FLAG_RESPONSE) | (1<<FLAG_AA); // response is required
        this.answers = Collections.<Record>unmodifiableList(answers);
        this.additionals = Collections.<Record>unmodifiableList(additionals);
        this.questions = this.authorities = Collections.<Record>emptyList();
        this.address = null;
    }

    /**
     * Create a packet from an incoming datagram
     * @param in the incoming packet
     * @param address the address we read from
     */
    Packet(ByteBuffer in, InetSocketAddress address) {
        try {
            this.address = address;
            this.id = in.getShort() & 0xFFFF;
            this.flags = in.getShort() & 0xFFFF;
            int numQuestions = in.getShort() & 0xFFFF;
            int numAnswers = in.getShort() & 0xFFFF;
            int numAuthorities = in.getShort() & 0xFFFF;
            int numAdditionals = in.getShort() & 0xFFFF;
            if (numQuestions > 0) {
                List<Record> questions = new ArrayList<Record>(numQuestions);
                for (int i=0;i<numQuestions;i++) {
                    questions.add(Record.readQuestion(in));
                }
                this.questions = Collections.<Record>unmodifiableList(questions);
            } else {
                this.questions = Collections.<Record>emptyList();
            }
            if (numAnswers > 0) {
                List<Record> answers = new ArrayList<Record>(numAnswers);
                for (int i=0;i<numAnswers;i++) {
                    if (in.hasRemaining()) {
                        answers.add(Record.readAnswer(in));
                    }
                }
                this.answers = Collections.<Record>unmodifiableList(answers);
            } else {
                this.answers = Collections.<Record>emptyList();
            }
            if (numAuthorities > 0) {
                List<Record> authorities = new ArrayList<Record>(numAuthorities);
                for (int i=0;i<numAuthorities;i++) {
                    if (in.hasRemaining()) {
                        authorities.add(Record.readAnswer(in));
                    }
                }
                this.authorities = Collections.<Record>unmodifiableList(authorities);
            } else {
                this.authorities = Collections.<Record>emptyList();
            }
            if (numAdditionals > 0) {
                List<Record> additionals = new ArrayList<Record>(numAdditionals);
                for (int i=0;i<numAdditionals;i++) {
                    if (in.hasRemaining()) {
                        additionals.add(Record.readAnswer(in));
                    }
                }
                this.additionals = Collections.<Record>unmodifiableList(additionals);
            } else {
                this.additionals = Collections.<Record>emptyList();
            }
        } catch (Exception e) {
            in.position(0);
            throw (RuntimeException)new RuntimeException("Can't read packet from " + dump(in)).initCause(e);
        }
    }

    /**
     * The address we read from
     */
    InetSocketAddress getAddress() {
        return address;
    }

    /**
     * The ID of the packet
     */
    int getID() {
        return id;
    }

    /**
     * Return true if it's a reponse, false if it's a query
     */
    boolean isResponse() {
        return (flags & (1<<FLAG_RESPONSE)) != 0;
    }

    /**
     * Write the packet
     * @param out the ByteByffer to write to
     */
    public void write(ByteBuffer out) {
        out.putShort((short)id);
        out.putShort((short)flags);
        out.putShort((short)questions.size());
        out.putShort((short)answers.size());
        out.putShort((short)authorities.size());
        out.putShort((short)additionals.size());
        for (Record r : questions) {
            r.write(out);
        }
        for (Record r : answers) {
            r.write(out);
        }
        for (Record r : authorities) {
            r.write(out);
        }
        for (Record r : additionals) {
            r.write(out);
        }
    }

    static String dump(ByteBuffer b) {
        StringBuilder sb = new StringBuilder();
        int pos = b.position();
        int len = b.remaining();
        for (int i=0;i<len;i++) {
            int v = b.get() & 0xff;
            if (v < 0x10) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(v));
        }
        b.position(pos);
        return sb.toString() + "@" + pos;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":");
        sb.append(id);
        sb.append(",\"flags\":");
        sb.append(flags);
        sb.append(",\"response\":" + isResponse());
        if (questions.size() > 0) {
            sb.append(",\"questions\":[");
            for (int i=0;i<questions.size();i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(questions.get(i));
            }
            sb.append(']');
        }
        if (answers.size() > 0) {
            sb.append(",\"answers\":[");
            for (int i=0;i<answers.size();i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(answers.get(i));
            }
            sb.append(']');
        }
        if (additionals.size() > 0) {
            sb.append(",\"additionals\":[");
            for (int i=0;i<additionals.size();i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(additionals.get(i));
            }
            sb.append(']');
        }
        if (authorities.size() > 0) {
            sb.append(",\"authorities\":[");
            for (int i=0;i<authorities.size();i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(authorities.get(i));
            }
            sb.append(']');
        }
        sb.append("}");
        return sb.toString();
    }

    List<Record> getQuestions() {
        return questions;
    }

    List<Record> getAnswers() {
        return answers;
    }

    List<Record> getAdditionals() {
        return additionals;
    }

    /*
    public static void main(String[] args) throws Exception {
        for (String s : args) {
            byte[] b = new byte[s.length() / 2];
            for (int i=0;i<s.length();i+=2) {
                b[i / 2] = (byte)Integer.parseInt(s.substring(i, i + 2), 16);
            }
            java.io.FileOutputStream out = new java.io.FileOutputStream("/tmp/t");
            out.write(b);
            out.close();
            Packet p = new Packet(ByteBuffer.wrap(b, 0, b.length), null);
            System.out.println(p);
        }
    }
    */

}

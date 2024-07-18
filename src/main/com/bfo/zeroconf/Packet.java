package com.bfo.zeroconf;

import java.net.*;
import java.util.*;
import java.nio.*;

/**
 * A Service Dicovery Packet. This class is only of interest to developers,
 */
public class Packet {

    private final int id;
    private final int flags;
    private final long timestamp;
    private final List<Record> questions, answers, authorities, additionals;
    private final NetworkInterface nic;

    private static final int FLAG_RESPONSE = 15;
    private static final int FLAG_AA       = 10;

    /**
     * Create a Packet from its String representation
     * @param tostring the packets string-format, in the same format as {@link #toString}
     * @throws IllegalArgumentException if the format is incorrec5
     * @since 1.0.1
     */
    @SuppressWarnings("unchecked") public Packet(String tostring) {
        Map<String,Object> map = (Map<String,Object>)Stringify.parse(tostring);
        this.id = ((Integer)map.get("id")).intValue();
        this.flags = ((Integer)map.get("flags")).intValue();
        this.timestamp = ((Number)map.get("timestamp")).longValue();
        NetworkInterface nic = null;
        if (map.get("nic") instanceof String) {
            try {
                nic = NetworkInterface.getByName((String)map.get("nic"));
            } catch (Exception e) { }
        }
        this.nic = nic;
        if (map.get("questions") instanceof List) {
            List<Record> l = new ArrayList<Record>();
            for (Object o : (List<Object>)map.get("questions")) {
                l.add(Record.parse((Map<String,Object>)o));
            }
            this.questions = Collections.<Record>unmodifiableList(l);
        } else {
            this.questions = Collections.<Record>emptyList();
        }
        if (map.get("answers") instanceof List) {
            List<Record> l = new ArrayList<Record>();
            for (Object o : (List<Object>)map.get("answers")) {
                l.add(Record.parse((Map<String,Object>)o));
            }
            this.answers = Collections.<Record>unmodifiableList(l);
        } else {
            this.answers = Collections.<Record>emptyList();
        }
        if (map.get("additionals") instanceof List) {
            List<Record> l = new ArrayList<Record>();
            for (Object o : (List<Object>)map.get("additionals")) {
                l.add(Record.parse((Map<String,Object>)o));
            }
            this.additionals = Collections.<Record>unmodifiableList(l);
        } else {
            this.additionals = Collections.<Record>emptyList();
        }
        if (map.get("authorities") instanceof List) {
            List<Record> l = new ArrayList<Record>();
            for (Object o : (List<Object>)map.get("authorities")) {
                l.add(Record.parse((Map<String,Object>)o));
            }
            this.authorities = Collections.<Record>unmodifiableList(l);
        } else {
            this.authorities = Collections.<Record>emptyList();
        }
    }

    private Packet(int id, int flags, NetworkInterface nic, List<Record> questions, List<Record> answers, List<Record> authorities, List<Record> additionals)  {
        this.timestamp = System.currentTimeMillis();
        this.id = id;
        this.flags = flags;
        this.nic = nic;
        this.questions = Collections.<Record>unmodifiableList(questions);
        this.answers = Collections.<Record>unmodifiableList(answers);
        this.authorities = Collections.<Record>unmodifiableList(authorities);
        this.additionals = Collections.<Record>unmodifiableList(additionals);
    }

    /**
     * Create a question packet.
     * If the supplied question is for A or AAAA, we automatically add the other one
     * @param question the question record
     */
    Packet(Record question) {
        this.timestamp = System.currentTimeMillis();
        this.id = 0;
        this.flags = 0;
        if (question.getType() == Record.TYPE_A) {
            Record aaaa = Record.newQuestion(Record.TYPE_AAAA, question.getName());
            this.questions = Collections.<Record>unmodifiableList(Arrays.asList(question, aaaa));
        } else if (question.getType() == Record.TYPE_AAAA) {
            Record a = Record.newQuestion(Record.TYPE_A, question.getName());
            this.questions = Collections.<Record>unmodifiableList(Arrays.asList(a, question));
        } else {
            this.questions = Collections.<Record>singletonList(question);
        }
        this.answers = this.additionals = this.authorities = Collections.<Record>emptyList();
        this.nic = null;
    }

    /**
     * Create a response packet
     * @param question the packet we're responding to
     * @param answers the answer records
     * @param additionals the additionals records
     */
    Packet(Packet question, List<Record> answers, List<Record> additionals) {
        this.timestamp = System.currentTimeMillis();
        this.id = question.id;
        this.nic = question.nic;
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
        answers.add(Record.newPtr(service.getTTL_PTR(), domain, fqdn));
        answers.add(Record.newSrv(service.getTTL_SRV(), fqdn, service.getHost(), service.getPort(), 0, 0));
        answers.add(Record.newTxt(service.getTTL_TXT(), fqdn, service.getText()));    // Seems "txt" is always required
        for (InetAddress address : service.getAddresses()) {
            additionals.add(Record.newAddress(service.getTTL_A(), service.getHost(), address));
        }

        this.timestamp = System.currentTimeMillis();
        this.id = 0;
        this.flags = (1<<FLAG_RESPONSE) | (1<<FLAG_AA); // response is required
        this.answers = Collections.<Record>unmodifiableList(answers);
        this.additionals = Collections.<Record>unmodifiableList(additionals);
        this.questions = this.authorities = Collections.<Record>emptyList();
        this.nic = null;
    }

    /**
     * Create a packet from an incoming datagram
     * @param in the incoming packet
     * @param address the address we read from
     */
    Packet(ByteBuffer in, NetworkInterface nic) {
        try {
            this.timestamp = System.currentTimeMillis();
            this.nic = nic;
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
    NetworkInterface getNetworkInterface() {
        return nic;
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
     * Return true if the specified address matches one of the local addresses
     * of the Network Interface.
     */
    private static boolean appliesTo(InetAddress address, NetworkInterface nic) {
        for (InterfaceAddress ia : nic.getInterfaceAddresses()) {
            byte[] a0 = ia.getAddress().getAddress();
            byte[] a1 = address.getAddress();
            if (a0.length == a1.length) {
                byte[] mask = new byte[a0.length];
                for (int i=0;i<ia.getNetworkPrefixLength();i++) {
                    mask[i>>3] |= (byte)(1<<(7-(i&7)));
                }
                for (int i=0;i<mask.length;i++) {
                    if ((a0[i]&mask[i]) != (a1[i]&mask[i])) {
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }


    /**
     * Does a record apply to a specific NIC?
     * We are not choosing the best NIC, only if it's a possibility. A record applies if:
     *  -- it has no address (eg it's a SRV)
     *  -- it has an address that matches the local addresses on this NIC
     *  -- it doesn't match ANY local addresses - in that case, send it to all NICs
     * @param record the record network interface
     * @param nic the current network interface
     * @param nics all the network interfaces being considered
     */
    private static boolean appliesTo(Record r, NetworkInterface nic, Collection<NetworkInterface> nics) {
        InetAddress address = r.getAddress();
        if (address == null) {
            return true;
        }
        if (appliesTo(address, nic)) {
            return true;
        }
        for (NetworkInterface onic : nics) {
            if (onic != nic && appliesTo(address, onic)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Return a clone of this Packet but excluding any A or AAAA records in the list of addresses.
     * @param excludedAddresses the addresses to exclude
     * @return a new Packet, or null if all records were excluded
     */
    Packet appliedTo(NetworkInterface nic, Collection<NetworkInterface> nics) {
        List<Record> questions = this.questions.isEmpty() ? this.questions : new ArrayList<Record>(this.questions);
        List<Record> answers = this.answers.isEmpty() ? this.answers : new ArrayList<Record>(this.answers);
        List<Record> additionals = this.additionals.isEmpty() ? this.additionals : new ArrayList<Record>(this.additionals);
        List<Record> authorities = this.authorities.isEmpty() ? this.authorities : new ArrayList<Record>(this.authorities);
        for (int i=0;i<questions.size();i++) {
            if (!appliesTo(questions.get(i), nic, nics)) {
                questions.remove(i--);
            }
        }
        for (int i=0;i<answers.size();i++) {
            if (!appliesTo(answers.get(i), nic, nics)) {
                answers.remove(i--);
            }
        }
        for (int i=0;i<additionals.size();i++) {
            if (!appliesTo(additionals.get(i), nic, nics)) {
                additionals.remove(i--);
            }
        }
        for (int i=0;i<authorities.size();i++) {
            if (!appliesTo(authorities.get(i), nic, nics)) {
                authorities.remove(i--);
            }
        }
        if (questions.isEmpty() && answers.isEmpty() && authorities.isEmpty() && additionals.isEmpty()) {
            return null;
        }
        Packet p = new Packet(id, flags, nic, questions, answers, authorities, additionals);
        return p;
    }

    /**
     * Return the packet timestamp
     */
    public long timestamp() {
        return timestamp;
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
        sb.append(",\"timestamp\":");
        sb.append(timestamp);
        sb.append(",\"flags\":");
        sb.append(flags);
        if (nic != null) {
            sb.append(",\"nic\":\"");
            sb.append(nic.getName());
            sb.append("\"");
        }
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

    //-------------------------------------------------

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

package org.captainunlikely.zeroconf;

import java.net.*;
import java.util.*;

/**
 * Service represents a Service to be announced by the Zeroconf class. It is created
 * by the {@link Zeroconf#newService Zeroconf.newService()} method.
 */
public class Service {

    private final Zeroconf zeroconf;
    private final String alias, service;
    private final int port;
    private String domain, protocol, host;
    private Map<String,String> text;
    private List<InetAddress> addresses;
    private Packet packet;
    private boolean done;

    Service(Zeroconf zeroconf, String alias, String service, int port) {
        this.zeroconf = zeroconf;
        this.alias = alias;
        for (int i=0;i<alias.length();i++) {
            char c = alias.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new IllegalArgumentException(alias);
            }
        }
        this.service = service;
        this.port = port;
        this.protocol = "tcp";
        this.domain = zeroconf.getDomain();
        this.host = zeroconf.getLocalHostName();
        this.text = new LinkedHashMap<String,String>();
    }

    /**
     * Set the protocol, which can be one of "tcp" (the default) or "udp"
     * @param protocol the protocol
     * @return this
     */
    public Service setProtocol(String protocol) {
        if (packet != null) {
            throw new IllegalStateException("Already announced");
        }
        if ("tcp".equals(protocol) || "udp".equals(protocol)) {
            this.protocol = protocol;
        } else {
            throw new IllegalArgumentException(protocol);
        }
        return this;
    }

    /**
     * Set the domain, which defaults to {@link Zeroconf#getDomain} and must begin with "."
     * @param domain the domain
     * @return this
     */
    public Service setDomain(String domain) {
        if (packet != null) {
            throw new IllegalStateException("Already announced");
        }
        if (domain == null || domain.length() < 2 || domain.charAt(0) != '.') {
            throw new IllegalArgumentException(domain);
        }
        this.domain = domain;
        return this;
    }

    /**
     * Set the host which is hosting this Service, which defaults to {@link Zeroconf#getLocalHostName}.
     * It is possible to announce a service on a non-local host
     * @param host the host
     * @return this
     */
    public Service setHost(String host) {
        if (packet != null) {
            throw new IllegalStateException("Already announced");
        }
        this.host = host;
        return this;
    }

    /**
     * Set the Text record to go with this Service, which is of the form "key1=value1, key2=value2"
     * Any existing Text records are replaced
     * @param text the text
     * @return this
     */
    public Service setText(String text) {
        if (packet != null) {
            throw new IllegalStateException("Already announced");
        }
        this.text.clear();
        String[] q = text.split(", *");
        for (int i=0;i<q.length;i++) {
            String[] r = q[i].split("=");
            if (r.length == 2) {
                this.text.put(r[0], r[1]);
            } else {
                throw new IllegalArgumentException(text);
            }
        }
        return this;
    }

    /**
     * Set the Text record to go with this Service, which is specified as a Map of keys and values
     * Any existing Text records are replaced
     * @param text the text
     * @return this
     */
    public Service setText(Map<String,String> text) {
        if (packet != null) {
            throw new IllegalStateException("Already announced");
        }
        this.text.clear();
        this.text.putAll(text);
        return this;
    }

    /**
     * Add a Text record entry to go with this Service to the existing list of Text record entries.
     * @param key the text key
     * @param value the corresponding value.
     * @return this
     */
    public Service putText(String key, String value) {
        if (packet != null) {
            throw new IllegalStateException();
        }
        this.text.put(key, value);
        return this;
    }

    /**
     * Add an InetAddress to the list of addresses for this service. By default they are taken
     * from {@link Zeroconf#getLocalAddresses}, as the hostname is taken from {@link Zeroconf#getLocalHostName}.
     * If advertising a Service on a non-local host, the addresses must be set manually using this
     * method.
     * @param address the InetAddress this Service resides on
     * @return this
     */
    public Service addAddress(InetAddress address) {
        if (packet != null) {
            throw new IllegalStateException();
        }
        if (addresses == null) {
            addresses = new ArrayList<InetAddress>();
        }
        addresses.add(address);
        return this;
    }

    /**
     * Return the Alias for this service, as set in the {@link Zeroconf#newService} method
     * @return the alias
     */
    public String getAlias() {
        return alias;
    }

    /**
     * Return the instance-name for this service. This is the "fully qualified domain name" of
     * the service and looks something like "My Service._http._tcp.local"
     * @return the instance name
     */
    public String getInstanceName() {
        StringBuilder sb = new StringBuilder();
        esc(alias, sb);
        sb.append("._");
        esc(service, sb);
        sb.append("._");
        sb.append(protocol);
        sb.append(domain);
        return sb.toString();
    }

    /**
     * Return the service-name for this service. This is the "domain name" of
     * the service and looks something like "._http._tcp.local" - i.e. the InstanceName
     * without the alias. Note the rather ambiguous term "service name" comes from the spec.
     * @return the service name
     */
    public String getServiceName() {
        StringBuilder sb = new StringBuilder();
        sb.append('_');
        esc(service, sb);
        sb.append("._");
        sb.append(protocol);
        sb.append(domain);
        return sb.toString();
    }

    private static void esc(String in, StringBuilder out) {
        for (int i=0;i<in.length();i++) {
            char c = in.charAt(i);
            if (c == '.' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
    }

    Packet getPacket() {
        if (packet == null) {
            packet = new Packet();
            String fqdn = getInstanceName();
            String ptrname = getServiceName();
            String host = this.host;
            packet.addAnswer(new RecordPTR(ptrname, fqdn).setTTL(28800));
            packet.addAnswer(new RecordSRV(fqdn, host, port).setTTL(120));
            if (!text.isEmpty()) {
                packet.addAnswer(new RecordTXT(fqdn, text));
            }
            List<InetAddress> addresses = this.addresses;
            if (addresses == null) {
                addresses = zeroconf.getLocalAddresses();
            }
            for (InetAddress address : addresses) {
                if (address instanceof Inet4Address) {
                    packet.addAnswer(new RecordA(host, (Inet4Address)address));
                } else if (address instanceof Inet6Address) {
                    packet.addAnswer(new RecordAAAA(host, (Inet6Address)address));
                }
            }
        }
        return packet;
    }

    /**
     * Announce this Service on the network. Services can be announced more than once, although I'm unsure
     * if this is valid
     * @return this
     */
    public Service announce() {
        if (done) {
            throw new IllegalStateException("Already Cancelled");
        }
        zeroconf.announce(this);
        return this;
    }

    /** 
     * Cancel the announcement of this Service on the Network
     * @return this
     */
    public Service cancel() {
        if (done) {
            throw new IllegalStateException("Already Cancelled");
        }
        zeroconf.unannounce(this);
        done = true;
        return this;
    }

}

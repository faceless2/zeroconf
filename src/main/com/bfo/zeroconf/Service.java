package com.bfo.zeroconf;

import java.net.*;
import java.util.*;

/**
 * Service represents a Service to be announced by the Zeroconf class. It is created
 * by the {@link Zeroconf#newService Zeroconf.newService()} method.
 */
public class Service {

    private final Zeroconf zeroconf;
    private final String fqdn, name, type, domain;      // Store FQDN because it may not be escaped properly. Store it exactly as we hear it
    private String host;
    private int port;
    private List<InetAddress> addresses;
    private Map<String,String> text;

    Service(Zeroconf zeroconf, String fqdn, String name, String type, String domain) {
        this.zeroconf = zeroconf;
        this.fqdn = fqdn;
        this.name = name;
        this.type = type;
        this.domain = domain;
    }

    Service(Zeroconf zeroconf, String fqdn) {
        List<String> l = splitFQDN(fqdn);
        if (l == null) {
            throw new IllegalArgumentException("Can't split " + quote(fqdn));
        }
        this.zeroconf = zeroconf;
        this.fqdn = fqdn;
        this.name = l.get(0);
        this.type = l.get(1);
        this.domain = l.get(2);
    }

    /**
     * Given an FQDN, split into three parts: the instance name, the type+protocol, and the domain, eg "Foo bar", "_http._tcp", ".local"
     * return null if it fails
     */
    static List<String> splitFQDN(String name) {
        List<String> l = new ArrayList<String>();
        StringBuilder sb = new StringBuilder();
        for (int i=0;i<name.length();i++) {
            char c = name.charAt(i);
            if (c == '\\' && i + 1 < name.length()) {
                sb.append(name.charAt(++i));
            } else if (c == '.') {
                l.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        l.add(sb.toString());
        // Part split is not always obvious, eg 99.99.0.110-FOO.123abcde-8f43-4985-8221-123456789abc._nvstream_dbd._udp.foo.com
        // Look for "_tcp", "_udp", or two strings in row beginning with "_". Then build out from there.
        for (int i=l.size()-2;i>1;i--) {
            String s = l.get(i);
            if (s.equals("_tcp") || s.equals("_udp") || (s.charAt(0) == '_' && l.get(i - 1).charAt(0) == '_')) {
                String type = l.get(i - 1) + "." + s;
                sb.setLength(0);
                for (int j=i+1;j<l.size();j++) {
                    sb.append('.');
                    sb.append(l.get(j));
                }
                String domain = sb.toString();
                sb.setLength(0);
                for (int j=0;j<i - 1;j++) {
                    if (j > 0) {
                        sb.append('.');
                    }
                    sb.append(l.get(j));
                }
                String instance = sb.toString();
                return Arrays.asList(instance, type, domain);
            }
        }
        return null;
    }

    boolean setHost(String host, int port) {
        boolean modified = false;
        if (port != this.port) {
            this.port = port;
            modified = true;
        }
        if (host == null ? this.host != null : !host.equals(this.host)) {
            this.host = host;
            modified = true;
        }
        return modified;
    }

    boolean setText(Map<String,String> text) {
        if (text == null ? this.text != null : !text.equals(this.text)) {
            this.text = text;
            return true;
        }
        return false;
    }

    boolean addAddress(InetAddress address) {
        if (addresses == null) {
            addresses = new ArrayList<InetAddress>();
        }
        if (!addresses.contains(address)) {
            addresses.add(address);
            return true;
        }
        return false;
    }

    boolean removeAddress(InetAddress address) {
        return addresses != null && addresses.remove(address);
    }

    String getFQDN() {
        return fqdn;
        /*
        StringBuilder sb = new StringBuilder();
        for (int i=0;i<name.length();i++) {
            char c = name.charAt(i);
            if (c == '.' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append('.');
        sb.append(type);
        sb.append(domain);
        return sb.toString();
        */
    }

    /**
     * Return the instance name for this service.
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Return the type for this service, a combination of the service type and protocol, eg "_http._tcp"
     * @return the type
     */
    public String getType() {
        return type;
    }

    /**
     * Return the domain for this service, usually ".local"
     * @return the domain
     */
    public String getDomain() {
        return domain;
    }

    /**
     * Return the port for the service
     */
    public int getPort() {
        return port;
    }

    /**
     * Return the named host for this service
     */
    public String getHost() {
        if (host == null) {
            return zeroconf.getLocalHostName() + zeroconf.getDomain();
        } else {
            return host;
        }
    }

    /** 
     * Return an unmodifiable map containing the text
     */
    public Map<String,String> getText() {
        return text == null ? Collections.<String,String>emptyMap() : text;
    }

    /** 
     * Return an unmodifiable list containing the addresses
     */
    public List<InetAddress> getAddresses() {
        if (addresses == null) {
            return zeroconf.getLocalAddresses();
        } else {
            return Collections.<InetAddress>unmodifiableList(addresses);
        }
    }

    /**
     * Announce this Service on the network. Services can be announced more than once, although I'm unsure
     * if this is valid
     * @return this
     */
    public boolean announce() {
        return zeroconf.announce(this);
    }

    /** 
     * Cancel the announcement of this Service on the Network
     * @return this
     */
    public boolean cancel() {
        return zeroconf.unannounce(this);
    }

    public int hashCode() {
        int hc = getName().hashCode();
        hc ^= getHost().hashCode();
        return hc;
    }

    public boolean equals(Object o) {
        if (o instanceof Service) {
            Service s = (Service)o;
            return getName().equals(s.getName()) && getHost().equals(s.getHost());
        }
        return false;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"name\":");
        sb.append(quote(name));
        sb.append(",\"type\":");
        sb.append(quote(type));
        sb.append(",\"domain\":");
        sb.append(quote(domain));
        if (host != null) {
            sb.append(",\"host\":");
            sb.append(quote(host));
            sb.append(",\"port\":");
            sb.append(Integer.toString(port));
        }
        if (text != null) {
            sb.append(",\"text\":{");
            boolean first = true;
            for (Map.Entry<String,String> e : text.entrySet()) {
                if (first) {
                    first = false;
                } else {
                    sb.append(',');
                }
                sb.append(quote(e.getKey()));
                sb.append(':');
                sb.append(quote(e.getValue()));
            }
            sb.append('}');
        }
        if (addresses != null) {
            sb.append(",\"addresses\":[");
            for (int i=0;i<addresses.size();i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(quote(addresses.get(i).toString()));
            }
            sb.append("]");
        }
        sb.append("}");
        return sb.toString();
    }

    static String quote(String s) {
        if (s == null) {
            return s;
        }
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
            } else if (Character.isISOControl(c)) {
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
    }

    public static class Builder {
        private String name, type, domain, host;
        private int port;
        private Map<String,String> props = new LinkedHashMap<String,String>();
        private List<InetAddress> addresses = new ArrayList<InetAddress>();

        public Builder setName(String name) {
            if (name == null || name.length() == 0) {
                throw new IllegalArgumentException("Empty name");
            }
            for (int i=0;i<name.length();i++) {
                char c = name.charAt(i);
                if (c < 0x20 || c >= 0x7F) {
                    throw new IllegalArgumentException("Invalid name character U+" + Integer.toHexString(c)+" in " + quote(name));
                }
            }
            this.name = name;
            return this;
        }
        public Builder setHost(String host) {
            if (host != null && host.length() == 0) {
                throw new IllegalArgumentException("Invalid host");
            }
            this.host = host;
            return this;
        }
        public Builder setType(String type) {
            int ix;
            if (type == null || (ix=type.indexOf(".")) < 0 || type.length() < 2 || ix + 1 >= type.length() || type.charAt(0) != '_' || type.charAt(ix + 1) != '_') {
                throw new IllegalArgumentException("Invalid type: must contain service+protocol, both starting with underscore eg \"_http._tcp\"");
            }
            this.type = type;
            return this;
        }
        public Builder setDomain(String domain) {
            if (domain != null && (domain.length() < 2 || domain.charAt(0) != '.')) {
                throw new IllegalArgumentException("Invalid domain: must start with dot, eg \".local\"");
            }
            this.domain = domain;
            return this;
        }
        public Builder setFQDN(String fqdn) {
            List<String> l = splitFQDN(fqdn);
            if (l != null) {
                setName(l.get(0));
                setType(l.get(1));
                setDomain(l.get(2));
            } else {
                throw new IllegalArgumentException("Invalid FQDN: " + quote(fqdn) + " can't split");
            }
            return this;
        }
        public Builder setPort(int port) {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Invalid port");
            }
            this.port = port;
            return this;
        }
        public Builder put(String key, String value) {
            if (value == null) {
                props.remove(key);
            } else {
                props.put(key, value);
            }
            return this;
        }
        public Builder putAll(Map<String,String> map) {
            props.putAll(map);
            return this;
        }
        public Builder addAddress(InetAddress address) {
            if (address != null) {
                addresses.add(address);
            }
            return this;
        }
        public Service build(Zeroconf zeroconf) {
            if (name == null) {
                throw new IllegalStateException("Name is required");
            }
            if (type == null) {
                throw new IllegalStateException("Type is required");
            }
            if (port == 0) {
                throw new IllegalStateException("Port is required");
            }
            if (domain == null) {
                domain = zeroconf.getDomain();
            }
            if (host == null && zeroconf.getLocalHostName() == null) {
                throw new IllegalStateException("Host is required (cannot be determined automatically)");
            }
            StringBuilder sb = new StringBuilder();
            for (int i=0;i<name.length();i++) {
                char c = name.charAt(i);
                if (c == '.' || c == '\\') {
                    sb.append('\\');
                }
                sb.append(c);
            }
            sb.append('.');
            sb.append(type);
            sb.append(domain);
            Service service = new Service(zeroconf, sb.toString(), name, type, domain);
            System.out.println("MADE " + service);
            service.setHost(host, port);
            if (!addresses.isEmpty()) {
                service.addresses = new ArrayList<InetAddress>();
                for (InetAddress address : addresses) {
                    service.addAddress(address);
                }
            }
            if (!props.isEmpty()) {
                service.setText(props);
            }
            return service;
        }
    }

}

package org.captainunlikely.zeroconf;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * <p>
 * This is the root class for the Service Discovery object. A typical use to publish a record is
 * </p>
 * <pre>
 * Zeroconf zeroconf = new Zeroconf();
 * zeroconf.addAllNetworkInterfaces();
 * Service service = zeroconf.newService("MyWeb", "http", 8080).putText("path", "/path/toservice").announce();
 * // time passes
 * service.cancel();
 * // time passes
 * zeroconf.close();
 * </pre>
 * <p>
 * This class does not have any fancy hooks to clean up. The {@link #close} method should be called when the
 * class is to be discarded, but failing to do so won't break anything. Announced services will expire in
 * their own time, which is typically two minutes - although during this time, conforming implementations
 * should refuse to republish any duplicate services.
 * </p>
 */
public class Zeroconf {

    private static final String DISCOVERY = "_services._dns-sd._udp.local";
    private static final InetSocketAddress BROADCAST4, BROADCAST6;
    static {
        try {
            BROADCAST4 = new InetSocketAddress(InetAddress.getByName("224.0.0.251"), 5353);
            BROADCAST6 = new InetSocketAddress(InetAddress.getByName("FF02::FB"), 5353);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private ListenerThread thread;
    private boolean useipv4, useipv6;
    private String hostname, domain;
    private InetAddress address;
    private List<Record> registry;
    private Collection<Service> serviceregistry;
    private CopyOnWriteArrayList<PacketListener> receivelisteners;
    private CopyOnWriteArrayList<PacketListener> sendlisteners;
    private CopyOnWriteArrayList<InetAddress> localaddresses;

    /**
     * Create a new Zeroconf object
     */
    public Zeroconf() {
        setDomain(".local");
        try {
            setLocalHostName(InetAddress.getLocalHost().getHostName());
        } catch (IOException e) {
            // Not worthy of an IOException
        }
        useipv4 = true;
        useipv6 = false;
        receivelisteners = new CopyOnWriteArrayList<PacketListener>();
        sendlisteners = new CopyOnWriteArrayList<PacketListener>();
        thread = new ListenerThread();
        registry = new ArrayList<Record>();
        serviceregistry = new HashSet<Service>();
//        System.out.println("Listening on "+getLocalAddresses());
    }

    /** 
     * Close down this Zeroconf object and cancel any services it has advertised.
     * @throws InterruptedException if we couldn't rejoin the listener thread
     */
    public void close() throws InterruptedException {
        List<Service> list = new ArrayList<Service>(serviceregistry);
        for (Service service : list) {
            unannounce(service);
        }
        thread.close();
    }

    /**
     * Add a {@link PacketListener} to the list of listeners notified when a Service Discovery
     * Packet is received
     * @param listener the listener
     * @return this Zeroconf
     */
    public Zeroconf addReceiveListener(PacketListener listener) {
        receivelisteners.addIfAbsent(listener);
        return this;
    }

    /**
     * Remove a previously added {@link PacketListener} from the list of listeners notified when
     * a Service Discovery Packet is received
     * @param listener the listener
     * @return this Zeroconf
     */
    public Zeroconf removeReceiveListener(PacketListener listener) {
        receivelisteners.remove(listener);
        return this;
    }

    /**
     * Add a {@link PacketListener} to the list of listeners notified when a Service
     * Discovery Packet is sent
     * @param listener the listener
     * @return this Zeroconf
     */
    public Zeroconf addSendListener(PacketListener listener) {
        sendlisteners.addIfAbsent(listener);
        return this;
    }

    /**
     * Remove a previously added {@link PacketListener} from the list of listeners notified
     * when a Service Discovery Packet is sent
     * @param listener the listener
     * @return this Zeroconf
     */
    public Zeroconf removeSendListener(PacketListener listener) {
        sendlisteners.remove(listener);
        return this;
    }

    /**
     * <p>
     * Add a {@link NetworkInterface} to the list of interfaces that send and received Service
     * Discovery Packets. The interface should be up, should
     * {@link NetworkInterface#supportsMulticast} support Multicast and not be a
     * {@link NetworkInterface#isLoopback Loopback interface}. However, adding a
     * NetworkInterface that does not match this requirement will not throw an Exception - it
     * will just be ignored, as will any attempt to add a NetworkInterface that has already
     * been added.
     * </p><p>
     * All the interface's IP addresses will be added to the list of
     * {@link #getLocalAddresses local addresses}.
     * If the interface's addresses change, or the interface is otherwise modified in a
     * significant way, then it should be removed and re-added to this object. This is
     * not done automatically.
     * </p>
     * @param nic a NetworkInterface 
     * @return this
     * @throws IOException if something goes wrong in an I/O way
     */
    public Zeroconf addNetworkInterface(NetworkInterface nic) throws IOException {
        if (nic == null) {
            throw new NullPointerException("NIC is null");
        }
        thread.addNetworkInterface(nic);
        return this;
    }

    /**
     * Remove a {@link #addNetworkInterface previously added} NetworkInterface from this
     * object's list. The addresses that were part of the interface at the time it was added
     * will be removed from the list of {@link #getLocalAddresses local addresses}.
     * @param nic a NetworkInterface 
     * @return this
     * @throws IOException if something goes wrong in an I/O way
     */
    public Zeroconf removeNetworkInterface(NetworkInterface nic) throws IOException {
        thread.removeNetworkInterface(nic);
        return this;
    }

    /**
     * A convenience method to add all local NetworkInterfaces - it simply runs
     * <pre>
     * for (Enumeration&lt;NetworkInterface&gt; e = NetworkInterface.getNetworkInterfaces();e.hasMoreElements();) {
     *     addNetworkInterface(e.nextElement());
     * }
     * </pre>
     * @throws IOException if something goes wrong in an I/O way
     * @return this
     */
    public Zeroconf addAllNetworkInterfaces() throws IOException {
        for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();e.hasMoreElements();) {
            addNetworkInterface(e.nextElement());
        }
        return this;
    }

    /**
     * Get the Service Discovery Domain, which is set by {@link #setDomain}. It defaults to ".local",
     * but can be set by {@link #setDomain}
     * @return the domain
     */
    public String getDomain() {
        return domain;
    }

    /**
     * Set the Service Discovery Domain
     * @param domain the domain
     * @return this
     */
    public Zeroconf setDomain(String domain) {
        if (domain == null) {
            throw new NullPointerException("Domain cannot be null");
        }
        this.domain = domain;
        return this;
    }

    /**
     * Get the local hostname, which defaults to <code>InetAddress.getLocalHost().getHostName()</code>.
     * @return the local host name
     */
    public String getLocalHostName() {
        if (hostname == null) {
            throw new IllegalStateException("Hostname cannot be determined");
        }
        return hostname;
    }

    /**
     * Set the local hostname, as returned by {@link #getLocalHostName}
     * @param name the hostname, which should be undotted
     * @return this
     */
    public Zeroconf setLocalHostName(String name) {
        if (name == null) {
            throw new NullPointerException("Hostname cannot be null");
        }
        this.hostname = name;
        return this;
    }

    /**
     * Return a list of InetAddresses which the Zeroconf object considers to be "local". These
     * are the all the addresses of all the {@link NetworkInterface} objects added to this
     * object. The returned list is a copy, it can be modified and will not be updated
     * by this object.
     * @return a List of local {@link InetAddress} objects
     */
    public List<InetAddress> getLocalAddresses() {
        return thread.getLocalAddresses();
    }

    /**
     * Send a packet
     */
    void send(Packet packet) {
        thread.push(packet);
    }

    /**
     * Return the registry of records. This is the list of DNS records that we will
     * automatically match any queries against. The returned list is live.
     */
    List<Record> getRegistry() {
        return registry;
    }

    /** 
     * Return the list of all Services that have been {@link Service#announce announced}
     * by this object. The returned Collection is read-only and live, so will be updated
     * by this object.
     * @return the Collection of announced Services
     */
    public Collection<Service> getAnnouncedServices() {
        return Collections.unmodifiableCollection(serviceregistry);
    }
     
    /**
     * Given a query packet, trawl through our registry and try to find any records that
     * match the queries. If there are any, send our own response packet.
     *
     * This is largely derived from other implementations, but broadly the logic here is
     * that questions are matched against records based on the "name" and "type" fields,
     * where {@link #DISCOVERY} and {@link Record#TYPE_ANY} are wildcards for those
     * fields. Currently we match against all packet types - should these be just "PTR"
     * records?
     *
     * Once we have this list of matched records, we search this list for any PTR records
     * and add any matching SRV or TXT records (RFC 6763 12.1). After that, we scan our
     * updated list and add any A or AAAA records that match any SRV records (12.2).
     *
     * At the end of all this, if we have at least one record, send it as a response
     */
    private void sendResponse(Packet packet) {
        Packet response = null;
        Set<String> targets = null;
        for (Record question : packet.getQuestions()) {
            for (Record record : getRegistry()) {
                if ((question.getName().equals(DISCOVERY) || question.getName().equals(record.getName())) && (question.getType() == record.getType() || question.getType() == Record.TYPE_ANY && record.getType() != Record.TYPE_NSEC)) {
                    if (response == null) {
                        response = new Packet(packet.getID());
                        response.setAuthoritative(true);
                    }
                    response.addAnswer(record);
                    if (record instanceof RecordSRV) {
                        if (targets == null) {
                            targets = new HashSet<String>();
                        }
                        targets.add(((RecordSRV)record).getTarget());
                    }
                }
            }

            if (response != null && question.getType() != Record.TYPE_ANY) {
                // When including a DNS-SD Service Instance Enumeration or Selective
                // Instance Enumeration (subtype) PTR record in a response packet, the
                // server/responder SHOULD include the following additional records:
                // o The SRV record(s) named in the PTR rdata.
                // o The TXT record(s) named in the PTR rdata.
                // o All address records (type "A" and "AAAA") named in the SRV rdata.
                for (Record answer : response.getAnswers()) {
                    if (answer.getType() == Record.TYPE_PTR) {
                        for (Record record : getRegistry()) {
                            if (record.getName().equals(answer.getName()) && (record.getType() == Record.TYPE_SRV || record.getType() == Record.TYPE_TXT)) {
                                response.addAdditional(record);
                                if (record instanceof RecordSRV) {
                                    if (targets == null) {
                                        targets = new HashSet<String>();
                                    }
                                    targets.add(((RecordSRV)record).getTarget());
                                }
                            }
                        }
                    }
                }

            }
        }
        if (response != null) {
            // When including an SRV record in a response packet, the
            // server/responder SHOULD include the following additional records:
            // o All address records (type "A" and "AAAA") named in the SRV rdata.
            if (targets != null) {
                for (String target : targets) {
                    for (Record record : getRegistry()) {
                        if (record.getName().equals(target) && (record.getType() == Record.TYPE_A || record.getType() == Record.TYPE_AAAA)) {
                            response.addAdditional(record);
                        }
                    }
                }
            }
            send(response);
        }
    }

    /**
     * Create a new {@link Service} to be announced by this object.
     * @param alias the Service alias, eg "My Web Server"
     * @param service the Service type, eg "http"
     * @param port the Service port.
     * @return a {@link Service} which can be announced, after further modifications if necessary
     */
    public Service newService(String alias, String service, int port) {
        return new Service(this, alias, service, port);
    }

    /**
     * Probe for a ZeroConf service with the specified name and return true if a matching
     * service is found.
     *
     * The approach is borrowed from https://www.npmjs.com/package/bonjour - we send three
     * broadcasts trying to match the service name, 250ms apart. If we receive no response,
     * assume there is no service that matches
     *
     * Note the approach here is the only example of where we send a query packet. It could
     * be used as the basis for us acting as a service discovery client
     *
     * @param the fully qualified servicename, eg "My Web Service._http._tcp.local".
     */
    private boolean probe(final String fqdn) {
        final Packet probe = new Packet();
        probe.setResponse(false);
        probe.addQuestion(new RecordANY(fqdn));
        final AtomicBoolean match = new AtomicBoolean(false);
        PacketListener probelistener = new PacketListener() {
            public void packetEvent(Packet packet) {
                if (packet.isResponse()) {
                    for (Record r : packet.getAnswers()) {
                        if (r.getName().equalsIgnoreCase(fqdn)) {
                            synchronized(match) {
                                match.set(true);
                                match.notifyAll();
                            }
                        }
                    }
                    for (Record r : packet.getAdditionals()) {
                        if (r.getName().equalsIgnoreCase(fqdn)) {
                            synchronized(match) {
                                match.set(true);
                                match.notifyAll();
                            }
                        }
                    }
                }
            }
        };
        addReceiveListener(probelistener);
        for (int i=0;i<3 && !match.get();i++) {
            send(probe);
            synchronized(match) {
                try {
                    match.wait(250);
                } catch (InterruptedException e) {}
            }
        }
        removeReceiveListener(probelistener);
        return match.get();
    }

    /**
     * Announce the service - probe to see if it already exists and fail if it does, otherwise
     * announce it
     */
    void announce(Service service) {
        Packet packet = service.getPacket();
        if (probe(service.getInstanceName())) {
            throw new IllegalArgumentException("Service "+service.getInstanceName()+" already on network");
        }
        getRegistry().addAll(packet.getAnswers());
        serviceregistry.add(service);
        send(packet);
    }

    /**
     * Unannounce the service. Do this by re-announcing all our records but with a TTL of 0 to
     * ensure they expire. Then remove from the registry.
     */
    void unannounce(Service service) {
        Packet packet = service.getPacket();
        getRegistry().removeAll(packet.getAnswers());
        for (Record r : packet.getAnswers()) {
            getRegistry().remove(r);
            r.setTTL(0);
        }
        send(packet);
        serviceregistry.remove(service);
    }

    /**
     * The thread that listens to one or more Multicast DatagramChannels using a Selector,
     * waiting for incoming packets. This wait can be also interuppted and a packet sent.
     */
    private class ListenerThread extends Thread {
        private volatile boolean cancelled;
        private Deque<Packet> sendq;
        private Map<NetworkInterface,SelectionKey> channels;
        private Map<NetworkInterface,List<InetAddress>> localaddresses;
        private Selector selector;

        ListenerThread() {
            setDaemon(true);
            sendq = new ArrayDeque<Packet>();
            channels = new HashMap<NetworkInterface,SelectionKey>();
            localaddresses = new HashMap<NetworkInterface,List<InetAddress>>();
        }

        private synchronized Selector getSelector() throws IOException {
            if (selector == null) {
                selector = Selector.open();
            }
            return selector;
        }

        /**
         * Stop the thread and rejoin
         */
        synchronized void close() throws InterruptedException {
            this.cancelled = true;
            if (selector != null) {
                selector.wakeup();
                if (isAlive()) {
                    join();
                }
            }
        }

        /**
         * Add a packet to the send queue
         */
        synchronized void push(Packet packet) {
            sendq.addLast(packet);
            if (selector != null) {
                // Only send if we have a Nic
                selector.wakeup();
            }
        }

        /**
         * Pop a packet from the send queue or return null if none available
         */
        private synchronized Packet pop() {
            return sendq.pollFirst();
        }

        /**
         * Add a NetworkInterface. Try to idenfity whether it's IPV4 or IPV6, or both. IPV4 tested,
         * IPV6 is not but at least it doesn't crash.
         */
        public synchronized void addNetworkInterface(NetworkInterface nic) throws IOException {
            if (!channels.containsKey(nic) && nic.supportsMulticast() && nic.isUp() && !nic.isLoopback()) {
                boolean ipv4 = false, ipv6 = false;
//                System.out.print("Adding "+nic+": ");
                List<InetAddress> locallist = new ArrayList<InetAddress>();
                for (Enumeration<InetAddress> e = nic.getInetAddresses();e.hasMoreElements();) {
                    InetAddress a = e.nextElement();
                    ipv4 |= a instanceof Inet4Address;
                    ipv6 |= a instanceof Inet4Address;
//                    System.out.print(a);
                    if (!a.isLoopbackAddress() && !a.isMulticastAddress()) {
                        locallist.add(a);
                    }
                }
//                System.out.println();

                DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET);
                channel.configureBlocking(false);
                channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                channel.setOption(StandardSocketOptions.IP_MULTICAST_TTL, 255);
                if (ipv4) {
                    channel.bind(new InetSocketAddress(BROADCAST4.getPort()));
                    channel.setOption(StandardSocketOptions.IP_MULTICAST_IF, nic);
                    channel.join(BROADCAST4.getAddress(), nic);
                } else if (ipv6) {
                    // TODO test
                    channel.bind(new InetSocketAddress(BROADCAST6.getPort()));
                    channel.join(BROADCAST6.getAddress(), nic);
                }
                channels.put(nic, channel.register(getSelector(), SelectionKey.OP_READ));
                localaddresses.put(nic, locallist);
                if (!isAlive()) {
                    start();
                }
            }
        }

        synchronized void removeNetworkInterface(NetworkInterface nic) throws IOException {
            SelectionKey key = channels.remove(nic);
            if (key != null) {
                localaddresses.remove(nic);
                key.channel().close();
                getSelector().wakeup();
            }
        }

        synchronized List<InetAddress> getLocalAddresses() {
            List<InetAddress> list = new ArrayList<InetAddress>();
            for (List<InetAddress> pernic : localaddresses.values()) {
                for (InetAddress address : pernic) {
                    if (!list.contains(address)) {
                        list.add(address);
                    }
                }
            }
            return list;
        }

        public void run() {
            ByteBuffer buf = ByteBuffer.allocate(65536);
            buf.order(ByteOrder.BIG_ENDIAN);
            while (!cancelled) {
                buf.clear();
                try {
                    Packet packet = pop();
                    if (packet != null) {
                        // Packet to Send
                        buf.clear();
                        packet.write(buf);
                        buf.flip();
                        for (PacketListener listener : sendlisteners) {
                            listener.packetEvent(packet);
                        }
                        for (SelectionKey key : channels.values()) {
                            DatagramChannel channel = (DatagramChannel)key.channel();
                            InetSocketAddress address = packet.getAddress();
                            if (address != null) {
                                channel.send(buf, address);
                            } else {
                                if (useipv4) {
                                    channel.send(buf, BROADCAST4);
                                }
                                if (useipv6) {
                                    channel.send(buf, BROADCAST6);
                                }
                            }
                        }
                    }

                    // We know selector exists
                    Selector selector = getSelector();
                    selector.select();
                    Set<SelectionKey> selected = selector.selectedKeys();
                    for (SelectionKey key : selected) {
                        // We know selected keys are readable
                        DatagramChannel channel = (DatagramChannel)key.channel();
                        InetSocketAddress address = (InetSocketAddress)channel.receive(buf);
                        if (address != null && buf.position() != 0) {
                            buf.flip();
                            packet = new Packet();
                            packet.read(buf, address);
                            for (PacketListener listener : receivelisteners) {
                                listener.packetEvent(packet);
                            }
                            sendResponse(packet);
                        }
                    }
                    selected.clear();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

}

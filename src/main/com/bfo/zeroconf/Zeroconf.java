package com.bfo.zeroconf;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * <p>
 * This is the root class for the Service Discovery object, which can be used to announce a {@link Service},
 * listen for announcements or both. Typical use to announce a Sevice:
 * </p>
 * <pre>
 * Zeroconf zc = new Zeroconf();
 * Service service = new Service.Builder().setName("MyWeb").setType("_http._tcp").setPort(8080).put("path", "/path/toservice").build(zc);
 * service.announce();
 * // time passes
 * service.cancel();
 * // time passes
 * zc.close();
 * </pre>
 * And to retrieve records, either add a {@link ZeroconfListener} by calling {@link #addListener},
 * or simply traverse the Collection returned from {@link #getServices}. Services will be added when
 * they are heard on the network - to ask the network to announce them, see {@link #query}.
 * <pre>
 * Zeroconf zc = new Zeroconf();
 * zc.addListener(new ZeroconfListener() {
 *   public void serviceNamed(String type, String name) {
 *     if ("_http._tcp.local".equals(type)) {
 *       // Ask for details on any announced HTTP services
 *       zc.query(type, name);
 *     }
 *   }
 * });
 * // Ask for any HTTP services
 * zc.query("_http._tcp.local", null);
 * // time passes
 * for (Service s : zc.getServices()) {
 *   if (s.getType().equals("_http._tcp") {
 *     // We've found an HTTP service
 *   }
 * }
 * // time passes
 * zc.close();
 * </pre>
 * <p>
 * This class does not have any fancy hooks to clean up. The {@link #close} method should be called when the
 * class is to be discarded, but failing to do so won't break anything. Announced services will expire in
 * their own time, which is typically two minutes - although during this time, conforming implementations
 * should refuse to republish any duplicate services.
 * </p>
 */
public class Zeroconf {

    private static final int PORT = 5353;
    private static final String DISCOVERY = "_services._dns-sd._udp.local";
    private static final InetSocketAddress BROADCAST4, BROADCAST6;
    static {
        try {
            BROADCAST4 = new InetSocketAddress(InetAddress.getByName("224.0.0.251"), PORT);
            BROADCAST6 = new InetSocketAddress(InetAddress.getByName("FF02::FB"), PORT);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private ListenerThread thread;
    private String hostname, domain;
    private InetAddress address;
    private final CopyOnWriteArrayList<ZeroconfListener> listeners;
    private final Map<Service,Packet> announceServices;
    private final Map<String,Service> heardServices;    // keyed on FQDN and also "!" + hostname
    private final Collection<String> heardServiceTypes, heardServiceNames;
    private final Map<Object,ExpiryTask> expiry;
    private final Collection<NetworkInterface> nics;

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
        listeners = new CopyOnWriteArrayList<ZeroconfListener>();
        announceServices = new ConcurrentHashMap<Service,Packet>();
        heardServices = new ConcurrentHashMap<String,Service>();
        heardServiceTypes = new CopyOnWriteArraySet<String>();
        heardServiceNames = new CopyOnWriteArraySet<String>();
        expiry = new HashMap<Object,ExpiryTask>();
        thread = new ListenerThread();

        nics = new AbstractCollection<NetworkInterface>() {
            private Set<NetworkInterface> mynics = new HashSet<NetworkInterface>();
            @Override public int size() {
                return mynics.size();
            }
            @Override public boolean add(NetworkInterface nic) {
                if (nic == null) {
                    throw new IllegalArgumentException("NIC is null");
                }
                if (mynics.add(nic)) {
                    try {
                        thread.addNetworkInterface(nic);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    return true;
                } else {
                    return false;
                }
            }
            @Override public Iterator<NetworkInterface> iterator() {
                return new Iterator<NetworkInterface>() {
                    private Iterator<NetworkInterface> i = mynics.iterator();
                    private NetworkInterface cur;
                    @Override public boolean hasNext() {
                        return i.hasNext();
                    }
                    @Override public NetworkInterface next() {
                        return cur = i.next();
                    }
                    @Override public void remove() {
                        try {
                            thread.removeNetworkInterface(cur);
                            i.remove();
                            cur = null;
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                };
            }
        };
        try {
            for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();e.hasMoreElements();) {
                nics.add(e.nextElement());
            }
        } catch (Exception e) {
            log("Can't add NetworkInterfaces", e);
        }
    }

    /** 
     * Close down this Zeroconf object and cancel any services it has advertised.
     * @throws InterruptedException if we couldn't rejoin the listener thread
     */
    public void close() throws InterruptedException {
        for (Service service : announceServices.keySet()) {
            unannounce(service);
        }
        thread.close();
    }

    /**
     * Add a {@link ZeroconfListener} to the list of listeners notified of events
     * @param listener the listener
     * @return this
     */
    public Zeroconf addListener(ZeroconfListener listener) {
        listeners.addIfAbsent(listener);
        return this;
    }

    /**
     * Remove a previously added {@link ZeroconfListener} from the list of listeners notified of events
     * @param listener the listener
     * @return this
     */
    public Zeroconf removeListener(ZeroconfListener listener) {
        listeners.remove(listener);
        return this;
    }

    /**
     * <p>
     * Return a modifiable Collection containing the interfaces that send and received Service Discovery Packets.
     * All the interface's IP addresses will be added to the {@link #getLocalAddresses} list,
     * and if that list changes or the interface goes up or down, the list will be updated automatically.
     * </p><p>
     * The default list is everything from {@link NetworkInterface#getNetworkInterfaces}.
     * Interfaces that don't {@link NetworkInterface#supportsMulticast support Multicast} or that
     * are {@link NetworkInterface#isLoopback loopbacks} are silently ignored. Interfaces that have both
     * IPv4 and IPv6 addresses will listen on both protocols if possible.
     * </p>
     * @return a modifiable collection of {@link NetworkInterface} objects
     */
    public Collection<NetworkInterface> getNetworkInterfaces() {
        return nics;
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
     * Get the local hostname, which defaults to <code>InetAddress.getLocalHost().getHostName()</code>
     * @return the local host name
     */
    public String getLocalHostName() {
        if (hostname == null) {
            throw new IllegalStateException("Hostname cannot be determined");
        }
        return hostname;
    }

    /**
     * Set the local hostname, as returned by {@link #getLocalHostName}.
     * It should not have a dot - the fully-qualified name will be created by appending
     * this value to {@link #getDomain}
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
    public Collection<InetAddress> getLocalAddresses() {
        return thread.getLocalAddresses().keySet();
    }

    /** 
     * Return the list of all Services that have been {@link Service#announce announced}
     * by this object.
     * The returned Collection is read-only, thread-safe without synchronization and live - it will be updated by this object.
     * @return the Collection of announced Services
     */
    public Collection<Service> getAnnouncedServices() {
        return Collections.unmodifiableCollection(announceServices.keySet());
    }

    /** 
     * Return the list of all Services that have been heard by this object.
     * It may contain Services that are also in {@link #getAnnouncedServices}
     * The returned Collection is read-only, thread-safe without synchronization and live - it will be updated by this object.
     * @return the Collection of Services
     */
    public Collection<Service> getServices() {
        return Collections.unmodifiableCollection(heardServices.values());
    }

    /**
     * Return the list of type names that have been heard by this object, eg "_http._tcp.local"
     * The returned Collection is read-only, thread-safe without synchronization and live - it will be updated by this object.
     * @return the Collection of type names.
     */
    public Collection<String> getServiceTypes() {
        return Collections.unmodifiableCollection(heardServiceTypes);
    }

    /**
     * Return the list of fully-qualified service names that have been heard by this object
     * The returned Collection is read-only, thread-safe without synchronization and live - it will be updated by this object.
     * @return the Collection of fully-qualfied service names.
     */
    public Collection<String> getServiceNames() {
        return Collections.unmodifiableCollection(heardServiceNames);
    }
     
    /**
     * Send a query to the network to probe for types or services.
     * Any responses will trigger changes to the list of services, and usually arrive within a second or two.
     * @param type the service type, eg "_http._tcp" ({@link #getDomain} will be appended if necessary), or null to query for known types
     * @param name the service instance name, or null to discover services of the specified type
     */
    public void query(String type, String name) {
        query(type, name, Record.TYPE_SRV);
    }

    void query(String type, String name, int recordType) {
        if (type == null) {
            send(new Packet(Record.newQuestion(Record.TYPE_PTR, DISCOVERY)));
        } else {
            if (type != null && type.endsWith(".")) {
                throw new IllegalArgumentException("Type " + Service.quote(type) + " should not end with a dot");
            }
            int ix = type.indexOf(".");
            if (ix > 0 && type.indexOf('.', ix + 1) < 0) {
                type += getDomain();
            }
            if (name == null) {
                send(new Packet(Record.newQuestion(Record.TYPE_PTR, type)));
            } else {
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
                send(new Packet(Record.newQuestion(recordType, sb.toString())));
            }
        }
    }

    //--------------------------------------------------------------------

    void send(Packet packet) {
        // System.out.println("# TX " + packet);
        thread.push(packet);
    }

    boolean announce(Service service) {
        if (announceServices.containsKey(service)) {
            return false;
        }
        final String fqdn = service.getFQDN();
        if (heardServices.containsKey(fqdn)) {
            return false;
        }

        // Do a probe to see if it exists
        // Send three broadcasts trying to match the service name, 250ms apart.
        // If we receive no response, assume there is no service that matches
        final Packet probe = new Packet(Record.newQuestion(Record.TYPE_ANY, fqdn));
        final AtomicBoolean match = new AtomicBoolean(false);
        ZeroconfListener probelistener = new ZeroconfListener() {
            public void packetReceived(Packet packet) {
                if (packet.isResponse()) {
                    for (Record r : packet.getAnswers()) {
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
        addListener(probelistener);
        for (int i=0;i<3 && !match.get();i++) {
            send(probe);
            synchronized(match) {
                try {
                    match.wait(250);
                } catch (InterruptedException e) {}
            }
        }
        removeListener(probelistener);

        if (match.get()) {
            return false;
        }
        reannounce(service);
        return true;
    }

    private void reannounce(Service service) {
        Packet packet = new Packet(service);
        announceServices.put(service, packet);
        send(packet);
    }

    /**
     * Unannounce the service. Do this by re-announcing all our records but with a TTL of 0 to
     * ensure they expire. Then remove from the registry.
     */
    boolean unannounce(Service service) {
        Packet packet = announceServices.remove(service);
        if (packet != null) {
            for (Record r : packet.getAnswers()) {
                r.setTTL(0);
            }
            send(packet);
            return true;
        }
        return false;
    }

    /**
     * The thread that listens to one or more Multicast DatagramChannels using a Selector,
     * waiting for incoming packets. This wait can be also interupted and a packet sent.
     */
    private class ListenerThread extends Thread {
        private volatile boolean cancelled;
        private Deque<Packet> sendq;
        private List<NicSelectionKey> channels;
        private Map<NetworkInterface,List<InetAddress>> localAddresses;
        private Selector selector;

        ListenerThread() {
            setDaemon(true);
            sendq = new ArrayDeque<Packet>();
            channels = new ArrayList<NicSelectionKey>();
            localAddresses = new HashMap<NetworkInterface,List<InetAddress>>();
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
         * Add a NetworkInterface.
         */
        void addNetworkInterface(NetworkInterface nic) throws IOException {
            boolean changed = false;
            synchronized(this) {
                if (!localAddresses.containsKey(nic) && nic.supportsMulticast() && !nic.isLoopback()) {
                    localAddresses.put(nic, new ArrayList<InetAddress>());
                    changed = processTopologyChange(nic, false);
                    if (changed) {
                        if (!isAlive()) {
                            start();
                        } else {
                            getSelector().wakeup();
                        }
                    }
                }
            }
            if (changed) {
                for (ZeroconfListener listener : listeners) {
                    try {
                        listener.topologyChange(nic);
                    } catch (Exception e) {
                        log("Listener exception", e);
                    }
                }
            }
        }

        void removeNetworkInterface(NetworkInterface nic) throws IOException, InterruptedException {
            boolean changed = false;
            synchronized(this) {
                if (localAddresses.containsKey(nic)) {
                    changed = processTopologyChange(nic, true);
                    localAddresses.remove(nic);
                    if (localAddresses.isEmpty()) {
                        close();
                    } else if (changed) {
                        getSelector().wakeup();
                    }
                }
            }
            if (changed) {
                for (ZeroconfListener listener : listeners) {
                    try {
                        listener.topologyChange(nic);
                    } catch (Exception e) {
                        log("Listener exception", e);
                    }
                }
            }
        }


        private boolean processTopologyChange(NetworkInterface nic, boolean remove) throws IOException {
            List<InetAddress> oldlist = localAddresses.get(nic);
            List<InetAddress> newlist = new ArrayList<InetAddress>();
            boolean ipv4 = false, ipv6 = false;
            if (nic.isUp() && !remove) {
                for (Enumeration<InetAddress> e = nic.getInetAddresses();e.hasMoreElements();) {
                    InetAddress a = e.nextElement();
                    if (!a.isLoopbackAddress() && !a.isMulticastAddress()) {
                        ipv4 |= a instanceof Inet4Address;
                        ipv6 |= a instanceof Inet6Address;
                        newlist.add(a);
                    }
                }
            }
            boolean changed = false;
            if (oldlist.isEmpty() && !newlist.isEmpty()) {
                if (ipv4) {
                    try {
                        DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET);
                        channel.configureBlocking(false);
                        channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                        channel.setOption(StandardSocketOptions.IP_MULTICAST_TTL, 255);
                        channel.setOption(StandardSocketOptions.IP_MULTICAST_IF, nic);
                        channel.bind(new InetSocketAddress(PORT));
                        channel.join(BROADCAST4.getAddress(), nic);
                        channels.add(new NicSelectionKey(nic, BROADCAST4, channel.register(getSelector(), SelectionKey.OP_READ, nic)));
                    } catch (Exception e) {
                        // Don't report, this method is called regularly and what is the user going to do about it?
                        // e.printStackTrace();
                        ipv4 = false;
                        for (int i=0;i<newlist.size();i++) {
                            if (newlist.get(i) instanceof Inet4Address) {
                                newlist.remove(i--);
                                
                            }
                        }
                    }
                }
                if (ipv6) {
                    try {
                        DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET6);
                        channel.configureBlocking(false);
                        channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                        channel.setOption(StandardSocketOptions.IP_MULTICAST_TTL, 255);
                        channel.setOption(StandardSocketOptions.IP_MULTICAST_IF, nic);
                        channel.bind(new InetSocketAddress(PORT));
                        channel.join(BROADCAST6.getAddress(), nic);
                        channels.add(new NicSelectionKey(nic, BROADCAST6, channel.register(getSelector(), SelectionKey.OP_READ, nic)));
                    } catch (Exception e) {
                        // Don't report, this method is called regularly and what is the user going to do about it?
                        // e.printStackTrace();
                        ipv6 = false;
                        for (int i=0;i<newlist.size();i++) {
                            if (newlist.get(i) instanceof Inet6Address) {
                                newlist.remove(i--);
                            }
                        }
                    }
                }
                if (!newlist.isEmpty()) {
                    oldlist.addAll(newlist);
                    changed = true;
                }
            } else if (!oldlist.isEmpty() && newlist.isEmpty()) {
                for (int i=0;i<channels.size();i++) {
                    NicSelectionKey nsk = channels.get(i);
                    if (nsk.nic == nic) {
                        nsk.key.channel().close();
                        channels.remove(i--);
                    }
                }
                oldlist.clear();
                changed = true;
            } else {
                for (Iterator<InetAddress> i = oldlist.iterator();i.hasNext();) {
                    InetAddress a = i.next();
                    if (!newlist.contains(a)) {
                        i.remove();
                        changed = true;
                    }
                }
                for (Iterator<InetAddress> i = newlist.iterator();i.hasNext();) {
                    InetAddress a = i.next();
                    if (!oldlist.contains(a)) {
                        oldlist.add(a);
                        changed = true;
                    }
                }
            }
            return changed;
        }

        synchronized Map<InetAddress,NetworkInterface> getLocalAddresses() {
            Map<InetAddress,NetworkInterface> map = new HashMap<InetAddress,NetworkInterface>();
            for (Map.Entry<NetworkInterface,List<InetAddress>> e : localAddresses.entrySet()) {
                for (InetAddress address : e.getValue()) {
                    if (!map.containsKey(address)) {
                        map.put(address, e.getKey());
                    }
                }
            }
            return map;
        }

        public void run() {
            ByteBuffer buf = ByteBuffer.allocate(65536);
            buf.order(ByteOrder.BIG_ENDIAN);
            try {
                // This bodge is to cater for the special case where someone does
                // Zeroconf zc = new Zeroconf();
                // zc.getInterfaces().clear();
                // We don't want to start then stop, so give it a fraction of a second.
                // Not the end of the world if it happens
                Thread.sleep(100);
            } catch (InterruptedException e) {}
            while (!cancelled) {
                ((Buffer)buf).clear();
                try {
                    Packet packet = pop();
                    if (packet != null) {
                        // Packet to send.
                        // * If it is a response to one we received, reply only on the NIC it was received on
                        // * If it contains addresses that are local addresses (assigned to a NIC on this machine)
                        //   then send only those addresses that apply to the NIC we are sending on.
                        NetworkInterface nic = packet.getNetworkInterface();
                        Collection<NetworkInterface> nics;
                        synchronized(this) {
                            nics = new HashSet<NetworkInterface>(localAddresses.keySet());
                        }
                        for (NicSelectionKey nsk : channels) {
                            if (nsk.nic.isUp()) {
                                DatagramChannel channel = (DatagramChannel)nsk.key.channel();
                                if (nic == null || nic.equals(nsk.nic)) {
                                    Packet dup = packet.appliedTo(nsk.nic, nics);
                                    if (dup != null) {
                                        ((Buffer)buf).clear();
                                        dup.write(buf);
                                        ((Buffer)buf).flip();
                                        channel.send(buf, nsk.broadcast);
                                        // System.out.println("# Sending " + dup + " to " + nsk.broadcast + " on " + nsk.nic.getName());
                                        for (ZeroconfListener listener : listeners) {
                                            try {
                                                listener.packetSent(dup);
                                            } catch (Exception e) {
                                                log("Listener exception", e);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // We know selector exists
                    selector.select(5000);
                    for (Iterator<SelectionKey> i=selector.selectedKeys().iterator();i.hasNext();) {
                        SelectionKey key = i.next();
                        i.remove();
                        // We know selected keys are readable
                        DatagramChannel channel = (DatagramChannel)key.channel();
                        InetSocketAddress address = (InetSocketAddress)channel.receive(buf);
                        if (buf.position() != 0) {
                            ((Buffer)buf).flip();
                            NetworkInterface nic = (NetworkInterface)key.attachment();
                            packet = new Packet(buf, nic);
                            // System.out.println("# RX: on " + nic.getName() + ": " + packet);
                            processPacket(packet);
                        }
                    }

                    processExpiry();
                    List<NetworkInterface> changed = null;
                    synchronized(this) {
                        for (NetworkInterface nic : localAddresses.keySet()) {
                            if (processTopologyChange(nic, false)) {
                                if (changed == null) {
                                    changed = new ArrayList<NetworkInterface>();
                                }
                                changed.add(nic);
                            }
                        }
                    }
                    if (changed != null) {      // Reannounce all services
                        for (Service service : getAnnouncedServices()) {
                            reannounce(service);
                        }
                        for (NetworkInterface nic : changed) {
                            for (ZeroconfListener listener : listeners) {
                                try {
                                    listener.topologyChange(nic);
                                } catch (Exception e) {
                                    log("Listener exception", e);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log("ListenerThread exception", e);
                }
            }
        }
    }

    private static class NicSelectionKey {
        final NetworkInterface nic;
        final InetSocketAddress broadcast;
        final SelectionKey key;
        NicSelectionKey(NetworkInterface nic, InetSocketAddress broadcast, SelectionKey key) {
            this.nic = nic;
            this.broadcast = broadcast;
            this.key = key;
        }
    }

    private void processPacket(Packet packet) {
        for (ZeroconfListener listener : listeners) {
            try {
                listener.packetReceived(packet);
            } catch (Exception e) {
                log("Listener exception", e);
            }
        }
        processQuestions(packet);
        Collection<Service> mod = null, add = null;
        // answers-ptr, additionals-ptr, answers-srv, additionals-srv, answers-other, additionals-other
        for (int pass=0;pass<6;pass++) {
            for (Record r : pass == 0 || pass == 2 || pass == 4 ? packet.getAnswers() : packet.getAdditionals()) {
                boolean ok = false;
                switch (pass) {
                    case 0:
                    case 1:
                        ok = r.getType() == Record.TYPE_PTR;
                        break;
                    case 2:
                    case 3:
                        ok = r.getType() == Record.TYPE_SRV;
                        break;
                    default:
                        ok = r.getType() != Record.TYPE_SRV && r.getType() != Record.TYPE_PTR;
                }
                if (ok) {
                    for (Service service : processAnswer(r, packet, null)) {
                        if (heardServices.putIfAbsent(service.getFQDN(), service) != null) {
                            if (mod == null) {
                                mod = new LinkedHashSet<Service>();
                            }
                            if (!mod.contains(service)) {
                                mod.add(service);
                            }
                        } else {
                            if (add == null) {
                                add = new ArrayList<Service>();
                            }
                            if (!add.contains(service)) {
                                add.add(service);
                            }
                        }
                    }
                }
            }
        }
        if (mod != null) {
            if (add != null) {
                mod.removeAll(add);
            }
            for (Service service : mod) {
                for (ZeroconfListener listener : listeners) {
                    try {
                        listener.serviceModified(service);
                    } catch (Exception e) {
                        log("Listener exception", e);
                    }
                }
            }
        }
        if (add != null) {
            for (Service service : add) {
                for (ZeroconfListener listener : listeners) {
                    try {
                        listener.serviceAnnounced(service);
                    } catch (Exception e) {
                        log("Listener exception", e);
                    }
                }
            }
        }
    }

    private void processQuestions(Packet packet) {
        final NetworkInterface nic = packet.getNetworkInterface();
        List<Record> answers = null, additionals = null;
        for (Record question : packet.getQuestions()) {
            if (question.getName().equals(DISCOVERY) && (question.getType() == Record.TYPE_PTR || question.getType() == Record.TYPE_ANY)) {
                for (Service s : announceServices.keySet()) { 
                    if (answers == null) {
                        answers = new ArrayList<Record>();
                    }
                    answers.add(Record.newPtr(DISCOVERY, s.getType()));
                }
            } else {
                for (Packet p : announceServices.values()) { 
                    for (Record answer : p.getAnswers()) {
                        if (!question.getName().equals(answer.getName())) {
                            continue;
                        }
                        if (question.getType() != answer.getType() && question.getType() != Record.TYPE_ANY) {
                            continue;
                        }
                        if (answers == null) {
                            answers = new ArrayList<Record>();
                        }
                        if (additionals == null) {
                            additionals = new ArrayList<Record>();
                        }
                        answers.add(answer);
                        List<Record> l = new ArrayList<Record>();
                        l.addAll(p.getAnswers());
                        l.addAll(p.getAdditionals());
                        if (answer.getType() == Record.TYPE_PTR && question.getType() != Record.TYPE_ANY) {
                            // When including a DNS-SD Service Instance Enumeration or Selective
                            // Instance Enumeration (subtype) PTR record in a response packet, the
                            // server/responder SHOULD include the following additional records:
                            // * The SRV record(s) named in the PTR rdata.
                            // * The TXT record(s) named in the PTR rdata.
                            // * All address records (type "A" and "AAAA") named in the SRV rdata.
                            for (Record a : l) {
                                if (a.getType() == Record.TYPE_SRV || a.getType() == Record.TYPE_A || a.getType() == Record.TYPE_AAAA || a.getType() == Record.TYPE_TXT) {
                                    additionals.add(a);
                                }
                            }
                        } else if (answer.getType() == Record.TYPE_SRV && question.getType() != Record.TYPE_ANY) {
                            // When including an SRV record in a response packet, the
                            // server/responder SHOULD include the following additional records:
                            // * All address records (type "A" and "AAAA") named in the SRV rdata.
                            for (Record a : l) {
                                if (a.getType() == Record.TYPE_A || a.getType() == Record.TYPE_AAAA || a.getType() == Record.TYPE_TXT) {
                                    additionals.add(a);
                                }
                            }
                        }
                    }
                }
            }
        }
        if (answers != null) {
            Packet response = new Packet(packet, answers, additionals);
            send(response);
        }
    }

    private List<Service> processAnswer(final Record r, final Packet packet, Service service) {
        List<Service> out = null;
        if (r.getType() == Record.TYPE_PTR && r.getName().equals(DISCOVERY)) {
            String type = r.getPtrValue();
            if (heardServiceTypes.add(type)) {
                for (ZeroconfListener listener : listeners) {
                    try {
                        listener.typeNamed(type);
                    } catch (Exception e) {
                        log("Listener exception", e);
                    }
                }
                expire(type, r.getTTL(), new Runnable() {
                    public void run() {
                        heardServiceTypes.remove(type);
                        for (ZeroconfListener listener : listeners) {
                            try {
                                listener.typeNameExpired(type);
                            } catch (Exception e) {
                                log("Listener exception", e);
                            }
                        }
                    }
                });
            }
        } else if (r.getType() == Record.TYPE_PTR) {
            final String type = r.getName();
            final String fqdn = r.getPtrValue();        // Will be a service FQDN
            if (heardServiceTypes.add(type)) {
                for (ZeroconfListener listener : listeners) {
                    try {
                        listener.typeNamed(type);
                    } catch (Exception e) {
                        log("Listener exception", e);
                    }
                }
                expire(type, r.getTTL(), new Runnable() {
                    public void run() {
                        heardServiceTypes.remove(type);
                        for (ZeroconfListener listener : listeners) {
                            try {
                                listener.typeNameExpired(type);
                            } catch (Exception e) {
                                log("Listener exception", e);
                            }
                        }
                    }
                });
            }
            if (heardServiceNames.add(fqdn)) {
                if (fqdn.endsWith(type)) {
                    final String name = fqdn.substring(0, fqdn.length() - type.length() - 1);
                    for (ZeroconfListener listener : listeners) {
                        try {
                            listener.serviceNamed(type, name);
                        } catch (Exception e) {
                            log("Listener exception", e);
                        }
                    }
                    expire(fqdn, r.getTTL(), new Runnable() {
                        public void run() {
                            heardServiceNames.remove(fqdn);
                            for (ZeroconfListener listener : listeners) {
                                try {
                                    listener.serviceNameExpired(type, name);
                                } catch (Exception e) {
                                    log("Listener exception", e);
                                }
                            }
                        }
                    });
                } else {
                    for (ZeroconfListener listener : listeners) {
                        try {
                            listener.packetError(packet, "PTR name " + Service.quote(fqdn) + " doesn't end with type " + Service.quote(type));
                        } catch (Exception e) {
                            log("Listener exception", e);
                        }
                    }
                    service = null;
                }
            }
        } else if (r.getType() == Record.TYPE_SRV) {
            final String fqdn = r.getName();
            service = heardServices.get(fqdn);
            boolean modified = false;
            if (service == null) {
                List<String> l = Service.splitFQDN(fqdn);
                if (l != null) {
                    for (Service s : getAnnouncedServices()) {
                        if (s.getFQDN().equals(fqdn)) {
                            service = s;
                            modified = true;
                            break;
                        }
                    }
                    if (service == null && r.getTTL() != 0) {
                        service = new Service(Zeroconf.this, fqdn, l.get(0), l.get(1), l.get(2));
                        modified = true;
                    }
                } else {
                    for (ZeroconfListener listener : listeners) {
                        try {
                            listener.packetError(packet, "Couldn't split SRV name " + Service.quote(fqdn));
                        } catch (Exception e) {
                            log("Listener exception", e);
                        }
                    }
                }
            }
            if (service != null) {
                final Service fservice = service;
                if (getAnnouncedServices().contains(service)) {
                    int ttl = r.getTTL();
                    ttl = Math.min(ttl * 9/10, ttl - 5);        // Refresh at 90% of expiry or at least 5s before
                    expire(service, r.getTTL(), new Runnable() {
                        public void run() {
                            if (getAnnouncedServices().contains(fservice)) {
                                reannounce(fservice);
                            }
                        }
                    });
                } else {
                    if (service.setHost(r.getSrvHost(), r.getSrvPort()) && !modified) {
                        modified = true;
                    }
                    int ttl = r.getTTL();
                    expire(service, r.getTTL(), new Runnable() {
                        public void run() {
                            heardServices.remove(fqdn);
                            for (ZeroconfListener listener : listeners) {
                                try {
                                    listener.serviceExpired(fservice);
                                } catch (Exception e) {
                                    log("Listener exception", e);
                                }
                            }
                        }
                    });
                    if (!modified) {
                        service = null;
                    }
                }
            }
        } else if (r.getType() == Record.TYPE_TXT) {
            final String fqdn = r.getName();
            if (service == null) {
                service = heardServices.get(fqdn);
                if (service != null) {
                    if (processAnswer(r, packet, service) == null) {
                        service = null;
                    }
                }
            } else if (fqdn.equals(service.getFQDN()) && !getAnnouncedServices().contains(service)) {
                final Service fservice = service;
                if (!service.setText(r.getText())) {
                    service = null;
                }
                expire("txt " + fqdn, r.getTTL(), new Runnable() {
                    public void run() {
                        if (fservice.setText(null)) {
                            for (ZeroconfListener listener : listeners) {
                                try {
                                    listener.serviceModified(fservice);
                                } catch (Exception e) {
                                    log("Listener exception", e);
                                }
                            }
                        }
                    }
                });
            }
        } else if (r.getType() == Record.TYPE_A || r.getType() == Record.TYPE_AAAA) {
            final String host = r.getName();
            if (service == null) {
                out = new ArrayList<Service>();
                for (Service s : heardServices.values()) {
                    if (host.equals(s.getHost())) {
                        if (processAnswer(r, packet, s) != null)  {
                            out.add(s);
                        }
                    }
                }
            } else if (host.equals(service.getHost()) && !getAnnouncedServices().contains(service)) {
                final Service fservice = service;
                InetAddress address = r.getAddress();
                if (!service.addAddress(address)) {
                    service = null;
                }
                expire(host + " " + address, r.getTTL(), new Runnable() {
                    public void run() {
                        if (fservice.removeAddress(address)) {
                            for (ZeroconfListener listener : listeners) {
                                try {
                                    listener.serviceModified(fservice);
                                } catch (Exception e) {
                                    log("Listener exception", e);
                                }
                            }
                        }
                    }
                });
            }
        }
        if (out == null) {
            out = service == null ? Collections.<Service>emptyList() : Collections.<Service>singletonList(service);
        }
        return out;
    }

    private void processExpiry() {
        // Poor mans ScheduledExecutorQueue - we won't have many of these and we're interrupting
        // regularly anyway, so expire then when we wake.
        long now = System.currentTimeMillis();
        for (Iterator<ExpiryTask> i = expiry.values().iterator();i.hasNext();) {
            ExpiryTask e = i.next();
            if (now > e.expiry) {
                i.remove();
                e.task.run();
            }
        }
    }

    private void expire(Object key, int ttl, Runnable task) {
        expiry.put(key, new ExpiryTask(System.currentTimeMillis() + ttl * 1000, task));
    }

    private static class ExpiryTask {
        final long expiry;
        final Runnable task;
        ExpiryTask(long expiry, Runnable task) {
            this.expiry = expiry;
            this.task = task;
        }
    }

    private static void log(String message, Exception e) {
        try {
            System.getLogger(Zeroconf.class.getName()).log(System.Logger.Level.ERROR, message, e);
        } catch (Throwable ex) {
            java.util.logging.Logger.getLogger(Zeroconf.class.getName()).log(java.util.logging.Level.SEVERE, message, e);
        }
    }

}

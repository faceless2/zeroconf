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
 * This is the root class for the Service Discovery object. A typical use to publish a record is
 * </p>
 * <pre>
 * Zeroconf zeroconf = new Zeroconf();
 * zeroconf.addAllNetworkInterfaces();
 * Service service = new Service.Builder().setAlias("MyWeb").setServiceName("http").setPort(8080).putText("path", "/path/toservice").build(zeroconf);
 * service.announce();
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
    private final CopyOnWriteArrayList<ZeroconfListener> listeners;
    private final Map<Service,Packet> announceServices;
    private final Map<String,Service> heardServices;    // keyed on FQDN and also "!" + hostname
    private final Collection<String> heardServiceTypes, heardServiceNames;
    private final Map<Object,ExpiryTask> expiry;

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
        listeners = new CopyOnWriteArrayList<ZeroconfListener>();
        announceServices = new ConcurrentHashMap<Service,Packet>();
        heardServices = new ConcurrentHashMap<String,Service>();
        heardServiceTypes = new CopyOnWriteArraySet<String>();
        heardServiceNames = new CopyOnWriteArraySet<String>();
        expiry = new HashMap<Object,ExpiryTask>();
        thread = new ListenerThread();
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
     * Add a {@link PacketListener} to the list of listeners notified when a Service Discovery
     * Packet is received
     * @param listener the listener
     * @return this Zeroconf
     */
    public Zeroconf addListener(ZeroconfListener listener) {
        listeners.addIfAbsent(listener);
        return this;
    }

    /**
     * Remove a previously added {@link PacketListener} from the list of listeners notified when
     * a Service Discovery Packet is received
     * @param listener the listener
     * @return this Zeroconf
     */
    public Zeroconf removeListener(ZeroconfListener listener) {
        listeners.remove(listener);
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
     * Return the list of all Services that have been {@link Service#announce announced}
     * by this object. The returned Collection is read-only and live, so will be updated
     * by this object.
     * @return the Collection of announced Services
     */
    public Collection<Service> getAnnouncedServices() {
        return Collections.unmodifiableCollection(announceServices.keySet());
    }

    /** 
     * Return the list of all Services that have been heard by this object.
     * The returned Collection is read-only and live, so will be updated by this object.
     * @return the Collection of announced Services
     */
    public Collection<Service> getServices() {
        return Collections.unmodifiableCollection(heardServices.values());
    }

    public Collection<String> getServiceTypes() {
        return Collections.unmodifiableCollection(heardServiceTypes);
    }

    public Collection<String> getServiceNames() {
        return Collections.unmodifiableCollection(heardServiceNames);
    }
     
    /**
     * Probe for the specified service. Responses will typically come in over the
     * next second or so and can be queried by {@link #getServices}
     * @param type the service type, eg "_http._tcp" ({@link #getDomain} will be appended if necessary), or null to discover services
     * @param name the service instance name, or null to discover services of the specified type
     */
    public void query(String type, String name) {
        if (type == null) {
            send(new Packet(Record.newQuestion(Record.TYPE_PTR, DISCOVERY)));
        } else {
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
                send(new Packet(Record.newQuestion(Record.TYPE_SRV, sb.toString())));
            }
        }
    }

    /**
     * Announce the service - probe to see if it already exists and fail if it does, otherwise
     * announce it
     */
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
        Packet packet = new Packet(service);
        announceServices.put(service, packet);
        send(packet);
        return true;
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
        private Map<NetworkInterface,SelectionKey> channels;
        private Map<NetworkInterface,List<InetAddress>> localAddresses;
        private Selector selector;

        ListenerThread() {
            setDaemon(true);
            sendq = new ArrayDeque<Packet>();
            channels = new HashMap<NetworkInterface,SelectionKey>();
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
         * Add a NetworkInterface. Try to idenfity whether it's IPV4 or IPV6, or both. IPV4 tested,
         * IPV6 is not but at least it doesn't crash.
         */
        public synchronized void addNetworkInterface(NetworkInterface nic) throws IOException {
            if (!channels.containsKey(nic) && nic.supportsMulticast() && nic.isUp() && !nic.isLoopback()) {
                boolean ipv4 = false, ipv6 = false;
                System.out.print("Adding "+nic+": ");
                List<InetAddress> locallist = new ArrayList<InetAddress>();
                for (Enumeration<InetAddress> e = nic.getInetAddresses();e.hasMoreElements();) {
                    InetAddress a = e.nextElement();
                    ipv4 |= a instanceof Inet4Address;
                    ipv6 |= a instanceof Inet4Address;
                    System.out.print(a);
                    if (!a.isLoopbackAddress() && !a.isMulticastAddress()) {
                        locallist.add(a);
                    }
                }
                System.out.println();

                DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET);
                channel.configureBlocking(false);
                channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                channel.setOption(StandardSocketOptions.IP_MULTICAST_TTL, 255);
                if (ipv4) {
                    channel.bind(new InetSocketAddress(BROADCAST4.getPort()));
                    channel.setOption(StandardSocketOptions.IP_MULTICAST_IF, nic);
                    channel.join(BROADCAST4.getAddress(), nic);
                } else if (ipv6) {
                    channel.bind(new InetSocketAddress(BROADCAST6.getPort()));
                    channel.join(BROADCAST6.getAddress(), nic);
                }
                channels.put(nic, channel.register(getSelector(), SelectionKey.OP_READ));
                localAddresses.put(nic, locallist);
                if (!isAlive()) {
                    start();
                }
            }
        }

        synchronized void removeNetworkInterface(NetworkInterface nic) throws IOException {
            SelectionKey key = channels.remove(nic);
            if (key != null) {
                localAddresses.remove(nic);
                key.channel().close();
                getSelector().wakeup();
            }
        }

        synchronized List<InetAddress> getLocalAddresses() {
            List<InetAddress> list = new ArrayList<InetAddress>();
            for (List<InetAddress> pernic : localAddresses.values()) {
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
                        for (ZeroconfListener listener : listeners) {
                            listener.packetSent(packet);
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
                    selector.select(5000);
                    Set<SelectionKey> selected = selector.selectedKeys();
                    for (SelectionKey key : selected) {
                        // We know selected keys are readable
                        DatagramChannel channel = (DatagramChannel)key.channel();
                        InetSocketAddress address = (InetSocketAddress)channel.receive(buf);
                        if (address != null && buf.position() != 0) {
                            buf.flip();
                            packet = new Packet(buf, address);
                            processPacket(packet);
                        }
                    }
                    selected.clear();

                    processExpiry();
                    processTopologyChange();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

    }

    private void processPacket(Packet packet) {
        for (ZeroconfListener listener : listeners) {
            listener.packetReceived(packet);
        }
        processQuestions(packet);
        Collection<Service> mod = null, add = null;
        for (int pass=0;pass<4;pass++) {
            for (Record r : pass == 3 ? packet.getAdditionals() : packet.getAnswers()) {
                boolean ok = false;
                switch (pass) {
                    case 0:  ok = r.getType() == Record.TYPE_PTR; break;
                    case 1:  ok = r.getType() == Record.TYPE_SRV; break;
                    default: ok = r.getType() != Record.TYPE_SRV && r.getType() != Record.TYPE_PTR;
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
                    listener.serviceModified(service);
                }
            }
        }
        if (add != null) {
            for (Service service : add) {
                for (ZeroconfListener listener : listeners) {
                    listener.serviceAnnounced(service);
                }
            }
        }
    }

    private void processQuestions(Packet packet) {
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
                        if (question.getName().equals(answer.getName()) && (question.getType() == answer.getType() || question.getType() == Record.TYPE_ANY)) {
                            if (answers == null) {
                                answers = new ArrayList<Record>();
                            }
                            if (additionals == null) {
                                additionals = new ArrayList<Record>();
                            }
                            answers.add(answer);
                            if (answer.getType() == Record.TYPE_PTR && question.getType() != Record.TYPE_ANY) {
                                // When including a DNS-SD Service Instance Enumeration or Selective
                                // Instance Enumeration (subtype) PTR record in a response packet, the
                                // server/responder SHOULD include the following additional records:
                                // * The SRV record(s) named in the PTR rdata.
                                // * The TXT record(s) named in the PTR rdata.
                                // * All address records (type "A" and "AAAA") named in the SRV rdata.
                                for (Record a : p.getAnswers()) {
                                    if (a.getType() == Record.TYPE_SRV || a.getType() == Record.TYPE_A || a.getType() == Record.TYPE_AAAA || a.getType() == Record.TYPE_TXT) {
                                        additionals.add(a);
                                    }
                                }
                            } else if (answer.getType() == Record.TYPE_SRV && question.getType() != Record.TYPE_ANY) {
                                // When including an SRV record in a response packet, the
                                // server/responder SHOULD include the following additional records:
                                // * All address records (type "A" and "AAAA") named in the SRV rdata.
                                for (Record a : p.getAnswers()) {
                                    if (a.getType() == Record.TYPE_A || a.getType() == Record.TYPE_AAAA || a.getType() == Record.TYPE_TXT) {
                                        additionals.add(a);
                                    }
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
                    listener.typeNamed(type);
                }
                expire(type, r.getTTL(), new Runnable() {
                    public void run() {
                        heardServiceTypes.remove(type);
                        for (ZeroconfListener listener : listeners) {
                            listener.typeNameExpired(type);
                        }
                    }
                });
            }
        } else if (r.getType() == Record.TYPE_PTR) {
            final String type = r.getName();
            final String fqdn = r.getPtrValue();        // Will be a service FQDN
            if (heardServiceTypes.add(type)) {
                for (ZeroconfListener listener : listeners) {
                    listener.typeNamed(type);
                }
                expire(type, r.getTTL(), new Runnable() {
                    public void run() {
                        heardServiceTypes.remove(type);
                        for (ZeroconfListener listener : listeners) {
                            listener.typeNameExpired(type);
                        }
                    }
                });
            }
            if (heardServiceNames.add(fqdn)) {
                if (fqdn.endsWith(type)) {
                    final String name = fqdn.substring(0, fqdn.length() - type.length() - 1);
                    for (ZeroconfListener listener : listeners) {
                        listener.serviceNamed(type, name);
                    }
                    expire(fqdn, r.getTTL(), new Runnable() {
                        public void run() {
                            heardServiceNames.remove(fqdn);
                            for (ZeroconfListener listener : listeners) {
                                listener.serviceNameExpired(type, name);
                            }
                        }
                    });
                } else {
                    for (ZeroconfListener listener : listeners) {
                        listener.packetError(packet, "PTR name " + Service.quote(fqdn) + " doesn't end with type " + Service.quote(type));
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
                    service = new Service(Zeroconf.this, fqdn, l.get(0), l.get(1), l.get(2));
                    modified = true;
                } else {
                    for (ZeroconfListener listener : listeners) {
                        listener.packetError(packet, "Couldn't split SRV name " + Service.quote(fqdn));
                    }
                }
            }
            if (service != null) {
                if (service.setHost(r.getSrvHost(), r.getSrvPort()) && !modified) {
                    modified = true;
                }
                final Service fservice = service;
                expire(service, r.getTTL(), new Runnable() {
                    public void run() {
                        heardServices.remove(fservice);
                        for (ZeroconfListener listener : listeners) {
                            listener.serviceExpired(fservice);
                        }
                    }
                });
                if (!modified) {
                    service = null;
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
            } else if (fqdn.equals(service.getFQDN())) {
                final Service fservice = service;
                if (!service.setText(r.getText())) {
                    service = null;
                }
                expire("txt " + fqdn, r.getTTL(), new Runnable() {
                    public void run() {
                        if (fservice.setText(null)) {
                            for (ZeroconfListener listener : listeners) {
                                listener.serviceModified(fservice);
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
            } else if (host.equals(service.getHost())) {
                final Service fservice = service;
                InetAddress address = r.getAddress();
                if (!service.addAddress(address)) {
                    service = null;
                }
                expire(host + " " + address, r.getTTL(), new Runnable() {
                    public void run() {
                        if (fservice.removeAddress(address)) {
                            for (ZeroconfListener listener : listeners) {
                                listener.serviceModified(fservice);
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

    private void processTopologyChange() {
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

}

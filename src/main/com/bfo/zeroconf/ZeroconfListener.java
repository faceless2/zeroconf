package com.bfo.zeroconf;

import java.net.NetworkInterface;

/**
 * An interface that can be added to the Zeroconf class to be notified about events.
 * All methods on the interface have a default implementation which does nothing.
 * @see Zeroconf#addListener
 * @see Zeroconf#removeListener
 */
public interface ZeroconfListener {

    /**
     * Called with the Zeroconf class sends a packet. 
     * @param packet the packet
     */
    public default void packetSent(Packet packet) {}

    /**
     * Called with the Zeroconf class receives a packet. 
     * @param packet the packet
     */
    public default void packetReceived(Packet packet) {}

    /**
     * Called with the Zeroconf class receives a packet it isn't able to process.
     * @param packet the packet
     * @param message the error message
     */
    public default void packetError(Packet packet, String message) {}

    /**
     * Called when the Zeroconf class detects a network topology change on an interdace
     * @param nic the NIC
     */
    public default void topologyChange(NetworkInterface nic) {}

    /**
     * Called when the Zeroconf class is notified of a new service type
     * @param type the type, eg "_http._tcp.local"
     */
    public default void typeNamed(String type) {}

    /**
     * Called when the Zeroconf class expires a type it was previously notified about
     * @param type the type, eg "_http._tcp.local"
     */
    public default void typeNameExpired(String type) {}

    /**
     * Called when the Zeroconf class is notified of a new service name
     * @param type the type, eg "_http._tcp.local"
     * @param name the instance name
     */
    public default void serviceNamed(String type, String name) {}

    /**
     * Called when the Zeroconf class expires a service name it was previously notified about
     * @param type the type, eg "_http._tcp.local"
     * @param name the instance name
     */
    public default void serviceNameExpired(String type, String name) {}

    /**
     * Called when the Zeroconf class is notified of a new service
     * @param service the service
     */
    public default void serviceAnnounced(Service service) {}

    /**
     * Called when the Zeroconf class modifies a service that has previously been announced, perhaps to change a network address
     * @param service the service
     */
    public default void serviceModified(Service service) {}

    /**
     * Called when the Zeroconf class exoires a service name that was previously announced.
     * @param service the service
     */
    public default void serviceExpired(Service service) {}

}

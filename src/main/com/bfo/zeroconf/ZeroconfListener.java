package com.bfo.zeroconf;

/**
 * An interface that will be notified of a packet transmission
 * @see Zeroconf#addReceiveListener
 * @see Zeroconf#addSendListener
 */
public interface ZeroconfListener {

    public default void packetSent(Packet packet) {}
    public default void packetReceived(Packet packet) {}
    public default void packetError(Packet packet, String message) {}
    public default void typeNamed(String type) {}
    public default void typeNameExpired(String type) {}
    public default void serviceNamed(String name, String type) {}
    public default void serviceNameExpired(String name, String type) {}
    public default void serviceAnnounced(Service service) {}
    public default void serviceModified(Service service) {}
    public default void serviceExpired(Service service) {}

}

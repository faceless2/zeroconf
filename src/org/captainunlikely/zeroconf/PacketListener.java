package org.captainunlikely.zeroconf;

/**
 * An interface that will be notified of a packet transmission
 * @see Zeroconf#addReceiveListener
 * @see Zeroconf#addSendListener
 */
public interface PacketListener {
    public void packetEvent(Packet packet);
}

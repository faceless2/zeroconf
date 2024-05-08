package com.bfo.zeroconf;

import java.time.*;
import java.net.*;

public class Test {

    private final static String now() {
        return Instant.now().toString() + ": ";
    }

    public static void main(String[] args) throws Exception {
        Zeroconf zeroconf = new Zeroconf();
        // zeroconf.getNetworkInterfaces().clear();
        zeroconf.addListener(new ZeroconfListener() {
            @Override public void packetSent(Packet packet) {
                System.out.println(now() + "packetSend:        " + packet);
            }
            @Override public void packetReceived(Packet packet) {
                System.out.println(now() + "packetReceived:    " + packet);
            }
            @Override public void topologyChange(NetworkInterface nic) {
                System.out.println(now() + "toplogyChange     " + nic);
            }
            @Override public void typeNamed(String type) {
                System.out.println(now() + "typeNamed:        \"" + type + "\"");
            }
            @Override public void serviceNamed(String type, String name) {
                System.out.println(now() + "serviceNamed:     \"" + type + "\", \"" + name + "\"");
            }
            @Override public void typeNameExpired(String type) {
                System.out.println(now() + "typeNameExpire:   \"" + type + "\"");
            }
            @Override public void serviceNameExpired(String type, String name) {
                System.out.println(now() + "serviceNameExpire: \"" + type + "\", \"" + name + "\"");
            }
            @Override public void serviceAnnounced(Service service) {
                System.out.println(now() + "serviceAnnounced:  " + service);
            }
            @Override public void serviceModified(Service service) {
                System.out.println(now() + "serviceModified:   " + service);
            }
            @Override public void serviceExpired(Service service) {
                System.out.println(now() + "serviceExpired:    " + service);
            }
            @Override public void packetError(Packet packet, String msg) {
                System.out.println(now() + "ERROR:             " + msg + " " + packet);
            }
        });

        Service s = new Service.Builder().setName("Goblin").setType("_http._tcp").setPort(8080).put("this", "that").build(zeroconf);
        s.announce();
        zeroconf.query("_http._tcp.local", null);

        Thread.sleep(5000);
        System.out.println("Cancelling service");
        s.cancel();
        Thread.sleep(25000);
        zeroconf.close();
    }
}

package org.captainunlikely.zeroconf;

import java.time.*;

public class Test {

    private final static String now() {
        return Instant.now().toString() + ": ";
    }

    public static void main(String[] args) throws Exception {
        Zeroconf zeroconf = new Zeroconf();
        zeroconf.addAllNetworkInterfaces();
        zeroconf.addListener(new ZeroconfListener() {
            @Override public void packetSent(Packet packet) {
                System.out.println(now() + "packetSend:        " + packet);
            }
            @Override public void packetReceived(Packet packet) {
                System.out.println(now() + "packetReceived:    " + packet);
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

        Service s = new Service.Builder().setName("Goblin").setType("_aardvark._tcp").setPort(8080).put("this", "that").build(zeroconf);
//        s.announce();
        zeroconf.query("_aardvark._tcp.local", null);

        for (int i=0;i<300;i++) {
            Thread.sleep(1000);
//            System.out.println("---");
//            for (Service s2 : zeroconf.getServices()) {
//                System.out.println("    " + s2);
//            }
        }
//        s.cancel();
        zeroconf.close();
    }
}

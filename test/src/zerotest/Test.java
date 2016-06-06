package zerotest;

import org.captainunlikely.zeroconf.*;

public class Test {
    public static void main(String[] args) throws Exception {
        Zeroconf zeroconf = new Zeroconf();
        zeroconf.addAllNetworkInterfaces();
        zeroconf.addReceiveListener(new PacketListener() {
            public void packetEvent(Packet packet) {
                System.out.println("RECV: "+packet);
            }
        });
        zeroconf.addSendListener(new PacketListener() {
            public void packetEvent(Packet packet) {
                System.out.println("SEND: "+packet);
            }
        });
        Service s = zeroconf.newService("MyWeb", "http", 8080).putText("this", "that");
        s.announce();
        Thread.sleep(5000);
//        s.cancel();
        zeroconf.close();
    }
}

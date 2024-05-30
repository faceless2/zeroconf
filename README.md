# Zeroconf

Zeroconf is a simple Java implementation of Multicast DNS Service Discovery, _aka_ the service discovery bit of Zeroconf.
Originally written as a quick hack to avoid having to use [https://github.com/jmdns/jmdns](https://github.com/jmdns/jmdns), it has evolved into something
that can both announce and listen for Services:

* Listens on multiple interfaces (IPv4 and IPv6)
* Network topology changes are handled transparently.
* Sent packets include only the A and AAAA records that apply to the interface they're sent on
* Requires Java 8+ and no other dependencies.
* Javadocs at [https://faceless2.github.io/zeroconf/docs](https://faceless2.github.io/zeroconf/docs/)
* Prebuilt binary at [https://faceless2.github.io/zeroconf/dist/zeroconf-1.0.jar](https://faceless2.github.io/zeroconf/dist/zeroconf-1.0.jar)

Here's a simple example which announces a service on all interfaces on the local machine:

```java
import com.bfo.zeroconf.*;

Zeroconf zc = new Zeroconf();
Service service = new Service.Builder()
                    .setName("MyWeb")
                    .setType("_http._tcp")
                    .setPort(8080)
                    .put("path", "/path/to/service")
                    .build(zc);
service.announce();
// time passes
service.cancel();
// time passes
zc.close();
```

And to listen, either add a Listener for events or use the live, thread-safe Collection of Services.

```java
import com.bfo.zeroconf.*;

Zeroconf zc = new Zeroconf();
zc.addListener(new ZeroconfListener() {
    public void serviceNamed(String type, String name) {
        if ("_http._tcp".equals(type)) {
            zc.query(type, name);  // Ask for details on any announced HTTP services
        }
    }
    public void serviceAnnounced(Service service) {
        // A new service has just been announced
    }
});

zc.query("_http._tcp", null); // Ask for any HTTP services

// time passes
for (Service s : zc.getServices()) {
  if (s.getType().equals("_http._tcp") {
     // A service has been announced at some point in the past, and has not yet expired.
   }
}

// time passes
zc.close();
```

To build
--

Run `ant` to build the Jar in the `build` directory, and javadoc in the `doc` directory.

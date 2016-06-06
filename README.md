# CU-Zeroconf

CU-Zeroconf is a very simple Java implementation of Multicast DNS Service Discovery, aka the service discovery bit of Zeroconf. It was written in response to the authors unpublishable opinions on the only other existing pure-Java implementation at https://github.com/jmdns/jmdns, which claims to be correct. It may well be.

This implementation is not correct, but it tries to make up for that by being simple, documented and readable. It has been cobbled together in 8 hours from a skim read of one or two specifications and a glance at some implementations in other languages. It is certainly less fully-featured, as it currently only supports announcing new services, and it may not even do that properly. However it does successfully announce a service in a way that Zeroconf clients seem to recognise, on one _or more_ local NetworkInterfaces, without immediately hanging, which for me at least is progress. It does this using a single Thread and a selector (from the java.nio.channels) package.

There is considerable room for improvement:

* IPV6 is largely untested, although it appears to work.
* There's no facility to search for services, just to announce them. However it's not a great leap to add this - the facility is there to create queries and receive responses.
* The addition of a monitor class to track and automatically update Zeroconf objects with changes to NetworkInterface topology would be nice.
* The responses to incoming queries are very simple, because I couldn't be bothered to read the spec to see how to do it properly. I think we should skip existing answers, but that's not implemented. We respond authoritively to queries for A and AAAA records that we have published as part of a Service announcement - I'm unsure if this is correct, but it doesn't seem to break anything,


To build
--

Run "ant". There are no external dependencies. Needs Java 7 or later, although only tested under Java 8

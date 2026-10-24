package com.cloudhopper.smpp.proxy;

/*
 * #%L
 * ch-smpp
 * %%
 * Copyright (C) 2009 - 2015 Cloudhopper by Twitter
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

// java imports
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;

// third party imports
import org.junit.*;

/**
 * Tests the trust decision of {@link ProxyProtocolDetector}.
 */
public class ProxyProtocolDetectorTest {

    private static final TrustedNetworks IPV4_TRUSTED = TrustedNetworks.parse(List.of("172.16.24.0/24"));

    private static InetSocketAddress socketAddress(InetAddress address) {
        return new InetSocketAddress(address, 40000);
    }

    // an IPv6 address object that keeps the ::ffff:a.b.c.d form (the JDK would normally return an Inet4Address)
    private static Inet6Address mapped(int a, int b, int c, int d) throws Exception {
        byte[] bytes = new byte[16];
        bytes[10] = (byte) 0xFF;
        bytes[11] = (byte) 0xFF;
        bytes[12] = (byte) a;
        bytes[13] = (byte) b;
        bytes[14] = (byte) c;
        bytes[15] = (byte) d;
        return Inet6Address.getByAddress(null, bytes, -1);
    }

    @Test
    public void ipv4PeerInTrustedNetworkIsTrusted() throws Exception {
        Assert.assertTrue(ProxyProtocolDetector.isTrusted(IPV4_TRUSTED, socketAddress(InetAddress.getByName("172.16.24.9"))));
    }

    @Test
    public void ipv4PeerOutsideTrustedNetworksIsNotTrusted() throws Exception {
        Assert.assertFalse(ProxyProtocolDetector.isTrusted(IPV4_TRUSTED, socketAddress(InetAddress.getByName("172.16.25.9"))));
    }

    @Test
    public void ipv4MappedIpv6PeerIsMatchedAgainstIpv4Networks() throws Exception {
        Inet6Address peer = mapped(172, 16, 24, 9);
        Assert.assertEquals(16, peer.getAddress().length);
        Assert.assertTrue(ProxyProtocolDetector.isTrusted(IPV4_TRUSTED, socketAddress(peer)));
    }

    @Test
    public void ipv4MappedIpv6PeerOutsideTheNetworkIsNotTrusted() throws Exception {
        Assert.assertFalse(ProxyProtocolDetector.isTrusted(IPV4_TRUSTED, socketAddress(mapped(10, 1, 2, 3))));
    }

    @Test
    public void nativeIpv6PeerDoesNotMatchIpv4Networks() throws Exception {
        Assert.assertFalse(ProxyProtocolDetector.isTrusted(IPV4_TRUSTED, socketAddress(InetAddress.getByName("2001:db8::1"))));
    }

    @Test
    public void unresolvedOrNonInetPeerIsNotTrusted() {
        Assert.assertFalse(ProxyProtocolDetector.isTrusted(IPV4_TRUSTED, InetSocketAddress.createUnresolved("proxy.example", 1)));
        Assert.assertFalse(ProxyProtocolDetector.isTrusted(IPV4_TRUSTED, (SocketAddress) null));
        Assert.assertFalse(ProxyProtocolDetector.isTrusted(IPV4_TRUSTED, new SocketAddress() { }));
    }
}

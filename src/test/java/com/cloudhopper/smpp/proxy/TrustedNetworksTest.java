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
import java.net.InetAddress;
import java.util.List;

// third party imports
import org.junit.*;

/**
 * Tests for {@link TrustedNetworks} and {@link ProxyProtocolConfiguration}.
 */
public class TrustedNetworksTest {

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    @Test
    public void containsAddressesInSplitIpv4Ranges() throws Exception {
        TrustedNetworks networks = TrustedNetworks.parse(List.of("172.16.24.0/25", "172.16.24.128/25"));
        Assert.assertTrue(networks.contains(ip("172.16.24.8")));
        Assert.assertTrue(networks.contains(ip("172.16.24.136")));
        Assert.assertFalse(networks.contains(ip("172.16.25.1")));
    }

    @Test
    public void bareIpv4AddressIsSlash32() throws Exception {
        TrustedNetworks networks = TrustedNetworks.parse(List.of("127.0.0.1"));
        Assert.assertTrue(networks.contains(ip("127.0.0.1")));
        Assert.assertFalse(networks.contains(ip("127.0.0.2")));
    }

    @Test
    public void containsAddressesInIpv6Network() throws Exception {
        TrustedNetworks networks = TrustedNetworks.parse(List.of("2001:db8::/32"));
        Assert.assertTrue(networks.contains(ip("2001:db8::1")));
        Assert.assertFalse(networks.contains(ip("2001:db9::1")));
    }

    @Test
    public void ipv4AddressNeverMatchesIpv6Network() throws Exception {
        TrustedNetworks networks = TrustedNetworks.parse(List.of("2001:db8::/32"));
        Assert.assertFalse(networks.contains(ip("10.0.0.1")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsIpv4PrefixLengthAbove32() {
        TrustedNetworks.parse(List.of("10.0.0.0/33"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsIpv4SlashZero() {
        TrustedNetworks.parse(List.of("0.0.0.0/0"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsIpv6SlashZero() {
        TrustedNetworks.parse(List.of("::/0"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsSlashZeroWithNonZeroAddress() {
        TrustedNetworks.parse(List.of("10.0.0.0/0"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void slashZeroIsRejectedEvenAmongValidEntries() {
        TrustedNetworks.parse(List.of("10.0.0.0/8", "::/0"));
    }

    @Test
    public void broadButNonZeroPrefixesAreAcceptedWithAWarning() throws Exception {
        // shorter than /8 (IPv4) or /16 (IPv6) is only logged as a warning
        TrustedNetworks networks = TrustedNetworks.parse(List.of("10.0.0.0/7", "2001::/15"));
        Assert.assertTrue(networks.contains(ip("11.1.2.3")));
        Assert.assertTrue(networks.contains(ip("2000::1")));
        Assert.assertFalse(networks.contains(ip("12.0.0.1")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnparsableAddress() {
        TrustedNetworks.parse(List.of("not-an-ip/8"));
    }

    @Test
    public void validateThrowsWhenEnabledWithoutTrustedProxies() {
        ProxyProtocolConfiguration config = new ProxyProtocolConfiguration();
        config.setEnabled(true);
        try {
            config.validate();
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void validateDoesNotThrowWhenDisabled() {
        ProxyProtocolConfiguration config = new ProxyProtocolConfiguration();
        Assert.assertFalse(config.isEnabled());
        config.validate();
    }
}

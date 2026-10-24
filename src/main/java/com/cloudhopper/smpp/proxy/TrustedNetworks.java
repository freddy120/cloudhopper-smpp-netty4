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
import java.util.ArrayList;
import java.util.List;

/**
 * An immutable set of IPv4 and IPv6 networks given in CIDR notation, used to
 * decide whether a peer address is allowed to send a PROXY protocol header.
 * <p>
 * A bare address (no "/prefix") is treated as a single host (/32 for IPv4,
 * /128 for IPv6). Addresses must be numeric literals: host names are rejected
 * and no DNS lookups are performed. An IPv4 address never matches an IPv6
 * network and vice versa.
 */
public final class TrustedNetworks {

    private final List<Network> networks;

    private TrustedNetworks(List<Network> networks) {
        this.networks = List.copyOf(networks);
    }

    /**
     * Parses a list of CIDR networks, e.g. "10.0.0.0/8", "127.0.0.1" or
     * "2001:db8::/32".
     * @param cidrs The networks to parse. Must not be null
     * @return The parsed, immutable networks
     * @throws IllegalArgumentException if any entry is not a valid CIDR network
     */
    public static TrustedNetworks parse(List<String> cidrs) {
        if (cidrs == null) {
            throw new IllegalArgumentException("Trusted networks must not be null");
        }
        List<Network> parsed = new ArrayList<>(cidrs.size());
        for (String cidr : cidrs) {
            parsed.add(parseNetwork(cidr));
        }
        return new TrustedNetworks(parsed);
    }

    /**
     * Returns true if the address falls inside any of the networks.
     * @param address The address to test
     * @return True if the address is within a trusted network, otherwise false
     */
    public boolean contains(InetAddress address) {
        byte[] candidate = address.getAddress();
        for (Network network : networks) {
            if (network.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static Network parseNetwork(String cidr) {
        if (cidr == null) {
            throw new IllegalArgumentException("Trusted network must not be null");
        }
        String value = cidr.trim();
        int slash = value.indexOf('/');
        String addressPart = slash < 0 ? value : value.substring(0, slash);

        byte[] address = parseAddress(cidr, addressPart);
        int maxPrefix = address.length * 8;
        if (slash < 0) {
            return new Network(address, maxPrefix);
        }

        String prefixPart = value.substring(slash + 1);
        if (!prefixPart.matches("[0-9]{1,3}")) {
            throw new IllegalArgumentException("Invalid prefix length in trusted network: " + cidr);
        }
        int prefixLength = Integer.parseInt(prefixPart);
        if (prefixLength > maxPrefix) {
            throw new IllegalArgumentException("Prefix length out of range in trusted network: " + cidr);
        }
        return new Network(address, prefixLength);
    }

    private static byte[] parseAddress(String cidr, String addressPart) {
        byte[] bytes = addressPart.indexOf(':') >= 0 ? parseIpv6(addressPart) : parseIpv4(addressPart);
        if (bytes == null) {
            throw new IllegalArgumentException("Invalid IP address in trusted network: " + cidr);
        }
        return bytes;
    }

    /**
     * Parses a dotted-quad IPv4 literal, or returns null if it is not one.
     */
    private static byte[] parseIpv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].matches("[0-9]{1,3}")) {
                return null;
            }
            int value = Integer.parseInt(parts[i]);
            if (value > 255) {
                return null;
            }
            bytes[i] = (byte) value;
        }
        return bytes;
    }

    /**
     * Parses an IPv6 literal (with "::" compression and an optional trailing
     * dotted-quad IPv4 part), or returns null if it is not one.
     */
    private static byte[] parseIpv6(String text) {
        String body = text;
        byte[] embeddedIpv4 = null;
        if (text.indexOf('.') >= 0) {
            int lastColon = text.lastIndexOf(':');
            embeddedIpv4 = parseIpv4(text.substring(lastColon + 1));
            if (embeddedIpv4 == null) {
                return null;
            }
            // the embedded IPv4 part occupies the last two 16-bit groups
            body = text.substring(0, lastColon + 1) + "0:0";
        }

        List<String> head;
        List<String> tail;
        int compression = body.indexOf("::");
        if (compression >= 0) {
            if (body.indexOf("::", compression + 1) >= 0) {
                return null;
            }
            head = splitGroups(body.substring(0, compression));
            tail = splitGroups(body.substring(compression + 2));
            if (head == null || tail == null || head.size() + tail.size() > 7) {
                return null;
            }
        } else {
            head = splitGroups(body);
            tail = List.of();
            if (head == null || head.size() != 8) {
                return null;
            }
        }

        byte[] bytes = new byte[16];
        for (int i = 0; i < head.size(); i++) {
            writeGroup(bytes, i, head.get(i));
        }
        for (int i = 0; i < tail.size(); i++) {
            writeGroup(bytes, 8 - tail.size() + i, tail.get(i));
        }
        if (embeddedIpv4 != null) {
            System.arraycopy(embeddedIpv4, 0, bytes, 12, 4);
        }
        return bytes;
    }

    /**
     * Splits colon-separated IPv6 groups, or returns null if any group is not
     * one to four hex digits. An empty input has no groups.
     */
    private static List<String> splitGroups(String text) {
        List<String> groups = new ArrayList<>();
        if (text.isEmpty()) {
            return groups;
        }
        for (String group : text.split(":", -1)) {
            if (!group.matches("[0-9A-Fa-f]{1,4}")) {
                return null;
            }
            groups.add(group);
        }
        return groups;
    }

    private static void writeGroup(byte[] bytes, int index, String hex) {
        int value = Integer.parseInt(hex, 16);
        bytes[index * 2] = (byte) (value >> 8);
        bytes[index * 2 + 1] = (byte) value;
    }

    private static final class Network {

        private final byte[] address;
        private final int prefixLength;

        Network(byte[] address, int prefixLength) {
            this.address = address.clone();
            this.prefixLength = prefixLength;
        }

        boolean contains(byte[] candidate) {
            if (candidate.length != address.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != address[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (candidate[fullBytes] & mask) == (address[fullBytes] & mask);
        }
    }
}

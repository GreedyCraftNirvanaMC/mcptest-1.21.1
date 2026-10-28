package com.gctn.mcptest.mcp;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/** IPv4 LAN policy. No DNS lookup is used to validate a bind address, Host, or Origin. */
final class NetworkAccess {
    private NetworkAccess() {}

    static Inet4Address bindAddress(String value) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) {
            throw new IllegalArgumentException("bindAddress must be a literal IPv4 address, not a hostname or URL");
        }
        String[] octets = value.split("\\.");
        byte[] bytes = new byte[4];
        for (int i = 0; i < bytes.length; i++) {
            int octet = Integer.parseInt(octets[i]);
            if (octet > 255) throw new IllegalArgumentException("bindAddress contains an invalid IPv4 octet");
            bytes[i] = (byte) octet;
        }
        try {
            Inet4Address address = (Inet4Address) InetAddress.getByAddress(bytes);
            if (!address.isAnyLocalAddress() && !isLocalPeer(address)) {
                throw new IllegalArgumentException("bindAddress must be loopback, RFC1918 private IPv4, link-local IPv4, or 0.0.0.0");
            }
            return address;
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException("Invalid IPv4 byte length", impossible);
        }
    }

    static boolean isLocalPeer(InetAddress address) {
        if (!(address instanceof Inet4Address)) return false;
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        return first == 127 || first == 10 || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168) || (first == 169 && second == 254);
    }

    static boolean allows(InetAddress bind, InetAddress peer, InetAddress local, int port, String host, String origin) {
        if (!isLocalPeer(peer) || !isLocalPeer(local)) return false;
        if (bind.isLoopbackAddress() && !peer.isLoopbackAddress()) return false;
        if (!bind.isAnyLocalAddress() && !bind.equals(local)) return false;
        if (!matchesHost(local, port, host)) return false;
        if (origin == null) return true; // Native MCP clients normally omit Origin.
        try {
            URI uri = URI.create(origin);
            return "http".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && matchesHost(local, port, uri.getHost() + ":" + uri.getPort())
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean matchesHost(InetAddress local, int port, String host) {
        return (local.getHostAddress() + ":" + port).equals(host)
                || (local.isLoopbackAddress() && ("localhost:" + port).equalsIgnoreCase(host));
    }
}

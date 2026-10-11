package com.seqwawa.seq.images;

import org.apache.hc.client5.http.DnsResolver;

import java.net.*;

final class PublicDns implements DnsResolver {
    public InetAddress[] resolve(String host) throws UnknownHostException {
        if (!MediaLinks.allowed(URI.create("https://" + host + "/")))
            throw new UnknownHostException("Unsupported media host");
        return checked(InetAddress.getAllByName(host));
    }

    public String resolveCanonicalHostname(String host) {
        return host;
    }

    static InetAddress[] checked(InetAddress[] addresses) throws UnknownHostException {
        if (addresses == null || addresses.length == 0)
            throw new UnknownHostException("No public media address");
        for (var address : addresses)
            if (!isPublic(address))
                throw new UnknownHostException("Media host resolves to a restricted network");
        return addresses;
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        int a = b[0] & 255, c = b[1] & 255, d = b[2] & 255;
        if (b.length == 4)
            return a != 0
                    && a != 10
                    && a != 127
                    && a < 224
                    && !(a == 100 && c >= 64 && c <= 127)
                    && !(a == 169 && c == 254)
                    && !(a == 172 && c >= 16 && c <= 31)
                    && !(a == 192
                            && (c == 168 || c == 0 && (d == 0 || d == 2) || c == 88 && d == 99))
                    && !(a == 198 && (c == 18 || c == 19 || c == 51 && d == 100))
                    && !(a == 203 && c == 0 && d == 113);
        // Reject IPv6 transition, special-use and documentation ranges.
        return b.length == 16
                && (a & 0xe0) == 0x20
                && !(a == 0x20 && c == 0x01 && d < 2)
                && !(a == 0x20 && c == 0x01 && d == 0x0d && (b[3] & 255) == 0xb8)
                && !(a == 0x20 && c == 0x02)
                && !(a == 0x3f && (c & 0xf0) == 0xf0);
    }
}

package com.seqwawa.seq.images;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PublicDnsTest {
    private static long deadline(long millis) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    }

    @Test
    void privateReservedAndTransitionNetworksAreBlocked() throws Exception {
        for (String ip : List.of(
                "0.0.0.0",
                "10.0.0.1",
                "127.0.0.1",
                "169.254.169.254",
                "172.16.0.1",
                "192.168.1.1",
                "100.64.0.1",
                "192.0.0.1",
                "192.0.2.1",
                "192.88.99.1",
                "198.18.0.1",
                "198.51.100.1",
                "203.0.113.1",
                "224.0.0.1",
                "255.255.255.255",
                "::",
                "::1",
                "fc00::1",
                "fe80::1",
                "ff02::1",
                "::ffff:127.0.0.1",
                "64:ff9b::7f00:1",
                "2001::1",
                "2001:db8::1",
                "2002:7f00:1::",
                "3fff::1")) assertFalse(PublicDns.isPublic(InetAddress.getByName(ip)), ip);
        for (String ip : List.of("1.1.1.1", "8.8.8.8", "104.16.1.1", "2606:4700:4700::1111", "2001:4860:4860::8888"))
            assertTrue(PublicDns.isPublic(InetAddress.getByName(ip)), ip);
    }

    @Test
    void onePrivateDnsAnswerRejectsTheEntireLookup() throws Exception {
        var mixed = new InetAddress[] {InetAddress.getByName("1.1.1.1"), InetAddress.getByName("127.0.0.1")};
        assertThrows(UnknownHostException.class, () -> PublicDns.checked(mixed));
        assertThrows(UnknownHostException.class, () -> PublicDns.checked(new InetAddress[0]));
        assertThrows(UnknownHostException.class, () -> new PublicDns(deadline(2_000)).resolve("localhost"));
    }

    @Test
    void stalledResolverIsBoundedByTheDownloadDeadline() throws Exception {
        var answer = new InetAddress[] {InetAddress.getByName("1.1.1.1")};
        assertArrayEquals(answer, new PublicDns(deadline(2_000), host -> answer).resolve("i.imgur.com"));
        var release = new CountDownLatch(1);
        // Like the OS resolver, a stalled lookup ignores interruption.
        PublicDns.Lookup stalled = host -> {
            long end = deadline(10_000);
            boolean interrupted = false;
            while (release.getCount() > 0 && System.nanoTime() < end) {
                try {
                    release.await(end - System.nanoTime(), TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return answer;
        };
        try {
            long start = System.nanoTime();
            var timeout = assertThrows(
                    UnknownHostException.class, () -> new PublicDns(deadline(200), stalled).resolve("i.imgur.com"));
            assertEquals("Media host lookup timed out", timeout.getMessage());
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2), "lookup outlived its deadline");
            // While stalled lookups hold the resolver threads, further lookups wait only until their own
            // deadline and give their queue slot back.
            long burst = System.nanoTime();
            for (int i = 0; i < 8; i++)
                assertThrows(
                        UnknownHostException.class, () -> new PublicDns(deadline(50), stalled).resolve("i.imgur.com"));
            assertTrue(System.nanoTime() - burst < TimeUnit.SECONDS.toNanos(3), "lookups queued past deadlines");
            assertEquals(0, PublicDns.queuedLookups(), "abandoned lookups still hold queue slots");
        } finally {
            release.countDown();
        }
    }
}

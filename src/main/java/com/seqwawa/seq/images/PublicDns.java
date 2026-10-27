package com.seqwawa.seq.images;

import java.net.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.hc.client5.http.DnsResolver;

/** Resolves allowlisted media hosts to public addresses only, within the download deadline. */
final class PublicDns implements DnsResolver {
    // The OS resolver cannot be interrupted. A stalled lookup keeps its thread until the OS
    // gives up, so lookups share two threads and fail fast when both are stuck.
    private static final ThreadPoolExecutor LOOKUPS =
            new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), r -> {
                var thread = new Thread(r, "Sequoia media DNS");
                thread.setDaemon(true);
                return thread;
            });

    static {
        LOOKUPS.allowCoreThreadTimeOut(true);
    }

    @FunctionalInterface
    interface Lookup {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final long deadline;
    private final Lookup lookup;

    PublicDns(long deadline) {
        this(deadline, InetAddress::getAllByName);
    }

    PublicDns(long deadline, Lookup lookup) {
        this.deadline = deadline;
        this.lookup = lookup;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        if (!MediaLinks.allowedHost(host))
            throw new UnknownHostException("Unsupported media host");
        Future<InetAddress[]> pending;
        try {
            pending = LOOKUPS.submit(() -> lookup.resolve(host));
        } catch (RejectedExecutionException busy) {
            throw new UnknownHostException("Media host lookup unavailable");
        }
        try {
            long remaining = Math.max(0, deadline - System.nanoTime());
            return checked(pending.get(remaining, TimeUnit.NANOSECONDS));
        } catch (TimeoutException late) {
            abandon(pending);
            throw new UnknownHostException("Media host lookup timed out");
        } catch (InterruptedException interrupted) {
            abandon(pending);
            Thread.currentThread().interrupt();
            throw new UnknownHostException("Media host lookup cancelled");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof UnknownHostException unknown) throw unknown;
            var wrapped = new UnknownHostException("Media host lookup failed");
            wrapped.initCause(failed.getCause());
            throw wrapped;
        }
    }

    /** Frees a queued lookup's slot at once; a running one keeps its thread until the OS returns. */
    private static void abandon(Future<InetAddress[]> pending) {
        pending.cancel(true);
        LOOKUPS.remove((Runnable) pending);
    }

    static int queuedLookups() {
        return LOOKUPS.getQueue().size();
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        return host;
    }

    static InetAddress[] checked(InetAddress[] addresses) throws UnknownHostException {
        if (addresses == null || addresses.length == 0) throw new UnknownHostException("No public media address");
        for (var address : addresses)
            if (!isPublic(address)) throw new UnknownHostException("Media host resolves to a restricted network");
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
                    && !(a == 192 && (c == 168 || c == 0 && (d == 0 || d == 2) || c == 88 && d == 99))
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

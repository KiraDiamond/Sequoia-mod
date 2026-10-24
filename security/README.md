# Java TLS trust

Standalone JVMs need the certificate blocklist update from the [OpenJDK advisory](https://openjdk.org/groups/vulnerability/advisories/2026-10-21).
A Java version pin alone does not establish that its trust store contains the fix.

`blocked.certs` contains the SHA-256 entries published at
<https://ops.java/unix/blocked.certs> and <https://ops.java/windows/blocked.certs>.
The published entry sets are identical. The installer preserves existing vendor
and operator blocks and requires SHA-256 format.

Before starting a standalone build or server JVM, or a JVM you selected for a Minecraft launcher, apply:

```sh
bash scripts/install-java-blocklist.sh "$JAVA_HOME"
```

This installer supports Java 17 and later. Java 8 uses `blacklisted.certs` and
is not supported by this script; follow the linked OpenJDK advisory or your
distribution's security update instructions for that runtime.

Use the JVM home actually selected by the service or launcher, not just the shell
Java. Restart it after applying the update. This does not change a custom
application trust manager or a Java runtime that a launcher downloads and manages
itself, such as the official Minecraft launcher's bundled runtime. Do not
edit a distribution-managed Java store; use that distribution's security update
and Java trust-store synchronization instead.

## Protection limit

This installs an exact-certificate fingerprint blocklist, not a complete trust
policy. [Re-encoded intermediates can bypass fingerprint-only blocking](https://www.sonatype.com/blog/the-limits-of-fingerprint-blocklists-a-reproducible-java-certificate-bypass).
Rejecting the 14 published incident chains does not test that bypass. Keep a
production rollout gated on a vendor trust-store update or another policy that
also rejects equivalent re-encoded chains. Validate legitimate service chains
before removing the affected roots or enabling strict revocation checks. This
installer does neither and must not be treated as proof of complete containment.

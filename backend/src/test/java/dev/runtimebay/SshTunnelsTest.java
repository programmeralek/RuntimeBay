package dev.runtimebay;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SshTunnelsTest {
    @TempDir Path temporary;
    @Test void killRejectsAnUnrelatedProcessEvenWhenItsPidAndStartTimeAreKnown() throws Exception {
        Process child = new ProcessBuilder("/bin/sleep", "30").start();
        try {
            var info = child.info();
            var expected = new TunnelDiscovery.SshProcess(child.pid(), java.util.List.of(info.arguments().orElseThrow()), java.util.Set.of(8099), -1, info.startInstant().orElseThrow());
            assertThrows(ApiException.class, () -> TunnelDiscovery.kill(expected));
            assertTrue(child.isAlive());
        } finally { child.destroyForcibly(); }
    }
    @Test void killPreviewsSharedTunnelsRevalidatesIdentityAndKillsOnlyVerifiedOwner() throws Exception {
        var registry = new HostRegistry(temporary);
        var host = registry.save(null, "Helfer", "helfer", "alek", 22);
        var hosts = new MachineHosts(registry, new HostTools());
        var started = java.time.Instant.parse("2026-11-06T00:00:00Z");
        var master = new TunnelDiscovery.SshProcess(100, java.util.List.of("-N", "-L", "127.0.0.1:18098:localhost:8098", "--", "helfer-remote"), java.util.Set.of(18098, 8099), -1, started);
        var client = new TunnelDiscovery.SshProcess(101, java.util.List.of("-N", "-L", "127.0.0.1:8099:localhost:8099", "--", "helfer-remote"), java.util.Set.of(), 100, started);
        var snapshot = new java.util.concurrent.atomic.AtomicReference<java.util.List<TunnelDiscovery.SshProcess>>(java.util.List.of(master, client));
        var killed = new java.util.ArrayList<Long>();
        var tunnels = new SshTunnels(temporary, hosts, null, temporary, snapshot::get, process -> { killed.add(process.pid()); snapshot.set(java.util.List.of()); });
        var first = tunnels.save(null, "Processor", host.id(), 18098, "localhost", 8098, "helfer-remote");
        var second = tunnels.save(null, "UI", host.id(), 8099, "localhost", 8099, "helfer-remote");
        var plan = tunnels.previewKill(second.definition().id());
        assertEquals(100, plan.pid()); assertEquals(java.util.List.of("Processor", "UI"), plan.affectedTunnels());
        snapshot.set(java.util.List.of(new TunnelDiscovery.SshProcess(100, master.arguments(), master.ports(), -1, started.plusSeconds(1)), client));
        assertThrows(ApiException.class, () -> tunnels.kill(second.definition().id(), plan.processKey())); assertTrue(killed.isEmpty());
        snapshot.set(java.util.List.of(new TunnelDiscovery.SshProcess(100, master.arguments(), java.util.Set.of(18098, 8099, 9999), -1, started), client));
        assertThrows(ApiException.class, () -> tunnels.kill(second.definition().id(), plan.processKey())); assertTrue(killed.isEmpty());
        snapshot.set(java.util.List.of(master, client));
        assertEquals("stopped", tunnels.kill(second.definition().id(), plan.processKey()).state());
        assertEquals(java.util.List.of(100L), killed); assertEquals("stopped", tunnels.list().getFirst().state());
        assertThrows(ApiException.class, () -> tunnels.previewKill(first.definition().id()));
        tunnels.close(); hosts.close();
    }

    @Test void recoversVerifiedForwardAfterReloadWithoutStartingOrTakingOwnership() throws Exception {
        var registry = new HostRegistry(temporary);
        var host = registry.save(null, "Helfer", "helfer", "alek", 22);
        var hosts = new MachineHosts(registry, new HostTools());
        var first = new SshTunnels(temporary, hosts, null, temporary, java.util.List::of);
        var saved = first.save(null, "Processor", host.id(), 18098, "localhost", 8098, "helfer-remote");
        first.recordFailure(saved.definition().id(), "Address already in use"); first.close();
        var client = new TunnelDiscovery.SshProcess(100, java.util.List.of("-N", "-L", "127.0.0.1:18098:localhost:8098", "--", "helfer-remote"), java.util.Set.of(18098), -1);
        var reopened = new SshTunnels(temporary, hosts, null, temporary, () -> java.util.List.of(client));
        var recovered = reopened.list().getFirst();
        assertEquals("running", recovered.state()); assertTrue(recovered.external()); assertNull(recovered.error()); assertFalse(recovered.unreadError());
        assertEquals(recovered, reopened.start(saved.definition().id()));
        assertThrows(ApiException.class, () -> reopened.stop(saved.definition().id()));
        reopened.close(); hosts.close();
    }

    @Test void detectionRequiresExactDestinationRouteAndVerifiedListenerIncludingMultiplexedMaster() {
        var host = new HostRegistry.Host("host-id", "Helfer", "helfer", "alek", 22, false);
        var definition = new SshTunnels.Definition("id", "UI", host.id(), 8099, "localhost", 8099, "helfer-remote");
        var args = java.util.List.of("-N", "-L", "127.0.0.1:8099:localhost:8099", "--", "helfer-remote");
        assertFalse(TunnelDiscovery.matches(definition, host, java.util.List.of(new TunnelDiscovery.SshProcess(10, args, java.util.Set.of(), -1))));
        assertTrue(TunnelDiscovery.matches(definition, host, java.util.List.of(new TunnelDiscovery.SshProcess(10, args, java.util.Set.of(8099), -1))));
        var wrong = new TunnelDiscovery.SshProcess(10, java.util.List.of("-N", "-L", "8099:localhost:8000", "other-host"), java.util.Set.of(8099), -1);
        assertFalse(TunnelDiscovery.matches(definition, host, java.util.List.of(wrong)));
        var client = new TunnelDiscovery.SshProcess(10, args, java.util.Set.of(), 20);
        var master = new TunnelDiscovery.SshProcess(20, java.util.List.of("-N", "helfer-remote"), java.util.Set.of(8099), -1);
        assertTrue(TunnelDiscovery.matches(definition, host, java.util.List.of(client, master)));
        assertFalse(TunnelDiscovery.matches(definition, host, java.util.List.of(new TunnelDiscovery.SshProcess(10, args, java.util.Set.of(), 99), master)));
    }

    @Test void savedForwardingProfilesSurviveReloadAndKeepTheirHostBinding() throws Exception {
        var registry = new HostRegistry(temporary);
        var host = registry.save(null, "Helfer", "helfer", "alek", 22);
        var hosts = new MachineHosts(registry, new HostTools());
        var tunnels = new SshTunnels(temporary, hosts, null, temporary, java.util.List::of);
        var saved = tunnels.save(null, "Gobble MySQL", host.id(), 13306, "localhost", 3306, "helfer-remote");
        assertEquals("stopped", saved.state());
        assertEquals("Gobble MySQL", tunnels.list().getFirst().definition().name());
        assertThrows(ApiException.class, () -> tunnels.save(null, "Collision", host.id(), 13306, "localhost", 80, null));
        assertThrows(ApiException.class, () -> tunnels.requireUnusedHost(host.id()));
        tunnels.close();

        var restored = new SshTunnels(temporary, hosts, null, temporary, java.util.List::of);
        assertEquals(saved.definition(), restored.list().getFirst().definition());
        assertEquals("helfer-remote", restored.list().getFirst().definition().sshAlias());
        assertEquals("Helfer", restored.list().getFirst().hostName());
        restored.close(); hosts.close();
    }

    @Test void rejectsUnsafeDestinationsAndInvalidPorts() {
        assertThrows(ApiException.class, () -> SshTunnels.validate("bad", "host-id", 1234, "-oProxyCommand=evil", 80));
        assertThrows(ApiException.class, () -> SshTunnels.validate("bad", "host-id", 0, "localhost", 80));
        assertThrows(ApiException.class, () -> SshTunnels.validate("bad", "host-id", 1234, "localhost", 65536));
        assertThrows(ApiException.class, () -> SshTunnels.validate("bad", "host-id", 1234, "localhost", 80, "-oProxyCommand=evil"));
        assertDoesNotThrow(() -> SshTunnels.validate("Tunnel", "host-id", 19789, "localhost", 19789, "helfer-remote"));
    }

    @Test void sshAliasOverridesHostAuthTargetButKeepsControlOptions() {
        var host = new HostRegistry.Host("123", "Helfer", "helfer", "alek", 22, false);
        var args = HostRegistry.sshArguments(host, false, Path.of("/tmp/ssh-controls"), "helfer-remote");
        assertTrue(args.contains("helfer-remote"));
        assertFalse(args.contains("alek@helfer"));
        assertFalse(args.contains("-p"));
        assertTrue(args.stream().anyMatch(arg -> arg.startsWith("ControlPath=")));
    }

    @Test void unexpectedTunnelFailuresRemainUnreadUntilAcknowledgedAcrossReload() throws Exception {
        var registry = new HostRegistry(temporary);
        var host = registry.save(null, "Helfer", "helfer", "alek", 22);
        var hosts = new MachineHosts(registry, new HostTools());
        var tunnels = new SshTunnels(temporary, hosts, null, temporary, java.util.List::of);
        var saved = tunnels.save(null, "Processor", host.id(), 18098, "localhost", 8098, "helfer-remote");

        tunnels.recordFailure(saved.definition().id(), "Broken pipe");
        var failed = tunnels.list().getFirst();
        assertEquals("error", failed.state());
        assertTrue(failed.unreadError());
        tunnels.close();

        var restored = new SshTunnels(temporary, hosts, null, temporary, java.util.List::of);
        assertTrue(restored.list().getFirst().unreadError());
        assertEquals("Broken pipe", restored.list().getFirst().error());
        restored.acknowledgeErrors();
        assertFalse(restored.list().getFirst().unreadError());
        restored.close();
        var acknowledged = new SshTunnels(temporary, hosts, null, temporary, java.util.List::of);
        assertFalse(acknowledged.list().getFirst().unreadError());
        assertEquals("error", acknowledged.list().getFirst().state());
        acknowledged.close(); hosts.close();
    }
}

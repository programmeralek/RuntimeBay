package dev.runtimebay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;

/** Saved SSH local-forward profiles. Credentials remain in the host credential store. */
final class SshTunnels implements AutoCloseable {
    record Definition(String id, String name, String hostId, int localPort, String remoteHost, int remotePort, String sshAlias) {}
    record View(Definition definition, String hostName, String state, String error, boolean unreadError, boolean external) {}
    private record Saved(int version, List<Definition> tunnels, Map<String, String> errors, Set<String> unreadErrors) {}
    private static final class Live {
        final Process process;
        volatile String error;
        Live(Process process) { this.process = process; }
    }

    private final Path file, controls;
    private final MachineHosts hosts;
    private final HostPasswords passwords;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final Map<String, Live> live = new HashMap<>();
    private final Map<String, String> errors = new HashMap<>();
    private final Set<String> unreadErrors = new LinkedHashSet<>();
    private final Set<String> recovered = new HashSet<>();
    private final java.util.function.Supplier<List<TunnelDiscovery.SshProcess>> discovery;
    private long lastProbe;
    private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
    private boolean closed;

    SshTunnels(Path directory, MachineHosts hosts, HostPasswords passwords, Path controls) throws IOException {
        this(directory, hosts, passwords, controls, TunnelDiscovery::snapshot);
    }
    SshTunnels(Path directory, MachineHosts hosts, HostPasswords passwords, Path controls, java.util.function.Supplier<List<TunnelDiscovery.SshProcess>> discovery) throws IOException {
        this.file = directory.resolve("tunnels.json"); this.hosts = hosts; this.passwords = passwords; this.controls = controls;
        this.discovery = discovery;
        load();
    }

    synchronized List<View> list() {
        reconcile();
        return definitions.values().stream().map(this::view).toList();
    }

    synchronized View save(String id, String name, String hostId, int localPort, String remoteHost, int remotePort, String sshAlias) throws IOException {
        validate(name, hostId, localPort, remoteHost, remotePort, sshAlias);
        hosts.get(hostId);
        if ("local".equals(hostId)) throw new ApiException(400, "Choose a saved SSH host for this tunnel");
        if (id != null && !definitions.containsKey(id)) throw new ApiException(404, "Tunnel not found");
        for (Definition saved : definitions.values()) if (!saved.id().equals(id) && saved.localPort() == localPort)
            throw new ApiException(409, "Another saved tunnel already uses this local port");
        Definition value = new Definition(id == null ? UUID.randomUUID().toString() : id, name.trim(), hostId, localPort, remoteHost.trim(), remotePort, cleanAlias(sshAlias));
        Definition previous = definitions.get(id);
        if (previous != null && isRunning(id) && !previous.equals(value)) throw new ApiException(409, "Stop this tunnel before changing its connection details");
        String previousError = previous == null ? null : errors.get(value.id());
        boolean wasUnread = unreadErrors.contains(value.id());
        definitions.put(value.id(), value);
        errors.remove(value.id()); unreadErrors.remove(value.id());
        try { persist(); } catch (IOException e) {
            if (previous == null) definitions.remove(value.id()); else definitions.put(value.id(), previous);
            if (previousError != null) errors.put(value.id(), previousError);
            if (wasUnread) unreadErrors.add(value.id());
            throw e;
        }
        return view(value);
    }

    synchronized void requireUnusedHost(String hostId) {
        if (definitions.values().stream().anyMatch(item -> item.hostId().equals(hostId)))
            throw new ApiException(409, "Remove or reassign saved tunnels before changing or removing this host");
    }

    synchronized void remove(String id) throws IOException {
        Definition old = require(id);
        reconcile();
        if (recovered.contains(id)) throw new ApiException(409, "This tunnel is active in another session. Stop it there before removing its profile.");
        String oldError = errors.get(id);
        boolean wasUnread = unreadErrors.contains(id);
        stopProcess(id);
        definitions.remove(id);
        errors.remove(id); unreadErrors.remove(id);
        try { persist(); } catch (IOException e) {
            definitions.put(id, old);
            if (oldError != null) errors.put(id, oldError);
            if (wasUnread) unreadErrors.add(id);
            throw e;
        }
    }

    synchronized void acknowledgeErrors() throws IOException {
        if (unreadErrors.isEmpty()) return;
        Set<String> previous = Set.copyOf(unreadErrors);
        unreadErrors.clear();
        try { persist(); } catch (IOException e) { unreadErrors.addAll(previous); throw e; }
    }

    synchronized View start(String id) {
        Definition definition = require(id);
        reconcile();
        if (recovered.contains(id)) return view(definition);
        if (closed) throw new ApiException(503, "Tunnels are shutting down");
        Live existing = live.get(id);
        if (existing != null && existing.process.isAlive()) return view(definition);
        if (existing != null) live.remove(id);
        String previousError = errors.remove(id);
        boolean wasUnread = unreadErrors.remove(id);
        try { persist(); } catch (IOException e) {
            if (previousError != null) errors.put(id, previousError);
            if (wasUnread) unreadErrors.add(id);
        }
        HostRegistry.Host host = hosts.get(definition.hostId());
        passwords.restore(host);
        List<String> command = new ArrayList<>(HostRegistry.sshArguments(host, false, controls, definition.sshAlias()));
        int destination = command.indexOf("--");
        command.addAll(destination, List.of("-N", "-o", "ExitOnForwardFailure=yes", "-o", "BatchMode=yes", "-L", "127.0.0.1:" + definition.localPort() + ":" + definition.remoteHost() + ":" + definition.remotePort()));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().keySet().removeIf(key -> key.startsWith("RUNTIMEBAY_"));
        builder.environment().put("PATH", "/opt/homebrew/bin:/usr/local/bin:" + builder.environment().getOrDefault("PATH", "/usr/bin:/bin:/usr/sbin:/sbin"));
        passwords.configure(builder, host.id());
        try {
            Process process = builder.start();
            Live running = new Live(process); live.put(id, running);
            readers.submit(() -> monitor(id, running));
            return view(definition);
        } catch (IOException e) {
            recordFailure(id, safeMessage(e.getMessage()));
            throw new ApiException(502, "Could not start the SSH tunnel: " + safeMessage(e.getMessage()));
        }
    }

    synchronized View stop(String id) {
        Definition definition = require(id);
        reconcile();
        if (recovered.contains(id)) throw new ApiException(409, "This tunnel is active in another session. Stop it from the session that started it.");
        stopProcess(id);
        return view(definition);
    }

    private void monitor(String id, Live process) {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) synchronized (process) {
                if (output.length() < 2048) output.append(line).append('\n');
            }
            int exit = process.process.waitFor();
            synchronized (this) {
                if (live.get(id) == process) {
                    if (exit != 0) recordFailure(id, safeMessage(output.toString().strip()));
                    live.remove(id);
                }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (IOException ignored) { /* Process shutdown closes its error stream. */ }
    }

    private synchronized View view(Definition definition) {
        Live process = live.get(definition.id());
        boolean external = recovered.contains(definition.id());
        String state = external ? "running" : process == null ? errors.containsKey(definition.id()) ? "error" : "stopped" : process.process.isAlive() ? "running" : "error";
        return new View(definition, hosts.get(definition.hostId()).name(), state, external ? null : process == null ? errors.get(definition.id()) : process.error, unreadErrors.contains(definition.id()), external);
    }
    synchronized void recordFailure(String id, String message) {
        errors.put(id, message); unreadErrors.add(id);
        try { persist(); } catch (IOException ignored) { /* Keep the failure visible for this app session. */ }
    }
    private boolean isRunning(String id) { reconcile(); Live value = live.get(id); return recovered.contains(id) || value != null && value.process.isAlive(); }
    private void reconcile() {
        if (closed || definitions.isEmpty() || System.nanoTime() - lastProbe < TimeUnit.SECONDS.toNanos(3)) return;
        lastProbe = System.nanoTime();
        var processes = discovery.get();
        if (processes == null) return; // A failed probe must not turn active forwards into failures.
        boolean changed = false;
        for (Definition definition : definitions.values()) {
            String id = definition.id();
            Live owned = live.get(id);
            if (owned != null && owned.process.isAlive()) continue;
            if (TunnelDiscovery.matches(definition, hosts.get(definition.hostId()), processes)) {
                recovered.add(id);
                changed |= errors.remove(id) != null;
                changed |= unreadErrors.remove(id);
            } else if (recovered.remove(id)) {
                errors.put(id, "The SSH forward detected in another session is no longer listening.");
                unreadErrors.add(id); changed = true;
            }
        }
        if (changed) try { persist(); } catch (IOException ignored) { /* Keep reconciliation visible for this session. */ }
    }
    private Definition require(String id) { Definition value = definitions.get(id); if (value == null) throw new ApiException(404, "Tunnel not found"); return value; }
    private void stopProcess(String id) {
        Live value = live.remove(id); if (value == null) return;
        errors.remove(id);
        value.process.descendants().forEach(ProcessHandle::destroy);
        value.process.destroy();
        try { if (!value.process.waitFor(1200, TimeUnit.MILLISECONDS)) value.process.destroyForcibly(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); value.process.destroyForcibly(); }
    }
    private void load() throws IOException {
        if (!Files.exists(file)) return;
        try {
            Saved saved = gson.fromJson(Files.readString(file), Saved.class);
            if (saved == null || saved.version() != 1 || saved.tunnels() == null || saved.tunnels().size() > 100) throw new IllegalArgumentException();
            for (Definition item : saved.tunnels()) {
                if (item == null || item.id() == null || !item.id().equals(UUID.fromString(item.id()).toString()) || definitions.containsKey(item.id())) throw new IllegalArgumentException();
                validate(item.name(), item.hostId(), item.localPort(), item.remoteHost(), item.remotePort(), item.sshAlias());
                hosts.get(item.hostId());
                if (item.hostId().equals("local")) throw new IllegalArgumentException();
                definitions.put(item.id(), item);
            }
            if (saved.errors() != null) for (var entry : saved.errors().entrySet()) {
                if (!definitions.containsKey(entry.getKey()) || entry.getValue() == null || entry.getValue().isBlank() || entry.getValue().length() > 1200) throw new IllegalArgumentException();
                errors.put(entry.getKey(), entry.getValue());
            }
            if (saved.unreadErrors() != null) {
                if (!definitions.keySet().containsAll(saved.unreadErrors()) || !errors.keySet().containsAll(saved.unreadErrors())) throw new IllegalArgumentException();
                unreadErrors.addAll(saved.unreadErrors());
            }
        } catch (RuntimeException e) { throw new IOException("Cannot read saved tunnels. Existing data has been preserved.", e); }
    }
    private void persist() throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), "tunnels-", ".tmp");
        try {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            Files.writeString(temporary, gson.toJson(new Saved(1, List.copyOf(definitions.values()), Map.copyOf(errors), Set.copyOf(unreadErrors))));
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    static void validate(String name, String hostId, int localPort, String remoteHost, int remotePort) {
        validate(name, hostId, localPort, remoteHost, remotePort, null);
    }
    static void validate(String name, String hostId, int localPort, String remoteHost, int remotePort, String sshAlias) {
        if (name == null || name.isBlank() || name.length() > 100 || name.chars().anyMatch(Character::isISOControl)) throw new ApiException(400, "Enter a tunnel name of up to 100 characters");
        if (hostId == null || !hostId.matches("[A-Za-z0-9-]{1,64}")) throw new ApiException(400, "Choose a saved SSH host");
        if (localPort < 1 || localPort > 65535 || remotePort < 1 || remotePort > 65535) throw new ApiException(400, "Ports must be whole numbers between 1 and 65535");
        if (remoteHost == null || !remoteHost.trim().matches("[A-Za-z0-9:][A-Za-z0-9_.:%-]{0,254}")) throw new ApiException(400, "Enter a valid destination hostname or IP address");
        if (sshAlias != null && !sshAlias.isBlank() && !sshAlias.trim().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) throw new ApiException(400, "Enter a valid SSH configuration alias");
    }
    private static String cleanAlias(String alias) { return alias == null || alias.isBlank() ? null : alias.trim(); }
    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) return "SSH exited before the tunnel became available. Check host access and local port availability.";
        String clean = message.replaceAll("(?i)(password|passphrase)\\s*[:=].*", "$1: [hidden]").strip();
        return clean.length() > 1200 ? clean.substring(clean.length() - 1200) : clean;
    }
    @Override public synchronized void close() { closed = true; for (String id : new ArrayList<>(live.keySet())) stopProcess(id); readers.shutdownNow(); }
}

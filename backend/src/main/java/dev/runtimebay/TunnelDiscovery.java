package dev.runtimebay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Read-only reconciliation: require both an exact SSH forward and its listening socket. */
final class TunnelDiscovery {
    record SshProcess(long pid, List<String> arguments, Set<Integer> ports, long masterPid) {}

    static List<SshProcess> snapshot() {
        Map<Long, Set<Integer>> listeners = listeners();
        if (listeners == null) return null;
        if (listeners.isEmpty()) return List.of();
        try (var processes = ProcessHandle.allProcesses()) {
            return processes.filter(ProcessHandle::isAlive).map(process -> {
                var info = process.info();
                if (!info.command().map(command -> Path.of(command).getFileName().toString().equals("ssh")).orElse(false)) return null;
                return info.arguments().map(args -> {
                    var arguments = List.of(args); var ports = listeners.getOrDefault(process.pid(), Set.of());
                    return new SshProcess(process.pid(), arguments, ports, ports.isEmpty() ? masterPid(arguments) : -1);
                }).orElse(null);
            }).filter(Objects::nonNull).toList();
        }
    }

    static boolean matches(SshTunnels.Definition definition, HostRegistry.Host host, List<SshProcess> processes) {
        String target = definition.sshAlias() == null ? (host.user().isEmpty() ? "" : host.user() + "@") + host.address() : definition.sshAlias();
        String route = "127.0.0.1:" + definition.localPort() + ":" + definition.remoteHost() + ":" + definition.remotePort();
        for (var client : processes) {
            var args = client.arguments();
            if (!target.equals(destination(args)) || !hasForward(args, route)) continue;
            if (definition.sshAlias() == null && host.port() != null && !Integer.toString(host.port()).equals(option(args, "-p"))) continue;
            if (client.ports().contains(definition.localPort())) return true;
            long masterPid = client.masterPid();
            // Multiplexed clients do not own the listener; their SSH master does.
            if (masterPid > 0 && processes.stream().anyMatch(master -> master.pid() == masterPid && master.ports().contains(definition.localPort()))) return true;
        }
        return false;
    }

    private static boolean hasForward(List<String> args, String route) {
        String shortRoute = route.substring("127.0.0.1:".length());
        for (int i = 0; i < args.size(); i++) {
            String value = args.get(i);
            if (value.equals("--")) break;
            if (value.equals("-L") && i + 1 < args.size()) value = args.get(++i);
            else if (value.startsWith("-L")) value = value.substring(2);
            else continue;
            if (value.equals(route) || value.equals(shortRoute)) return true;
        }
        return false;
    }
    private static String destination(List<String> args) {
        Set<String> takesValue = Set.of("-L", "-R", "-D", "-o", "-p", "-l", "-i", "-F", "-S", "-J", "-b", "-c", "-m", "-E", "-W", "-w", "-O", "-e", "-Q");
        for (int i = 0; i < args.size(); i++) {
            String value = args.get(i);
            if (value.equals("--")) return i + 1 < args.size() ? args.get(i + 1) : null;
            if (takesValue.contains(value)) { i++; continue; }
            if (!value.startsWith("-")) return value;
        }
        return null;
    }
    private static String option(List<String> args, String flag) {
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("--")) break;
            if (arg.equals(flag) && i + 1 < args.size()) return args.get(i + 1);
            if (arg.startsWith(flag) && arg.length() > flag.length()) return arg.substring(flag.length());
        }
        return null;
    }
    private static String controlPath(List<String> args) {
        String socket = option(args, "-S"); if (socket != null) return socket;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("--")) break;
            if (arg.equals("-o") && i + 1 < args.size()) arg = args.get(++i);
            else if (arg.startsWith("-o")) arg = arg.substring(2);
            else continue;
            if (arg.startsWith("ControlPath=")) return arg.substring("ControlPath=".length());
        }
        return null;
    }
    private static long masterPid(List<String> args) {
        String control = controlPath(args), target = destination(args);
        if (control == null || target == null) return -1;
        var command = new ArrayList<>(List.of("/usr/bin/ssh", "-S", control, "-O", "check", "-o", "BatchMode=yes"));
        for (String flag : List.of("-p", "-l")) { String value = option(args, flag); if (value != null) command.addAll(List.of(flag, value)); }
        command.addAll(List.of("--", target));
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(1, TimeUnit.SECONDS)) return -1;
            String output = new String(process.getInputStream().readNBytes(4096), StandardCharsets.UTF_8);
            var match = java.util.regex.Pattern.compile("Master running \\(pid=([0-9]+)\\)").matcher(output);
            return process.exitValue() == 0 && match.find() ? Long.parseLong(match.group(1)) : -1;
        } catch (IOException | NumberFormatException ignored) { return -1; }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return -1; }
        finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
    private static Map<Long, Set<Integer>> listeners() {
        Process process = null;
        try {
            process = new ProcessBuilder("/usr/sbin/lsof", "-nP", "-a", "-c", "ssh", "-iTCP", "-sTCP:LISTEN", "-Fpn").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            // Only SSH listeners are requested; draining concurrently bounds the probe duration.
            Process running = process;
            var output = new java.io.ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> { try (var in = running.getInputStream()) { byte[] buffer = new byte[4096]; int count; while ((count = in.read(buffer)) >= 0) if (output.size() < 1_000_000) output.write(buffer, 0, Math.min(count, 1_000_000 - output.size())); } catch (IOException ignored) {} });
            if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); return null; }
            reader.join(1000);
            if (reader.isAlive() || process.exitValue() > 1) return null;
            Map<Long, Set<Integer>> ports = new HashMap<>(); long pid = -1;
            for (String line : output.toString(StandardCharsets.UTF_8).split("\n")) {
                if (line.startsWith("p")) pid = Long.parseLong(line.substring(1));
                else if (pid > 0 && line.startsWith("n127.0.0.1:")) ports.computeIfAbsent(pid, key -> new HashSet<>()).add(Integer.parseInt(line.substring("n127.0.0.1:".length())));
            }
            return ports;
        } catch (IOException | NumberFormatException ignored) { return null; }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return null; }
        finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
}

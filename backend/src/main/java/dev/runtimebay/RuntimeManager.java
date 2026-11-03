package dev.runtimebay;

import dev.runtimebay.Models.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

final class RuntimeManager implements AutoCloseable {
    private final ProjectStore store;
    private final WorkflowStore workflows;
    private final Path launcher;
    private final PortInspector ports;
    private final ConcurrentMap<String, Slot> slots = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean closing;
    static final int LOG_LIMIT = 1000;
    private static final int LINE_LIMIT = 4096;

    private static final class Slot {
        final ReentrantLock operation = new ReentrantLock();
        final ArrayDeque<Log> logs = new ArrayDeque<>();
        long sequence;
        int logCharacters;
        Process process;
        Long group;
        String state = "idle";
        Integer exitCode;
        String startedAt;
        boolean stopping;
        CompletableFuture<Void> completion;
        synchronized void log(String stream, String message) {
            logs.addLast(new Log(++sequence, Instant.now().toString(), stream, message));
            logCharacters += message.length();
            while (logs.size() > LOG_LIMIT || logCharacters > 524288) logCharacters -= logs.removeFirst().message().length();
        }
    }

    RuntimeManager(ProjectStore store, Path launcher) throws IOException {
        this.store = store; this.launcher = launcher; this.ports = new PortInspector(launcher);
        this.workflows = new WorkflowStore(store);
    }
    WorkflowStore workflows() { return workflows; }
    WorkflowModels.Workflow saveWorkflow(String id, WorkflowModels.Workflow value) throws IOException {
        // Target changes cannot race a running/starting service or its definition edit.
        var locked = store.project(id).services().stream().map(Service::id).sorted().map(this::slot).toList();
        for (Slot slot : locked) slot.operation.lock();
        try {
            for (Slot slot : locked) synchronized (slot) { requireStopped(slot); }
            return workflows.save(id, value);
        } finally { for (int i = locked.size() - 1; i >= 0; i--) locked.get(i).operation.unlock(); }
    }
    <T> T withStoppedDefinitions(String projectId,List<String> futureIds,java.util.concurrent.Callable<T> action)throws Exception {
        var ids=new TreeSet<String>(futureIds);for(var service:store.project(projectId).services())ids.add(service.id());
        var locked=ids.stream().map(this::slot).toList();for(var slot:locked)slot.operation.lock();
        try{if(closing)throw new ApiException(409,"Runtime Bay is shutting down");for(var slot:locked)synchronized(slot){requireStopped(slot);}return action.call();}
        finally{for(int i=locked.size()-1;i>=0;i--)locked.get(i).operation.unlock();}
    }
    record ProcessState(String state,Integer exitCode,String startedAt){}
    ProcessState processState(String projectId,String id){store.service(projectId,id);var slot=slot(id);synchronized(slot){return new ProcessState(slot.state,slot.exitCode,slot.startedAt);}}
    void awaitProcess(String projectId,String id,int seconds)throws Exception{store.service(projectId,id);var slot=slot(id);java.util.concurrent.CompletableFuture<Void> done;synchronized(slot){done=slot.completion;}if(done==null)throw new ApiException(409,"Process did not start");done.get(seconds,java.util.concurrent.TimeUnit.SECONDS);}
    private Slot slot(String id) { return slots.computeIfAbsent(id, ignored -> new Slot()); }

    List<ProjectView> projects() {
        return store.list().stream().map(p -> new ProjectView(p.id(), p.name(), p.path(),
            p.services().stream().map(this::view).toList())).toList();
    }
    private ServiceView view(Service service) {
        Slot slot = slot(service.id());
        String state; Long pid; Integer code; String time;
        synchronized (slot) { state = slot.state; pid = slot.group; code = slot.exitCode; time = slot.startedAt; }
        return new ServiceView(service.id(), service.name(), service.command(), service.directory(), service.port(), service.dependsOnResourceId(),
            state, pid, code, time, ports.inspect(service.port(), pid));
    }
    // Saving a definition and launching it use the same lock so an edit cannot race a start.
    Service save(String projectId, String id, String name, String command, String directory, Integer port) throws IOException {
        return save(projectId, id, name, command, directory, port, null);
    }
    Service save(String projectId, String id, String name, String command, String directory, Integer port, String dependsOnResourceId) throws IOException {
        if (id == null) return store.saveService(projectId, null, name, command, directory, port, dependsOnResourceId);
        Slot slot = slot(id);
        slot.operation.lock();
        try { synchronized (slot) {
                requireStopped(slot);
                return store.saveService(projectId, id, name, command, directory, port, dependsOnResourceId);
            }
        } finally { slot.operation.unlock(); }
    }
    Project reorder(String projectId, List<String> ids) throws IOException { return store.reorderServices(projectId, ids); }
    void remove(String projectId, String id) throws IOException {
        Slot slot = slot(id);
        slot.operation.lock();
        try { synchronized (slot) { requireStopped(slot); store.removeService(projectId, id); slots.remove(id); } }
        finally { slot.operation.unlock(); }
    }
    private void requireStopped(Slot slot) {
        if (slot.completion != null && !slot.completion.isDone()) throw new ApiException(409, "Stop the service before editing it");
    }
    void start(String projectId, String id) throws IOException {
        Slot slot = slot(id);
        slot.operation.lock();
        try { startUnlocked(projectId, id, Map.of()); } finally { slot.operation.unlock(); }
    }
    void startWithEnvironment(String projectId,String id,Map<String,String> environment)throws IOException {
        var slot=slot(id);slot.operation.lock();try{startUnlocked(projectId,id,environment);}finally{slot.operation.unlock();}
    }
    private void startUnlocked(String projectId, String id, Map<String,String> environment) throws IOException {
        Slot slot = slot(id);
        synchronized (slot) {
            if (closing) throw new ApiException(409, "Runtime Bay is shutting down");
            requireStopped(slot);
            Project project = store.project(projectId);
            Service service = store.service(projectId, id);
            workflows.requireLocalService(projectId, id);
            Path cwd = ProjectStore.resolveDirectory(project, service.directory());
            ports.invalidate(service.port());
            slot.state = "starting";
            slot.exitCode = null;
            slot.stopping = false;
            slot.log("system", "Starting · " + service.command());
            Process process = null;
            try {
                ProcessBuilder builder = new ProcessBuilder(launcher.toString(), service.command()).directory(cwd.toFile());
                // Runtime Bay's session token belongs to its own API, never to a managed service.
                builder.environment().keySet().removeIf(k -> k.startsWith("RUNTIMEBAY_"));
                for(var entry:environment.entrySet()){if(entry.getKey().startsWith("RUNTIMEBAY_"))throw new ApiException(400,"Reserved runtime environment variable");builder.environment().put(entry.getKey(),entry.getValue());}
                process = builder.start();
                Process launched = process;
                BufferedReader stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                Future<String> handshake = workers.submit(stdout::readLine);
                String first;
                try { first = handshake.get(3, TimeUnit.SECONDS); }
                catch (Exception e) { handshake.cancel(true); throw new IOException("Service supervisor did not become ready", e); }
                if (first == null || !first.matches("RUNTIME_BAY_CHILD:[1-9][0-9]*")) throw new IOException("Invalid service supervisor handshake");
                slot.group = Long.parseLong(first.substring("RUNTIME_BAY_CHILD:".length()));
                slot.process = process;
                slot.state = "running";
                slot.startedAt = Instant.now().toString();
                CompletableFuture<Void> out = CompletableFuture.runAsync(() -> consume(stdout, slot, "stdout"), workers);
                CompletableFuture<Void> err = CompletableFuture.runAsync(() -> consume(new BufferedReader(
                    new InputStreamReader(launched.getErrorStream(), StandardCharsets.UTF_8)), slot, "stderr"), workers);
                slot.completion = CompletableFuture.runAsync(() -> {
                    int code;
                    try { code = launched.waitFor(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    try { launched.getOutputStream().close(); } catch (IOException ignored) {}
                    CompletableFuture.allOf(out, err).join();
                    synchronized (slot) {
                        if (slot.process != launched) return;
                        slot.exitCode = code;
                        slot.state = slot.stopping ? "stopped" : code == 0 ? "exited" : "failed";
                        slot.group = null;
                        slot.log("system", (slot.stopping ? "Stopped" : "Exited") + " · exit code " + code);
                    }
                }, workers);
            } catch (IOException | RuntimeException e) {
                if (process != null) {
                    process.getOutputStream().close();
                    process.destroy();
                }
                slot.state = "failed";
                slot.group = null;
                slot.log("system", "Could not start: " + e.getMessage());
                throw e;
            }
        }
    }
    void stop(String projectId, String id) throws IOException {
        Slot slot = slot(id);
        slot.operation.lock();
        try { Service service = store.service(projectId, id); stop(slot); ports.invalidate(service.port()); }
        finally { slot.operation.unlock(); }
    }
    private void stop(Slot slot) throws IOException {
        Process process; Long group; CompletableFuture<Void> completion;
        synchronized (slot) {
            completion = slot.completion;
            if (completion == null || completion.isDone()) return;
            process = slot.process; group = slot.group;
            slot.stopping = true;
            slot.state = "stopping";
            slot.log("system", "Stopping service and its child processes…");
            process.destroy();
        }
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                if (group != null) signalGroup(group);
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            }
            completion.get(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Stop interrupted", e); }
        catch (ExecutionException | TimeoutException e) { throw new IOException("Could not finish stopping this service", e); }
    }
    void restart(String projectId, String id) throws IOException {
        Slot slot = slot(id);
        slot.operation.lock();
        try { stop(projectId, id); start(projectId, id); } finally { slot.operation.unlock(); }
    }
    private void signalGroup(long group) throws IOException, InterruptedException {
        Process signal = new ProcessBuilder(launcher.toString(), "--signal-group", "" + group, "KILL")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try { if (!signal.waitFor(1, TimeUnit.SECONDS)) throw new IOException("Process signal timed out"); }
        finally { if (signal.isAlive()) signal.destroyForcibly(); }
    }
    Logs logs(String projectId, String id, long after) {
        store.service(projectId, id);
        Slot slot = slot(id);
        synchronized (slot) {
            boolean truncated = !slot.logs.isEmpty() && after < slot.logs.getFirst().sequence() - 1;
            return new Logs(slot.logs.stream().filter(l -> l.sequence() > after).toList(), slot.sequence, truncated);
        }
    }
    private void consume(Reader reader, Slot slot, String stream) {
        try (reader) {
            char[] buffer = new char[2048];
            StringBuilder line = new StringBuilder();
            int count;
            while ((count = reader.read(buffer)) != -1) {
                for (int i = 0; i < count; i++) {
                    char c = buffer[i];
                    if (c == '\n') { slot.log(stream, line.toString()); line.setLength(0); }
                    else if (c != '\r') {
                        line.append(c);
                        if (line.length() >= LINE_LIMIT) { slot.log(stream, line.toString()); line.setLength(0); }
                    }
                }
            }
            if (!line.isEmpty()) slot.log(stream, line.toString());
        } catch (IOException e) { slot.log("system", "Log stream closed"); }
    }
    @Override public void close() {
        closing = true;
        var tasks = slots.values().stream().map(slot -> CompletableFuture.runAsync(() -> {
            try { stop(slot); } catch (IOException e) { System.err.println("Shutdown: " + e.getMessage()); }
        }, workers)).toList();
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
        workers.shutdown();
    }
}

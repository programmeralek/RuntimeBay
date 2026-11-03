package dev.runtimebay;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Persistent references to existing Docker containers; linking never changes Docker workloads. */
final class WorkflowContainers {
    record Link(String id, String projectId, String context, String endpoint, String containerId, String name, String image) {}
    record Registry(int version, List<Link> links) {}
    record Status(Link link, String state, String status, String ports, String error) {}
    private final ProjectStore projects;
    private final DockerService docker;
    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private List<Link> links;
    WorkflowContainers(ProjectStore projects, DockerService docker) throws IOException {
        this.projects = projects; this.docker = docker; file = projects.directory().resolve("workflow-containers.json");
        reload();
    }
    synchronized void reload() throws IOException {
        if (Files.exists(file)) try {
            Registry registry = gson.fromJson(Files.readString(file), Registry.class);
            if (registry == null || registry.version() != 1 || registry.links() == null) throw new IllegalArgumentException("Invalid workflow containers registry");
            for (Link link : registry.links()) {
                if (link == null || link.id() == null || link.projectId() == null || link.context() == null || link.endpoint() == null || link.name() == null || link.image() == null) throw new IllegalArgumentException("Invalid container link");
                DockerService.containerId(link.containerId());
            }
            links = List.copyOf(registry.links());
        } catch (RuntimeException error) { throw new IOException("Cannot read workflow containers. Existing data has been preserved.", error); }
        else links=List.of();
    }
    synchronized List<Link> all() { return List.copyOf(links); }
    synchronized List<Link> list(String projectId) { projects.project(projectId); return links.stream().filter(link -> link.projectId().equals(projectId)).toList(); }
    Link add(String projectId, String context, String endpoint, String containerId) throws IOException {
        projects.project(projectId); DockerService.containerId(containerId);
        if (context == null || context.isBlank() || endpoint == null || endpoint.isBlank()) throw new ApiException(400, "Choose a Docker host");
        DockerModels.Overview overview = docker.overview(context, endpoint);
        DockerModels.Container container = overview.containers().stream().filter(value -> value.id().equals(containerId)).findFirst().orElseThrow(() -> new ApiException(404, "This container no longer exists on the selected host"));
        return record(projectId, context, overview.environment().endpoint(), container.id(), container.name(), container.image());
    }
    synchronized Link record(String projectId, String context, String endpoint, String containerId, String name, String image) throws IOException {
        projects.project(projectId); DockerService.containerId(containerId);
        Link existing = links.stream().filter(link -> link.projectId().equals(projectId) && link.context().equals(context) && link.endpoint().equals(endpoint) && link.containerId().equals(containerId)).findFirst().orElse(null);
        if (existing != null) return existing;
        Link link = new Link(UUID.randomUUID().toString(), projectId, context, endpoint, containerId, name, image);
        var updated = new ArrayList<>(links); updated.add(link); commit(updated); return link;
    }
    synchronized void remove(String projectId, String id) throws IOException {
        if (projects.project(projectId).services().stream().anyMatch(service -> id.equals(service.dependsOnResourceId())))
            throw new ApiException(409, "A workflow service depends on this container. Update the service dependency before unlinking it.");
        Link link = find(projectId, id); var updated = new ArrayList<>(links); updated.remove(link); commit(updated);
    }
    private synchronized Link find(String projectId, String id) { return list(projectId).stream().filter(link -> link.id().equals(id)).findFirst().orElseThrow(() -> new ApiException(404, "Workflow container not found")); }
    List<Status> status(String projectId) { return status(list(projectId)); }
    List<Status> status(List<Link> current) {
         Map<List<String>, DockerModels.Overview> hosts = new HashMap<>(); Map<List<String>, String> errors = new HashMap<>();
        for (Link link : current) {
            var key = List.of(link.context(), link.endpoint());
            if (!hosts.containsKey(key) && !errors.containsKey(key)) try { hosts.put(key, docker.overview(link.context(), link.endpoint())); }
            catch (ApiException error) { errors.put(key, error.getMessage()); }
        }
        return current.stream().map(link -> {
            var key = List.of(link.context(), link.endpoint());
            if (errors.containsKey(key)) return new Status(link, "unavailable", "Host unavailable", "", errors.get(key));
            var container = hosts.get(key).containers().stream().filter(value -> value.id().equals(link.containerId())).findFirst().orElse(null);
            if (container == null) return new Status(link, "missing", "Container no longer exists", "", "Link the replacement container, then select it for this resource in Environments. Names never retarget a saved container ID automatically.");
            return new Status(link, container.state(), container.status(), container.ports(), null);
        }).toList();
    }
    void action(String projectId, String id, String action) {
        if (!List.of("start", "stop", "restart", "resume").contains(action)) throw new ApiException(404, "Workflow container action not found");
        action(find(projectId, id), action);
    }
    void action(Link link, String action) {
        if (!List.of("start", "stop", "restart", "resume").contains(action)) throw new ApiException(404, "Workflow container action not found");
        docker.action(link.context(), link.endpoint(), link.containerId(), action.equals("resume") ? "unpause" : action);
    }
    private void commit(List<Link> updated) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), "workflow-containers-", ".tmp");
        try {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            Files.writeString(temporary, gson.toJson(new Registry(1, updated)));
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); links = List.copyOf(updated);
        } finally { Files.deleteIfExists(temporary); }
    }
}

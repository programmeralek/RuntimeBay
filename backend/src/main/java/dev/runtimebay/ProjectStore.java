package dev.runtimebay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.runtimebay.Models.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

final class ProjectStore {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private List<Project> projects;

    ProjectStore(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        file = directory.resolve("projects.json");
        DefinitionTransaction.recover(directory);
        reload();
    }
    synchronized void reload() throws IOException {
        if (Files.exists(file)) {
            try {
                Registry registry = gson.fromJson(Files.readString(file), Registry.class);
                if (registry == null || registry.version() != 1 || registry.projects() == null)
                    throw new IllegalArgumentException("Unsupported project registry");
                var loaded = new ArrayList<>(registry.projects());
                for (Project project : loaded) {
                    if (project.id() == null || project.path() == null || project.services() == null)
                        throw new IllegalArgumentException("Invalid project registry");
                }
                projects=loaded;
            } catch (RuntimeException e) {
                throw new IOException("Cannot read project registry. Existing data has been preserved.", e);
            }
        } else projects = new ArrayList<>();
    }

    Path directory() { return file.getParent(); }
    synchronized List<Project> list() { return List.copyOf(projects); }
    synchronized Project project(String id) {
        return projects.stream().filter(p -> p.id().equals(id)).findFirst()
            .orElseThrow(() -> new ApiException(404, "Project not found"));
    }
    synchronized Service service(String projectId, String id) {
        return project(projectId).services().stream().filter(s -> s.id().equals(id)).findFirst()
            .orElseThrow(() -> new ApiException(404, "Service not found"));
    }
    synchronized Project addProject(String name, String directory) throws IOException {
        Path root = realDirectory(directory);
        for (Project existing : projects) if (existing.path().equals(root.toString())) return existing;
        String label = name == null || name.isBlank() ? (root.getFileName() == null ? "Root" : root.getFileName().toString()) : text(name, "Project name", 80);
        Project project = new Project(UUID.randomUUID().toString(), label, root.toString(), List.of());
        var updated = new ArrayList<>(projects);
        updated.add(project);
        commit(updated);
        return project;
    }
    synchronized Service saveService(String projectId, String id, String name, String command,
                                     String directory, Integer port) throws IOException {
        return saveService(projectId, id, name, command, directory, port, null);
    }
    synchronized Service saveService(String projectId, String id, String name, String command,
                                     String directory, Integer port, String dependsOnResourceId) throws IOException {
        Project project = project(projectId);
        if (port != null && (port < 1 || port > 65535)) throw new ApiException(400, "Port must be between 1 and 65535");
        String cwd = directory == null || directory.isBlank() ? "." : directory;
        resolveDirectory(project, cwd);
        String serviceId = id == null ? UUID.randomUUID().toString() : id;
        validateDependency(project, serviceId, dependsOnResourceId);
        Service service = new Service(serviceId,
            text(name, "Service name", 80), text(command, "Command", 4096), cwd, port, cleanDependency(dependsOnResourceId));
        var services = new ArrayList<>(project.services());
        if (id != null) {
            Service previous = service(projectId, id);
            int index = services.indexOf(previous);
            services.set(index, service);
        } else {
            services.add(service);
        }
        if (services.stream().anyMatch(s -> !s.id().equals(service.id()) && s.name().equalsIgnoreCase(service.name())))
            throw new ApiException(409, "A service with this name already exists");
        replace(project, services);
        return service;
    }
    synchronized Project reorderServices(String projectId, List<String> ids) throws IOException {
        Project project = project(projectId);
        if (ids == null || ids.size() != project.services().size() || new HashSet<>(ids).size() != ids.size()
            || !new HashSet<>(ids).equals(project.services().stream().map(Service::id).collect(java.util.stream.Collectors.toSet())))
            throw new ApiException(400, "Service order must include every workflow service exactly once");
        Map<String, Service> byId = new HashMap<>(); project.services().forEach(service -> byId.put(service.id(), service));
        List<Service> ordered = ids.stream().map(byId::get).toList(); replace(project, ordered); return project(projectId);
    }
    synchronized void removeService(String projectId, String id) throws IOException {
        Project project = project(projectId);
        service(projectId, id);
        if (project.services().stream().anyMatch(item -> id.equals(item.dependsOnResourceId())))
            throw new ApiException(409, "Another service depends on this one. Update its dependency before removing it.");
        replace(project, project.services().stream().filter(s -> !s.id().equals(id)).toList());
    }
    private void validateDependency(Project project, String serviceId, String dependency) throws IOException {
        if (dependency == null || dependency.isBlank()) return;
        if (dependency.equals(serviceId)) throw new ApiException(400, "A service cannot depend on itself");
        boolean serviceResource = project.services().stream().anyMatch(item -> item.id().equals(dependency));
        boolean containerResource = false;
        Path linksFile = file.getParent().resolve("workflow-containers.json");
        if (Files.exists(linksFile)) try {
            WorkflowContainers.Registry registry = gson.fromJson(Files.readString(linksFile), WorkflowContainers.Registry.class);
            containerResource = registry != null && registry.links() != null && registry.links().stream()
                .anyMatch(link -> link.projectId().equals(project.id()) && link.id().equals(dependency));
        } catch (RuntimeException error) { throw new IOException("Cannot validate service dependencies because saved workflow containers are invalid.", error); }
        if (!serviceResource && !containerResource) throw new ApiException(400, "Choose a service or linked container from this workflow");
        if (!serviceResource) return;
        Map<String, String> dependencies = new HashMap<>();
        for (Service item : project.services()) if (!item.id().equals(serviceId) && item.dependsOnResourceId() != null) dependencies.put(item.id(), item.dependsOnResourceId());
        dependencies.put(serviceId, dependency);
        Set<String> visited = new HashSet<>(); String cursor = dependency;
        while (cursor != null && visited.add(cursor)) cursor = dependencies.get(cursor);
        if (cursor != null) throw new ApiException(400, "Service dependencies cannot contain a cycle");
    }
    private static String cleanDependency(String dependency) { return dependency == null || dependency.isBlank() ? null : dependency.trim(); }
    synchronized void importServices(String projectId, String name, List<Service> services) throws IOException {
        Project original=project(projectId);Set<String> ids=new HashSet<>(),names=new HashSet<>();
        for(var service:services) {
            if(service==null||service.id()==null||!ids.add(service.id())||!names.add(service.name().toLowerCase(Locale.ROOT)))throw new ApiException(400,"Duplicate imported service identity or name");
            text(service.name(),"Service name",80);text(service.command(),"Command",4096);resolveDirectory(original,service.directory());
            if(service.port()!=null&&(service.port()<1||service.port()>65535))throw new ApiException(400,"Invalid service port");
        }
        var next=new ArrayList<>(projects);next.set(next.indexOf(original),new Project(original.id(),text(name,"Project name",80),original.path(),List.copyOf(services)));commit(next);
    }
    private void replace(Project original, List<Service> services) throws IOException {
        var updated = new ArrayList<>(projects);
        updated.set(updated.indexOf(original), new Project(original.id(), original.name(), original.path(), List.copyOf(services)));
        commit(updated);
    }
    private void commit(List<Project> updated) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), "projects-", ".tmp");
        try {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            Files.writeString(temporary, gson.toJson(new Registry(1, updated)), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            projects = updated;
        } finally { Files.deleteIfExists(temporary); }
    }
    static Path resolveDirectory(Project project, String relative) throws IOException {
        if (Path.of(relative).isAbsolute()) throw new ApiException(400, "Service directory must be relative to the project");
        Path root = realDirectory(project.path());
        Path directory = realDirectory(root.resolve(relative).toString());
        if (!directory.startsWith(root)) throw new ApiException(400, "Service directory must stay inside the project");
        return directory;
    }
    private static Path realDirectory(String value) throws IOException {
        if (value == null || value.isBlank()) throw new ApiException(400, "Choose a project directory");
        Path path = Path.of(value);
        if (!path.isAbsolute()) throw new ApiException(400, "Project path must be absolute");
        try {
            path = path.toRealPath();
            if (!Files.isDirectory(path)) throw new ApiException(400, "Path must be a directory");
            return path;
        } catch (NoSuchFileException e) { throw new ApiException(400, "Directory does not exist"); }
    }
    private static String text(String value, String label, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf('\0') >= 0)
            throw new ApiException(400, label + " is required (maximum " + max + " characters)");
        return value.trim();
    }
}

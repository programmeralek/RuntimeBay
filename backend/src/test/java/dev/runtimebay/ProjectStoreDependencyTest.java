package dev.runtimebay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProjectStoreDependencyTest {
    @TempDir Path temporary;

    @Test void serviceDependenciesAreSavedValidatedAndPreventCyclesOrDanglingReferences() throws Exception {
        var store = new ProjectStore(temporary.resolve("data"));
        var project = store.addProject("Fixture", temporary.toString());
        var database = store.saveService(project.id(), null, "Database", "sleep 10", ".", null);
        var api = store.saveService(project.id(), null, "API", "sleep 10", ".", null, database.id());
        assertEquals(database.id(), api.dependsOnResourceId());
        assertThrows(ApiException.class, () -> store.saveService(project.id(), database.id(), "Database", "sleep 10", ".", null, api.id()));
        assertThrows(ApiException.class, () -> store.removeService(project.id(), database.id()));

        var reordered = store.reorderServices(project.id(), List.of(api.id(), database.id()));
        assertEquals(List.of(api.id(), database.id()), reordered.services().stream().map(Models.Service::id).toList());
        assertEquals(api, new ProjectStore(store.directory()).service(project.id(), api.id()));
        store.saveService(project.id(), api.id(), "API", "sleep 10", ".", null, "");
        store.removeService(project.id(), database.id());
    }

    @Test void linkedContainerCanBeAServiceDependencyAndCannotBeUnlinkedWhileReferenced() throws Exception {
        var store = new ProjectStore(temporary.resolve("data"));
        var project = store.addProject("Fixture", temporary.toString());
        var containers = new WorkflowContainers(store, null);
        var link = containers.record(project.id(), "local", "unix:///docker.sock", DockerServiceTest.ID, "Redis", "redis:7");
        var service = store.saveService(project.id(), null, "API", "sleep 10", ".", null, link.id());
        assertEquals(link.id(), service.dependsOnResourceId());
        assertThrows(ApiException.class, () -> containers.remove(project.id(), link.id()));
    }
}

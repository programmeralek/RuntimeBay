package dev.runtimebay;

import java.util.List;

final class Models {
    record Service(String id, String name, String command, String directory, Integer port, String dependsOnResourceId) {
        Service(String id, String name, String command, String directory, Integer port) { this(id, name, command, directory, port, null); }
    }
    record Project(String id, String name, String path, List<Service> services) {}
    record Registry(int version, List<Project> projects) {}
    record Log(long sequence, String time, String stream, String message) {}
    record Owner(long pid, String command, boolean managed) {}
    record Port(int number, String status, List<Owner> owners) {}
    record ServiceView(String id, String name, String command, String directory, Integer port, String dependsOnResourceId,
                       String state, Long pid, Integer exitCode, String startedAt, Port portInfo) {}
    record ProjectView(String id, String name, String path, List<ServiceView> services) {}
    record Logs(List<Log> entries, long next, boolean truncated) {}
    private Models() {}
}

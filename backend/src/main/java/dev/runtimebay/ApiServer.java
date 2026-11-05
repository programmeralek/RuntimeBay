package dev.runtimebay;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

final class ApiServer implements AutoCloseable {
    private final Object definitionsLock = new Object();
    private final HttpServer server;
    private final ProjectStore store;
    private final RuntimeManager runtimes;
    private final DockerService docker;
    private final AppearanceStore appearance;
    private final TerminalManager terminals;
    private final WorkflowContainers workflowContainers;
    private final WorkflowAttachments attachments;
    private final WorkflowManifests manifests;
    private final WorkflowPlans plans;
    private final DatabaseWorkspace databases;
    private final GitWorkspace git;
    private final MachineHosts machineHosts;
    private final Path hostSshControls;
    private final HostPasswords hostPasswords;
    private final SshTunnels tunnels;
    private final Path webRoot;
    private final String token;
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
    ApiServer(ProjectStore store, RuntimeManager runtimes, Path webRoot, String token) throws IOException {
        this(store, runtimes, webRoot, token, new DockerService(null));
    }
    ApiServer(ProjectStore store, RuntimeManager runtimes, Path webRoot, String token, DockerService docker) throws IOException {
        if (token == null || token.length() < 32) throw new IllegalArgumentException("A strong session token is required");
        this.store = store; this.runtimes = runtimes; this.webRoot = webRoot.toRealPath(); this.token = token; this.docker = docker;
        appearance = new AppearanceStore(store.directory());
        workflowContainers = new WorkflowContainers(store, docker);
        attachments = new WorkflowAttachments(store, runtimes.workflows());
        manifests = new WorkflowManifests(store, runtimes, workflowContainers, attachments);
        hostSshControls = Files.createTempDirectory(Path.of("/tmp"), "rb-");
        Files.setPosixFilePermissions(hostSshControls, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        Path supervisor = Path.of(System.getenv().getOrDefault("RUNTIMEBAY_LAUNCHER", "../.build/runtimebay-launcher"));
        hostPasswords = new HostPasswords(hostSshControls, supervisor.resolveSibling("runtimebay-askpass"));
        machineHosts = new MachineHosts(new HostRegistry(store.directory()), new HostTools(hostSshControls, hostPasswords));
        tunnels = new SshTunnels(store.directory(), machineHosts, hostPasswords, hostSshControls);
        plans = new WorkflowPlans(store, runtimes, manifests, machineHosts::view);
        docker.configureHosts(machineHosts::view,hostPasswords,hostSshControls);
        terminals = new TerminalManager(store, supervisor.resolveSibling("runtimebay-terminal"), hostPasswords);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 32);
        databases = new DatabaseWorkspace(store, new CloudBeaverRuntime(docker), "http://127.0.0.1:" + port());
        git = new GitWorkspace(store);
        server.setExecutor(requests);
        server.createContext("/", this::handle);
    }
    void start() { server.start(); }
    int port() { return server.getAddress().getPort(); }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            headers(exchange);
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (!("127.0.0.1:" + port()).equals(host)) throw new ApiException(403, "Invalid host");
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/database/")) { databases.proxy(exchange); return; }
            if (path.startsWith("/api/")) {
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                if (auth == null || !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8), auth.getBytes(StandardCharsets.UTF_8)))
                    throw new ApiException(401, "Open this workspace from Runtime Bay to connect");
                String origin = exchange.getRequestHeaders().getFirst("Origin");
                if (origin != null && !("http://127.0.0.1:" + port()).equals(origin)) throw new ApiException(403, "Invalid origin");
                if (List.of("POST", "PUT", "DELETE").contains(exchange.getRequestMethod()) && !path.startsWith("/api/terminals")) {
                    synchronized (definitionsLock) { manifests.requireHealthy(); route(exchange, path); }
                } else route(exchange, path);
            } else assets(exchange, path);
        } catch (ApiException e) { json(exchange, e.status, Map.of("error", e.getMessage())); }
        catch (JsonParseException | IllegalArgumentException e) { json(exchange, 400, Map.of("error", "Invalid request: " + e.getMessage())); }
        catch (Exception e) {
            System.err.println("Request failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            json(exchange, 500, Map.of("error", "Operation failed. Check that the project is accessible and try again."));
        } finally { exchange.close(); }
    }
    private void route(HttpExchange e, String path) throws Exception {
        String method = e.getRequestMethod();
        String[] parts = path.substring(1).split("/");
        if (path.equals("/api/terminals") || path.startsWith("/api/terminals/")) { routeTerminals(e, parts); return; }
        if (path.equals("/api/tunnels") || path.startsWith("/api/tunnels/")) { routeTunnels(e, parts); return; }
        if (path.equals("/api/hosts") || path.startsWith("/api/hosts/")) { routeHosts(e, parts); return; }
        if (parts.length >= 4 && parts[1].equals("projects") && parts[3].equals("git")) { routeGit(e, parts); return; }
        if (path.startsWith("/api/docker/")) { routeDocker(e, parts); return; }
        if (parts.length >= 4 && parts[1].equals("workflows") && parts[3].equals("manifest")) { routeManifest(e, parts); return; }
        if (parts.length >= 4 && parts[1].equals("workflows") && parts[3].equals("attachments")) { routeAttachments(e, parts); return; }
        if (path.equals("/api/workflows") && method.equals("GET")) {
            var workflows = runtimes.workflows();
            var hosts = machineHosts.view();
            var views = new ArrayList<WorkflowModels.View>();
            for (var workflow : workflows.list()) views.add(workflows.view(workflow, hosts));
            json(e, 200, views); return;
        }
        if (parts.length == 4 && parts[1].equals("workflows") && parts[3].equals("targets") && method.equals("GET")) {
            runtimes.workflows().get(parts[2]); json(e, 200, runtimes.workflows().containerOptions()); return;
        }
        if (parts.length == 3 && parts[1].equals("workflows")) {
            var workflows = runtimes.workflows();
            if (method.equals("GET")) { json(e, 200, workflows.view(workflows.get(parts[2]), machineHosts.view())); return; }
            if (method.equals("PUT")) {
                plans.requireIdle(parts[2]);
                String expected = e.getRequestHeaders().getFirst("If-Match");
                if (expected != null && !expected.equals(workflows.revision(workflows.get(parts[2])))) throw new ApiException(409, "This workflow changed while you were editing. Reopen Environments and review its latest bindings.");
                var definition = gson.fromJson(body(e), WorkflowModels.Workflow.class);
                WorkflowStore.validate(definition);
                attachments.protectEnvironments(parts[2], definition);
                json(e, 200, workflows.view(runtimes.saveWorkflow(parts[2], definition), machineHosts.view())); return;
            }
        }
        if (path.equals("/api/preferences")) {
            if (method.equals("GET")) { json(e, 200, appearance.get()); return; }
            if (method.equals("PUT")) { json(e, 200, appearance.save(body(e))); return; }
        }
        if (path.equals("/api/status") && method.equals("GET")) {
            json(e, 200, Map.of("name", "Runtime Bay", "version", "0.5.0", "platform", "macOS")); return;
        }
        if (path.equals("/api/projects")) {
            if (method.equals("GET")) { json(e, 200, runtimes.projects()); return; }
            if (method.equals("POST")) {
                JsonObject body = body(e);
                json(e, 201, store.addProject(string(body, "name"), string(body, "path"))); return;
            }
        }
        if (parts.length == 4 && parts[1].equals("projects") && parts[3].equals("databases")) {
            String project = parts[2];
            if (method.equals("GET")) { var view = databases.status(project); databases.authorizeFrame(e, project); json(e, 200, view); return; }
            if (method.equals("POST")) { var view = databases.start(project, string(body(e), "theme")); databases.authorizeFrame(e, project); json(e, 202, view); return; }
            if (method.equals("DELETE")) { json(e, 200, databases.stop(project)); return; }
        }
        if (parts.length >= 4 && parts[1].equals("projects") && parts[3].equals("containers")) {
            String project = parts[2];
            if (parts.length == 4) {
                if (method.equals("GET")) { json(e, 200, runtimes.workflows().effectiveContainers(project)); return; }
                if (method.equals("POST")) {
                    JsonObject value = body(e);
                    json(e, 201, workflowContainers.add(project, string(value, "context"), string(value, "endpoint"), string(value, "containerId"))); return;
                }
            }
            if (parts.length == 5 && parts[4].equals("links") && method.equals("GET")) { json(e, 200, workflowContainers.list(project)); return; }
            if (parts.length == 5 && parts[4].equals("status") && method.equals("GET")) { json(e, 200, workflowContainers.status(runtimes.workflows().effectiveContainers(project))); return; }
            if (parts.length == 5 && method.equals("DELETE")) { runtimes.workflows().requireUnsharedLink(parts[4]); workflowContainers.remove(project, parts[4]); json(e, 200, Map.of("ok", true)); return; }
            if (parts.length == 6 && method.equals("POST")) { var selection = body(e); runtimes.workflows().requireSelection(project, string(selection, "environmentId"), string(selection, "workflowRevision")); var link = runtimes.workflows().effectiveContainers(project).stream().filter(l -> l.id().equals(parts[4])).findFirst().orElseThrow(() -> new ApiException(404, "Workflow container not found")); workflowContainers.action(link, parts[5]); json(e, 200, Map.of("ok", true)); return; }
        }
        if (parts.length >= 4 && parts[1].equals("projects") && parts[3].equals("services")) {
            String project = parts[2];
            if (!method.equals("GET")) plans.requireIdle(project);
            if (parts.length == 5 && parts[4].equals("order") && method.equals("PUT")) {
                JsonObject body = body(e); JsonArray raw = body.has("ids") && body.get("ids").isJsonArray() ? body.getAsJsonArray("ids") : null;
                if (raw == null) throw new ApiException(400, "Include the ordered service IDs");
                List<String> ids = new ArrayList<>(); for (JsonElement item : raw) { if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw new ApiException(400, "Service IDs must be strings"); ids.add(item.getAsString()); }
                json(e, 200, runtimes.reorder(project, ids)); return;
            }
            if ((parts.length == 4 && method.equals("POST")) || (parts.length == 5 && method.equals("PUT"))) {
                JsonObject body = body(e);
                Integer port = null;
                if (body.has("port") && !body.get("port").isJsonNull()) {
                    JsonElement value = body.get("port");
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !value.getAsString().matches("[0-9]{1,5}"))
                        throw new ApiException(400, "Port must be a whole number between 1 and 65535");
                    port = value.getAsInt();
                }
                json(e, parts.length == 4 ? 201 : 200, runtimes.save(project, parts.length == 5 ? parts[4] : null,
                    string(body, "name"), string(body, "command"), string(body, "directory"), port, string(body, "dependsOnResourceId"))); return;
            }
            if (parts.length == 5 && method.equals("DELETE")) {
                runtimes.remove(project, parts[4]); json(e, 200, Map.of("ok", true)); return;
            }
            if (parts.length == 6) {
                String id = parts[4];
                if (method.equals("GET") && parts[5].equals("logs")) {
                    long after = 0;
                    String query = e.getRequestURI().getRawQuery();
                    if (query != null && query.matches("after=[0-9]+")) after = Long.parseLong(query.substring(6));
                    json(e, 200, runtimes.logs(project, id, after)); return;
                }
                if (method.equals("POST")) {
                    var selection = body(e); runtimes.workflows().requireSelection(project, string(selection, "environmentId"), string(selection, "workflowRevision"));
                    if (!parts[5].equals("stop") && manifests.managed(project, id)) throw new ApiException(409, "This process is YAML managed. Use Run stack or select a process in YAML configuration so dependencies and environment variables are applied.");
                    switch (parts[5]) {
                        case "start" -> runtimes.start(project, id);
                        case "stop" -> runtimes.stop(project, id);
                        case "restart" -> runtimes.restart(project, id);
                        default -> throw new ApiException(404, "Action not found");
                    }
                    json(e, 200, Map.of("ok", true)); return;
                }
            }
        }
        throw new ApiException(404, "Endpoint not found");
    }
    private void routeGit(HttpExchange e, String[] parts) throws IOException {
        String method = e.getRequestMethod(), project = parts[2];
        if (parts.length == 4 && method.equals("GET")) { json(e, 200, git.status(project)); return; }
        if (parts.length == 5 && parts[4].equals("init") && method.equals("POST")) { git.init(project); json(e, 200, git.status(project)); return; }
        if (parts.length == 5 && parts[4].equals("diff") && method.equals("GET")) { json(e, 200, Map.of("diff", git.diff(project, query(e, "path"), "true".equals(query(e, "staged"))))); return; }
        if (parts.length == 5 && parts[4].equals("log") && method.equals("GET")) { json(e, 200, git.log(project)); return; }
        if (parts.length == 6 && parts[4].equals("commit") && method.equals("GET")) { json(e, 200, Map.of("detail", git.show(project, parts[5]))); return; }
        if (parts.length == 5 && parts[4].equals("stage") && method.equals("POST")) { git.stage(project, string(body(e), "path")); json(e, 200, git.status(project)); return; }
        if (parts.length == 5 && parts[4].equals("unstage") && method.equals("POST")) { git.unstage(project, string(body(e), "path")); json(e, 200, git.status(project)); return; }
        if (parts.length == 5 && parts[4].equals("commit") && method.equals("POST")) { git.commit(project, string(body(e), "message")); json(e, 200, git.status(project)); return; }
        if (parts.length == 5 && parts[4].equals("pull") && method.equals("POST")) { git.pull(project); json(e, 200, git.status(project)); return; }
        if (parts.length == 5 && parts[4].equals("push") && method.equals("POST")) { git.push(project); json(e, 200, git.status(project)); return; }
        throw new ApiException(404, "Git endpoint not found");
    }
    private void routeManifest(HttpExchange e,String[] parts)throws Exception {
        String id=parts[2],method=e.getRequestMethod();store.project(id);
        if(parts.length==4&&method.equals("GET")){
            var entry=manifests.get(id);var value=new LinkedHashMap<String,Object>();value.put("configured",entry!=null);value.put("definition",entry==null?null:entry.definition());value.put("managedServiceIds",entry==null?List.of():List.copyOf(entry.services().values()));value.put("processKeys",entry==null?Map.of():entry.services());value.put("mappings",entry==null?Map.of("local","local"):entry.hosts());value.put("hosts",machineHosts.view().hosts().stream().map(h->Map.of("id",h.id(),"name",h.name())).toList());value.put("references",entry==null?Map.of():entry.references());value.put("choices",manifests.choices(machineHosts.view()));value.put("plan",plans.state(id));json(e,200,value);return;
        }
        if(parts.length==5&&method.equals("GET")){
            switch(parts[4]){
                case "export" -> {json(e,200,manifests.export(id));return;}
                case "file" -> {json(e,200,manifests.readFile(id));return;}
                case "plan" -> {json(e,200,plans.preview(id,query(e,"process")));return;}
            }
        }
        if(parts.length==5&&method.equals("POST")){
            var value=body(e);
            if(parts[4].equals("cancel")){json(e,200,plans.cancel(id,string(value,"runId")));return;}
            plans.requireIdle(id);
            if(parts[4].equals("inspect")){var definition=WorkflowYaml.parse(string(value,"yaml"));json(e,200,Map.of("definition",definition,"referenceKinds",WorkflowYaml.referenceKinds(definition)));return;}
            switch(parts[4]){
                case "preview", "apply" -> {
                    Map<String,String> mappings=new LinkedHashMap<>();var raw=value.get("mappings");
                    if(raw!=null){if(!raw.isJsonObject())throw new ApiException(400,"Host mappings must be an object");for(var item:raw.getAsJsonObject().entrySet()){if(!item.getValue().isJsonPrimitive()||!item.getValue().getAsJsonPrimitive().isString())throw new ApiException(400,"Host mappings must use saved machine IDs");mappings.put(item.getKey(),item.getValue().getAsString());}}
                    Map<String,WorkflowAttachments.Source> references=new LinkedHashMap<>();var rawReferences=value.get("references");
                    if(rawReferences!=null){if(!rawReferences.isJsonObject())throw new ApiException(400,"Resource mappings must be an object");for(var item:rawReferences.getAsJsonObject().entrySet())references.put(item.getKey(),gson.fromJson(item.getValue(),WorkflowAttachments.Source.class));}
                    if(parts[4].equals("preview"))json(e,200,manifests.preview(id,string(value,"yaml"),mappings,references,machineHosts.view()));
                    else json(e,200,manifests.apply(id,string(value,"yaml"),mappings,references,string(value,"revision"),machineHosts.view()));return;
                }
                case "save" -> {manifests.saveFile(id,string(value,"yaml"),string(value,"expectedFileHash"));json(e,200,manifests.readFile(id));return;}
                case "run" -> {json(e,202,plans.start(id,string(value,"process"),string(value,"revision")));return;}
            }
        }
        throw new ApiException(404,"YAML configuration endpoint not found");
    }
    private Set<String> unavailableAttachmentHosts() {
        var unavailable = new HashSet<String>();
        for (var host : machineHosts.view().hosts()) if (machineHosts.status(host.id()).state().equals("error")) unavailable.add(host.id());
        return unavailable;
    }
    private void routeAttachments(HttpExchange e, String[] parts) throws IOException {
        String method = e.getRequestMethod(), workflowId = parts[2];
        var workflows = runtimes.workflows(); var workflow = workflows.get(workflowId);
        if (parts.length == 4 && method.equals("GET")) {
            json(e, 200, Map.of("environmentId", workflow.defaultEnvironmentId(), "revision", workflows.revision(workflow), "resources", attachments.view(workflowId, machineHosts.view(), databases, unavailableAttachmentHosts()))); return;
        }
        if (parts.length == 5 && parts[4].equals("catalog") && method.equals("GET")) {
            json(e, 200, new WorkflowAttachments.Catalog(attachments.choices(machineHosts.view(), workflowContainers.all()), store.list().stream().map(p -> new WorkflowAttachments.Workspace(p.id(), p.name())).toList())); return;
        }
        if (parts.length == 6 && parts[4].equals("databases") && method.equals("POST")) {
            body(e); var connections = databases.savedConnections(parts[5]); attachments.refreshDatabase(parts[5], connections);
            json(e, 200, Map.of("count", connections.size())); return;
        }
        if (List.of("POST", "PUT", "DELETE").contains(method)) {
            var value = body(e);
            String environmentId = string(value, "environmentId");
            if (environmentId == null || string(value, "workflowRevision") == null) throw new ApiException(400, "Include the selected environment and workflow revision");
            workflows.requireSelection(workflowId, environmentId, string(value, "workflowRevision"));
            if (parts.length == 4 && method.equals("POST")) {
                var source = gson.fromJson(value.get("source"), WorkflowAttachments.Source.class);
                if (source != null && "container".equals(source.kind())) {
                    var choice = attachments.choices(machineHosts.view(), workflowContainers.all()).stream().filter(c -> c.source().equals(source)).findFirst().orElseThrow(() -> new ApiException(409, "The saved container reference is missing. Refresh the catalog."));
                    var link = workflowContainers.all().stream().filter(l -> l.id().equals(choice.source().sourceId()) && l.projectId().equals(choice.source().projectId())).findFirst().orElseThrow();
                    // Copy the association only; the physical container ID and endpoint stay fixed.
                    json(e, 201, workflowContainers.record(workflowId, link.context(), link.endpoint(), link.containerId(), link.name(), link.image())); return;
                }
                json(e, 201, attachments.attach(workflowId, environmentId, source, machineHosts.view(), workflowContainers.all())); return;
            }
            if (parts.length >= 5) attachments.requireReferenceKey(workflowId, parts[4], string(value, "referenceKey"));
            if (parts.length == 5 && method.equals("DELETE")) { attachments.unlink(workflowId, parts[4]); json(e, 200, Map.of("unlinked", true)); return; }
            if (parts.length == 5 && method.equals("PUT")) { json(e, 200, attachments.rebind(workflowId, parts[4], gson.fromJson(value.get("source"), WorkflowAttachments.Source.class), machineHosts.view(), workflowContainers.all())); return; }
            if (parts.length == 6 && parts[5].equals("resolve") && method.equals("POST")) {
                var resolved = attachments.resolve(workflowId, parts[4], machineHosts.view(), databases, unavailableAttachmentHosts());
                if (resolved.state().equals("missing")) throw new ApiException(409, resolved.detail());
                json(e, 200, resolved); return;
            }
        }
        throw new ApiException(404, "Attachment endpoint not found");
    }
    private void routeHosts(HttpExchange e, String[] parts) throws IOException {
        String method = e.getRequestMethod();
        if (parts.length == 2 && method.equals("GET")) { json(e, 200, machineHosts.view()); return; }
        if ((parts.length == 2 && method.equals("POST")) || (parts.length == 3 && method.equals("PUT"))) {
            JsonObject value = body(e); Integer port = null;
            if (value.has("port") && !value.get("port").isJsonNull()) {
                var number = value.get("port");
                if (!number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber() || !number.getAsString().matches("[0-9]{1,5}")) throw new ApiException(400, "Enter a whole-number SSH port");
                port = number.getAsInt();
            }
            String password = string(value, "password"); HostPasswords.validate(password);
            String id = parts.length == 2 ? null : parts[2];
            var previous = id == null ? null : machineHosts.get(id);
            if (previous != null && (!previous.address().equals(string(value, "address")) || !previous.user().equals(Objects.requireNonNullElse(string(value, "user"), "")) || !Objects.equals(previous.port(), port))) { runtimes.workflows().requireUnusedMachine(id); attachments.protectHost(id); tunnels.requireUnusedHost(id); }
            var saved = machineHosts.save(id, string(value, "name"), string(value, "address"), Objects.requireNonNullElse(string(value, "user"), ""), port);
            if (previous != null && (!previous.address().equals(saved.address()) || !previous.user().equals(saved.user()) || !Objects.equals(previous.port(), saved.port()))) hostPasswords.forget(previous);
            if (value.has("clearPassword") && value.get("clearPassword").getAsBoolean()) hostPasswords.forget(saved);
            hostPasswords.update(saved, password, value.has("rememberPassword") && value.get("rememberPassword").getAsBoolean(), false);
            json(e, parts.length == 2 ? 201 : 200, saved); return;
        }
        if (parts.length == 3 && method.equals("DELETE")) { runtimes.workflows().requireUnusedMachine(parts[2]); attachments.protectHost(parts[2]); tunnels.requireUnusedHost(parts[2]); hostPasswords.forget(machineHosts.get(parts[2])); machineHosts.remove(parts[2]); json(e, 200, Map.of("removed", true)); return; }
        if (parts.length == 4) {
            String id = parts[2];
            if (parts[3].equals("authentication") && method.equals("GET")) { json(e, 200, hostPasswords.authentication(machineHosts.get(id))); return; }
            if (parts[3].equals("default") && method.equals("POST")) { body(e); machineHosts.makeDefault(id); json(e, 200, machineHosts.view()); return; }
            if (parts[3].equals("inventory") && method.equals("GET")) { json(e, 200, machineHosts.status(id)); return; }
            if (parts[3].equals("inventory") && method.equals("POST")) { body(e); hostPasswords.restore(machineHosts.get(id)); json(e, 202, machineHosts.inspect(id)); return; }
        }
        throw new ApiException(404, "Host endpoint not found");
    }
    private void routeTerminals(HttpExchange e, String[] parts) throws IOException {
        String method = e.getRequestMethod();
        if (parts.length == 2) {
            if (method.equals("GET")) { json(e, 200, terminals.list()); return; }
            if (method.equals("POST")) {
                JsonObject value = body(e); String hostId = string(value, "hostId");
                if (value.has("workflowScoped") && value.get("workflowScoped").getAsBoolean()) {
                    if (hostId != null && !hostId.isBlank()) throw new ApiException(400,"Workflow terminals currently run on This Mac");
                    json(e,201,terminals.createWorkflow(string(value,"projectId"))); return;
                }
                var host = machineHosts.get(hostId == null || hostId.isBlank() ? machineHosts.view().defaultHostId() : hostId);
                hostPasswords.restore(host);
                json(e, 201, host.local() ? terminals.create(string(value, "projectId")) : terminals.createRemote(host, hostSshControls)); return;
            }
        }
        if(parts.length==3 && method.equals("PUT")){json(e,200,terminals.rename(parts[2],string(body(e),"name")));return;}
        if(parts.length==4 && parts[3].equals("open") && method.equals("POST")){
            body(e);var tab=terminals.definition(parts[2]);var host=machineHosts.get(tab.hostId());hostPasswords.restore(host);
            json(e,200,terminals.open(tab.id(),host,hostSshControls));return;
        }
        if (parts.length == 3 && method.equals("DELETE")) { terminals.remove(parts[2]); json(e, 200, Map.of("ok", true)); return; }
        if (parts.length == 4) {
            String id = parts[2];
            if (parts[3].equals("output") && method.equals("GET")) {
                String cursor = query(e, "after");
                if (cursor == null || !cursor.matches("[0-9]{1,18}")) throw new ApiException(400, "An output cursor is required");
                try { json(e, 200, terminals.output(id, Long.parseLong(cursor))); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new ApiException(503, "Terminal connection closed"); }
                return;
            }
            if (parts[3].equals("assist") && method.equals("GET")) { json(e, 200, terminals.assist(id)); return; }
            if (parts[3].equals("input") && method.equals("POST")) { terminals.input(id, string(body(e), "data")); json(e, 200, Map.of("ok", true)); return; }
            if (parts[3].equals("resize") && method.equals("POST")) {
                JsonObject value = body(e);
                for (String key : List.of("columns", "rows")) {
                    if (!value.has(key) || !value.get(key).isJsonPrimitive() || !value.getAsJsonPrimitive(key).isNumber() || !value.get(key).getAsString().matches("[0-9]{1,4}")) throw new ApiException(400, "Invalid terminal dimensions");
                }
                terminals.resize(id, value.get("columns").getAsInt(), value.get("rows").getAsInt()); json(e, 200, Map.of("ok", true)); return;
            }
        }
        throw new ApiException(404, "Terminal endpoint not found");
    }
    private void routeTunnels(HttpExchange e, String[] parts) throws IOException {
        String method = e.getRequestMethod();
        if (parts.length == 2 && method.equals("GET")) { json(e, 200, tunnels.list()); return; }
        if (parts.length == 3 && parts[2].equals("acknowledge") && method.equals("POST")) { body(e); tunnels.acknowledgeErrors(); json(e, 200, Map.of("acknowledged", true)); return; }
        if (parts.length == 2 && method.equals("POST")) {
            JsonObject value = body(e);
            json(e, 201, tunnels.save(null, string(value, "name"), string(value, "hostId"), integer(value, "localPort"), string(value, "remoteHost"), integer(value, "remotePort"), string(value, "sshAlias"))); return;
        }
        if (parts.length == 3 && method.equals("PUT")) {
            JsonObject value = body(e);
            json(e, 200, tunnels.save(parts[2], string(value, "name"), string(value, "hostId"), integer(value, "localPort"), string(value, "remoteHost"), integer(value, "remotePort"), string(value, "sshAlias"))); return;
        }
        if (parts.length == 3 && method.equals("DELETE")) { tunnels.remove(parts[2]); json(e, 200, Map.of("removed", true)); return; }
        if (parts.length == 4 && parts[3].equals("start") && method.equals("POST")) { body(e); json(e, 200, tunnels.start(parts[2])); return; }
        if (parts.length == 4 && parts[3].equals("stop") && method.equals("POST")) { body(e); json(e, 200, tunnels.stop(parts[2])); return; }
        throw new ApiException(404, "Tunnel endpoint not found");
    }
    private void routeDocker(HttpExchange e, String[] parts) throws IOException {
        String method = e.getRequestMethod();
        if (parts.length == 3 && parts[2].equals("operations") && method.equals("GET")) { json(e, 200, docker.operations()); return; }
        if (parts.length == 3 && parts[2].equals("environments")) {
            if (method.equals("GET")) { json(e, 200, docker.environments()); return; }
            if (method.equals("POST")) {
                JsonObject value = body(e);
                json(e, 201, docker.addSshEnvironment(string(value, "name"), string(value, "endpoint"))); return;
            }
        }
        if (parts.length >= 4 && parts[2].equals("operations")) {
            if (parts.length == 4 && method.equals("GET")) { json(e, 200, docker.operation(parts[3])); return; }
            if (parts.length == 5 && parts[4].equals("cancel") && method.equals("POST")) {
                body(e); json(e, 200, docker.cancel(parts[3])); return;
            }
        }
        if (parts.length == 4 && parts[2].equals("environments") && method.equals("PUT")) {
            JsonObject value = body(e);
            if (!Objects.equals(string(value, "expectedEndpoint"), string(value, "endpoint"))) runtimes.workflows().requireUnusedDocker(parts[3]);
            json(e, 200, docker.updateEnvironment(parts[3], string(value, "expectedEndpoint"), string(value, "endpoint"), string(value, "description"))); return;
        }
        if (parts.length == 4 && parts[2].equals("environments") && method.equals("DELETE")) {
            JsonObject value = body(e);
            runtimes.workflows().requireUnusedDocker(parts[3]);
            docker.removeEnvironment(parts[3], string(value, "expectedEndpoint"));
            json(e, 200, Map.of("removed", true)); return;
        }
        if (parts.length >= 5 && parts[2].equals("environments")) {
            String context = parts[3];
            if (parts.length == 5 && parts[4].equals("default") && method.equals("POST")) {
                JsonObject value = body(e);
                json(e, 200, docker.makeDefaultEnvironment(context, string(value, "expectedEndpoint"))); return;
            }
            if (parts.length == 5 && method.equals("GET")) {
                String endpoint = query(e, "endpoint");
                if (parts[4].equals("overview")) { json(e, 200, docker.overview(context, endpoint)); return; }
                if (parts[4].equals("inventory")) { json(e, 200, docker.inventory(context, endpoint)); return; }
            }
            if (parts.length == 5 && parts[4].equals("containers") && method.equals("POST")) {
                JsonObject value = body(e);
                if (!value.has("definition") || !value.get("definition").isJsonObject()) throw new ApiException(400, "A container definition is required");
                JsonObject definition = value.getAsJsonObject("definition");
                if (definition.has("ports") && definition.get("ports").isJsonArray()) for (JsonElement port : definition.getAsJsonArray("ports")) {
                    if (!port.isJsonObject()) throw new ApiException(400, "Invalid published port");
                    for (String key : List.of("host", "container")) {
                        JsonElement number = port.getAsJsonObject().get(key);
                        if (number == null || !number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber() || !number.getAsString().matches("[0-9]{1,5}")) throw new ApiException(400, "Published ports must be whole numbers between 1 and 65535");
                    }
                }
                DockerModels.Create create = gson.fromJson(definition, DockerModels.Create.class);
                if (create.projectId() != null && !create.projectId().isBlank()) store.project(create.projectId());
                String endpoint = string(value, "endpoint");
                String id = docker.create(context, endpoint, create);
                String warning = null;
                if (create.projectId() != null && !create.projectId().isBlank()) try {
                    workflowContainers.record(create.projectId(), context, endpoint, id, create.name(), create.image());
                } catch (IOException error) { warning = "Container created, but its workflow link could not be saved. Link it from the workflow's Containers section."; }
                json(e, 201, warning == null ? Map.of("id", id) : Map.of("id", id, "warning", warning)); return;
            }
            if (parts.length == 6 && parts[4].equals("images") && parts[5].equals("pull") && method.equals("POST")) {
                JsonObject value = body(e); json(e, 202, docker.pull(context, string(value, "endpoint"), string(value, "image"))); return;
            }
            if (parts.length == 6 && parts[4].equals("containers") && method.equals("GET")) {
                json(e, 200, docker.details(context, query(e, "endpoint"), parts[5])); return;
            }
            if (parts.length == 7 && parts[4].equals("containers") && method.equals("POST")) {
                JsonObject value = body(e);
                docker.action(context, string(value, "endpoint"), parts[5], parts[6]); json(e, 200, Map.of("ok", true)); return;
            }
        }
        throw new ApiException(404, "Container endpoint not found");
    }
    private String query(HttpExchange e, String name) {
        String query = e.getRequestURI().getRawQuery();
        if (query != null) for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals(name)) return pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
        }
        return null;
    }
    private JsonObject body(HttpExchange e) throws IOException {
        String type = e.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.split(";")[0].trim().equalsIgnoreCase("application/json"))
            throw new ApiException(415, "Use application/json");
        byte[] data = e.getRequestBody().readNBytes(65537);
        if (data.length > 65536) throw new ApiException(413, "Request too large");
        JsonElement value = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
        if (!value.isJsonObject()) throw new ApiException(400, "Expected a JSON object");
        return value.getAsJsonObject();
    }
    private String string(JsonObject body, String key) {
        if (!body.has(key) || body.get(key).isJsonNull()) return null;
        if (!body.get(key).isJsonPrimitive() || !body.get(key).getAsJsonPrimitive().isString())
            throw new ApiException(400, key + " must be text");
        return body.get(key).getAsString();
    }
    private int integer(JsonObject body, String key) {
        if (!body.has(key) || !body.get(key).isJsonPrimitive() || !body.getAsJsonPrimitive(key).isNumber() || !body.get(key).getAsString().matches("[0-9]{1,5}"))
            throw new ApiException(400, key + " must be a whole number between 1 and 65535");
        return body.get(key).getAsInt();
    }
    private void assets(HttpExchange e, String path) throws IOException {
        if (!e.getRequestMethod().equals("GET")) throw new ApiException(405, "Method not allowed");
        Path file = webRoot.resolve(path.equals("/") ? "index.html" : path.substring(1)).normalize();
        if (!file.startsWith(webRoot) || !Files.isRegularFile(file) || !file.toRealPath().startsWith(webRoot))
            throw new ApiException(404, "File not found");
        String name = file.getFileName().toString();
        String type = name.endsWith(".html") ? "text/html" : name.endsWith(".js") ? "text/javascript" : name.endsWith(".css") ? "text/css" : name.endsWith(".svg") ? "image/svg+xml" : name.endsWith(".png") ? "image/png" : "application/octet-stream";
        e.getResponseHeaders().set("Content-Type", type + (type.startsWith("text/") ? "; charset=utf-8" : ""));
        e.sendResponseHeaders(200, Files.size(file));
        Files.copy(file, e.getResponseBody());
    }
    private void headers(HttpExchange e) {
        e.getResponseHeaders().set("Cache-Control", "no-store");
        e.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        e.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        e.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'");
    }
    private void json(HttpExchange e, int status, Object value) throws IOException {
        byte[] bytes = gson.toJson(value).getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        e.sendResponseHeaders(status, bytes.length);
        e.getResponseBody().write(bytes);
    }
    @Override public void close() {
        server.stop(0); plans.close(); tunnels.close(); machineHosts.close(); databases.close(); terminals.close(); requests.shutdownNow();
        hostPasswords.close();
        try (DirectoryStream<Path> sockets = Files.newDirectoryStream(hostSshControls)) { for (Path socket : sockets) Files.deleteIfExists(socket); }
        catch (IOException e) { System.err.println("Could not clean SSH control sockets"); }
        try { Files.deleteIfExists(hostSshControls); } catch (IOException e) { System.err.println("Could not remove SSH session directory"); }
    }
}

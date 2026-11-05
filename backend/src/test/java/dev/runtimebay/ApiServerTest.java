package dev.runtimebay;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.Socket;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class ApiServerTest {
    @TempDir Path temporary;
    RuntimeManager runtimes;
    ApiServer server;
    HttpClient client = HttpClient.newHttpClient();
    final String token = "a".repeat(64);
    @BeforeEach void setUp() throws Exception {
        Path web = Files.createDirectory(temporary.resolve("web"));
        Files.writeString(web.resolve("index.html"), "<html>Runtime Bay</html>");
        ProjectStore store = new ProjectStore(temporary.resolve("data"));
        runtimes = new RuntimeManager(store, Path.of(System.getenv().getOrDefault("RUNTIMEBAY_LAUNCHER", "../.build/runtimebay-launcher")));
        server = new ApiServer(store, runtimes, web, token);
        server.start();
    }
    @AfterEach void tearDown() { if (server != null) server.close(); if (runtimes != null) runtimes.close(); }
    HttpRequest.Builder request(String path) { return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).timeout(Duration.ofSeconds(5)); }
    int status(HttpRequest.Builder request) throws Exception { return client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode(); }
    @Test void rejectsMissingTokenAndCrossOriginCommands() throws Exception {
        assertEquals(401, status(request("/api/projects")));
        assertEquals(401, status(request("/api/projects").header("Authorization", "Bearer wrong")));
        assertEquals(403, status(request("/api/projects").header("Authorization", "Bearer " + token).header("Origin", "https://example.com")));
        assertEquals(200, status(request("/api/projects").header("Authorization", "Bearer " + token)));
        assertEquals(415, status(request("/api/projects").header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.ofString("{}"))));
    }
    @Test void workflowGitRoutesInitializeAndReadRepositoryThroughTheApi() throws Exception {
        Path folder = Files.createDirectory(temporary.resolve("git-workflow"));
        JsonObject project = new JsonObject(); project.addProperty("name", "Git workflow"); project.addProperty("path", folder.toString());
        var created = client.send(request("/api/projects").header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(project.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode());
        String id = JsonParser.parseString(created.body()).getAsJsonObject().get("id").getAsString();
        String git = "/api/projects/" + id + "/git";
        var before = client.send(request(git).header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, before.statusCode());
        assertFalse(JsonParser.parseString(before.body()).getAsJsonObject().get("repository").getAsBoolean());
        assertEquals(200, status(request(git + "/init").header("Authorization", "Bearer " + token).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"))));
        var after = client.send(request(git).header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, after.statusCode());
        assertTrue(JsonParser.parseString(after.body()).getAsJsonObject().get("repository").getAsBoolean());
        assertEquals(200, status(request(git + "/log").header("Authorization", "Bearer " + token)));
    }
    @Test void terminalApiRequiresTokenAndSupportsCreationInputResizeAndClose() throws Exception {
        assertEquals(401, status(request("/api/terminals").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"))));
        assertEquals(403, status(request("/api/terminals").header("Authorization", "Bearer " + token).header("Origin", "https://example.com").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"))));
        var response = client.send(request("/api/terminals").header("Authorization", "Bearer " + token).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}" )).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode());
        String id = JsonParser.parseString(response.body()).getAsJsonObject().get("id").getAsString();
        for (String action : java.util.List.of("input", "resize")) {
            String body = action.equals("input") ? "{\"data\":\"printf hello\\n\"}" : "{\"columns\":100,\"rows\":30}";
            assertEquals(401, status(request("/api/terminals/" + id + "/" + action).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))));
            assertEquals(200, status(request("/api/terminals/" + id + "/" + action).header("Authorization", "Bearer " + token).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))));
        }
        assertEquals(400, status(request("/api/terminals/" + id + "/output?after=-1").header("Authorization", "Bearer " + token)));
        assertEquals(400, status(request("/api/terminals/" + id + "/resize").header("Authorization", "Bearer " + token).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"columns\":12.5,\"rows\":30}"))));
        assertEquals(200, status(request("/api/terminals/" + id).header("Authorization", "Bearer " + token).DELETE()));
        assertEquals(404, status(request("/api/terminals/" + id + "/output?after=0").header("Authorization", "Bearer " + token)));
    }
    @Test void databaseWorkspaceEndpointsRequireTheAppTokenAndValidateBeforeStarting() throws Exception {
        assertEquals(401, status(request("/api/projects/unknown/databases")));
        assertEquals(401, status(request("/api/projects/unknown/databases").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"theme\":\"light\"}"))));
        assertEquals(403, status(request("/api/projects/unknown/databases").header("Authorization", "Bearer " + token).header("Origin", "https://example.com").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"theme\":\"light\"}"))));
        assertEquals(400, status(request("/api/projects/unknown/databases").header("Authorization", "Bearer " + token).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"theme\":\"unexpected\"}"))));
        assertEquals(404, status(request("/database/unknown/")));
    }
    @Test void servesAssetsWithSecurityHeadersAndBlocksTraversal() throws Exception {
        var response = client.send(request("/").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Security-Policy").orElseThrow().contains("frame-ancestors 'none'"));
        assertEquals(404, status(request("/%2e%2e/pom.xml")));
        Files.writeString(temporary.resolve("private.txt"), "This must not be served");
        Files.createSymbolicLink(temporary.resolve("web/escape"), temporary.resolve("private.txt"));
        assertEquals(404, status(request("/escape")));
    }
    @Test void rejectsAnUnexpectedHostHeader() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(("GET /api/status HTTP/1.1\r\nHost: attacker.example\r\nAuthorization: Bearer " + token + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            String first = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
            assertTrue(first.contains("403"));
        }
    }
    @Test void servesLogoAsBinaryPngWithItsImageContentType() throws Exception {
        Path assets = Files.createDirectory(temporary.resolve("web/assets"));
        byte[] png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a0V8AAAAASUVORK5CYII=");
        Files.write(assets.resolve("runtime-bay.png"), png);
        var response = client.send(request("/assets/runtime-bay.png").build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertEquals("image/png", response.headers().firstValue("Content-Type").orElseThrow());
        assertArrayEquals(png, response.body());
    }
    @Test void createsAProjectAndServiceThroughApi() throws Exception {
        Gson gson = new Gson();
        JsonObject project = new JsonObject(); project.addProperty("name", "API test"); project.addProperty("path", temporary.toString());
        var response = client.send(request("/api/projects").header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(project))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode());
        String id = JsonParser.parseString(response.body()).getAsJsonObject().get("id").getAsString();
        assertEquals(201, status(request("/api/projects/" + id + "/services").header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Worker\",\"command\":\"echo hello\",\"directory\":\".\"}"))));
        assertEquals(400, status(request("/api/projects/" + id + "/services").header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Fraction\",\"command\":\"echo hello\",\"port\":4310.5}"))));
    }
    @Test void workflowGraphApiPreservesTargetsAndBlocksUnresolvedLocalExecution() throws Exception {
        assertEquals(401, status(request("/api/workflows")));
        var created = client.send(request("/api/projects").header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(java.util.Map.of("name", "Bound workflow", "path", temporary.toString())))).build(), HttpResponse.BodyHandlers.ofString());
        String project = JsonParser.parseString(created.body()).getAsJsonObject().get("id").getAsString();
        var serviceResponse = client.send(request("/api/projects/" + project + "/services").header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"API\",\"command\":\"echo never-executed\",\"directory\":\".\"}")).build(), HttpResponse.BodyHandlers.ofString());
        String service = JsonParser.parseString(serviceResponse.body()).getAsJsonObject().get("id").getAsString();
        var response = client.send(request("/api/workflows/" + project).header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        JsonObject view = JsonParser.parseString(response.body()).getAsJsonObject(), definition = view.getAsJsonObject("definition");
        assertEquals("local", view.getAsJsonArray("resources").get(0).getAsJsonObject().get("status").getAsString());
        JsonObject machine = new JsonObject(); machine.addProperty("id", "unresolved-machine"); definition.getAsJsonArray("machines").add(machine);
        definition.getAsJsonArray("bindings").get(0).getAsJsonObject().getAsJsonObject("target").addProperty("id", "unresolved-machine");
        assertEquals(401, status(request("/api/workflows/" + project).header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(definition.toString()))));
        assertEquals(403, status(request("/api/workflows/" + project).header("Authorization", "Bearer " + token).header("Origin", "https://example.com").header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(definition.toString()))));
        var updated = client.send(request("/api/workflows/" + project).header("Authorization", "Bearer " + token).header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(definition.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals("unresolved", JsonParser.parseString(updated.body()).getAsJsonObject().getAsJsonArray("resources").get(0).getAsJsonObject().get("status").getAsString());
        assertEquals(409, status(request("/api/projects/" + project + "/services/" + service + "/start").header("Authorization", "Bearer " + token).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"))));
        assertEquals(400, status(request("/api/workflows/" + project).header("Authorization", "Bearer " + token).header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString("{}"))));
    }
    @Test void preferencesRequireAuthenticationAndValidateSelections() throws Exception {
        assertEquals(401, status(request("/api/preferences")));
        assertEquals(200, status(request("/api/preferences").header("Authorization", "Bearer " + token)));
        var valid = "{\"version\":1,\"palette\":\"violet\",\"appearance\":\"light\",\"onboardingComplete\":true}";
        assertEquals(200, status(request("/api/preferences").header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(valid))));
        assertEquals(400, status(request("/api/preferences").header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(valid.replace("violet", "unsupported")))));
        var response = client.send(request("/api/preferences").header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(response.body().contains("violet"));
        var larger = JsonParser.parseString(valid).getAsJsonObject(); larger.addProperty("fontSize", 18);
        var updated = client.send(request("/api/preferences").header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(larger.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, updated.statusCode());
        assertEquals(18, JsonParser.parseString(updated.body()).getAsJsonObject().get("fontSize").getAsInt());
    }

}

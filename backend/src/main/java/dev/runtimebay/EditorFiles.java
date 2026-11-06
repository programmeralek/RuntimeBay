package dev.runtimebay;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.*;

/** Explicitly opened local documents. Saves require the exact revision that was read. */
final class EditorFiles {
    record Document(String id, String path, String content, String version, boolean exists) {}
    record Entry(String name, String path, boolean directory) {}
    private static final int LIMIT = 2 * 1024 * 1024;
    private final ProjectStore projects;
    private final Map<String, Path> documents = new HashMap<>();
    EditorFiles(ProjectStore projects) { this.projects = projects; }
    synchronized Document open(String projectId, String filename) throws IOException {
        Path path = resolve(projectId, filename);
        String id = documents.entrySet().stream().filter(entry -> entry.getValue().equals(path)).map(Map.Entry::getKey).findFirst().orElseGet(() -> UUID.randomUUID().toString());
        Document document = read(id, path); documents.put(id, path); return document;
    }
    synchronized Document reload(String id) throws IOException { return read(id, require(id)); }
    synchronized Document save(String id, String version, String content) throws IOException {
        Path path = require(id);
        if (content == null || content.indexOf('\0') >= 0 || content.getBytes(StandardCharsets.UTF_8).length > LIMIT) throw new ApiException(400, "Use a UTF-8 text file of up to 2 MiB");
        Document current = read(id, path);
        if (!Objects.equals(version, current.version())) throw new ApiException(409, "This file changed on disk. Reload it before saving; your editor changes have been kept.");
        Set<PosixFilePermission> permissions = current.exists() ? Files.getPosixFilePermissions(path) : java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
        Path temporary = Files.createTempFile(path.getParent(), ".runtimebay-edit-", ".tmp");
        try {
            Files.setPosixFilePermissions(temporary, permissions);
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            if (!read(id, path).version().equals(current.version())) throw new ApiException(409, "This file changed while saving. Your editor changes have been kept.");
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return read(id, path);
        } finally { Files.deleteIfExists(temporary); }
    }
    List<Entry> tree(String projectId, String directory) throws IOException {
        Path root = Path.of(projects.project(projectId).path()).toRealPath();
        Path path = resolve(projectId, directory == null || directory.isBlank() ? "." : directory);
        if (!Files.isDirectory(path)) throw new ApiException(400, "Choose a directory");
        try (var entries = Files.list(path)) {
            return entries.filter(entry -> !Files.isSymbolicLink(entry) && !entry.getFileName().toString().equals(".git"))
                .sorted(Comparator.<Path, Boolean>comparing(entry -> !Files.isDirectory(entry)).thenComparing(entry -> entry.getFileName().toString().toLowerCase()))
                .limit(500).map(entry -> new Entry(entry.getFileName().toString(), root.relativize(entry).toString(), Files.isDirectory(entry))).toList();
        }
    }
    private Path resolve(String projectId, String filename) throws IOException {
        if (filename == null || filename.isBlank() || filename.indexOf('\0') >= 0) throw new ApiException(400, "Enter a file path");
        Path root = projectId == null || projectId.isBlank() ? null : Path.of(projects.project(projectId).path()).toRealPath();
        Path requested = Path.of(filename);
        if (!requested.isAbsolute()) { if (root == null) throw new ApiException(400, "An absolute local path is required"); requested = root.resolve(requested); }
        requested = requested.normalize();
        Path resolved = Files.exists(requested, LinkOption.NOFOLLOW_LINKS) ? requested.toRealPath() : requested.getParent().toRealPath().resolve(requested.getFileName());
        if (root != null && !resolved.startsWith(root)) throw new ApiException(403, "Choose a file inside this workflow folder");
        return resolved;
    }
    private Path require(String id) { Path path = documents.get(id); if (path == null) throw new ApiException(404, "Reopen this document to edit it"); return path; }
    private Document read(String id, Path path) throws IOException {
        if (!path.getParent().toRealPath().equals(path.getParent()) || Files.isSymbolicLink(path)) throw new ApiException(409, "The file path changed. Reopen it before editing.");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new Document(id, path.toString(), "", "missing", false);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > LIMIT) throw new ApiException(400, "Choose a regular UTF-8 text file of up to 2 MiB");
        byte[] bytes;
        try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(LIMIT + 1); }
        if (bytes.length > LIMIT) throw new ApiException(400, "This file is too large for the editor");
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException error) { throw new ApiException(400, "This file is not UTF-8 text"); }
        if (text.indexOf('\0') >= 0) throw new ApiException(400, "Binary files cannot be edited here");
        try { return new Document(id, path.toString(), text, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), true); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}

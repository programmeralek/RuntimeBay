package dev.runtimebay;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EditorFilesTest {
    @TempDir Path temporary;
    @Test void savesExactUtf8AndPermissionsRejectsExternalChangesAndCreatesNewFiles() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project"));
        var store = new ProjectStore(temporary.resolve("data")); var project = store.addProject("Editor", root.toString());
        var editor = new EditorFiles(store);
        Path file = root.resolve(".env"); Files.writeString(file, "# comment\r\nKEY=\"héllo\"\r\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        var document = editor.open(project.id(), ".env");
        var saved = editor.save(document.id(), document.version(), "# comment\r\nKEY=\"new\"\r\n");
        assertEquals("# comment\r\nKEY=\"new\"\r\n", Files.readString(file));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        Files.writeString(file, "KEY=external\n");
        assertEquals(409, assertThrows(ApiException.class, () -> editor.save(saved.id(), saved.version(), "KEY=editor\n")).status);
        assertEquals("KEY=external\n", Files.readString(file));
        var fresh = editor.open(project.id(), "new.txt"); assertFalse(fresh.exists());
        Files.writeString(root.resolve("new.txt"), "created elsewhere");
        assertThrows(ApiException.class, () -> editor.save(fresh.id(), fresh.version(), "do not overwrite"));
        var newFile = editor.open(project.id(), "other.txt"); editor.save(newFile.id(), newFile.version(), "hello");
        assertEquals("hello", Files.readString(root.resolve("other.txt")));
    }
    @Test void rejectsTraversalBinaryLargeFilesAndSymlinkReplacement() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("project"));
        var store = new ProjectStore(temporary.resolve("data")); var project = store.addProject("Editor", root.toString());
        var editor = new EditorFiles(store); Path outside = temporary.resolve("outside"); Files.writeString(outside, "private");
        assertThrows(ApiException.class, () -> editor.open(project.id(), "../outside"));
        Files.createSymbolicLink(root.resolve("escape"), outside);
        assertThrows(ApiException.class, () -> editor.open(project.id(), "escape"));
        Files.write(root.resolve("binary"), new byte[]{0, 1}); assertThrows(ApiException.class, () -> editor.open(project.id(), "binary"));
        Files.write(root.resolve("invalid"), new byte[]{(byte)0xff}); assertThrows(ApiException.class, () -> editor.open(project.id(), "invalid"));
        Files.write(root.resolve("large"), new byte[2 * 1024 * 1024 + 1]); assertThrows(ApiException.class, () -> editor.open(project.id(), "large"));
        Files.writeString(root.resolve("safe"), "original"); var doc = editor.open(project.id(), "safe");
        Files.delete(root.resolve("safe")); Files.createSymbolicLink(root.resolve("safe"), outside);
        assertThrows(ApiException.class, () -> editor.save(doc.id(), doc.version(), "replacement")); assertEquals("private", Files.readString(outside));
    }
}

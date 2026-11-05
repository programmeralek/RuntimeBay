package dev.runtimebay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class GitWorkspaceTest {
    @TempDir Path directory;
    @Test void initializesTracksStagesCommitsAndReadsHistoryWithinWorkflow() throws Exception {
        Path projectPath = Files.createDirectory(directory.resolve("project"));
        ProjectStore store = new ProjectStore(directory.resolve("data"));
        var project = store.addProject("Project", projectPath.toString());
        GitWorkspace git = new GitWorkspace(store);
        assertFalse(git.status(project.id()).repository());
        git.init(project.id());
        gitConfig(projectPath, "user.name", "Runtime Bay Test");
        gitConfig(projectPath, "user.email", "runtimebay@example.test");
        Files.writeString(projectPath.resolve("README.md"), "hello\n");
        var status = git.status(project.id());
        assertTrue(status.repository()); assertEquals("main", status.branch()); assertEquals(1, status.changes().size()); assertTrue(status.changes().getFirst().untracked());
        assertThrows(ApiException.class, () -> git.diff(project.id(), "../outside", false));
        git.stage(project.id(), "README.md");
        assertEquals("hello", git.diff(project.id(), "README.md", true).lines().filter(line -> line.startsWith("+hello")).findFirst().orElse("").substring(1));
        git.commit(project.id(), "Add readme");
        assertTrue(git.status(project.id()).changes().isEmpty());
        var commits = git.log(project.id());
        assertEquals(1, commits.size()); assertEquals("Add readme", commits.getFirst().subject());
        assertTrue(git.show(project.id(), commits.getFirst().hash()).contains("README.md"));
        assertThrows(ApiException.class, () -> git.commit(project.id(), " "));
    }
    private void gitConfig(Path cwd, String key, String value) throws Exception {
        Process process = new ProcessBuilder("git", "-C", cwd.toString(), "config", key, value).start();
        assertEquals(0, process.waitFor());
    }
}

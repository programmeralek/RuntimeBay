package dev.runtimebay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Small, allowlisted Git interface rooted at an imported workflow directory. */
final class GitWorkspace {
    record Change(String path, String indexStatus, String worktreeStatus, boolean untracked) {}
    record Status(boolean repository, String branch, String upstream, int ahead, int behind,
                  boolean hasRemote, List<Change> changes) {}
    record Commit(String hash, String shortHash, String subject, String author, String date) {}
    private record Result(int code, String out, String err) {}
    private static final int MAX_OUTPUT = 2_000_000;
    private final ProjectStore store;

    GitWorkspace(ProjectStore store) { this.store = store; }

    Status status(String projectId) throws IOException {
        Path cwd = cwd(projectId);
        if (!repository(cwd)) return new Status(false, "", "", 0, 0, false, List.of());
        Result raw = run(cwd, "status", "--porcelain=v1", "--branch", "--untracked-files=all");
        requireOk(raw);
        String branch = "", upstream = ""; int ahead = 0, behind = 0;
        List<Change> changes = new ArrayList<>();
        for (String line : raw.out().split("\n")) {
            if (line.startsWith("## ")) {
                String meta = line.substring(3); int dots = meta.indexOf("...");
                branch = (dots < 0 ? meta : meta.substring(0, dots)).split(" \\[", 2)[0];
                if (branch.startsWith("No commits yet on ")) branch = branch.substring("No commits yet on ".length());
                if (dots >= 0) {
                    String rest = meta.substring(dots + 3); int bracket = rest.indexOf(" [");
                    upstream = bracket < 0 ? rest : rest.substring(0, bracket);
                    if (bracket >= 0) {
                        String counts = rest.substring(bracket + 2).replace("]", "");
                        for (String part : counts.split(", ")) {
                            try { if (part.startsWith("ahead ")) ahead = Integer.parseInt(part.substring(6)); if (part.startsWith("behind ")) behind = Integer.parseInt(part.substring(7)); }
                            catch (NumberFormatException ignored) {}
                        }
                    }
                } else if (meta.contains("[")) branch = meta.substring(0, meta.indexOf(" ["));
                continue;
            }
            if (line.length() < 4) continue;
            String x = line.substring(0, 1), y = line.substring(1, 2), path = line.substring(3);
            int rename = path.indexOf(" -> "); if (rename >= 0) path = path.substring(rename + 4);
            changes.add(new Change(path, x, y, x.equals("?") && y.equals("?")));
        }
        Result remotes = run(cwd, "remote");
        return new Status(true, branch, upstream, ahead, behind, remotes.code() == 0 && !remotes.out().isBlank(), changes);
    }

    void init(String projectId) throws IOException {
        Path cwd = cwd(projectId);
        if (repository(cwd)) throw new ApiException(409, "This workflow is already inside a Git repository");
        requireOk(run(cwd, "init", "-b", "main"));
    }

    String diff(String projectId, String path, boolean staged) throws IOException {
        Path cwd = cwd(projectId); requireRepository(cwd);
        String safe = path(path);
        Result result = staged ? run(cwd, "diff", "--cached", "--no-ext-diff", "--no-textconv", "--", safe)
                               : run(cwd, "diff", "--no-ext-diff", "--no-textconv", "--", safe);
        requireOk(result); return result.out();
    }

    void stage(String projectId, String path) throws IOException { Path cwd = cwd(projectId); requireRepository(cwd); requireOk(run(cwd, "add", "-A", "--", path(path))); }
    void unstage(String projectId, String path) throws IOException { Path cwd = cwd(projectId); requireRepository(cwd); requireOk(run(cwd, "restore", "--staged", "--", path(path))); }
    void commit(String projectId, String message) throws IOException {
        Path cwd = cwd(projectId); requireRepository(cwd);
        if (message == null || message.isBlank() || message.length() > 500) throw new ApiException(400, "Enter a commit message (up to 500 characters)");
        requireOk(run(cwd, "commit", "-m", message));
    }
    void pull(String projectId) throws IOException {
        Path cwd = cwd(projectId); Status status = status(projectId); requireRepository(cwd);
        if (status.upstream().isBlank()) throw new ApiException(409, "Set an upstream branch before pulling. Runtime Bay only performs fast-forward pulls.");
        requireOk(run(cwd, "pull", "--ff-only"));
    }
    void push(String projectId) throws IOException {
        Path cwd = cwd(projectId); Status status = status(projectId); requireRepository(cwd);
        if (status.upstream().isBlank()) throw new ApiException(409, "Set an upstream branch before pushing.");
        requireOk(run(cwd, "push"));
    }
    List<Commit> log(String projectId) throws IOException {
        Path cwd = cwd(projectId); requireRepository(cwd);
        Result result = run(cwd, "log", "-30", "--date=iso-strict", "--pretty=format:%H%x1f%h%x1f%s%x1f%an%x1f%ad");
        if (result.code() != 0 && !result.err().contains("does not have any commits yet")) requireOk(result);
        List<Commit> commits = new ArrayList<>();
        for (String line : result.out().split("\n")) { String[] f = line.split("\u001f", 5); if (f.length == 5) commits.add(new Commit(f[0], f[1], f[2], f[3], f[4])); }
        return commits;
    }
    String show(String projectId, String hash) throws IOException {
        Path cwd = cwd(projectId); requireRepository(cwd);
        if (hash == null || !hash.matches("[0-9a-fA-F]{7,64}")) throw new ApiException(400, "Invalid commit identifier");
        Result result = run(cwd, "show", "--no-ext-diff", "--no-textconv", "--stat", "--format=fuller", hash);
        requireOk(result); return result.out();
    }

    private Path cwd(String projectId) throws IOException {
        Path cwd = Path.of(store.project(projectId).path()).toRealPath();
        if (!Files.isDirectory(cwd)) throw new ApiException(404, "Workflow folder is unavailable");
        return cwd;
    }
    private boolean repository(Path cwd) throws IOException { Result r = run(cwd, "rev-parse", "--is-inside-work-tree"); return r.code() == 0 && r.out().trim().equals("true"); }
    private void requireRepository(Path cwd) throws IOException { if (!repository(cwd)) throw new ApiException(409, "Initialize Git for this workflow or open the folder that contains its repository"); }
    private String path(String value) {
        if (value == null || value.isBlank()) throw new ApiException(400, "Choose a file to manage");
        Path path = Path.of(value);
        if (path.isAbsolute() || path.normalize().startsWith("..")) throw new ApiException(400, "Git paths must stay inside the workflow folder");
        return value;
    }
    private Result run(Path cwd, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("git", "-C", cwd.toString())); command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
        builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
        builder.environment().put("GIT_TERMINAL_PROMPT", "0"); builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        builder.environment().put("GIT_CONFIG_COUNT", "2"); builder.environment().put("GIT_CONFIG_KEY_0", "core.pager"); builder.environment().put("GIT_CONFIG_VALUE_0", "cat");
        builder.environment().put("GIT_CONFIG_KEY_1", "color.ui"); builder.environment().put("GIT_CONFIG_VALUE_1", "false");
        Process process = builder.start();
        var out = new java.io.ByteArrayOutputStream(); var err = new java.io.ByteArrayOutputStream();
        Thread stdout = drain(process.getInputStream(), out), stderr = drain(process.getErrorStream(), err);
        try {
            if (!process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS)) { process.destroyForcibly(); throw new ApiException(504, "Git took too long. Check the repository and try again."); }
            stdout.join(); stderr.join();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); process.destroyForcibly(); throw new IOException("Git operation interrupted", e); }
        return new Result(process.exitValue(), out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
    private Thread drain(java.io.InputStream stream, java.io.ByteArrayOutputStream target) {
        Thread thread = new Thread(() -> { try (stream) { byte[] buf = new byte[8192]; int n; while ((n = stream.read(buf)) >= 0) { if (target.size() + n > MAX_OUTPUT) { target.write(buf, 0, Math.max(0, MAX_OUTPUT - target.size())); break; } target.write(buf, 0, n); } } catch (IOException ignored) {} });
        thread.setDaemon(true); thread.start(); return thread;
    }
    private void requireOk(Result result) {
        if (result.code() == 0) return;
        String message = result.err().isBlank() ? result.out().trim() : result.err().trim();
        if (message.length() > 1000) message = message.substring(0, 1000);
        throw new ApiException(409, message.isBlank() ? "Git could not complete this operation" : message);
    }
}

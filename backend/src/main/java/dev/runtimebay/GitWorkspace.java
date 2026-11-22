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
    record Branch(String name, String upstream) {}
    record Repository(String path, String name, String branch) {}
    record Status(boolean repository, String branch, String upstream, int ahead, int behind,
                  boolean hasRemote, List<Change> changes, List<Branch> branches) {}
    record Commit(String hash, String shortHash, String subject, String author, String date) {}
    private record Result(int code, String out, String err) {}
    private static final int MAX_OUTPUT = 2_000_000;
    private final ProjectStore store;

    GitWorkspace(ProjectStore store) { this.store = store; }

    Status status(String projectId) throws IOException { return status(projectId, null); }
    void checkout(String projectId, String branch, boolean create) throws IOException { checkout(projectId, null, branch, create); }
    void init(String projectId) throws IOException { init(projectId, null); }
    String diff(String projectId, String path, boolean staged) throws IOException { return diff(projectId, null, path, staged); }
    void stage(String projectId, String path) throws IOException { stage(projectId, null, path); }
    void unstage(String projectId, String path) throws IOException { unstage(projectId, null, path); }
    void commit(String projectId, String message) throws IOException { commit(projectId, null, message); }
    void pull(String projectId) throws IOException { pull(projectId, null); }
    void push(String projectId) throws IOException { push(projectId, null); }
    List<Commit> log(String projectId) throws IOException { return log(projectId, null); }
    String show(String projectId, String hash) throws IOException { return show(projectId, null, hash); }

    List<Repository> repositories(String projectId) throws IOException {
        Path root = cwd(projectId);
        Map<Path, Repository> byGitDirectory = new HashMap<>();
        Set<Path> primary = new HashSet<>();
        var skipped = Set.of(".git", ".recovery", ".cache", "node_modules", ".angular", ".build", "target", "dist", "vendor", "coverage");
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 7, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (!dir.equals(root) && skipped.contains(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                if (Files.isDirectory(dir.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) { add(dir, true); return FileVisitResult.SKIP_SUBTREE; }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (file.getFileName().toString().equals(".git") && attrs.isRegularFile()) add(file.getParent(), false);
                return FileVisitResult.CONTINUE;
            }
            private void add(Path path, boolean isPrimary) {
                try {
                    Path repo = path.toRealPath();
                    if (!repo.startsWith(root)) return;
                    Result common = run(repo, "rev-parse", "--path-format=absolute", "--git-common-dir");
                    Result top = run(repo, "rev-parse", "--show-toplevel");
                    if (common.code() != 0 || top.code() != 0 || !Path.of(top.out().trim()).toRealPath().equals(repo)) return;
                    Path commonPath = Path.of(common.out().trim()).toRealPath();
                    String relative = root.equals(repo) ? "." : root.relativize(repo).toString().replace('\\', '/');
                    Result current = run(repo, "branch", "--show-current");
                    Repository candidate = new Repository(relative, repo.equals(root) ? root.getFileName().toString() : repo.getFileName().toString(), current.out().trim());
                    Repository previous = byGitDirectory.get(commonPath);
                    if (previous == null || isPrimary && !primary.contains(commonPath) || (isPrimary == primary.contains(commonPath) && relative.length() < previous.path().length())) {
                        byGitDirectory.put(commonPath, candidate);
                        if (isPrimary) primary.add(commonPath); else primary.remove(commonPath);
                    }
                } catch (IOException | RuntimeException ignored) { /* Invalid or inaccessible Git metadata is not a selectable repository. */ }
            }
        });
        if (!byGitDirectory.isEmpty()) return byGitDirectory.values().stream().sorted(Comparator.comparing(Repository::name, String.CASE_INSENSITIVE_ORDER)).toList();
        // Preserve the existing single-repository workflow behavior when its Git root is an ancestor.
        if (repository(root)) {
            Result branch = run(root, "branch", "--show-current");
            return List.of(new Repository(".", root.getFileName().toString(), branch.out().trim()));
        }
        return List.of();
    }

    Status status(String projectId, String repositoryPath) throws IOException {
        Path cwd = cwd(projectId, repositoryPath);
        if (!repository(cwd)) return new Status(false, "", "", 0, 0, false, List.of(), List.of());
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
        return new Status(true, branch, upstream, ahead, behind, remotes.code() == 0 && !remotes.out().isBlank(), changes, branches(cwd));
    }

    private List<Branch> branches(Path cwd) throws IOException {
        Result result = run(cwd, "for-each-ref", "--format=%(refname:short)%09%(upstream:short)", "refs/heads");
        requireOk(result);
        List<Branch> branches = new ArrayList<>();
        for (String line : result.out().split("\\n")) {
            if (line.isBlank()) continue;
            String[] fields = line.split("\\t", 2);
            branches.add(new Branch(fields[0], fields.length > 1 ? fields[1] : ""));
        }
        return List.copyOf(branches);
    }

    void checkout(String projectId, String repositoryPath, String branch, boolean create) throws IOException {
        Path cwd = cwd(projectId, repositoryPath); requireRepository(cwd);
        if (branch == null || branch.isBlank() || branch.length() > 250) throw new ApiException(400, "Enter a valid branch name");
        Result valid = run(cwd, "check-ref-format", "--branch", branch);
        if (valid.code() != 0 || !valid.out().trim().equals(branch)) throw new ApiException(400, "Enter a valid Git branch name");
        List<Branch> local = branches(cwd);
        boolean exists = local.stream().anyMatch(item -> item.name().equals(branch));
        if (create && exists) throw new ApiException(409, "A local branch with that name already exists");
        if (!create && !exists) throw new ApiException(404, "That local branch no longer exists. Refresh and choose another branch.");
        requireOk(create ? run(cwd, "switch", "-c", branch) : run(cwd, "switch", "--", branch));
    }

    void init(String projectId, String repositoryPath) throws IOException {
        Path cwd = cwd(projectId, repositoryPath);
        if (repository(cwd)) throw new ApiException(409, "This workflow is already inside a Git repository");
        requireOk(run(cwd, "init", "-b", "main"));
    }

    String diff(String projectId, String repositoryPath, String path, boolean staged) throws IOException {
        Path cwd = cwd(projectId, repositoryPath); requireRepository(cwd);
        String safe = path(path);
        Result result = staged ? run(cwd, "diff", "--cached", "--no-ext-diff", "--no-textconv", "--", safe)
                               : run(cwd, "diff", "--no-ext-diff", "--no-textconv", "--", safe);
        requireOk(result); return result.out();
    }

    void stage(String projectId, String repositoryPath, String path) throws IOException { Path cwd = cwd(projectId, repositoryPath); requireRepository(cwd); requireOk(run(cwd, "add", "-A", "--", path(path))); }
    void unstage(String projectId, String repositoryPath, String path) throws IOException { Path cwd = cwd(projectId, repositoryPath); requireRepository(cwd); requireOk(run(cwd, "restore", "--staged", "--", path(path))); }
    void commit(String projectId, String repositoryPath, String message) throws IOException {
        Path cwd = cwd(projectId, repositoryPath); requireRepository(cwd);
        if (message == null || message.isBlank() || message.length() > 500) throw new ApiException(400, "Enter a commit message (up to 500 characters)");
        requireOk(run(cwd, "commit", "-m", message));
    }
    void pull(String projectId, String repositoryPath) throws IOException {
        Path cwd = cwd(projectId, repositoryPath); Status status = status(projectId, repositoryPath); requireRepository(cwd);
        if (status.upstream().isBlank()) throw new ApiException(409, "Set an upstream branch before pulling. Runtime Bay only performs fast-forward pulls.");
        requireOk(run(cwd, "pull", "--ff-only"));
    }
    void push(String projectId, String repositoryPath) throws IOException {
        Path cwd = cwd(projectId, repositoryPath); Status status = status(projectId, repositoryPath); requireRepository(cwd);
        if (status.upstream().isBlank()) throw new ApiException(409, "Set an upstream branch before pushing.");
        requireOk(run(cwd, "push"));
    }
    List<Commit> log(String projectId, String repositoryPath) throws IOException {
        Path cwd = cwd(projectId, repositoryPath); requireRepository(cwd);
        Result result = run(cwd, "log", "-30", "--date=iso-strict", "--pretty=format:%H%x1f%h%x1f%s%x1f%an%x1f%ad");
        if (result.code() != 0 && !result.err().contains("does not have any commits yet")) requireOk(result);
        List<Commit> commits = new ArrayList<>();
        for (String line : result.out().split("\n")) { String[] f = line.split("\u001f", 5); if (f.length == 5) commits.add(new Commit(f[0], f[1], f[2], f[3], f[4])); }
        return commits;
    }
    String show(String projectId, String repositoryPath, String hash) throws IOException {
        Path cwd = cwd(projectId, repositoryPath); requireRepository(cwd);
        if (hash == null || !hash.matches("[0-9a-fA-F]{7,64}")) throw new ApiException(400, "Invalid commit identifier");
        Result result = run(cwd, "show", "--no-ext-diff", "--no-textconv", "--stat", "--format=fuller", hash);
        requireOk(result); return result.out();
    }

    private Path cwd(String projectId) throws IOException {
        Path cwd = Path.of(store.project(projectId).path()).toRealPath();
        if (!Files.isDirectory(cwd)) throw new ApiException(404, "Workflow folder is unavailable");
        return cwd;
    }
    private Path cwd(String projectId, String repositoryPath) throws IOException {
        Path root = cwd(projectId);
        if (repositoryPath == null || repositoryPath.isBlank()) return root;
        Path requested = Path.of(repositoryPath);
        if (requested.isAbsolute() || requested.normalize().startsWith("..")) throw new ApiException(400, "Choose a repository inside this workflow");
        Path selected = root.resolve(requested).normalize().toRealPath();
        if (!selected.startsWith(root) || !Files.isDirectory(selected) || !repository(selected)) throw new ApiException(404, "This Git repository is no longer available inside the workflow");
        Path top = Path.of(run(selected, "rev-parse", "--show-toplevel").out().trim()).toRealPath();
        if (!top.equals(selected)) {
            boolean compatibilityFallback = selected.equals(root) && repositoryPath.equals(".")
                && !Files.exists(root.resolve(".git"), LinkOption.NOFOLLOW_LINKS)
                && repositories(projectId).stream().allMatch(item -> item.path().equals("."));
            if (!compatibilityFallback) throw new ApiException(400, "The selected path is not the root of a local Git repository");
        }
        return selected;
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

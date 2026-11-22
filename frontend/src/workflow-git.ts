import { Component, Input, OnChanges, OnDestroy, SimpleChanges, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

interface GitChange { path: string; indexStatus: string; worktreeStatus: string; untracked: boolean }
interface GitBranch { name: string; upstream: string }
interface GitRepository { path: string; name: string; branch: string }
interface GitStatus { repository: boolean; branch: string; upstream: string; ahead: number; behind: number; hasRemote: boolean; changes: GitChange[]; branches: GitBranch[] }
interface Commit { hash: string; shortHash: string; subject: string; author: string; date: string }

@Component({ selector: 'app-workflow-git', standalone: true, imports: [FormsModule], templateUrl: './workflow-git.html', styleUrl: './workflow-git.css' })
export class WorkflowGit implements OnChanges, OnDestroy {
  @Input() projectId = '';
  @Input() projectName = '';
  @Input() projectPath = '';
  @Input() session = '';
  @Input() visible = false;
  status = signal<GitStatus | null>(null);
  repositories = signal<GitRepository[]>([]);
  repositoryPath = signal('');
  commits = signal<Commit[]>([]);
  selectedPath = signal('');
  selectedStaged = signal(false);
  diff = signal('');
  commitDetail = signal('');
  message = '';
  error = signal('');
  notice = signal('');
  busy = signal(false);
  loading = signal(true);
  section = signal<'changes' | 'history'>('changes');
  branchPickerOpen = signal(false);
  repositoryPickerOpen = signal(false);
  newBranchName = signal('');
  private controller?: AbortController;
  private timer?: ReturnType<typeof setInterval>;
  private noticeTimer?: ReturnType<typeof setTimeout>;
  private revision = 0;
  private repositorySelections = this.readRepositorySelections();
  ngOnChanges(changes: SimpleChanges) {
    if (changes['projectId'] || changes['visible']) {
      this.revision++;
      clearInterval(this.timer);
      if (changes['projectId']) this.clearNotice();
      if (changes['projectId']) { this.branchPickerOpen.set(false); this.newBranchName.set(''); }
      if (changes['projectId']) { this.repositories.set([]); this.repositoryPath.set(this.repositorySelections[this.projectId] || ''); this.repositoryPickerOpen.set(false); }
      if (this.visible && this.projectId) { void this.load(); this.timer = setInterval(() => { if (!this.busy()) void this.load(false); }, 6000); }
      else { clearInterval(this.timer); this.diff.set(''); this.commits.set([]); }
    }
  }
  ngOnDestroy() { clearInterval(this.timer); clearTimeout(this.noticeTimer); this.controller?.abort(); }
  diffLines() {
    return this.diff().split('\n').map(text => ({
      text,
      kind: text.startsWith('+++') || text.startsWith('---') || text.startsWith('diff --git') || text.startsWith('index ')
        ? 'metadata'
        : text.startsWith('@@') ? 'hunk'
          : text.startsWith('+') ? 'addition'
            : text.startsWith('-') ? 'removal' : 'context'
    }));
  }
  private async api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
    const response = await fetch('/api' + path, { method, signal: this.controller?.signal,
      headers: { Authorization: 'Bearer ' + this.session, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    const value = await response.json();
    if (!response.ok) {
      if (response.status === 404 && value.error === 'Endpoint not found') throw new Error('This window is using an older Runtime Bay engine. Quit and reopen the updated app to enable Git. Quitting disconnects active terminals and tunnels.');
      throw new Error(value.error || 'Git could not complete that action');
    }
    return value as T;
  }
  async load(showLoading = true) {
    const revision = this.revision; this.controller?.abort(); this.controller = new AbortController();
    if (showLoading) this.loading.set(true);
    try {
      let repositories = this.repositories();
      if (showLoading || !repositories.length) {
        repositories = await this.api<GitRepository[]>(`/projects/${this.projectId}/git/repositories`);
        if (revision !== this.revision) return;
        this.repositories.set(repositories);
      }
      let selected = this.repositoryPath();
      if (!repositories.some(repository => repository.path === selected)) selected = this.repositorySelections[this.projectId] || repositories[0]?.path || '.';
      if (!repositories.some(repository => repository.path === selected)) selected = repositories[0]?.path || '.';
      this.repositoryPath.set(selected);
      this.repositorySelections[this.projectId] = selected;
      try { localStorage.setItem('runtimebay.gitRepositorySelections', JSON.stringify(this.repositorySelections)); } catch { /* Selection still works while this window remains open. */ }
      const status = await this.api<GitStatus>(`/projects/${this.projectId}/git?repository=${encodeURIComponent(selected)}`);
      if (revision !== this.revision) return;
      this.status.set(status); this.error.set('');
      if (status.repository && this.section() === 'history') await this.loadHistory();
      if (this.selectedPath() && !status.changes.some(change => change.path === this.selectedPath())) { this.selectedPath.set(''); this.diff.set(''); }
    } catch (e) { if (revision === this.revision && !this.controller.signal.aborted) this.error.set((e as Error).message); }
    finally { if (revision === this.revision) this.loading.set(false); }
  }
  async initialize() { await this.mutate('init', {}); }
  async checkoutBranch(branch: string, create = false) {
    if (this.busy() || !branch.trim()) return;
    const ok = await this.mutate('checkout', { branch: branch.trim(), create }, create ? `Created and switched to ${branch.trim()}.` : `Switched to ${branch.trim()}.`);
    if (ok) { this.branchPickerOpen.set(false); this.newBranchName.set(''); }
  }
  createBranch() { return this.checkoutBranch(this.newBranchName(), true); }
  selectRepository(path: string) {
    if (this.busy() || !this.repositories().some(repository => repository.path === path)) return;
    this.repositoryPath.set(path); this.repositorySelections[this.projectId] = path;
    try { localStorage.setItem('runtimebay.gitRepositorySelections', JSON.stringify(this.repositorySelections)); } catch { /* Selection still works while this window remains open. */ }
    this.repositoryPickerOpen.set(false); this.branchPickerOpen.set(false); this.selectedPath.set(''); this.diff.set(''); this.commits.set([]); this.commitDetail.set(''); this.status.set(null);
    void this.load();
  }
  async loadHistory() { try { this.commits.set(await this.api<Commit[]>(`/projects/${this.projectId}/git/log?repository=${encodeURIComponent(this.repositoryPath() || '.')}`)); } catch (e) { this.error.set((e as Error).message); } }
  async showDiff(change: GitChange, staged: boolean) {
    this.selectedPath.set(change.path); this.selectedStaged.set(staged); this.commitDetail.set(''); this.diff.set('Loading diff…');
    try { const value = await this.api<{diff: string}>(`/projects/${this.projectId}/git/diff?repository=${encodeURIComponent(this.repositoryPath() || '.')}&path=${encodeURIComponent(change.path)}&staged=${staged}`); this.diff.set(value.diff || 'No textual diff is available for this file.'); }
    catch (e) { this.diff.set((e as Error).message); }
  }
  async stage(change: GitChange) { await this.mutate('stage', { path: change.path }); }
  async stageAll() { await this.mutate('stage', { path: '.' }); }
  async unstage(change: GitChange) { await this.mutate('unstage', { path: change.path }); }
  async commit() {
    if (!this.message.trim()) return;
    const message = this.message.trim();
    if (await this.mutate('commit', { message }, 'Commit created successfully.')) {
      this.message = '';
      if (this.section() === 'history') await this.loadHistory();
    }
  }
  async pull() { if (!confirm('Pull the latest changes using a fast-forward only?')) return; await this.mutate('pull', {}, 'Pull completed successfully.'); }
  async push() { await this.mutate('push', {}, 'Push completed successfully.'); }
  dismissNotice() { this.clearNotice(); }
  private clearNotice() { clearTimeout(this.noticeTimer); this.noticeTimer = undefined; this.notice.set(''); }
  private showNotice(message: string) {
    clearTimeout(this.noticeTimer);
    this.notice.set(message);
    this.noticeTimer = setTimeout(() => { this.notice.set(''); this.noticeTimer = undefined; }, 4200);
  }
  async copyHash(shortHash: string) {
    try {
      await navigator.clipboard.writeText(shortHash);
      this.showNotice(`Copied ${shortHash} to clipboard.`);
    } catch {
      this.error.set('Could not copy the commit hash to the clipboard.');
    }
  }
  async openCommit(commit: Commit) {
    this.selectedPath.set(''); this.diff.set('');
    try { this.commitDetail.set((await this.api<{detail: string}>(`/projects/${this.projectId}/git/commit/${commit.hash}?repository=${encodeURIComponent(this.repositoryPath() || '.')}`)).detail); }
    catch (e) { this.error.set((e as Error).message); }
  }
  async mutate(action: string, body: unknown, successMessage?: string): Promise<boolean> {
    if (this.busy()) return false;
    const projectId = this.projectId;
    this.busy.set(true); this.error.set(''); this.clearNotice();
    try {
      const repositoryPath = this.repositoryPath() || '.';
      const payload = { ...(body as object), repositoryPath };
      const value = action === 'init' ? await this.api<GitStatus>(`/projects/${projectId}/git/init`, 'POST', payload)
        : await this.api<GitStatus>(`/projects/${projectId}/git/${action}`, 'POST', payload);
      if (projectId !== this.projectId) return true;
      this.status.set(value); this.diff.set(''); this.selectedPath.set('');
      if (this.section() === 'history') await this.loadHistory();
      if (successMessage) this.showNotice(successMessage);
      return true;
    } catch (e) { if (projectId === this.projectId) this.error.set((e as Error).message); }
    finally { this.busy.set(false); }
    return false;
  }
  staged(change: GitChange) { return change.indexStatus !== ' ' && change.indexStatus !== '?'; }
  changed(change: GitChange) { return change.worktreeStatus !== ' '; }
  statusLabel(change: GitChange) { return change.untracked ? 'Untracked' : `${change.indexStatus !== ' ' ? change.indexStatus : ''}${change.worktreeStatus !== ' ' ? change.worktreeStatus : ''}`; }
  trackPath(_index: number, item: GitChange) { return item.path; }
  currentRepository() { return this.repositories().find(repository => repository.path === this.repositoryPath()) || null; }
  private readRepositorySelections(): Record<string, string> {
    try {
      const value = JSON.parse(localStorage.getItem('runtimebay.gitRepositorySelections') || '{}');
      if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
      return Object.fromEntries(Object.entries(value).filter(([key, path]) => !!key && typeof path === 'string')) as Record<string, string>;
    } catch { return {}; }
  }
}

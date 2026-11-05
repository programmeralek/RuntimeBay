import { Component, Input, OnChanges, OnDestroy, SimpleChanges, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

interface GitChange { path: string; indexStatus: string; worktreeStatus: string; untracked: boolean }
interface GitStatus { repository: boolean; branch: string; upstream: string; ahead: number; behind: number; hasRemote: boolean; changes: GitChange[] }
interface Commit { hash: string; shortHash: string; subject: string; author: string; date: string }

@Component({ selector: 'app-workflow-git', standalone: true, imports: [FormsModule], templateUrl: './workflow-git.html', styleUrl: './workflow-git.css' })
export class WorkflowGit implements OnChanges, OnDestroy {
  @Input() projectId = '';
  @Input() projectName = '';
  @Input() projectPath = '';
  @Input() session = '';
  @Input() visible = false;
  status = signal<GitStatus | null>(null);
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
  private controller?: AbortController;
  private timer?: ReturnType<typeof setInterval>;
  private revision = 0;
  ngOnChanges(changes: SimpleChanges) {
    if (changes['projectId'] || changes['visible']) {
      this.revision++;
      clearInterval(this.timer);
      if (this.visible && this.projectId) { void this.load(); this.timer = setInterval(() => { if (!this.busy()) void this.load(false); }, 6000); }
      else { clearInterval(this.timer); this.diff.set(''); this.commits.set([]); }
    }
  }
  ngOnDestroy() { clearInterval(this.timer); this.controller?.abort(); }
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
      const status = await this.api<GitStatus>(`/projects/${this.projectId}/git`);
      if (revision !== this.revision) return;
      this.status.set(status); this.error.set('');
      if (status.repository && this.section() === 'history') await this.loadHistory();
      if (this.selectedPath() && !status.changes.some(change => change.path === this.selectedPath())) { this.selectedPath.set(''); this.diff.set(''); }
    } catch (e) { if (revision === this.revision && !this.controller.signal.aborted) this.error.set((e as Error).message); }
    finally { if (revision === this.revision) this.loading.set(false); }
  }
  async initialize() { await this.mutate('init', {}); }
  async loadHistory() { try { this.commits.set(await this.api<Commit[]>(`/projects/${this.projectId}/git/log`)); } catch (e) { this.error.set((e as Error).message); } }
  async showDiff(change: GitChange, staged: boolean) {
    this.selectedPath.set(change.path); this.selectedStaged.set(staged); this.commitDetail.set(''); this.diff.set('Loading diff…');
    try { const value = await this.api<{diff: string}>(`/projects/${this.projectId}/git/diff?path=${encodeURIComponent(change.path)}&staged=${staged}`); this.diff.set(value.diff || 'No textual diff is available for this file.'); }
    catch (e) { this.diff.set((e as Error).message); }
  }
  async stage(change: GitChange) { await this.mutate('stage', { path: change.path }); }
  async unstage(change: GitChange) { await this.mutate('unstage', { path: change.path }); }
  async commit() {
    if (!this.message.trim()) return;
    const message = this.message.trim(); await this.mutate('commit', { message });
    if (!this.error()) { this.message = ''; this.notice.set('Commit created.'); if (this.section() === 'history') await this.loadHistory(); }
  }
  async pull() { if (!confirm('Pull the latest changes using a fast-forward only?')) return; await this.mutate('pull', {}); }
  async push() { await this.mutate('push', {}); }
  async openCommit(commit: Commit) {
    this.selectedPath.set(''); this.diff.set('');
    try { this.commitDetail.set((await this.api<{detail: string}>(`/projects/${this.projectId}/git/commit/${commit.hash}`)).detail); }
    catch (e) { this.error.set((e as Error).message); }
  }
  async mutate(action: string, body: unknown) {
    if (this.busy()) return;
    this.busy.set(true); this.error.set(''); this.notice.set('');
    try {
      const value = action === 'init' ? await this.api<GitStatus>(`/projects/${this.projectId}/git/init`, 'POST', body)
        : await this.api<GitStatus>(`/projects/${this.projectId}/git/${action}`, 'POST', body);
      this.status.set(value); this.diff.set(''); this.selectedPath.set('');
      if (this.section() === 'history') await this.loadHistory();
    } catch (e) { this.error.set((e as Error).message); }
    finally { this.busy.set(false); }
  }
  staged(change: GitChange) { return change.indexStatus !== ' ' && change.indexStatus !== '?'; }
  changed(change: GitChange) { return change.worktreeStatus !== ' '; }
  statusLabel(change: GitChange) { return change.untracked ? 'Untracked' : `${change.indexStatus !== ' ' ? change.indexStatus : ''}${change.worktreeStatus !== ' ' ? change.worktreeStatus : ''}`; }
  trackPath(_index: number, item: GitChange) { return item.path; }
}

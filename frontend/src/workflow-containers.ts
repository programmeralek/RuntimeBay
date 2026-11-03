import { Component, Input, OnChanges, OnDestroy, inject, signal, computed } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { dockerHostName, DockerDiscovery, DockerEnvironment, DockerOverview, DockerContainer } from './docker-types';
import { LoadingService } from './loading/loading.service';
interface Link { id: string; projectId: string; context: string; endpoint: string; containerId: string; name: string; image: string }
interface ContainerState { link: Link; state: string; status: string; ports: string; error: string | null }

@Component({ selector: 'app-workflow-containers', standalone: true, imports: [FormsModule], templateUrl: './workflow-containers.html' })
export class WorkflowContainers implements OnChanges, OnDestroy {
  readonly hostLabel = dockerHostName;
  @Input() projectId = '';
  @Input() session = '';
  @Input() environmentId = '';
  @Input() workflowRevision = '';
  private tasks = inject(LoadingService).scope();
  readonly links = signal<Link[]>([]);
  private sourceLinks = signal<Link[]>([]);
  readonly states = signal<ContainerState[]>([]);
  readonly error = signal('');
  readonly loading = signal(false);
  readonly pending = signal<string[]>([]);
  readonly open = signal(false);
  readonly discovery = signal<DockerDiscovery | null>(null);
  readonly hostName = signal('');
  readonly host = computed(() => this.discovery()?.environments.find(host => host.name === this.hostName()));
  readonly candidates = signal<DockerContainer[]>([]);
  readonly connecting = signal(false);
  readonly saving = signal(false);
  readonly formError = signal('');
  readonly filter = signal('');
  readonly selectedId = signal('');
  readonly visible = computed(() => this.candidates().filter(container => `${container.name} ${container.image} ${container.composeProject}`.toLowerCase().includes(this.filter().toLowerCase())));
  private controller = new AbortController();
  private hostController = new AbortController();
  private timer?: ReturnType<typeof setInterval>;
  private refreshing = false;
  private revision = 0;
  private hostRevision = 0;
  private returnFocus: HTMLElement | null = null;
  private readonly keyListener = (event: KeyboardEvent) => {
    if (!this.open()) return;
    if (event.key === 'Escape' && !this.saving()) { event.preventDefault(); this.dismiss(); }
    if (event.key === 'Tab') {
      const controls = Array.from(document.querySelectorAll<HTMLElement>('.workflow-container-modal button:not([disabled]), .workflow-container-modal input:not([disabled]), .workflow-container-modal select:not([disabled])'));
      const first = controls[0], last = controls.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    }
  };
  constructor() { window.addEventListener('keydown', this.keyListener); }
  ngOnChanges() {
    this.revision++; this.controller.abort(); this.controller = new AbortController(); this.hostController.abort();
    this.refreshing = false; this.links.set([]); this.states.set([]); this.error.set(''); this.open.set(false); this.pending.set([]);
    if (this.timer) clearInterval(this.timer);
    void this.load(); this.timer = setInterval(() => void this.refresh(), 5000);
  }
  private async api<T>(path: string, method = 'GET', body?: unknown, signal = this.controller.signal): Promise<T> {
    const response = await fetch('/api' + path, { method, signal, headers: { Authorization: 'Bearer ' + this.session, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    const value = await response.json(); if (!response.ok) throw new Error(value.error || 'Container operation failed'); return value;
  }
  private path(project = this.projectId) { return '/projects/' + project + '/containers'; }
  async load() {
    const revision = this.revision; this.loading.set(true);
    try { const [links, sources] = await Promise.all([this.api<Link[]>(this.path()), this.api<Link[]>(this.path() + '/links')]); if (revision === this.revision) { this.links.set(links); this.sourceLinks.set(sources); await this.refresh(); } }
    catch (error) { if (revision === this.revision && !this.controller.signal.aborted) this.error.set((error as Error).message); }
    finally { if (revision === this.revision) this.loading.set(false); }
  }
  async refresh() {
    if (!this.links().length || this.refreshing) return;
    const revision = this.revision; this.refreshing = true;
    try { const states = await this.api<ContainerState[]>(this.path() + '/status'); if (revision === this.revision) this.states.set(states); }
    catch (error) { if (revision === this.revision && !this.controller.signal.aborted) { this.error.set((error as Error).message); this.states.set([]); } }
    finally { if (revision === this.revision) this.refreshing = false; }
  }
  state(link: Link) { return this.states().find(value => value.link.id === link.id); }
  async startDependency(resourceId: string) {
    await this.load(); await this.refresh();
    const state = this.states().find(value => value.link.id === resourceId);
    if (!state || state.state === 'missing' || state.state === 'unavailable') throw new Error(state?.error || 'The selected container dependency is not available in this workflow environment.');
    if (state.state === 'running') return;
    await this.runContainerAction(state.link, state.state === 'paused' ? 'resume' : 'start');
    await this.refresh();
  }
  async startAll() {
    await this.load(); await this.refresh(); if (this.error()) throw new Error(this.error());
    for (const state of this.states()) {
      if (state.state === 'running') continue;
      if (state.state === 'missing' || state.state === 'unavailable') throw new Error(state.error || `Container ${state.link.name} is unavailable.`);
      await this.runContainerAction(state.link, state.state === 'paused' ? 'resume' : 'start');
      await this.refresh();
    }
  }
  async stopAll() {
    await this.load(); await this.refresh(); if (this.error()) throw new Error(this.error());
    for (const state of [...this.states()].reverse()) {
      if (!['running', 'paused'].includes(state.state)) continue;
      await this.runContainerAction(state.link, 'stop');
      await this.refresh();
    }
  }
  private async runContainerAction(link: Link, action: 'start' | 'stop' | 'resume') {
    if (this.pending().includes(link.id)) return;
    this.pending.update(ids => [...ids, link.id]); this.error.set('');
    try { await this.api(this.path() + '/' + link.id + '/' + action, 'POST', { environmentId: this.environmentId, workflowRevision: this.workflowRevision }); }
    catch (error) { this.error.set((error as Error).message); throw error; }
    finally { this.pending.update(ids => ids.filter(id => id !== link.id)); }
  }
  linked(container: DockerContainer) { const host = this.host(); return this.sourceLinks().some(link => link.context === host?.name && link.endpoint === host.endpoint && link.containerId === container.id); }
  async show() {
    this.returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    this.open.set(true); this.formError.set(''); this.filter.set(''); this.candidates.set([]); this.selectedId.set('');
    requestAnimationFrame(() => document.querySelector<HTMLElement>('.workflow-container-modal button')?.focus());
    const revision = this.revision;
    await this.tasks.run('Finding Docker hosts…', async () => {
      try {
        const discovery = await this.api<DockerDiscovery>('/docker/environments');
        if (revision !== this.revision || !this.open()) return;
        this.discovery.set(discovery);
        const locals = discovery.environments.filter(host => host.supported && host.transport === 'local');
        const selected = discovery.environments.find(host => host.name === this.hostName() && host.supported) || discovery.environments.find(host => host.current && host.supported) || locals[0] || discovery.environments.find(host => host.supported);
        if (selected) await this.selectHost(selected.name);
      } catch (error) { if (revision === this.revision) this.formError.set((error as Error).message); }
    });
  }
  async selectHost(name: string) {
    this.hostController.abort(); this.hostController = new AbortController();
    const revision = ++this.hostRevision; this.hostName.set(name); this.candidates.set([]); this.selectedId.set(''); this.formError.set('');
    const host = this.host(); if (!host) return;
    this.connecting.set(true);
    try {
      const overview = await this.api<DockerOverview>('/docker/environments/' + encodeURIComponent(host.name) + '/overview?endpoint=' + encodeURIComponent(host.endpoint), 'GET', undefined, this.hostController.signal);
      if (revision === this.hostRevision && this.open()) this.candidates.set(overview.containers);
    } catch (error) { if (revision === this.hostRevision && !this.hostController.signal.aborted) this.formError.set((error as Error).message); }
    finally { if (revision === this.hostRevision) this.connecting.set(false); }
  }
  async attach() {
    const host = this.host(), containerId = this.selectedId(), revision = this.revision;
    if (!host || !containerId || this.saving()) return;
    this.saving.set(true); this.formError.set('');
    await this.tasks.run('Linking container to this workflow…', async () => {
      try {
        await this.api<Link>(this.path(), 'POST', { context: host.name, endpoint: host.endpoint, containerId });
        if (revision !== this.revision) return;
        this.saving.set(false); this.dismiss(); await this.load();
      } catch (error) { if (revision === this.revision) this.formError.set((error as Error).message); }
      finally { if (revision === this.revision) this.saving.set(false); }
    });
  }
  async action(link: Link, action: 'start' | 'stop' | 'restart' | 'resume' | 'unlink') {
    if (this.pending().includes(link.id)) return;
    const revision = this.revision; this.pending.update(ids => [...ids, link.id]); this.error.set('');
    await this.tasks.run(action === 'unlink' ? 'Unlinking container…' : `${action === 'stop' ? 'Stopping' : action === 'start' ? 'Starting' : action === 'restart' ? 'Restarting' : 'Resuming'} ${link.name}…`, async () => {
      try {
        await this.api(this.path() + '/' + link.id + (action === 'unlink' ? '' : '/' + action), action === 'unlink' ? 'DELETE' : 'POST', action === 'unlink' ? undefined : { environmentId: this.environmentId, workflowRevision: this.workflowRevision });
        if (revision !== this.revision) return;
        if (action === 'unlink') { this.links.update(links => links.filter(value => value.id !== link.id)); this.states.update(states => states.filter(value => value.link.id !== link.id)); }
        else await this.refresh();
      } catch (error) { if (revision === this.revision) this.error.set((error as Error).message); }
      finally { if (revision === this.revision) this.pending.update(ids => ids.filter(id => id !== link.id)); }
    });
  }
  dismiss() { if (this.saving()) return; this.open.set(false); this.hostController.abort(); this.returnFocus?.focus(); }
  ngOnDestroy() { this.revision++; this.controller.abort(); this.hostController.abort(); this.tasks.close(); if (this.timer) clearInterval(this.timer); window.removeEventListener('keydown', this.keyListener); }
}

import { Component, inject, computed, effect, signal, OnDestroy, ElementRef, ViewChild, provideZonelessChangeDetection } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { Project, Service, WorkflowView, LogEntry, LogResponse } from './types';
import { Terminals, TerminalWorkspace } from './terminals';
import { WorkflowYaml, YamlStatus } from './workflow-yaml';
import { Attachments, AttachmentSource } from './attachments';
import { Environments } from './environments';
import { WorkflowContainers } from './workflow-containers';
import { Databases } from './databases';
import { WorkflowGit } from './workflow-git';
import { Editor, type EditorDocument } from './editor';
import { Containers } from './containers';
import { Hosts } from './hosts';
import { Tunnels } from './tunnels';
import { Appearance } from './appearance';
import { LoadingService } from './loading/loading.service';
import { adjacentTerminal, workspaceShortcut, WorkspaceCommand } from './shortcuts';

const session = window.runtimeBaySession || new URLSearchParams(location.hash.slice(1)).get('session') || '';
if (location.hash) history.replaceState(null, '', location.pathname);

interface TunnelStatus { definition: { id: string; name: string }; state: 'running' | 'stopped' | 'error'; error: string | null; unreadError: boolean }

@Component({
  selector: 'app-root', standalone: true, imports: [FormsModule, DatePipe, Containers, Hosts, Tunnels, Terminals, WorkflowContainers, Databases, WorkflowGit, Editor, Environments, Attachments, WorkflowYaml],
  templateUrl: './app.html'
})
class App implements OnDestroy {
  view = signal<'runtime' | 'containers' | 'hosts' | 'tunnels' | 'terminal' | 'workflow-terminal' | 'databases' | 'git' | 'editor'>('runtime');
  pendingEditorDocument = signal<EditorDocument | null>(null);
  private editorListener = (event: Event) => { this.pendingEditorDocument.set((event as CustomEvent<EditorDocument>).detail); this.openEditor(); };
  readonly loader = inject(LoadingService);
  private loadingTasks = this.loader.scope();
  readonly appearance = new Appearance(session, this.loader);
  readonly sessionToken = session;
  readonly terminals = new TerminalWorkspace(session);
  projects = signal<Project[]>([]);
  workflows = signal<WorkflowView[]>([]);
  projectId = signal<string | null>(null);
  serviceId = signal<string | null>(null);
  connected = signal(false);
  loading = signal(true);
  error = signal('');
  pending = signal<string[]>([]);
  workflowOperation = signal(false);
  sheet = signal<'project' | 'service' | 'delete' | 'new' | null>(null);
  hostCreateRequested = signal(false);
  tunnelCreateRequested = signal(false);
  saving = signal(false);
  formError = signal('');
  logs = signal<LogEntry[]>([]);
  logFilter = signal('');
  follow = signal(true);
  project = computed(() => this.projects().find(p => p.id === this.projectId()) || null);
  workflow = computed(() => this.workflows().find(w => w.definition.projectId === this.projectId()) || null);
  environmentName = computed(() => this.workflow()?.definition.environments.find(e => e.id === this.workflow()?.definition.defaultEnvironmentId)?.name || 'Local');
  workflowActive = computed(() => this.project()?.services.some(s => this.active(s)) ?? false);
  workflowSaved(value: WorkflowView) { this.workflows.update(list => list.map(w => w.definition.id === value.definition.id ? value : w)); }
  yamlStatus = signal<YamlStatus>({ projectId: '', configured: false, managedServiceIds: [], running: false });
  yamlConfigured = computed(() => this.yamlStatus().projectId === this.projectId() && this.yamlStatus().configured);
  yamlRunning = computed(() => this.yamlStatus().projectId === this.projectId() && this.yamlStatus().running);
  yamlManaged(service: Service) { return this.yamlStatus().projectId === this.projectId() && this.yamlStatus().managedServiceIds.includes(service.id); }
  @ViewChild(WorkflowYaml) yamlConfig?: WorkflowYaml;
  @ViewChild(WorkflowContainers) workflowContainers?: WorkflowContainers;
  canStartAll = computed(() => !!this.workflow() && (this.project()?.services.filter(s => !this.active(s)).every(s => this.canStart(s)) ?? false));
  service = computed(() => this.project()?.services.find(s => s.id === this.serviceId()) || null);
  running = computed(() => this.projects().flatMap(p => p.services).filter(s => this.active(s)).length);
  activeTunnelCount = signal(0);
  unreadTunnelErrorCount = signal(0);
  serviceCount = computed(() => this.projects().reduce((sum, p) => sum + p.services.length, 0));
  visibleLogs = computed(() => {
    const query = this.logFilter().toLowerCase();
    return this.logs().filter(l => !query || l.message.toLowerCase().includes(query) || l.stream.includes(query));
  });
  projectForm = { name: '', path: '' };
  serviceForm = { name: '', command: '', directory: '.', port: null as number | null, dependsOnResourceId: '' };
  editId: string | null = null;
  private after = 0;
  private selectionRevision = 0;
  private refreshPromise?: Promise<void>;
  private destroyed = false;
  private timer: ReturnType<typeof setInterval>;
  private tunnelTimer: ReturnType<typeof setInterval>;
  private tunnelStatusReady = false;
  private knownUnreadTunnelErrors = new Set<string>();
  private controller = new AbortController();
  private returnFocus: HTMLElement | null = null;
  @ViewChild('logBody') logBody?: ElementRef<HTMLElement>;

  private folderListener = (event: Event) => {
    const path = (event as CustomEvent<string>).detail;
    this.projectForm.path = path;
    if (!this.projectForm.name) this.projectForm.name = path.split('/').filter(Boolean).at(-1) || 'Project';
  };
  private keyListener = (event: KeyboardEvent) => {
    if (this.loader.active) return;
    if (event.key === 'Tab' && this.sheet()) {
      const controls = Array.from(document.querySelectorAll<HTMLElement>('.modal button:not([disabled]), .modal input:not([disabled]), .modal textarea:not([disabled])'));
      const first = controls[0], last = controls.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    }
    if (event.key === 'Escape' && !this.saving()) this.sheet.set(null);
    if (this.appearance.open() || this.sheet() || document.querySelector('[role="dialog"]')) return;
    const command = workspaceShortcut(event);
    if (command) { event.preventDefault(); this.runCommand(command); }
    if (event.metaKey && !event.ctrlKey && !event.altKey && !event.shiftKey && event.key === ',' && !event.defaultPrevented) { event.preventDefault(); this.appearance.show(); }
  };
  private commandListener = (event: Event) => this.runCommand((event as CustomEvent<WorkspaceCommand>).detail);
  private runCommand(command: WorkspaceCommand) {
    if (this.loader.active || this.appearance.open() || this.sheet() || document.querySelector('.workflow-container-modal, [role="dialog"]')) return;
    if (command === 'new-terminal') void this.newTerminal();
    else if (command === 'import-workflow') this.openNew();
    else if (command === 'previous-terminal' || command === 'next-terminal') {
      const id = adjacentTerminal(this.terminals.tabs().map(tab => tab.id), this.terminals.selected(), command === 'previous-terminal' ? -1 : 1);
      if (id) this.selectTerminal(id);
    }
  }
  constructor() {
    effect(() => {
      if (this.sheet()) {
        this.returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
        setTimeout(() => (document.querySelector<HTMLElement>('.modal input, .modal textarea') || document.querySelector<HTMLElement>('.modal button'))?.focus(), 0);
      } else this.returnFocus?.focus();
    });
    window.addEventListener('runtimebay:edit', this.editorListener);
    window.addEventListener('runtimebay:folder', this.folderListener);
    window.addEventListener('keydown', this.keyListener);
    window.addEventListener('runtimebay:command', this.commandListener);
    void this.loadingTasks.run('Opening your workspace…', () => this.refresh());
    this.timer = setInterval(() => void this.refresh(), 1500);
    void this.refreshTunnelStatus();
    this.tunnelTimer = setInterval(() => void this.refreshTunnelStatus(), 1500);
  }
  ngOnDestroy() {
    this.terminals.destroy(); this.loadingTasks.close(); this.appearance.destroy(); this.destroyed = true; this.controller.abort(); clearInterval(this.timer); clearInterval(this.tunnelTimer);
    window.removeEventListener('runtimebay:edit', this.editorListener);
    window.removeEventListener('runtimebay:folder', this.folderListener);
    window.removeEventListener('keydown', this.keyListener);
    window.removeEventListener('runtimebay:command', this.commandListener);
  }
  private async refreshTunnelStatus() {
    try {
      const tunnels = await this.api<TunnelStatus[]>('/tunnels');
      this.activeTunnelCount.set(tunnels.filter(tunnel => tunnel.state === 'running').length);
      const unread = tunnels.filter(tunnel => tunnel.unreadError);
      if (this.view() === 'tunnels' && unread.length) {
        await this.acknowledgeTunnelErrors();
        this.knownUnreadTunnelErrors.clear();
        this.tunnelStatusReady = true;
        return;
      }
      this.unreadTunnelErrorCount.set(unread.length);
      const current = new Set(unread.map(tunnel => tunnel.definition.id));
      if (this.tunnelStatusReady && this.view() !== 'tunnels') for (const tunnel of unread) {
        if (this.knownUnreadTunnelErrors.has(tunnel.definition.id)) continue;
        window.webkit?.messageHandlers.runtimeBay.postMessage({
          action: 'tunnelFailure',
          id: tunnel.definition.id,
          title: tunnel.definition.name,
          body: tunnel.error || 'The SSH tunnel exited unexpectedly.'
        });
      }
      this.knownUnreadTunnelErrors = current;
      this.tunnelStatusReady = true;
    } catch { /* Workspace navigation remains available if tunnel status is temporarily offline. */ }
  }
  async openTunnels() {
    this.view.set('tunnels');
    await this.acknowledgeTunnelErrors();
    void this.refreshTunnelStatus();
  }
  private async acknowledgeTunnelErrors() {
    try {
      await this.api('/tunnels/acknowledge', 'POST', {});
      this.unreadTunnelErrorCount.set(0);
    } catch { /* Keep the unread badge if the acknowledgement did not reach the engine. */ }
  }
  private async api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
    const response = await fetch('/api' + path, {
      method, signal: this.controller.signal,
      headers: { Authorization: 'Bearer ' + session, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
      ...(body !== undefined ? { body: JSON.stringify(body) } : {})
    });
    const value = await response.json();
    if (!response.ok) throw new Error(value.error || 'Could not complete this operation');
    return value as T;
  }
  refresh(): Promise<void> {
    if (this.destroyed) return Promise.resolve();
    if (!this.refreshPromise) this.refreshPromise = this.readWorkspace().finally(() => { this.refreshPromise = undefined; });
    return this.refreshPromise;
  }
  refreshForeground() { return this.loadingTasks.run('Reconnecting to your workspace…', () => this.refresh()); }
  private async readWorkspace() {
    try {
      const [projects, workflows] = await Promise.all([this.api<Project[]>('/projects'), this.api<WorkflowView[]>('/workflows')]);
      this.workflows.set(workflows);
      this.projects.set(projects); this.connected.set(true);
      if (!projects.some(p => p.id === this.projectId())) this.selectProject(projects[0]?.id || null, false);
      const selected = this.service();
      if (selected && this.view() === 'runtime') await this.refreshLogs(this.projectId()!, selected.id);
    } catch (e) { if (!this.destroyed) { this.connected.set(false); this.error.set((e as Error).message); } }
    finally { this.loading.set(false); }
  }
  private async refreshLogs(project: string, service: string) {
    const revision = this.selectionRevision;
    const response = await this.api<LogResponse>(`/projects/${project}/services/${service}/logs?after=${this.after}`);
    if (revision !== this.selectionRevision || this.projectId() !== project || this.serviceId() !== service) return;
    const fresh = response.entries.filter(line => line.sequence > this.after);
    this.after = Math.max(this.after, response.next);
    if (fresh.length) {
      this.logs.update(entries => [...(response.truncated ? [] : entries), ...fresh].slice(-1000));
      this.scrollLogs();
    }
  }
  inspectAttachment(source: AttachmentSource) {
    if (source.kind === 'terminal') { this.view.set('terminal'); void this.terminals.create(this.projectId(), source.sourceId); return; }
    if (!this.projects().some(p => p.id === source.projectId)) { this.error.set('The source workflow is missing. Refresh or rebind this resource.'); return; }
    this.selectProject(source.projectId);
    if (source.kind === 'database') this.view.set('databases');
    else { this.view.set('runtime'); this.selectService(source.sourceId); }
  }
  async newTerminal() {
    this.view.set('terminal');
    await this.terminals.create(this.projectId());
  }
  workflowTerminal() { return this.terminals.tabs().find(tab => tab.workflowId === this.projectId()) || null; }
  sidebarTerminals() { return this.terminals.tabs().filter(tab => !tab.workflowId); }
  async openWorkflowTerminal() {
    const projectId=this.projectId(); if(!projectId)return;
    const tab=this.workflowTerminal() || await this.terminals.createWorkflow(projectId);
    if(tab){this.terminals.selected.set(tab.id);this.view.set('workflow-terminal');}
  }
  async openHostTerminal(hostId: string) { this.view.set('terminal'); await this.terminals.create(null, hostId); }
  selectTerminal(id: string) { this.terminals.selected.set(id); this.view.set('terminal'); }
  selectProject(id: string | null, switchView = true) {
    if (switchView && !['databases', 'git', 'editor'].includes(this.view())) this.view.set('runtime');
    this.projectId.set(id); this.selectService(this.project()?.services[0]?.id || null);
  }
  selectService(id: string | null) {
    if (this.serviceId() === id) return;
    this.selectionRevision++;
    this.serviceId.set(id); this.after = 0; this.logs.set([]); this.logFilter.set('');
    if (id && this.projectId() && this.view() === 'runtime') void this.loadingTasks.run('Loading service output…', () => this.refreshLogs(this.projectId()!, id)).catch(() => {});
  }
  active(service: Service) { return ['running', 'starting', 'stopping'].includes(service.state); }
  busy(service: Service) { return this.yamlRunning() || this.pending().includes(service.id) || service.state === 'starting' || service.state === 'stopping'; }
  canStart(service: Service) { return this.workflow()?.resources.some(r => r.resourceId === service.id && r.type === 'service' && r.status === 'local') ?? false; }
  async action(service: Service, action: 'start' | 'stop' | 'restart', withinWorkflow = false) {
    const project = this.project();
    if (!project || this.busy(service) || this.workflowOperation() && !withinWorkflow) return;
    if (this.yamlManaged(service) && action !== 'stop') { if (action === 'start') await this.yamlConfig?.showPlan(service.id); else this.error.set('Stop this YAML-managed process, then review its run to restart with the correct environment and dependencies.'); return; }
    if (action !== 'stop' && !this.canStart(service)) { this.error.set('Resolve this service’s machine binding before starting. Remote service execution is not available yet.'); return; }
    const releaseLoading = this.loadingTasks.begin(`${action === 'start' ? 'Starting' : action === 'stop' ? 'Stopping' : 'Restarting'} ${service.name}…`);
    this.pending.update(ids => [...ids, service.id]); this.error.set('');
    this.selectService(service.id);
    try {
      if (action === 'start' && !this.yamlManaged(service)) await this.startDependencies(service, new Set());
      await this.api(`/projects/${project.id}/services/${service.id}/${action}`, 'POST', { environmentId: this.workflow()?.definition.defaultEnvironmentId, workflowRevision: this.workflow()?.revision });
      await this.refresh();
    } catch (e) { this.error.set((e as Error).message); }
    finally { this.pending.update(ids => ids.filter(id => id !== service.id)); releaseLoading(); }
  }
  async all(action: 'start' | 'stop') {
    if (action === 'start' && this.yamlConfigured() && this.project()?.services.some(service => this.yamlManaged(service))) { await this.yamlConfig?.showPlan(); return; }
    if (action === 'start' && !this.canStartAll()) { this.error.set('Some services have unavailable targets. Resolve their bindings before starting this workflow.'); return; }
    if (!this.project()) return;
    const ordered = this.orderedServices();
    const services = action === 'start' ? ordered.filter(s => !this.active(s)) : ordered.filter(s => this.active(s)).reverse();
    this.error.set(''); this.workflowOperation.set(true);
    try {
      if (action === 'start') await this.workflowContainers?.startAll();
      for (const service of services) {
        await this.action(service, action, true);
        if (this.error()) throw new Error(this.error());
      }
      if (action === 'stop') await this.workflowContainers?.stopAll();
      await this.refresh();
    } catch (error) { this.error.set((error as Error).message); }
    finally { this.workflowOperation.set(false); }
  }
  openProject() { this.view.set('runtime'); this.projectForm = { name: '', path: '' }; this.formError.set(''); this.sheet.set('project'); }
  openEditor() { this.terminals.fullscreen.set(false); this.view.set('editor'); }
  openNew() { this.sheet.set('new'); }
  newWorkflow() { this.sheet.set(null); this.openProject(); }
  newHost() { this.sheet.set(null); this.hostCreateRequested.set(true); this.view.set('hosts'); }
  newTunnel() { this.sheet.set(null); this.tunnelCreateRequested.set(true); this.view.set('tunnels'); }
  chooseFolder() { window.webkit?.messageHandlers.runtimeBay.postMessage('chooseFolder'); }
  native() { return !!window.runtimeBayNative; }
  openService(service?: Service) {
    this.editId = service?.id || null;
    this.serviceForm = service ? { name: service.name, command: service.command, directory: service.directory, port: service.port, dependsOnResourceId: service.dependsOnResourceId || '' }
      : { name: '', command: '', directory: '.', port: null, dependsOnResourceId: '' };
    this.formError.set(''); this.sheet.set('service');
  }
  async saveProject() {
    const releaseLoading = this.loadingTasks.begin('Importing project…');
    this.saving.set(true); this.formError.set('');
    try {
      const project = await this.api<Project>('/projects', 'POST', this.projectForm);
      this.projects.set(await this.api<Project[]>('/projects'));
      this.selectProject(project.id); this.sheet.set(null);
    } catch (e) { this.formError.set((e as Error).message); }
    finally { this.saving.set(false); releaseLoading(); }
  }
  async saveService() {
    const project = this.project(); if (!project) return;
    const releaseLoading = this.loadingTasks.begin('Saving service…');
    this.saving.set(true); this.formError.set('');
    try {
      const service = await this.api<Service>(`/projects/${project.id}/services${this.editId ? '/' + this.editId : ''}`,
        this.editId ? 'PUT' : 'POST', this.serviceForm);
      this.projects.set(await this.api<Project[]>('/projects'));
      this.selectService(service.id); this.sheet.set(null);
    } catch (e) { this.formError.set((e as Error).message); }
    finally { this.saving.set(false); releaseLoading(); }
  }
  dependencyChoices() {
    return this.workflow()?.definition.resources.filter(resource => resource.id !== this.editId)
      .map(resource => ({ id: resource.id, name: resource.name, type: resource.type })) || [];
  }
  orderedServices() {
    const services = this.project()?.services || [], byId = new Map(services.map(service => [service.id, service]));
    const result: Service[] = [], visiting = new Set<string>(), visited = new Set<string>();
    const visit = (service: Service) => {
      if (visited.has(service.id) || visiting.has(service.id)) return;
      visiting.add(service.id);
      const dependency = service.dependsOnResourceId ? byId.get(service.dependsOnResourceId) : null;
      if (dependency) visit(dependency);
      visiting.delete(service.id); visited.add(service.id); result.push(service);
    };
    services.forEach(visit); return result;
  }
  private async startDependencies(service: Service, visited: Set<string>) {
    if (visited.has(service.id)) return; visited.add(service.id);
    const dependencyId = service.dependsOnResourceId; if (!dependencyId) return;
    const dependency = this.project()?.services.find(item => item.id === dependencyId);
    if (dependency) {
      if (dependency.state !== 'running') {
        if (this.yamlManaged(dependency)) throw new Error(`Start the YAML-managed dependency ${dependency.name} through the reviewed workflow run first.`);
        await this.startDependencies(dependency, visited);
        await this.api(`/projects/${this.projectId()}/services/${dependency.id}/start`, 'POST', { environmentId: this.workflow()?.definition.defaultEnvironmentId, workflowRevision: this.workflow()?.revision });
      }
    } else await this.workflowContainers?.startDependency(dependencyId);
  }
  async startRemainingWorkflowServices() {
    this.workflowOperation.set(true);
    try {
      for (const service of this.orderedServices()) if (!this.yamlManaged(service) && !this.active(service)) {
        await this.action(service, 'start', true);
        if (this.error()) throw new Error(this.error());
      }
    } finally { this.workflowOperation.set(false); }
  }
  async moveService(id: string, delta: -1 | 1) {
    const project = this.project(); if (!project) return;
    const ordered = [...project.services], from = ordered.findIndex(service => service.id === id), to = from + delta;
    if (from < 0 || to < 0 || to >= ordered.length || this.pending().length || this.workflowOperation() || this.yamlRunning()) return;
    [ordered[from], ordered[to]] = [ordered[to], ordered[from]]; this.error.set('');
    try { await this.api(`/projects/${project.id}/services/order`, 'PUT', { ids: ordered.map(service => service.id) }); this.projects.update(items => items.map(item => item.id === project.id ? { ...item, services: ordered } : item)); }
    catch (error) { this.error.set((error as Error).message); }
  }
  startWorkflowContainers = async () => { this.workflowOperation.set(true); try { await this.workflowContainers?.startAll(); } finally { this.workflowOperation.set(false); } };
  async deleteService() {
    const project = this.project(), service = this.service(); if (!project || !service) return;
    const releaseLoading = this.loadingTasks.begin('Removing service…');
    this.saving.set(true); this.formError.set('');
    try {
      await this.api(`/projects/${project.id}/services/${service.id}`, 'DELETE');
      this.projects.set(await this.api<Project[]>('/projects'));
      this.selectService(this.project()?.services[0]?.id || null); this.sheet.set(null);
    } catch (e) { this.formError.set((e as Error).message); }
    finally { this.saving.set(false); releaseLoading(); }
  }
  askDelete() { this.formError.set(''); this.sheet.set('delete'); }
  portLabel(service: Service) {
    if (!service.portInfo) return 'No port configured';
    if (service.portInfo.status === 'available') return `:${service.port} · no listener`;
    if (service.portInfo.status === 'unknown') return `:${service.port} · unavailable`;
    return `:${service.port} · ${service.portInfo.owners.some(o => o.managed) ? 'listening' : 'another process'}`;
  }
  elapsed(service: Service) {
    if (!service.startedAt || !this.active(service)) return service.exitCode === null ? 'Ready to start' : `Exit code ${service.exitCode}`;
    const seconds = Math.floor((Date.now() - Date.parse(service.startedAt)) / 1000);
    return seconds < 60 ? `${seconds}s uptime` : `${Math.floor(seconds / 60)}m uptime`;
  }
  onLogScroll() {
    const el = this.logBody?.nativeElement;
    if (el) this.follow.set(el.scrollHeight - el.scrollTop - el.clientHeight < 60);
  }
  scrollLogs() { if (this.follow()) setTimeout(() => { const el = this.logBody?.nativeElement; if (el) el.scrollTop = el.scrollHeight; }, 0); }
  toggleFollow() { this.follow.update(v => !v); this.scrollLogs(); }
  async copyLogs() {
    const text = this.visibleLogs().map(l => `${l.time} [${l.stream}] ${l.message}`).join('\n');
    try { await navigator.clipboard.writeText(text); }
    catch { this.error.set('Select and copy the log text with ⌘C.'); }
  }
}
bootstrapApplication(App, { providers: [provideZonelessChangeDetection()] }).catch(console.error);

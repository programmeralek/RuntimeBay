import { Component, inject, computed, effect, signal, OnDestroy, ElementRef, ViewChild, provideZonelessChangeDetection } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { Project, Service, WorkflowView, LogEntry, LogResponse } from './types';
import { Terminals, TerminalWorkspace } from './terminals';
import type { ShellTab } from './terminals';
import { WorkflowYaml, YamlStatus } from './workflow-yaml';
import { Attachments, AttachmentSource } from './attachments';
import { Environments } from './environments';
import { WorkflowContainers } from './workflow-containers';
import { Databases } from './databases';
import { WorkflowGit } from './workflow-git';
import { Editor, type EditorDocument } from './editor';
import { BrandMarkDirective } from './brand-mark.directive';
import { Containers } from './containers';
import { Hosts } from './hosts';
import { Tunnels } from './tunnels';
import { Appearance } from './appearance';
import { LoadingService } from './loading/loading.service';
import { adjacentTerminal, workspaceShortcut, WorkspaceCommand } from './shortcuts';
import './workflow-cockpit.css';

const session = window.runtimeBaySession || new URLSearchParams(location.hash.slice(1)).get('session') || '';
if (location.hash) history.replaceState(null, '', location.pathname);

interface TunnelStatus { definition: { id: string; name: string }; state: 'running' | 'stopped' | 'error'; error: string | null; unreadError: boolean }
interface TemporaryWorkspace { terminalId: string; name: string; directory: string; document: EditorDocument | null; mode: 'terminal' | 'editor'; dirty: boolean }
interface TerminalEditorRequest { document: EditorDocument; terminalId: string; terminalName: string; directory: string }

@Component({
  selector: 'app-root', standalone: true, imports: [FormsModule, DatePipe, Containers, Hosts, Tunnels, Terminals, WorkflowContainers, Databases, WorkflowGit, Editor, Environments, Attachments, WorkflowYaml, BrandMarkDirective],
  templateUrl: './app.html'
})
class App implements OnDestroy {
  view = signal<'runtime' | 'containers' | 'hosts' | 'tunnels' | 'terminal' | 'workflow-terminal' | 'databases' | 'git' | 'editor' | 'temporary'>('runtime');
  pendingEditorDocument = signal<EditorDocument | null>(null);
  temporaryWorkspaces = signal<TemporaryWorkspace[]>([]);
  activeTemporaryId = signal<string | null>(null);
  activeTemporary = computed(() => this.temporaryWorkspaces().find(item => item.terminalId === this.activeTemporaryId()) || null);
  temporaryRenameId = signal<string | null>(null);
  temporaryNameDraft = signal('');
  temporaryError = signal('');
  temporaryBusy = signal(false);
  private editorListener = (event: Event) => this.openTerminalEditor((event as CustomEvent<TerminalEditorRequest>).detail);
  readonly loader = inject(LoadingService);
  private loadingTasks = this.loader.scope();
  readonly appearance = new Appearance(session, this.loader);
  readonly sessionToken = session;
  readonly terminals = new TerminalWorkspace(session);
  projects = signal<Project[]>([]);
  workflows = signal<WorkflowView[]>([]);
  projectId = signal<string | null>(null);
  private readonly workflowViews = this.readWorkflowViews();
  private readonly workflowTerminalIds = this.readWorkflowTerminalIds();
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
  readonly cockpitPanelDefinitions = [
    { id: 'targets', label: 'Resource targets' },
    { id: 'services', label: 'Services' },
    { id: 'attachments', label: 'Attached resources' },
    { id: 'containers', label: 'Containers' },
    { id: 'inspector', label: 'Service logs' },
  ] as const;
  readonly cockpitArrangeOpen = signal(false);
  private readonly cockpitLayouts = signal<Record<string, { order: string[]; hidden: string[] }>>({});
  project = computed(() => this.projects().find(p => p.id === this.projectId()) || null);
  workflow = computed(() => this.workflows().find(w => w.definition.projectId === this.projectId()) || null);
  environmentName = computed(() => this.workflow()?.definition.environments.find(e => e.id === this.workflow()?.definition.defaultEnvironmentId)?.name || 'Local');
  workflowSidebarSubtitle(project: Project) {
    const workflow = this.workflows().find(item => item.definition.projectId === project.id);
    if (workflow && workflow.definition.environments.length > 1) {
      return workflow.definition.environments.find(environment => environment.id === workflow.definition.defaultEnvironmentId)?.name || 'Environment';
    }
    return `${project.services.length} ${project.services.length === 1 ? 'service' : 'services'}`;
  }
  private cockpitLayout() {
    const id = this.projectId() || '';
    return this.cockpitLayouts()[id] || { order: this.cockpitPanelDefinitions.map(panel => panel.id), hidden: [] };
  }
  cockpitOrder(id: string) { return this.cockpitLayout().order.indexOf(id); }
  cockpitHidden(id: string) { return this.cockpitLayout().hidden.includes(id); }
  moveCockpitPanel(id: string, direction: -1 | 1) {
    const projectId = this.projectId(); if (!projectId) return;
    const current = this.cockpitLayout(), order = [...current.order], index = order.indexOf(id), next = index + direction;
    if (index < 0 || next < 0 || next >= order.length) return;
    [order[index], order[next]] = [order[next], order[index]];
    this.cockpitLayouts.update(layouts => ({ ...layouts, [projectId]: { ...current, order } }));
  }
  toggleCockpitPanel(id: string) {
    const projectId = this.projectId(); if (!projectId) return;
    const current = this.cockpitLayout(), hidden = current.hidden.includes(id) ? current.hidden.filter(value => value !== id) : [...current.hidden, id];
    this.cockpitLayouts.update(layouts => ({ ...layouts, [projectId]: { ...current, hidden } }));
  }
  resetCockpitLayout() {
    const projectId = this.projectId(); if (!projectId) return;
    this.cockpitLayouts.update(layouts => ({ ...layouts, [projectId]: { order: this.cockpitPanelDefinitions.map(panel => panel.id), hidden: [] } }));
  }
  workflowActive = computed(() => this.project()?.services.some(s => this.active(s)) ?? false);
  workflowSaved(value: WorkflowView) { this.workflows.update(list => list.map(w => w.definition.id === value.definition.id ? value : w)); }
  showWorkflowEnvironmentSelector() { return !!this.project() && !!this.workflow() && ['runtime', 'databases', 'workflow-terminal', 'git', 'editor'].includes(this.view()); }
  toggleEnvironmentMenu() { this.environmentError.set(''); this.environmentMenuOpen.update(open => !open); }
  async selectEnvironment(environmentId: string) {
    if (this.environmentBusy() || !this.environmentManager) return;
    this.environmentBusy.set(true); this.environmentError.set('');
    try { await this.environmentManager.activate(environmentId); this.environmentMenuOpen.set(false); }
    catch (error) { this.environmentError.set((error as Error).message); }
    finally { this.environmentBusy.set(false); }
  }
  addWorkflowEnvironment() { this.environmentMenuOpen.set(false); this.environmentError.set(''); this.environmentManager?.show(true); }
  manageWorkflowEnvironments() { this.environmentMenuOpen.set(false); this.environmentError.set(''); this.environmentManager?.show(); }
  deleteWorkflowEnvironment(environmentId: string) { this.environmentMenuOpen.set(false); this.environmentError.set(''); this.environmentManager?.requestRemove(environmentId); }
  yamlStatus = signal<YamlStatus>({ projectId: '', configured: false, managedServiceIds: [], running: false });
  yamlConfigured = computed(() => this.yamlStatus().projectId === this.projectId() && this.yamlStatus().configured);
  yamlRunning = computed(() => this.yamlStatus().projectId === this.projectId() && this.yamlStatus().running);
  yamlManaged(service: Service) { return this.yamlStatus().projectId === this.projectId() && this.yamlStatus().managedServiceIds.includes(service.id); }
  @ViewChild(WorkflowYaml) yamlConfig?: WorkflowYaml;
  @ViewChild(WorkflowContainers) workflowContainers?: WorkflowContainers;
  @ViewChild(Environments) environmentManager?: Environments;
  canStartAll = computed(() => !!this.workflow() && (this.project()?.services.filter(s => !this.active(s)).every(s => this.canStart(s)) ?? false));
  service = computed(() => this.project()?.services.find(s => s.id === this.serviceId()) || null);
  running = computed(() => this.projects().flatMap(p => p.services).filter(s => this.active(s)).length);
  activeTunnelCount = signal(0);
  unreadTunnelErrorCount = signal(0);
  environmentMenuOpen = signal(false);
  environmentBusy = signal(false);
  environmentError = signal('');
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
    if (event.key === 'Escape' && this.environmentMenuOpen()) { event.preventDefault(); this.environmentMenuOpen.set(false); return; }
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
  private environmentOutsideListener = (event: MouseEvent) => {
    if (!(event.target instanceof Element) || !event.target.closest('.environment-switcher')) this.environmentMenuOpen.set(false);
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
    window.addEventListener('click', this.environmentOutsideListener);
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
    window.removeEventListener('click', this.environmentOutsideListener);
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
      if (!projects.some(p => p.id === this.projectId())) this.selectProject(projects[0]?.id || null);
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
    if (source.kind === 'database') this.setWorkflowView('databases');
    else { this.setWorkflowView('runtime'); this.selectService(source.sourceId); }
  }
  async newTerminal() {
    this.view.set('terminal');
    await this.terminals.create(this.projectId());
  }
  workflowTerminals() { return this.terminals.tabs().filter(tab => tab.workflowId === this.projectId()); }
  workflowTerminal() { return this.workflowTerminals().find(tab => tab.id === this.terminals.selected()) || this.workflowTerminals()[0] || null; }
  sidebarTerminals() { const merged = new Set(this.temporaryWorkspaces().map(item => item.terminalId)); return this.terminals.tabs().filter(tab => !tab.workflowId && !merged.has(tab.id)); }
  async openWorkflowTerminal() {
    const projectId=this.projectId(); if(!projectId)return;
    const tab=this.workflowTerminals().find(value => value.id === this.workflowTerminalIds[projectId]) || this.workflowTerminals()[0] || await this.terminals.createWorkflow(projectId);
    if(tab){this.terminals.selected.set(tab.id);this.view.set('workflow-terminal');this.rememberWorkflowView();}
  }
  selectWorkflowTerminal(id: string) {
    const projectId = this.projectId();
    if (!projectId) return;
    const tab = this.terminals.tabs().find(value => value.id === id && value.workflowId === projectId);
    if (!tab) return;
    this.terminals.selected.set(tab.id);
    this.workflowTerminalIds[projectId] = tab.id;
    try { localStorage.setItem('runtimebay.workflowTerminalIds', JSON.stringify(this.workflowTerminalIds)); } catch { /* Selection still works when storage is unavailable. */ }
  }
  async addWorkflowTerminal() {
    const projectId = this.projectId(); if (!projectId || this.terminals.creating()) return;
    const tab = await this.terminals.createWorkflow(projectId);
    if (tab) this.selectWorkflowTerminal(tab.id);
  }
  async openHostTerminal(hostId: string) { this.view.set('terminal'); await this.terminals.create(null, hostId); }
  selectTerminal(id: string) { this.terminals.selected.set(id); this.view.set('terminal'); }
  private openTerminalEditor(request: TerminalEditorRequest) {
    if (!request?.document) return;
    const terminal = this.terminals.tabs().find(tab => tab.id === request.terminalId);
    if (terminal?.workflowId) {
      this.selectProject(terminal.workflowId, false);
      this.pendingEditorDocument.set(request.document);
      this.view.set('editor');
      this.rememberWorkflowView();
      return;
    }
    if (!terminal) return;
    const workspace: TemporaryWorkspace = { terminalId: request.terminalId, name: request.terminalName, directory: request.directory, document: request.document, mode: 'editor', dirty: false };
    this.temporaryWorkspaces.update(items => items.some(item => item.terminalId === workspace.terminalId)
      ? items.map(item => item.terminalId === workspace.terminalId ? { ...item, name: workspace.name, directory: workspace.directory, document: workspace.document, mode: 'editor' } : item)
      : [...items, workspace]);
    this.activeTemporaryId.set(workspace.terminalId);
    this.terminals.selected.set(workspace.terminalId);
    this.terminals.fullscreen.set(false);
    this.view.set('temporary');
  }
  showTemporaryWorkspace(terminalId: string) {
    const item = this.temporaryWorkspaces().find(value => value.terminalId === terminalId);
    if (!item) return;
    this.activeTemporaryId.set(terminalId);
    if (this.terminals.tabs().some(tab => tab.id === terminalId)) this.terminals.selected.set(terminalId);
    this.terminals.fullscreen.set(false);
    this.view.set('temporary');
  }
  setTemporaryMode(mode: 'terminal' | 'editor') {
    const id = this.activeTemporaryId();
    if (!id) return;
    this.temporaryWorkspaces.update(items => items.map(item => item.terminalId === id ? { ...item, mode } : item));
    if (mode === 'terminal' && this.terminals.tabs().some(tab => tab.id === id)) this.terminals.selected.set(id);
    this.view.set('temporary');
  }
  setTemporaryDirty(terminalId: string, dirty: boolean) {
    this.temporaryWorkspaces.update(items => items.map(item => item.terminalId === terminalId ? { ...item, dirty } : item));
  }
  beginTemporaryRename() {
    const item = this.activeTemporary(); if (!item) return;
    this.temporaryNameDraft.set(item.name); this.temporaryError.set(''); this.temporaryRenameId.set(item.terminalId);
  }
  cancelTemporaryRename() { if (!this.temporaryBusy()) this.temporaryRenameId.set(null); }
  async promoteTemporary() {
    const item = this.activeTemporary(); if (!item || this.temporaryBusy()) return;
    const name = this.temporaryNameDraft().trim();
    if (!name || name.length > 80) { this.temporaryError.set('Enter a workflow name of up to 80 characters.'); return; }
    if (item.dirty) { this.temporaryError.set('Save your editor changes before accepting and creating this workflow.'); return; }
    this.temporaryBusy.set(true); this.temporaryError.set('');
    try {
      const project = await this.api<Project>('/projects', 'POST', { name, path: item.directory });
      const terminal = await this.terminals.api<ShellTab>('/' + item.terminalId + '/promote', 'POST', { projectId: project.id, name });
      this.terminals.tabs.update(tabs => tabs.map(tab => tab.id === terminal.id ? terminal : tab));
      this.temporaryWorkspaces.update(items => items.filter(value => value.terminalId !== item.terminalId));
      this.activeTemporaryId.set(null); this.temporaryRenameId.set(null);
      await this.refresh(); this.selectProject(project.id);
    } catch (error) { this.temporaryError.set((error as Error).message); }
    finally { this.temporaryBusy.set(false); }
  }
  private readWorkflowViews(): Record<string, 'runtime' | 'workflow-terminal' | 'databases' | 'git' | 'editor'> {
    try {
      const value = JSON.parse(localStorage.getItem('runtimebay.workflowViews') || '{}');
      if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
      const allowed = new Set(['runtime', 'workflow-terminal', 'databases', 'git', 'editor']);
      return Object.fromEntries(Object.entries(value).filter(([key, view]) => !!key && typeof view === 'string' && allowed.has(view))) as Record<string, 'runtime' | 'workflow-terminal' | 'databases' | 'git' | 'editor'>;
    } catch { return {}; }
  }
  private readWorkflowTerminalIds(): Record<string, string> {
    try {
      const value = JSON.parse(localStorage.getItem('runtimebay.workflowTerminalIds') || '{}');
      if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
      return Object.fromEntries(Object.entries(value).filter(([key, id]) => !!key && typeof id === 'string')) as Record<string, string>;
    } catch { return {}; }
  }
  private rememberWorkflowView() {
    const id = this.projectId(), view = this.view();
    if (!id || !['runtime', 'workflow-terminal', 'databases', 'git', 'editor'].includes(view)) return;
    this.workflowViews[id] = view as 'runtime' | 'workflow-terminal' | 'databases' | 'git' | 'editor';
    if (view === 'workflow-terminal' && this.workflowTerminals().some(tab => tab.id === this.terminals.selected())) {
      this.workflowTerminalIds[id] = this.terminals.selected()!;
      try { localStorage.setItem('runtimebay.workflowTerminalIds', JSON.stringify(this.workflowTerminalIds)); } catch { /* View switching still works if storage is unavailable. */ }
    }
    try { localStorage.setItem('runtimebay.workflowViews', JSON.stringify(this.workflowViews)); } catch { /* Workflow switching still works if storage is unavailable. */ }
  }
  setWorkflowView(view: 'runtime' | 'databases' | 'git' | 'editor') {
    this.view.set(view);
    this.rememberWorkflowView();
  }
  private restoreWorkflowView(id: string | null) {
    if (!id) return;
    const saved = this.workflowViews[id];
    if (saved === 'workflow-terminal') {
      const preferredId = this.workflowTerminalIds[id];
      const tabs = this.terminals.tabs().filter(value => value.workflowId === id);
      const tab = tabs.find(value => value.id === preferredId) || tabs[0];
      if (tab) { this.terminals.selected.set(tab.id); this.view.set('workflow-terminal'); }
      else this.view.set('runtime');
      return;
    }
    this.view.set(saved || 'runtime');
  }
  selectProject(id: string | null, switchView = true) {
    if (this.projectId() === id) { if (switchView) this.restoreWorkflowView(id); return; }
    this.rememberWorkflowView();
    this.projectId.set(id);
    this.selectService(this.project()?.services[0]?.id || null);
    if (switchView) this.restoreWorkflowView(id);
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
  openEditor() { this.terminals.fullscreen.set(false); this.setWorkflowView('editor'); }
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

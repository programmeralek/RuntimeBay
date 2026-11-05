import { Component, Input, Output, EventEmitter, OnInit, OnDestroy, OnChanges, SimpleChanges, inject, signal, computed } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { LoadingService } from './loading/loading.service';

interface Machine { id: string; name: string; address: string; user: string; port: number | null; local: boolean }
interface Registry { hosts: Machine[]; defaultHostId: string }
interface Tool { id: string; name: string; state: 'available' | 'missing' | 'error'; path: string; version: string }
interface Inventory { hostId: string; checkedAt: string; hostname: string; os: string; architecture: string; tools: Tool[]; processes: {pid: number; name: string}[] }
interface Inspection { state: 'idle' | 'scanning' | 'ready' | 'error'; inventory: Inventory | null; error: string | null }

@Component({ selector: 'app-hosts', standalone: true, imports: [FormsModule, DatePipe], templateUrl: './hosts.html' })
export class Hosts implements OnInit, OnDestroy, OnChanges {
  @Input() session = '';
  @Input() terminalBusy = false;
  @Input() createRequested = false;
  @Output() openTerminal = new EventEmitter<string>();
  @Output() createHandled = new EventEmitter<void>();
  private tasks = inject(LoadingService).scope();
  registry = signal<Registry>({ hosts: [], defaultHostId: 'local' });
  selectedId = signal<string | null>(null);
  selected = computed(() => this.registry().hosts.find(host => host.id === this.selectedId()) || null);
  status = signal<Inspection>({ state: 'idle', inventory: null, error: null });
  tab = signal<'tooling' | 'processes'>('tooling');
  filter = signal('');
  readonly toolLogos: Readonly<Record<string, string>> = Object.freeze({
    python3: 'python', pip3: 'python', java: 'java', javac: 'java',
    node: 'nodejs', npm: 'npm', aws: 'amazonwebservices', git: 'git',
    docker: 'docker', mvn: 'maven', gradle: 'gradle', pnpm: 'pnpm', yarn: 'yarn', go: 'go'
  });
  tools = computed(() => this.status().inventory?.tools.filter(tool => `${tool.name} ${tool.version} ${tool.path}`.toLowerCase().includes(this.filter().toLowerCase())) || []);
  available = computed(() => this.status().inventory?.tools.filter(tool => tool.state === 'available').length || 0);
  error = signal('');
  formError = signal('');
  loading = signal(true);
  saving = signal(false);
  modal = signal<'host' | 'remove' | null>(null);
  editing = signal<Machine | null>(null);
  removing = signal<Machine | null>(null);
  authentication = signal<{sessionPassword: boolean; remembered: boolean; keychainAvailable: boolean} | null>(null);
  form = { name: '', address: '', user: '', port: null as number | null, password: '', clearPassword: false, rememberPassword: false };
  private controller = new AbortController();
  private revision = 0;
  private timer?: ReturnType<typeof setInterval>;
  private previousFocus: HTMLElement | null = null;
  private keyListener = (event: KeyboardEvent) => {
    if (!this.modal() || this.saving()) return;
    if (event.key === 'Escape') { event.preventDefault(); this.close(); }
    if (event.key === 'Tab') {
      const controls = Array.from(document.querySelectorAll<HTMLElement>('.machine-modal button:not([disabled]), .machine-modal input:not([disabled])'));
      if (event.shiftKey && document.activeElement === controls[0]) { event.preventDefault(); controls.at(-1)?.focus(); }
      else if (!event.shiftKey && document.activeElement === controls.at(-1)) { event.preventDefault(); controls[0]?.focus(); }
    }
  };
  ngOnInit() {
    window.addEventListener('keydown', this.keyListener);
    void this.load(); this.timer = setInterval(() => { if (this.selectedId() && this.status().state === 'scanning') void this.readStatus(); }, 1500);
  }
  ngOnChanges(changes: SimpleChanges) { if (changes['createRequested']?.currentValue) { this.open(); this.createHandled.emit(); } }
  private async api<T>(path = '', method = 'GET', body?: unknown): Promise<T> {
    const response = await fetch('/api/hosts' + path, { method, signal: this.controller.signal,
      headers: { Authorization: 'Bearer ' + this.session, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    const value = await response.json(); if (!response.ok) throw new Error(value.error || 'Could not complete the host operation'); return value;
  }
  async load() {
    try { this.registry.set(await this.api<Registry>()); this.error.set(''); }
    catch (e) { if (!this.controller.signal.aborted) this.error.set((e as Error).message); }
    finally { this.loading.set(false); }
  }
  address(host: Machine | null) {
    if (!host) return '';
    return host.local ? 'Local machine' : `${host.user ? host.user + '@' : ''}${host.address}${host.port ? ':' + host.port : ''}`;
  }
  async viewHost(host: Machine) {
    this.revision++; this.authentication.set(null); this.selectedId.set(host.id); void this.readAuthentication(host); this.filter.set(''); this.tab.set('tooling'); this.status.set({ state: 'idle', inventory: null, error: null });
    const current = await this.readStatus();
    if (current?.state === 'idle' && this.selectedId() === host.id) void this.inspect();
  }
  private async readStatus() {
    const id = this.selectedId(), revision = this.revision; if (!id) return;
    try {
      const state = await this.api<Inspection>('/' + id + '/inventory');
      if (id === this.selectedId() && revision === this.revision) this.status.set(state);
      return state;
    } catch (e) { if (!this.controller.signal.aborted && revision === this.revision) this.error.set((e as Error).message); return; }
  }
  private async readAuthentication(host: Machine) {
    if(host.local) return;
    try { const value = await this.api<{sessionPassword: boolean; remembered: boolean; keychainAvailable: boolean}>('/' + host.id + '/authentication');
      if (this.selectedId() === host.id || this.editing()?.id === host.id) { this.authentication.set(value); if (this.editing()?.id === host.id) this.form.rememberPassword = value.remembered; }
    } catch { /* Credential availability is advisory; never prevents using keys. */ }
  }
  async reconnect(host: Machine) { this.open(host); requestAnimationFrame(() => document.querySelector<HTMLInputElement>('.machine-modal input[type=password]')?.focus()); }
  async inspect() {
    const id = this.selectedId(), revision = this.revision; if (!id || this.status().state === 'scanning') return;
    this.error.set('');
    try { const result = await this.api<Inspection>('/' + id + '/inventory', 'POST', {}); if (revision === this.revision) this.status.set(result); }
    catch (e) { if (!this.controller.signal.aborted && revision === this.revision) this.error.set((e as Error).message); }
  }
  back() { this.revision++; this.selectedId.set(null); this.error.set(''); }
  open(host?: Machine) {
    this.previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    this.editing.set(host || null); this.form = host ? { name: host.name, address: host.address, user: host.user, port: host.port, password: '', clearPassword: false, rememberPassword: false } : { name: '', address: '', user: '', port: null, password: '', clearPassword: false, rememberPassword: false };
    this.authentication.set(null); if(host) void this.readAuthentication(host); this.formError.set(''); this.modal.set('host'); setTimeout(() => document.querySelector<HTMLInputElement>('.machine-modal input')?.focus(), 0);
  }
  close() { if (this.saving()) return; this.form.password = ''; this.modal.set(null); setTimeout(() => this.previousFocus?.focus(), 0); }
  async save() {
    if (this.saving()) return;
    this.saving.set(true); this.formError.set('');
    try {
      const edited = this.editing(); await this.tasks.run('Saving host…', () => this.api(edited ? '/' + edited.id : '', edited ? 'PUT' : 'POST', { ...this.form, name: this.form.name.trim(), address: this.form.address.trim(), user: this.form.user.trim() }));
      this.form.password = ''; await this.load(); this.modal.set(null); if(edited) void this.readAuthentication(edited);
      if (edited?.id === this.selectedId()) { this.status.set({ state: 'idle', inventory: null, error: null }); void this.inspect(); }
    } catch (e) { this.formError.set((e as Error).message); }
    finally { this.saving.set(false); }
  }
  async makeDefault(host: Machine) {
    try { this.registry.set(await this.tasks.run('Updating your default host…', () => this.api<Registry>('/' + host.id + '/default', 'POST', {}))); this.error.set(''); }
    catch (e) { this.error.set((e as Error).message); }
  }
  askRemove(host: Machine) { this.previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null; this.removing.set(host); this.formError.set(''); this.modal.set('remove'); setTimeout(() => document.querySelector<HTMLElement>('.machine-modal button')?.focus(), 0); }
  async remove() {
    const host = this.removing(); if (!host || this.saving()) return;
    this.saving.set(true); this.formError.set('');
    try { await this.tasks.run('Removing saved host…', () => this.api('/' + host.id, 'DELETE')); if (this.selectedId() === host.id) this.back(); await this.load(); this.modal.set(null); }
    catch (e) { this.formError.set((e as Error).message); }
    finally { this.saving.set(false); }
  }
  ngOnDestroy() { this.form.password = ''; this.controller.abort(); this.tasks.close(); clearInterval(this.timer); window.removeEventListener('keydown', this.keyListener); }
}

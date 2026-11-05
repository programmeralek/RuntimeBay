import { Component, EventEmitter, Input, OnChanges, OnDestroy, OnInit, Output, SimpleChanges, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

interface Host { id: string; name: string; local: boolean }
interface Tunnel { definition: { id: string; name: string; hostId: string; localPort: number; remoteHost: string; remotePort: number; sshAlias?: string | null }; hostName: string; state: 'running' | 'stopped' | 'error'; error: string | null; external: boolean }

@Component({ selector: 'app-tunnels', standalone: true, imports: [FormsModule], templateUrl: './tunnels.html', styleUrl: './tunnels.css' })
export class Tunnels implements OnInit, OnDestroy, OnChanges {
  @Input() session = '';
  @Input() createRequested = false;
  @Output() navigateHosts = new EventEmitter<void>();
  @Output() createHandled = new EventEmitter<void>();
  tunnels = signal<Tunnel[]>([]);
  hosts = signal<Host[]>([]);
  loading = signal(true);
  saving = signal(false);
  working = signal<string[]>([]);
  error = signal('');
  modal = signal(false);
  editing = signal<Tunnel | null>(null);
  formError = signal('');
  form = { name: '', hostId: '', localPort: 8080, remoteHost: 'localhost', remotePort: 80, sshAlias: '' };
  private controller = new AbortController();
  private timer?: ReturnType<typeof setInterval>;

  ngOnInit() { void this.load(); this.timer = setInterval(() => void this.load(false), 2000); }
  ngOnChanges(changes: SimpleChanges) { if (changes['createRequested']?.currentValue) { this.open(); this.createHandled.emit(); } }
  private async api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
    const response = await fetch('/api' + path, { method, signal: this.controller.signal,
      headers: { Authorization: 'Bearer ' + this.session, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    const value = await response.json(); if (!response.ok) throw new Error(value.error || 'Could not complete the tunnel operation'); return value as T;
  }
  async load(showError = true) {
    try {
      const [tunnels, registry] = await Promise.all([this.api<Tunnel[]>('/tunnels'), this.api<{ hosts: Host[] }>('/hosts')]);
      this.tunnels.set(tunnels); this.hosts.set(registry.hosts.filter(host => !host.local)); this.error.set('');
    } catch (e) { if (!this.controller.signal.aborted && showError) this.error.set((e as Error).message); }
    finally { this.loading.set(false); }
  }
  open(tunnel?: Tunnel) {
    this.editing.set(tunnel || null);
    this.form = tunnel ? { ...tunnel.definition, sshAlias: tunnel.definition.sshAlias || '' } : { name: '', hostId: this.hosts()[0]?.id || '', localPort: 8080, remoteHost: 'localhost', remotePort: 80, sshAlias: '' };
    this.formError.set(''); this.modal.set(true);
  }
  close() { if (!this.saving()) this.modal.set(false); }
  async save() {
    const editing = this.editing(); if (this.saving()) return;
    this.saving.set(true); this.formError.set('');
    try {
      const body = { ...this.form, name: this.form.name.trim(), remoteHost: this.form.remoteHost.trim() };
      await this.api<Tunnel>(editing ? `/tunnels/${editing.definition.id}` : '/tunnels', editing ? 'PUT' : 'POST', body);
      this.modal.set(false); await this.load();
    } catch (e) { this.formError.set((e as Error).message); }
    finally { this.saving.set(false); }
  }
  async action(tunnel: Tunnel, action: 'start' | 'stop') {
    const id = tunnel.definition.id; if (this.working().includes(id)) return;
    this.working.update(ids => [...ids, id]); this.error.set('');
    try { await this.api(`/tunnels/${id}/${action}`, 'POST', {}); await this.load(); }
    catch (e) { this.error.set((e as Error).message); }
    finally { this.working.update(ids => ids.filter(value => value !== id)); }
  }
  async remove(tunnel: Tunnel) {
    if (!confirm(`Remove saved tunnel “${tunnel.definition.name}”?`)) return;
    try { await this.api(`/tunnels/${tunnel.definition.id}`, 'DELETE'); await this.load(); }
    catch (e) { this.error.set((e as Error).message); }
  }
  busy(tunnel: Tunnel) { return this.working().includes(tunnel.definition.id); }
  ngOnDestroy() { this.controller.abort(); clearInterval(this.timer); }
}

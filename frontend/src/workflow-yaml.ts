import { Component, Input, Output, EventEmitter, OnChanges, OnDestroy, inject, signal, computed } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AttachmentSource } from './attachments';
import { LoadingService } from './loading/loading.service';
export interface YamlStatus { projectId: string; configured: boolean; managedServiceIds: string[]; running: boolean }
interface RunState { id: string; state: string; current: string; message: string; completed: string[] }
interface Definition { name: string; hosts: string[]; processes: Record<string, { name: string; mode: string }>; resources: Record<string, { kind: string; reference: string }>; environments: Record<string, unknown> }
interface Choice { key: string; source: AttachmentSource; name: string; detail: string }
interface Info { configured: boolean; definition: Definition | null; managedServiceIds: string[]; processKeys: Record<string, string>; mappings: Record<string,string>; references: Record<string,AttachmentSource>; hosts: { id: string; name: string }[]; choices: Choice[]; plan: RunState }
interface ImportPreview { revision: string; changes: string[]; warnings: string[] }
interface RunPreview { revision: string; target: string; environmentName: string; steps: { key: string; name: string; mode: string; command: string; directory: string; port: number | null; environmentKeys: string[]; reuse: boolean }[]; warnings: string[] }
@Component({ selector: 'app-workflow-yaml', standalone: true, imports: [FormsModule], templateUrl: './workflow-yaml.html' })
export class WorkflowYaml implements OnChanges, OnDestroy {
  @Input() projectId = '';
  @Input() session = '';
  @Input() beforeRun?: () => Promise<void>;
  @Input() afterRun?: () => Promise<void>;
  @Output() changed = new EventEmitter<void>();
  @Output() statusChange = new EventEmitter<YamlStatus>();
  info = signal<Info | null>(null);
  open = signal(false);
  phase = signal<'edit'|'bindings'|'review'|'run'>('edit');
  yaml = signal('');
  parsed = signal<Definition | null>(null);
  referenceKinds = signal<Record<string,string>>({});
  preview = signal<ImportPreview | null>(null);
  runPreview = signal<RunPreview | null>(null);
  warnings = signal<string[]>([]);
  mappings: Record<string,string> = {};
  references: Record<string,AttachmentSource> = {};
  process = signal('');
  error = signal('');
  busy = signal(false);
  fileHash = signal('');
  fileExists = signal(false);
  message = signal('');
  running = computed(() => ['running','cancelling'].includes(this.info()?.plan.state || ''));
  aliases = computed(() => Object.entries(this.referenceKinds()).map(([name,kind]) => ({ name,kind })));
  processes = computed(() => Object.entries(this.info()?.definition?.processes || {}).map(([key,p]) => ({ key, name: p.name })));
  private controller = new AbortController();
  private revision = 0;
  private loading = false;
  private pendingFullStackRun = '';
  private tasks = inject(LoadingService).scope();
  private returnFocus: HTMLElement | null = null;
  private timer = setInterval(() => void this.load(), 1500);
  ngOnChanges() { this.revision++; this.controller.abort(); this.controller = new AbortController(); this.loading = false; this.busy.set(false); this.pendingFullStackRun = ''; this.info.set(null); this.open.set(false); this.publish(); void this.load(); }
  private publish() { this.statusChange.emit({ projectId: this.projectId, configured: !!this.info()?.configured, managedServiceIds: this.info()?.managedServiceIds || [], running: this.running() }); }
  private async api<T>(suffix = '', method = 'GET', body?: unknown): Promise<T> {
    const response = await fetch('/api/workflows/' + this.projectId + '/manifest' + suffix, { method, signal: this.controller.signal, headers: { Authorization: 'Bearer ' + this.session, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    const value = await response.json(); if (!response.ok) throw new Error(value.error || 'YAML configuration operation failed'); return value;
  }
  async load() {
    if (!this.projectId || this.loading) return; const revision = this.revision; this.loading = true;
    try { const value = await this.api<Info>(); if (revision === this.revision) { this.info.set(value); this.publish(); this.checkFullStackCompletion(value); } }
    catch (error) { if (revision === this.revision && !this.controller.signal.aborted) this.error.set((error as Error).message); }
    finally { if (revision === this.revision) this.loading = false; }
  }
  private begin(phase: 'edit'|'run') { this.returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null; this.phase.set(phase); this.error.set(''); this.message.set(''); this.preview.set(null); this.runPreview.set(null); this.open.set(true); requestAnimationFrame(() => document.querySelector<HTMLElement>('.yaml-modal textarea, .yaml-modal button')?.focus()); }
  async show() {
    this.begin('edit'); this.parsed.set(null); const revision = this.revision; this.busy.set(true);
    await this.tasks.run('Loading workflow YAML…', async () => {
      try {
        const [exported,file] = await Promise.all([this.api<{yaml: string; warnings: string[]}>('/export'),this.api<{exists: boolean; yaml: string; hash: string}>('/file')]);
        if (revision !== this.revision) return;
        this.yaml.set(file.exists ? file.yaml : exported.yaml); this.fileHash.set(file.hash); this.fileExists.set(file.exists); this.warnings.set(exported.warnings); this.mappings = { ...(this.info()?.mappings || {}), local: 'local' }; this.references = { ...(this.info()?.references || {}) };
      } catch (error) { if (revision === this.revision) this.error.set((error as Error).message); }
      finally { if (revision === this.revision) this.busy.set(false); }
    });
  }
  async loadFile() { await this.operation('Reading runtimebay.yaml…', async () => { const file = await this.api<{exists: boolean; yaml: string; hash: string}>('/file'); this.fileHash.set(file.hash); this.fileExists.set(file.exists); if (!file.exists) throw new Error('No runtimebay.yaml exists in this project yet. Export the current configuration or paste a definition.'); this.edit(file.yaml); this.message.set('Loaded runtimebay.yaml. No definitions were imported and no commands ran.'); }); }
  async exportCurrent() { await this.operation('Exporting logical workflow configuration…', async () => { const value = await this.api<{yaml: string; warnings: string[]}>('/export'); this.edit(value.yaml); this.warnings.set(value.warnings); this.message.set('Exported current definitions into the editor. Review before sharing or saving.'); }); }
  edit(value: string) { this.yaml.set(value); this.parsed.set(null); this.preview.set(null); this.phase.set('edit'); }
  async inspect() { await this.operation('Validating YAML schema…', async () => { const value = await this.api<{definition: Definition; referenceKinds: Record<string,string>}>('/inspect','POST',{ yaml: this.yaml() }); this.parsed.set(value.definition); this.referenceKinds.set(value.referenceKinds); this.phase.set('bindings'); }); }
  choices(kind: string) { return (this.info()?.choices || []).filter(c => c.source.kind === kind); }
  selectedReference(alias: string) { const source = this.references[alias]; return this.info()?.choices.find(c => source && c.source.kind === source.kind && c.source.projectId === source.projectId && c.source.sourceId === source.sourceId && c.source.scope === source.scope)?.key || ''; }
  setReference(alias: string,key: string) { const choice = this.info()?.choices.find(c => c.key === key); if (choice) this.references[alias] = choice.source; else delete this.references[alias]; }
  async review() { await this.operation('Previewing definition changes…', async () => { this.preview.set(await this.api<ImportPreview>('/preview','POST',{ yaml: this.yaml(), mappings: this.mappings, references: this.references })); this.phase.set('review'); }); }
  async apply() {
    const preview = this.preview(); if (!preview || this.running()) return;
    await this.operation('Applying reviewed YAML definitions…', async () => { await this.api('/apply','POST',{ yaml: this.yaml(), mappings: this.mappings, references: this.references, revision: preview.revision }); this.message.set('Imported definitions. Nothing has been executed. Use Run stack to review commands.'); this.preview.set(null); await this.load(); this.changed.emit(); });
  }
  async saveFile() { if (!this.parsed() || !this.fileHash()) return; await this.operation('Saving runtimebay.yaml in the project…', async () => { const file = await this.api<{hash: string}>('/save','POST',{ yaml: this.yaml(), expectedFileHash: this.fileHash() }); this.fileHash.set(file.hash); this.fileExists.set(true); this.message.set('Saved runtimebay.yaml. File saving does not import definitions or run commands.'); }); }
  async showPlan(serviceId?: string) {
    if (!this.info()?.configured) { await this.show(); return; }
    this.begin('run'); this.process.set(serviceId ? Object.entries(this.info()!.processKeys).find(([,id]) => id === serviceId)?.[0] || '' : ''); await this.reviewRun();
  }
  selectProcess(value: string) { this.process.set(value); this.runPreview.set(null); }
  async reviewRun() { await this.operation('Reviewing stack execution order…', async () => this.runPreview.set(await this.api<RunPreview>('/plan' + (this.process() ? '?process=' + encodeURIComponent(this.process()) : '')))); }
  async run() {
    const preview = this.runPreview(); if (!preview || this.running()) return;
    await this.operation('Starting the reviewed stack plan…', async () => { await this.beforeRun?.(); const run = await this.api<RunState>('/run','POST',{ process: this.process(), revision: preview.revision }); this.pendingFullStackRun = this.process() ? '' : run.id; await this.load(); this.changed.emit(); this.busy.set(false); this.dismiss(); });
  }
  private checkFullStackCompletion(value: Info) {
    if (!this.pendingFullStackRun || value.plan.id !== this.pendingFullStackRun || ['running','cancelling'].includes(value.plan.state)) return;
    const completed = value.plan.state === 'completed'; this.pendingFullStackRun = '';
    if (completed && this.afterRun) void this.afterRun().catch(error => this.error.set((error as Error).message));
  }
  async cancel() { const runId = this.info()?.plan.id; if (!runId) return; await this.operation('Requesting stack cancellation…', async () => { await this.api('/cancel','POST',{ runId }); await this.load(); }); }
  private async operation(label: string, operation: () => Promise<unknown>) {
    if (this.busy()) return; const revision = this.revision; this.busy.set(true); this.error.set(''); this.message.set('');
    await this.tasks.run(label,async () => { try { await operation(); } catch (error) { if (revision === this.revision && !this.controller.signal.aborted) this.error.set((error as Error).message); } finally { if (revision === this.revision) this.busy.set(false); } });
  }
  dismiss() { if (this.busy()) return; this.open.set(false); this.returnFocus?.focus(); }
  keydown(event: KeyboardEvent) {
    if (event.key === 'Escape') { event.preventDefault(); this.dismiss(); }
    if (event.key === 'Tab') { const controls = Array.from(document.querySelectorAll<HTMLElement>('.yaml-modal button:not([disabled]), .yaml-modal textarea:not([disabled]), .yaml-modal select:not([disabled])')); const first = controls[0],last = controls.at(-1); if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); } else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); } }
  }
  ngOnDestroy() { this.revision++; clearInterval(this.timer); this.controller.abort(); this.tasks.close(); }
}

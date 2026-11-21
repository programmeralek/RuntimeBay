import { Component, Input, Output, EventEmitter, OnDestroy, OnChanges, SimpleChanges, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { WorkflowDefinition, WorkflowView } from './types';
import { LoadingService } from './loading/loading.service';

@Component({ selector: 'app-environments', standalone: true, imports: [FormsModule], templateUrl: './environments.html' })
export class Environments implements OnChanges, OnDestroy {
  @Input({ required: true }) workflow!: WorkflowView;
  @Input() session = '';
  @Input() active = false;
  @Input() hideTrigger = false;
  @Output() saved = new EventEmitter<WorkflowView>();
  open = signal(false);
  draft = signal<WorkflowDefinition | null>(null);
  error = signal('');
  saving = signal(false);
  name = signal('');
  pendingRemoval = signal<string | null>(null);
  private revision = '';
  private controller = new AbortController();
  private original!: WorkflowDefinition;
  private returnFocus: HTMLElement | null = null;
  private tasks = inject(LoadingService).scope();

  ngOnChanges(changes: SimpleChanges) { if (changes['workflow'] && this.workflow) this.revision = this.workflow.revision; }

  private async api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
    const response = await fetch('/api' + path, { method, signal: this.controller.signal, headers: { ...(method === 'PUT' ? { 'If-Match': this.revision } : {}), Authorization: 'Bearer ' + this.session, ...(body ? { 'Content-Type': 'application/json' } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
    const value = await response.json(); if (!response.ok) throw new Error(value.error || 'Could not save environments'); return value;
  }

  show(addEnvironment = false) {
    this.returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    this.error.set(''); this.saving.set(false); this.pendingRemoval.set(null);
    this.revision = this.workflow.revision;
    this.original = structuredClone(this.workflow.definition);
    this.draft.set(structuredClone(this.original));
    this.name.set(this.selectedName()); this.open.set(true);
    if (addEnvironment) this.add();
    requestAnimationFrame(() => document.querySelector<HTMLElement>(addEnvironment ? '#environment-name' : '.environment-list-option[aria-pressed="true"]')?.focus());
  }

  selectedName() { const d = this.draft(); return d?.environments.find(e => e.id === d.defaultEnvironmentId)?.name || ''; }
  select(id: string) { this.draft.update(d => d && ({ ...d, defaultEnvironmentId: id })); this.name.set(this.selectedName()); this.error.set(''); }
  rename(value: string) {
    this.name.set(value);
    this.draft.update(d => d && ({ ...d, environments: d.environments.map(e => e.id === d.defaultEnvironmentId ? { ...e, name: value } : e) }));
    this.error.set('');
  }
  private withLocalTargets(definition: WorkflowDefinition): WorkflowDefinition {
    const localId = definition.environments.find(environment => environment.name.trim().toLowerCase() === 'local')?.id || definition.environments[0]?.id;
    const localTargets = new Map(definition.bindings.filter(binding => binding.environmentId === localId).map(binding => [binding.resourceId, binding.target]));
    return { ...definition, bindings: definition.bindings.map(binding => {
      const target = localTargets.get(binding.resourceId);
      return binding.environmentId === localId || !target ? binding : { ...binding, target: structuredClone(target) };
    }) };
  }
  add() {
    const d = this.draft(); if (!d || d.environments.length >= 20) return;
    const id = crypto.randomUUID(); let number = d.environments.length + 1;
    while (d.environments.some(e => e.name === 'Environment ' + number)) number++;
    const localId = d.environments.find(e => e.name.trim().toLowerCase() === 'local')?.id || d.environments[0]?.id;
    const localBindings = d.bindings.filter(binding => binding.environmentId === localId);
    this.draft.set({ ...d,
      defaultEnvironmentId: id,
      environments: [...d.environments, { id, name: 'Environment ' + number }],
      bindings: [...d.bindings, ...localBindings.map(binding => ({ ...binding, id: crypto.randomUUID(), environmentId: id }))]
    });
    this.name.set('Environment ' + number);
    requestAnimationFrame(() => { const input = document.querySelector<HTMLInputElement>('#environment-name'); input?.focus(); input?.select(); });
  }

  requestRemove(environmentId: string) {
    if (!this.open()) this.show();
    const environments = this.draft()?.environments || [];
    if (this.active || environments.length <= 1 || !environments.some(environment => environment.id === environmentId)) return;
    this.pendingRemoval.set(environmentId); this.error.set('');
  }
  cancelRemove() { if (!this.saving()) this.pendingRemoval.set(null); }
  async confirmRemove() {
    const id = this.pendingRemoval(), draft = this.draft();
    if (!id || !draft || this.saving() || this.active || draft.environments.length <= 1) return;
    const removed = draft.environments.find(environment => environment.id === id);
    if (!removed) { this.pendingRemoval.set(null); return; }
    const environments = draft.environments.filter(environment => environment.id !== id);
    const preferred = environments.find(environment => environment.name.trim().toLowerCase() === 'local') || environments[0];
    const next: WorkflowDefinition = {
      ...draft,
      environments,
      defaultEnvironmentId: draft.defaultEnvironmentId === id ? preferred.id : draft.defaultEnvironmentId,
      bindings: draft.bindings.filter(binding => binding.environmentId !== id)
    };
    if (!this.original.environments.some(environment => environment.id === id)) {
      this.draft.set(next); this.name.set(this.selectedName()); this.pendingRemoval.set(null); return;
    }
    this.saving.set(true); this.error.set('');
    await this.tasks.run('Deleting workflow environment…', async () => {
      try {
        const current = await this.api<WorkflowView>('/workflows/' + this.original.id);
        if (JSON.stringify(current.definition) !== JSON.stringify(this.original)) throw new Error('This workflow changed while you were editing. Close and reopen Environments to review the latest version.');
        const result = await this.api<WorkflowView>('/workflows/' + this.original.id, 'PUT', this.withLocalTargets(next));
        this.saved.emit(result);
        this.original = structuredClone(result.definition);
        this.draft.set(structuredClone(result.definition));
        this.revision = result.revision;
        this.name.set(this.selectedName());
        this.pendingRemoval.set(null);
      } catch (error) { this.error.set((error as Error).message); }
      finally { this.saving.set(false); }
    });
  }

  hasChanges() { return !!this.draft() && JSON.stringify(this.draft()) !== JSON.stringify(this.original); }

  async apply() {
    const draft = this.draft();
    if (this.saving() || !draft || !this.hasChanges() || this.active) return;
    const names = draft.environments.map(environment => environment.name.trim().toLocaleLowerCase());
    if (names.some(name => !name) || new Set(names).size !== names.length) { this.error.set('Give each environment a unique name.'); return; }
    this.saving.set(true); this.error.set('');
    await this.tasks.run('Saving workflow environments…', async () => {
      try {
        const current = await this.api<WorkflowView>('/workflows/' + this.original.id);
        if (JSON.stringify(current.definition) !== JSON.stringify(this.original)) throw new Error('This workflow changed while you were editing. Close and reopen Environments to review the latest version.');
        const result = await this.api<WorkflowView>('/workflows/' + this.original.id, 'PUT', this.withLocalTargets(draft));
        this.saved.emit(result); this.saving.set(false); this.dismiss();
      } catch (error) { this.error.set((error as Error).message); }
      finally { this.saving.set(false); }
    });
  }

  async activate(environmentId: string) {
    if (this.saving() || this.active || environmentId === this.workflow.definition.defaultEnvironmentId) return;
    if (!this.workflow.definition.environments.some(environment => environment.id === environmentId)) throw new Error('Choose an environment in this workflow.');
    this.saving.set(true);
    try {
      const current = await this.api<WorkflowView>('/workflows/' + this.workflow.definition.id);
      if (current.revision !== this.workflow.revision) throw new Error('This workflow changed. Refresh before switching environments.');
      this.revision = current.revision;
      const result = await this.api<WorkflowView>('/workflows/' + this.workflow.definition.id, 'PUT', this.withLocalTargets({ ...current.definition, defaultEnvironmentId: environmentId }));
      this.saved.emit(result);
    } finally { this.saving.set(false); }
  }

  ngOnDestroy() { this.controller.abort(); this.tasks.close(); }
  dismiss() { if (this.saving()) return; this.open.set(false); this.returnFocus?.focus(); }
  keydown(event: KeyboardEvent) {
    if (event.key === 'Escape') { event.preventDefault(); this.dismiss(); }
    if (event.key === 'Tab') {
      const controls = Array.from(document.querySelectorAll<HTMLElement>('.environment-modal button:not([disabled]), .environment-modal input:not([disabled])'));
      const first = controls[0], last = controls.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    }
  }
}

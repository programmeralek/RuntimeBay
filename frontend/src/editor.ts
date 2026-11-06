import { AfterViewInit, Component, ElementRef, EventEmitter, Input, OnChanges, OnDestroy, Output, SimpleChanges, ViewChild, effect, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { envFields, setEnvField, setYamlField, ValueField, yamlFields } from './editor-structured';
import { parseAllDocuments } from 'yaml';
import type { EditorView } from '@codemirror/view';
import type { EditorState } from '@codemirror/state';
export interface EditorDocument { id: string; path: string; content: string; version: string; exists: boolean }
interface Tab { document: EditorDocument; content: string; state?: EditorState }
interface FileEntry { name: string; path: string; directory: boolean }

@Component({ selector: 'app-editor', standalone: true, imports: [FormsModule], templateUrl: './editor.html', styleUrl: './editor.css' })
export class Editor implements AfterViewInit, OnChanges, OnDestroy {
  @Input() visible = false;
  @Input() projectId = '';
  @Input() session = '';
  @Input() requested: EditorDocument | null = null;
  @Output() opened = new EventEmitter<void>();
  @ViewChild('surface') surface!: ElementRef<HTMLElement>;
  tabs = signal<Tab[]>([]); selected = signal(''); error = signal(''); notice = signal(''); busy = signal(false);
  entries = signal<FileEntry[]>([]); directory = signal(''); mode = signal<'source' | 'values'>('source'); reveal = signal(false);
  filename = ''; private view?: EditorView; private mounted = false; private destroyed = false;
  private beforeUnload = (event: BeforeUnloadEvent) => { if (this.tabs().some(tab => this.dirty(tab))) { event.preventDefault(); event.returnValue = ''; } };
  private keyListener = (event: KeyboardEvent) => {
    if (!this.visible) return;
    if (event.metaKey && !event.ctrlKey && event.key.toLowerCase() === 's') { event.preventDefault(); event.stopImmediatePropagation(); void this.save(); }
  };
  constructor() { effect(() => { const dirty = this.tabs().some(tab => this.dirty(tab)); window.webkit?.messageHandlers.runtimeBay.postMessage({ action: 'editorDirty', dirty: dirty ? 'true' : 'false' }); }); }
  ngAfterViewInit() { this.mounted = true; window.addEventListener('beforeunload', this.beforeUnload); window.addEventListener('keydown', this.keyListener, true); if (this.requested) this.accept(this.requested); }
  ngOnChanges(changes: SimpleChanges) { if (changes['requested'] && this.requested && this.mounted) this.accept(this.requested); if ((changes['projectId'] || changes['visible']) && this.visible && this.projectId) void this.browse(''); if (this.visible) setTimeout(() => this.view?.requestMeasure(), 0); }
  ngOnDestroy() { this.destroyed = true; this.view?.destroy(); window.removeEventListener('beforeunload', this.beforeUnload); window.removeEventListener('keydown', this.keyListener, true); }
  current() { return this.tabs().find(tab => tab.document.id === this.selected()); }
  dirty(tab: Tab) { return tab.content !== tab.document.content; }
  kind() { const name = this.current()?.document.path.split('/').at(-1)?.toLowerCase() || ''; return /\.ya?ml$/.test(name) ? 'yaml' : name === '.env' || name.startsWith('.env.') || name.endsWith('.env') ? 'env' : 'text'; }
  fields() { const tab = this.current(); if (!tab) return []; return this.kind() === 'yaml' ? yamlFields(tab.content) : this.kind() === 'env' ? envFields(tab.content) : []; }
  private async api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
    const response = await fetch('/api/editor' + path, { method, headers: { Authorization: 'Bearer ' + this.session, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    const value = await response.json();
    if (!response.ok) {
      if(response.status === 404 && value.error === 'Endpoint not found') throw new Error('This window uses an older engine. Reopen the updated Runtime Bay app to enable the editor. Quitting disconnects this session’s terminals and tunnels.');
      throw new Error(value.error || 'Could not edit this file');
    }
    return value as T;
  }
  async browse(path: string) {
    const projectId = this.projectId;
    try { const entries = await this.api<FileEntry[]>(`/tree?projectId=${encodeURIComponent(projectId)}&path=${encodeURIComponent(path)}`); if (projectId === this.projectId) { this.entries.set(entries); this.directory.set(path); } }
    catch (error) { this.error.set((error as Error).message); }
  }
  parent() { const path = this.directory().split('/'); path.pop(); void this.browse(path.join('/')); }
  async open(path = this.filename) {
    if (!path.trim() || this.busy()) return;
    this.busy.set(true); this.error.set('');
    try { this.accept(await this.api<EditorDocument>('/open', 'POST', { path: path.trim(), projectId: this.projectId || null })); this.filename = ''; }
    catch (error) { this.error.set((error as Error).message); } finally { this.busy.set(false); }
  }
  accept(document: EditorDocument) {
    if (!document?.id) return;
    const existing = this.tabs().find(tab => tab.document.path === document.path);
    if (!existing) this.tabs.update(tabs => [...tabs, { document, content: document.content }]);
    this.opened.emit(); void this.select(existing?.document.id || document.id);
  }
  async select(id: string) {
    const previous = this.current(); if (previous && this.view) previous.state = this.view.state;
    this.selected.set(id); this.mode.set('source'); this.error.set(''); this.notice.set(''); this.reveal.set(false);
    const tab = this.current(); if (!tab || !this.mounted) return;
    const { EditorView, basicSetup } = await import('codemirror');
    const { EditorState } = await import('@codemirror/state'); const { keymap, highlightWhitespace } = await import('@codemirror/view');
    const { indentWithTab } = await import('@codemirror/commands');
    if (this.destroyed || this.selected() !== id) return;
    const extensions: any[] = [basicSetup, EditorState.lineSeparator.of(tab.content.includes('\r\n') ? '\r\n' : '\n'), highlightWhitespace(), keymap.of([{ key: 'Mod-s', run: () => { void this.save(); return true; } }, indentWithTab]), EditorView.lineWrapping,
      EditorView.theme({ '&': { height: '100%', color: 'var(--ink)', backgroundColor: 'var(--surface)' }, '.cm-scroller': { overflow: 'auto', fontFamily: 'ui-monospace, Menlo, monospace' }, '.cm-gutters': { backgroundColor: 'var(--surface-soft)', color: 'var(--muted)', borderColor: 'var(--line)' }, '.cm-cursor': { borderLeftColor: 'var(--ink)' }, '.cm-content': { caretColor: 'var(--ink)' }, '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, ::selection': { backgroundColor: '#5588aa66' } }),
      EditorView.updateListener.of(update => { if (update.docChanged) { tab.content = update.state.sliceDoc(); this.tabs.update(tabs => [...tabs]); this.notice.set(''); } })];
    const path = tab.document.path.toLowerCase();
    if (/\.ya?ml$/.test(path)) extensions.push((await import('@codemirror/lang-yaml')).yaml());
    else if (/\.[cm]?[jt]sx?$/.test(path)) extensions.push((await import('@codemirror/lang-javascript')).javascript({ typescript: /\.tsx?$/.test(path), jsx: /[jt]sx$/.test(path) }));
    else if (/\.json$/.test(path)) extensions.push((await import('@codemirror/lang-json')).json());
    else if (/\.py$/.test(path)) extensions.push((await import('@codemirror/lang-python')).python());
    if (this.destroyed || this.selected() !== id) return;
    this.view?.destroy(); this.surface.nativeElement.replaceChildren();
    this.view = new EditorView({ state: tab.state || EditorState.create({ doc: tab.content, extensions }), parent: this.surface.nativeElement }); this.view.focus();
  }
  change(field: ValueField, value: string) {
    const tab = this.current(); if (!tab || !this.view) return;
    try { const content = this.kind() === 'yaml' ? setYamlField(tab.content, field, value) : setEnvField(tab.content, field, value); this.view.dispatch({ changes: { from: 0, to: this.view.state.doc.length, insert: content } }); this.error.set(''); }
    catch (error) { this.error.set((error as Error).message); }
  }
  async save() {
    const tab = this.current(); if (!tab || this.busy()) return;
    if (this.kind() === 'yaml') { const errors = parseAllDocuments(tab.content).flatMap(document => document.errors); if (errors.length) { this.error.set(errors[0].message); return; } }
    this.busy.set(true); this.error.set(''); const content = tab.content;
    try { tab.document = await this.api<EditorDocument>('/' + tab.document.id, 'PUT', { version: tab.document.version, content }); this.tabs.update(tabs => [...tabs]); this.notice.set('Saved.'); }
    catch (error) { this.error.set((error as Error).message); } finally { this.busy.set(false); }
  }
  async reload() {
    const tab = this.current(); if (!tab || this.busy() || this.dirty(tab) && !confirm('Discard unsaved changes and reload this file from disk?')) return;
    try { tab.document = await this.api<EditorDocument>('/' + tab.document.id); tab.content = tab.document.content; tab.state = undefined; this.view?.destroy(); this.view = undefined; await this.select(tab.document.id); }
    catch (error) { this.error.set((error as Error).message); }
  }
  close(tab: Tab) {
    if (this.busy() || this.dirty(tab) && !confirm('Discard unsaved changes to ' + tab.document.path.split('/').at(-1) + '?')) return;
    const wasSelected = tab.document.id === this.selected();
    if (wasSelected) { this.view?.destroy(); this.view = undefined; this.selected.set(''); }
    this.tabs.update(tabs => tabs.filter(value => value !== tab)); if (wasSelected && this.tabs().length) void this.select(this.tabs().at(-1)!.document.id);
  }
}

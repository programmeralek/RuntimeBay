import { AfterViewInit, Component, ElementRef, OnDestroy, ViewChild, effect, input, signal, computed } from '@angular/core';
import type { Terminal } from '@xterm/xterm';
import type { FitAddon } from '@xterm/addon-fit';
import { pathSuggestionLabel } from './terminal-assist';

export interface ShellTab { id: string; name: string; directory: string; exitCode: number | null; hostId: string; hostName: string; connected?: boolean; workflowId?: string | null }
interface Output { data: string; next: number; truncated: boolean; exitCode: number | null }

/** Owns the tab collection; the backend owns shell lifetimes across view changes and reloads. */
export class TerminalWorkspace {
  readonly tabs = signal<ShellTab[]>([]);
  readonly selected = signal<string | null>(null);
  readonly fullscreen = signal(false);
  readonly selectedTab = computed(() => this.tabs().find(tab => tab.id === this.selected()));
  readonly error = signal('');
  readonly creating = signal(false);
  readonly closing = signal<string[]>([]);
  private controller = new AbortController();
  constructor(private token: string) { void this.restore(); }
  async api<T>(path: string, method = 'GET', body?: unknown, signal = this.controller.signal): Promise<T> {
    const response = await fetch('/api/terminals' + path, { method, signal,
      headers: { Authorization: 'Bearer ' + this.token, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    const result = await response.json();
    if (!response.ok) throw Object.assign(new Error(result.error || 'Could not connect to this terminal'), {status: response.status});
    return result;
  }
  private async restore() {
    try { const tabs = await this.api<ShellTab[]>(''); this.tabs.update(current => [...tabs, ...current.filter(tab => !tabs.some(existing => existing.id === tab.id))]); }
    catch (error) { if (!this.controller.signal.aborted) this.error.set((error as Error).message); }
  }
  async create(projectId: string | null, hostId?: string): Promise<boolean> {
    if (this.creating()) return false;
    this.creating.set(true); this.error.set('');
    try {
      const tab = await this.api<ShellTab>('', 'POST', { projectId, ...(hostId ? { hostId } : {}) });
      this.tabs.update(tabs => [...tabs, tab]); this.selected.set(tab.id); return true;
    } catch (error) { this.error.set((error as Error).message); return false; }
    finally { this.creating.set(false); }
  }
  async createWorkflow(projectId: string): Promise<ShellTab | null> {
    if (this.creating()) return null;
    this.creating.set(true); this.error.set('');
    try {
      const tab = await this.api<ShellTab>('', 'POST', { projectId, workflowScoped: true });
      this.tabs.update(tabs => [...tabs, tab]); this.selected.set(tab.id); return tab;
    } catch (error) { this.error.set((error as Error).message); return null; }
    finally { this.creating.set(false); }
  }
  async rename(id: string,name: string) {
    const tab=await this.api<ShellTab>('/'+id,'PUT',{name});this.tabs.update(tabs=>tabs.map(value=>value.id===id?tab:value));
  }
  async close(id: string) {
    if (this.closing().includes(id)) return;
    this.closing.update(ids => [...ids, id]); this.error.set('');
    try {
      await this.api('/' + id, 'DELETE');
      const tabs = this.tabs(), index = tabs.findIndex(tab => tab.id === id), remaining = tabs.filter(tab => tab.id !== id);
      this.tabs.set(remaining);
      if (this.selected() === id) this.selected.set(remaining[Math.min(index, remaining.length - 1)]?.id || null);
    } catch (error) { this.error.set((error as Error).message); }
    finally { this.closing.update(ids => ids.filter(value => value !== id)); }
  }
  destroy() { this.controller.abort(); }
}
interface AssistState { buffer: string; cursor: number; cwd: string; command: string; recognized: boolean; commands: string[]; paths: string[]; options: string[] }
interface Renderer { terminal: Terminal; fit: FitAddon; element: HTMLElement; abort: AbortController; next: number; input: Promise<void>; queued: number; exited: boolean; editorSupported?: boolean }

@Component({
  selector: 'app-terminals', standalone: true,
  template: `<section class="shell-workspace" [hidden]="!visible()">
    <div class="shell-heading"><div class="shell-title-area"><div class="eyebrow">YOUR SHELL, RIGHT HERE</div>
      @if (renamingId() === workspace().selected() && selectedTab()) {
        <div class="shell-title-editor"><input #terminalName class="shell-title-input" aria-label="Terminal name" maxlength="128" [value]="selectedTab()?.name || ''" [disabled]="savingName()" (keydown.enter)="$event.preventDefault(); rename(terminalName.value)" (keydown.escape)="$event.preventDefault(); cancelRename()"><button class="shell-title-confirm" aria-label="Save terminal name" title="Save name (Enter)" (click)="rename(terminalName.value)" [disabled]="savingName()">✓</button><button class="shell-title-cancel" aria-label="Cancel terminal rename" title="Cancel (Escape)" (click)="cancelRename()" [disabled]="savingName()">×</button></div>
      } @else {
        <h1><button class="shell-title-button" (click)="beginRename()" [disabled]="!selectedTab()" title="Click to rename terminal" aria-label="Rename terminal">{{ selectedTab()?.name || 'Terminals' }}</button></h1>
      }
      <p class="subtitle">{{ selectedTab()?.directory || 'Open a terminal from the workspace sidebar.' }}</p></div><div class="shell-heading-actions"><span class="local-pill">{{ selectedTab()?.hostId && selectedTab()?.hostId !== 'local' ? 'SSH · ' + selectedTab()?.hostName : 'zsh · This Mac' }}</span><button class="secondary shell-fullscreen-toggle" (click)="toggleFullscreen()" [attr.aria-label]="workspace().fullscreen() ? 'Exit full screen terminal' : 'Expand terminal to full screen'">{{ workspace().fullscreen() ? '↙ Exit full screen' : '⛶ Full screen' }}</button></div></div>
    @if (workspace().error()) { <div class="notice" role="alert">{{ workspace().error() }}<button (click)="workspace().error.set('')" aria-label="Dismiss terminal error">×</button></div> }
    @if (suggestionsEnabled()) { <div class="terminal-assist" aria-label="Command identification and suggestions">
      <div class="assist-identity" [class.assist-known]="assistIdentity().kind === 'known'" [class.assist-partial]="assistIdentity().kind === 'partial'" [class.assist-unknown]="assistIdentity().kind === 'unknown'">
        <span class="assist-dot"></span><span>{{ assistIdentity().label }}</span><small>{{ assistIdentity().detail }}</small>
      </div>
      <div class="assist-suggestions" aria-label="Completion suggestions">
        @for (item of suggestions(); track item.value) { <button class="assist-chip" (click)="complete(item.value)" [title]="'Insert ' + item.value">{{ item.label }}<small>{{ item.kind }}</small></button> }
        @if (!suggestions().length) { <span class="assist-hint">Type a command, path, or option to see suggestions</span> }
      </div>
    </div> }
    <div #surfaces class="shell-surfaces" aria-label="Workspace terminals"></div>
    @if (!selectedTab()) { <div class="shell-empty">Choose a terminal from the sidebar, or press ＋ Terminal to open a shell.</div> }
    <div class="shell-foot"><span>{{ selectedTab()?.exitCode != null ? 'Shell exited · code ' + selectedTab()!.exitCode : 'Independent shell · stays open as you switch views' }}</span><span>⌃ C Interrupt · ⌃ D Exit</span></div>
  </section>`
})
export class Terminals implements AfterViewInit, OnDestroy {
  readonly workspace = input.required<TerminalWorkspace>();
  readonly visible = input(false);
  readonly suggestionsEnabled = input(true);
  @ViewChild('surfaces') surfaces!: ElementRef<HTMLElement>;
  readonly renamingId=signal<string | null>(null);
  readonly savingName=signal(false);
  readonly assist = signal<AssistState | null>(null);
  readonly assistIdentity = computed(() => {
    const state=this.assist();
    if (!state) return {kind:'unknown',label:'Shell assistant',detail:this.workspace().selectedTab()?.hostId === 'local' ? 'Waiting for interactive Zsh' : 'Local Zsh suggestions only'};
    if (!state.command) return {kind:'partial',label:'Command',detail:state.cwd};
    if (state.recognized) return {kind:'known',label:state.command,detail:'Available in this shell'};
    if (state.commands.some(value=>value.startsWith(state.command))) return {kind:'partial',label:state.command,detail:'Command suggestion'};
    return {kind:'unknown',label:state.command,detail:'Not found in this shell'};
  });
  readonly suggestions = computed(() => {
    const state=this.assist();if(!state)return [] as {value:string;label:string;kind:string}[];
    const before=state.buffer.slice(0,state.cursor), token=before.split(/\s/).at(-1)||'';
    const words=before.trim().split(/\s+/), command=words[0]||'';
    const actions:Record<string,string[]>={npm:['install','ci','run','test','start','exec','publish','init','update'],node:['--version','--help','--inspect'],python:['--version','-m','-c'],python3:['--version','-m','-c'],java:['--version','-jar','-cp','--class-path'],javac:['-d','-cp','--class-path','--release','--version'],git:['status','add','commit','push','pull','switch','checkout','log','diff','branch'],docker:['ps','images','compose','run','exec','logs','pull','build','stop'],aws:['sts','s3','ec2','configure','--profile','--region'],go:['run','build','test','mod','get','fmt','vet'],gradle:['build','test','run','tasks','--version'],mvn:['clean','test','package','install','-DskipTests'],sudo:['-E','-u','-s'],chmod:['-R','u+x','g+w','o-rwx']};
    const values: {value:string;label:string;kind:string}[]=[];
    const add=(value:string,kind:string)=>{if(value.startsWith(token)&&value!==token&&!values.some(item=>item.value===value))values.push({value,label:value,kind});};
    for(const value of state.paths) {
      const completion=token+value;
      const label=pathSuggestionLabel(token,value);
      if(!values.some(item=>item.value===completion))values.push({value:completion,label,kind:'path'});
    }
    const commandPosition=words.length<=1&&!/\s$/.test(before);
    if(commandPosition)for(const value of state.commands)add(value,'command');
    else {
      for(const value of state.options||[])add(value,'flag');
      for(const value of actions[command]||[])add(value,'suggested');
    }
    return values.slice(0,12);
  });
  @ViewChild('terminalName') set terminalName(input: ElementRef<HTMLInputElement> | undefined) {
    if(input) requestAnimationFrame(()=>{input.nativeElement.focus();input.nativeElement.select();});
  }
  beginRename() { this.renamingId.set(this.workspace().selected()); }
  cancelRename() { if(!this.savingName())this.renamingId.set(null); }
  async rename(name: string) {
    const id=this.renamingId();if(!id || this.savingName())return;
    this.savingName.set(true);
    try{await this.workspace().rename(id,name);if(this.renamingId()===id)this.renamingId.set(null);}catch(error){this.workspace().error.set((error as Error).message);}
    finally{this.savingName.set(false);}
  }
  private ready = signal(false);
  private renderers = new Map<string, Renderer>();
  private observer?: ResizeObserver;
  private themeObserver?: MutationObserver;
  private frame = 0;
  private destroyed = false;
  private mounting = new Set<string>();
  private assistantTabId: string | null = null;
  private focusId: string | null = null;
  private resizeListener = () => this.fit();
  private fullscreenEscape = (event: KeyboardEvent) => {
    if (event.key !== 'Escape' || !this.workspace().fullscreen()) return;
    if (event.target instanceof HTMLElement && event.target.closest('.shell-title-editor')) return;
    event.preventDefault(); event.stopImmediatePropagation();
    this.workspace().fullscreen.set(false);
  };
  selectedTab() { return this.workspace().tabs().find(tab => tab.id === this.workspace().selected()); }
  constructor() {
    effect(() => {
      if (!this.ready()) return;
      const workspace = this.workspace(), tabs = workspace.tabs(), selected = workspace.selected(), visible = this.visible();
      for (const [id, renderer] of this.renderers) if (!tabs.some(tab => tab.id === id)) {
        renderer.abort.abort(); renderer.terminal.dispose(); renderer.element.remove(); this.renderers.delete(id);
      }
      if (selected !== this.assistantTabId) { this.assistantTabId = selected; this.assist.set(null); }
      // Keep existing emulators mounted, preserving scrollback and alternate screens.
      for (const tab of tabs) {
        const renderer = this.renderers.get(tab.id);
        if (renderer) { renderer.element.hidden = !visible || selected !== tab.id; renderer.terminal.textarea?.setAttribute('aria-label', 'Terminal input · ' + tab.name); }
      }
      if (visible && selected) {
        const tab = tabs.find(tab => tab.id === selected);
        if (tab && !this.renderers.has(selected) && !this.mounting.has(selected)) void this.mount(tab).catch(error => this.workspace().error.set((error as Error).message));
        cancelAnimationFrame(this.frame);
        this.frame = requestAnimationFrame(() => {
          const renderer = this.renderers.get(selected);
          this.fitRenderer(renderer);
          if (this.focusId !== selected) { renderer?.terminal.focus(); this.focusId = selected; }
        });
      } else this.focusId = null;
    });
  }
  ngAfterViewInit() {
    window.addEventListener('resize', this.resizeListener);
    window.addEventListener('keydown', this.fullscreenEscape, true);
    this.observer = new ResizeObserver(() => this.fit()); this.observer.observe(this.surfaces.nativeElement);
    this.themeObserver = new MutationObserver(() => {
      for (const renderer of this.renderers.values()) {
        renderer.terminal.options.theme = this.theme();
        const fontSize = this.fontSize();
        if (renderer.terminal.options.fontSize !== fontSize) { renderer.terminal.options.fontSize = fontSize; }
      }
      this.fit();
    });
    this.themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['style', 'class', 'data-appearance', 'data-palette'] });
    this.ready.set(true);
  }
  toggleFullscreen() {
    this.workspace().fullscreen.update(value => !value);
    requestAnimationFrame(() => this.renderers.get(this.workspace().selected() || '')?.terminal.focus());
  }
  private theme() {
    const style = getComputedStyle(document.documentElement);
    return { background: style.getPropertyValue('--terminal').trim() || '#141d21', foreground: '#e0e6e8', cursor: '#8fe3c2', selectionBackground: '#50657599', black: '#243139', red: '#ff8e91', green: '#8fe3aa', yellow: '#eed28a', blue: '#8bbcff', magenta: '#dca2f3', cyan: '#80d9e4', white: '#e0e6e8', brightBlack: '#91a2ad', brightRed: '#ffb2b4', brightGreen: '#b1f0c2', brightYellow: '#ffe6a7', brightBlue: '#b4d5ff', brightMagenta: '#edc4ff', brightCyan: '#b4f4fc', brightWhite: '#ffffff' };
  }
  private fontSize() {
    const scale = Number.parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--font-scale')) || 1;
    return 13 * scale;
  }
  private async mount(tab: ShellTab) {
    this.mounting.add(tab.id);
    if(tab.connected === false)try{
      const opened=await this.workspace().api<ShellTab>('/'+tab.id+'/open','POST',{});
      this.workspace().tabs.update(tabs=>tabs.map(value=>value.id===tab.id?opened:value));
    }catch(error){this.mounting.delete(tab.id);throw error;}

    let TerminalClass: typeof import('@xterm/xterm').Terminal, FitClass: typeof import('@xterm/addon-fit').FitAddon;
    try {
      const [xterm, addon] = await Promise.all([import('@xterm/xterm'), import('@xterm/addon-fit')]);
      TerminalClass = xterm.Terminal; FitClass = addon.FitAddon;
    } finally { this.mounting.delete(tab.id); }
    if (this.destroyed || !this.workspace().tabs().some(value => value.id === tab.id)) return;
    const element = document.createElement('div'); element.className = 'shell-surface'; this.surfaces.nativeElement.append(element);
    const terminal = new TerminalClass({ cursorBlink: true, fontFamily: 'Menlo, Monaco, monospace', fontSize: this.fontSize(), lineHeight: 1.35, scrollback: 10000, scrollOnUserInput: true, scrollOnEraseInDisplay: true, theme: this.theme(), screenReaderMode: true });
    const fit = new FitClass(); terminal.loadAddon(fit); terminal.open(element);
    terminal.textarea?.setAttribute('aria-label', 'Terminal input · ' + tab.name);
    const renderer: Renderer = { terminal, fit, element, abort: new AbortController(), next: 0, input: Promise.resolve(), queued: 0, exited: false };
    this.renderers.set(tab.id, renderer);
    terminal.attachCustomKeyEventHandler(event => {
      // Keep macOS copy/paste/select-all in the web view; Ctrl shortcuts belong to the shell.
      if (event.metaKey && !event.ctrlKey && !event.altKey && !event.shiftKey && event.key.toLowerCase() === 'a') { terminal.selectAll(); event.preventDefault(); return false; }
      if (event.metaKey) return false;
      return true;
    });
    terminal.onData(data => {
      if (renderer.exited || renderer.abort.signal.aborted) return;
      if (renderer.queued + data.length > 1024 * 1024) { this.workspace().error.set('The terminal input queue is full. Wait for the shell to catch up before pasting again.'); return; }
      renderer.queued += data.length;
      renderer.input = renderer.input.then(async () => {
        // Split on code points, preserving UTF-8 and keeping each request below 16 KiB.
        const characters = Array.from(data);
        for (let i = 0; i < characters.length; i += 2048) await this.workspace().api('/' + tab.id + '/input', 'POST', { data: characters.slice(i, i + 2048).join('') }, renderer.abort.signal);
      }).catch(error => { if (!renderer.abort.signal.aborted) this.workspace().error.set((error as Error).message); }).finally(() => { renderer.queued -= data.length; });
    });
    terminal.onResize(size => { void this.workspace().api('/' + tab.id + '/resize', 'POST', { columns: size.cols, rows: size.rows }, renderer.abort.signal).catch(error => { if (!renderer.abort.signal.aborted && !renderer.exited) this.workspace().error.set((error as Error).message); }); });
    element.hidden = !this.visible() || this.workspace().selected() !== tab.id;
    requestAnimationFrame(() => {
      if (!renderer.abort.signal.aborted && !element.hidden) { this.fitRenderer(renderer); terminal.focus(); }
    });
    void this.read(tab.id, renderer);
    if (tab.hostId === 'local') void this.pollAssist(tab.id, renderer);
  }
  private async read(id: string, renderer: Renderer) {
    while (!renderer.abort.signal.aborted) {
      try {
        const output = await this.workspace().api<Output>('/' + id + '/output?after=' + renderer.next, 'GET', undefined, renderer.abort.signal);
        if (renderer.abort.signal.aborted) return;
        if (output.truncated) { renderer.terminal.reset(); renderer.terminal.writeln('[Earlier terminal output was discarded: the live buffer holds 1 MiB.]'); }
        if (output.data) await new Promise<void>(resolve => renderer.terminal.write(Uint8Array.from(atob(output.data), value => value.charCodeAt(0)), resolve));
        renderer.next = output.next;
        if (output.exitCode !== null) {
          renderer.exited = true; renderer.terminal.options.disableStdin = true;
          renderer.terminal.writeln('\r\n[Shell exited · code ' + output.exitCode + ']');
          this.workspace().tabs.update(tabs => tabs.map(tab => tab.id === id ? { ...tab, exitCode: output.exitCode } : tab)); return;
        }
      } catch (error) {
        if (renderer.abort.signal.aborted) return;
        this.workspace().error.set((error as Error).message);
        await new Promise<void>(resolve => {
          const done = () => { clearTimeout(timeout); renderer.abort.signal.removeEventListener('abort', done); resolve(); };
          const timeout = setTimeout(done, 1500); renderer.abort.signal.addEventListener('abort', done, { once: true });
        });
      }
    }
  }
  private async pollAssist(id:string,renderer:Renderer) {
    while(!renderer.abort.signal.aborted&&!renderer.exited) {
      if(this.visible()&&this.workspace().selected()===id) {
        try {
          const state=await this.workspace().api<AssistState|null>('/'+id+'/assist','GET',undefined,renderer.abort.signal);
          if(state&&this.workspace().selected()===id){this.assistantTabId=id;this.assist.set(state);}
          if(renderer.editorSupported !== false) {
            try {
              const document=await this.workspace().api<unknown|null>('/'+id+'/editor','GET',undefined,renderer.abort.signal);
              if(document) window.dispatchEvent(new CustomEvent('runtimebay:edit', {detail:document}));
            } catch(error) { if((error as Error & {status?:number}).status === 404) renderer.editorSupported=false; else throw error; }
          }
        } catch(error) { if(!renderer.abort.signal.aborted)this.workspace().error.set((error as Error).message); }
      }
      await new Promise<void>(resolve=>setTimeout(resolve,250));
    }
  }
  async complete(value:string) {
    const state=this.assist(),tab=this.workspace().selectedTab();if(!state||!tab)return;
    const before=state.buffer.slice(0,state.cursor), token=before.split(/\s/).at(-1)||'';
    if(!value.startsWith(token))return;
    const suffix=value.slice(token.length);if(!suffix)return;
    const renderer=this.renderers.get(tab.id);if(!renderer||renderer.exited)return;
    renderer.input=renderer.input.then(async()=>{await this.workspace().api('/'+tab.id+'/input','POST',{data:suffix},renderer.abort.signal);}).catch(error=>{if(!renderer.abort.signal.aborted)this.workspace().error.set((error as Error).message);});
  }
  private fitRenderer(renderer?: Renderer) {
    if (!renderer || renderer.element.hidden || renderer.abort.signal.aborted) return;
    const buffer = renderer.terminal.buffer.active;
    const following = buffer.viewportY >= buffer.baseY;
    renderer.fit.fit();
    if (following) renderer.terminal.scrollToBottom();
  }
  private fit() { if (this.visible()) this.fitRenderer(this.renderers.get(this.workspace().selected() || '')); }
  ngOnDestroy() {
    window.removeEventListener('resize', this.resizeListener);
    window.removeEventListener('keydown', this.fullscreenEscape, true);
    this.destroyed = true; cancelAnimationFrame(this.frame); this.observer?.disconnect(); this.themeObserver?.disconnect();
    for (const renderer of this.renderers.values()) { renderer.abort.abort(); renderer.terminal.dispose(); }
  }
}

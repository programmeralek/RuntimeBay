export interface PortOwner { pid: number; command: string; managed: boolean }
export interface PortInfo { number: number; status: 'available' | 'listening' | 'unknown'; owners: PortOwner[] }
export interface Service {
  id: string; name: string; command: string; directory: string; port: number | null;
  dependsOnResourceId: string | null;
  state: 'idle' | 'starting' | 'running' | 'stopping' | 'stopped' | 'exited' | 'failed';
  pid: number | null; exitCode: number | null; startedAt: string | null; portInfo: PortInfo | null;
}
export interface Project { id: string; name: string; path: string; services: Service[] }
export interface WorkflowTarget { kind: 'machine' | 'docker'; id: string; linkId?: string | null }
export interface WorkflowDefinition {
  id: string; projectId: string; name: string; defaultEnvironmentId: string;
  environments: { id: string; name: string }[];
  machines: { id: string }[];
  dockerConnections: { id: string; context: string; endpoint: string; machineId: string | null }[];
  resources: { id: string; type: 'service' | 'container'; sourceId: string; name: string; ownership: string }[];
  bindings: { id: string; environmentId: string; resourceId: string; target: WorkflowTarget }[];
}
export interface WorkflowView {
  definition: WorkflowDefinition;
  revision: string;
  resources: { id: string; resourceId: string; name: string; type: 'service' | 'container'; targetKind: 'machine' | 'docker'; targetId: string; targetName: string; status: 'local' | 'unsupported' | 'unresolved' | 'linked'; detail: string }[];
}
export interface LogEntry { sequence: number; time: string; stream: string; message: string }
export interface LogResponse { entries: LogEntry[]; next: number; truncated: boolean }
declare global {
  interface Window {
    runtimeBayNative?: boolean;
    runtimeBaySession?: string;
    webkit?: { messageHandlers: { runtimeBay: { postMessage(value: string | Record<string, string>): void } } };
  }
}

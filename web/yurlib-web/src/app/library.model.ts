export interface LibraryMount {
  alias: string;
}

export interface CreateLibraryRootRequest {
  name: string;
  mountAlias: string;
  relativePath: string;
  identityToken: string;
  mode: 'READ_ONLY_SOURCE' | 'MANAGED_OUTPUT';
}

export interface LibraryRoot {
  id: string;
  name: string;
  mountAlias: string;
  relativePath: string;
  mode: 'READ_ONLY_SOURCE' | 'MANAGED_OUTPUT';
  availability: 'UNKNOWN' | 'AVAILABLE' | 'UNAVAILABLE' | 'IDENTITY_MISMATCH';
  lastSuccessfulScanAt?: string | null;
}

export type ScanState =
  'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'COMPLETED_WITH_FAILURES' | 'FAILED' | 'CANCELLED';

export interface FileFailure {
  relativePath: string;
  code: string;
  detail: string;
}

export interface ScanJob {
  id: string;
  rootId: string;
  state: ScanState;
  discoveredCount: number;
  processedCount: number;
  skippedCount: number;
  failedCount: number;
  coverageComplete: boolean;
  failures: FileFailure[];
  createdAt: string;
  startedAt?: string | null;
  completedAt?: string | null;
}

export type CatalogFormat = 'EPUB' | 'FB2' | 'MOBI' | 'PDF' | 'DOCX' | 'DJVU';

export interface CatalogAsset {
  id: string;
  format: CatalogFormat;
  size: number;
  availability: 'AVAILABLE' | 'UNAVAILABLE';
  original: true;
  metadataState: 'PENDING' | 'READY' | 'FAILED_SAFE';
}

export interface CatalogWork {
  id: string;
  title: string | null;
  contributors?: string[];
  provisional: boolean;
  assets: CatalogAsset[];
}

export interface CatalogPage {
  items: CatalogWork[];
  page: number;
  size: number;
  totalElements: number;
}

export interface ProblemDetails {
  title?: string;
  detail?: string;
  code?: string;
  correlationId?: string;
}

export interface OwnerSession {
  mode: 'OWNER' | 'LOOPBACK_DEVELOPMENT';
  authenticated: boolean;
  username: string | null;
  owner: boolean;
  capabilities: Array<'MANAGE_INGESTION_SOURCES' | 'CURATE_CATALOG'>;
}

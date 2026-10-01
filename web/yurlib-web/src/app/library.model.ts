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

export interface CurationOverrideHistory {
  id: string;
  value: string | null;
  valueState: 'PRESENT' | 'ABSENT';
  actorId: string;
  reason: string;
  version: number;
  active: boolean;
  createdAt: string;
}

export interface MetadataReviewItem {
  id: string;
  subjectId: string;
  subjectType: 'WORK' | 'EDITION' | 'ASSET' | 'CONTRIBUTOR';
  fieldName: string;
  reasonCode: 'INVALID_VALUE' | 'AMBIGUOUS_VALUE' | 'CONFLICT';
  detail: string;
  ruleName: string;
  ruleVersion: string;
  createdAt: string;
}

export interface CatalogAuditEvent {
  id: string;
  eventType: string;
  actorId: string | null;
  reason: string | null;
  createdAt: string;
}

export interface WorkCuration {
  id: string;
  version: number;
  title: {
    value: string | null;
    source: 'CURATED' | 'RESOLVED' | 'OBSERVED' | 'PROVISIONAL';
    overrideVersion: number;
    observedValues: string[];
    history: CurationOverrideHistory[];
  };
  contributors: ContributorCuration[];
  tags: string[];
  reviews: MetadataReviewItem[];
  audit: CatalogAuditEvent[];
}

export interface ContributorCuration {
  id: string;
  displayName: string;
  role: 'AUTHOR' | 'EDITOR' | 'TRANSLATOR' | 'ILLUSTRATOR' | 'OTHER';
  version: number;
  aliases: string[];
}

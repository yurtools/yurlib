import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import {
  CatalogPage,
  CatalogFormat,
  CreateLibraryRootRequest,
  LibraryMount,
  LibraryRoot,
  OwnerSession,
  ScanJob,
  MetadataReviewItem,
  WorkCuration,
  ContributorCuration,
  PersonalCollection,
  PersonalLibraryState,
  WorkReadState,
  FavoriteContributor,
  MergeOperation,
  RecoverableSubjectType,
  RecoveryPreview,
  SplitPreview,
  ConversionJob,
  ConversionRoute,
} from './library.model';

@Injectable({ providedIn: 'root' })
export class LibraryApi {
  private readonly http = inject(HttpClient);

  session() {
    return this.http.get<OwnerSession>('/api/v1/session');
  }

  login(username: string, password: string) {
    const body = new HttpParams().set('username', username).set('password', password);
    return this.http.post<void>('/api/v1/session', body, {
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    });
  }

  logout() {
    return this.http.post<void>('/api/v1/session/logout', undefined);
  }

  listMounts() {
    return this.http.get<LibraryMount[]>('/api/v1/library-mounts');
  }

  listRoots() {
    return this.http.get<LibraryRoot[]>('/api/v1/library-roots');
  }

  createRoot(request: CreateLibraryRootRequest) {
    return this.http.post<LibraryRoot>('/api/v1/library-roots', request);
  }

  startScan(rootId: string) {
    return this.http.post<ScanJob>(`/api/v1/library-roots/${rootId}/scans`, undefined);
  }

  getJob(jobId: string) {
    return this.http.get<ScanJob>(`/api/v1/jobs/${jobId}`);
  }

  searchCatalog(query: string, page: number, size: number, formats: CatalogFormat[] = []) {
    let params = new HttpParams().set('page', page).set('size', size);
    if (query !== '') params = params.set('query', query);
    for (const format of formats) params = params.append('format', format);
    return this.http.get<CatalogPage>('/api/v1/catalog/works', { params });
  }

  downloadUrl(assetId: string) {
    return `/api/v1/assets/${encodeURIComponent(assetId)}/content`;
  }

  coverUrl(workId: string) {
    return `/api/v1/catalog/works/${encodeURIComponent(workId)}/cover`;
  }

  requestConversion(assetId: string, route: ConversionRoute) {
    return this.http.post<ConversionJob>(
      `/api/v1/assets/${encodeURIComponent(assetId)}/conversions`,
      { route },
    );
  }

  getConversion(jobId: string) {
    return this.http.get<ConversionJob>(`/api/v1/conversions/${encodeURIComponent(jobId)}`);
  }

  cancelConversion(jobId: string, expectedVersion: number) {
    return this.http.post<ConversionJob>(
      `/api/v1/conversions/${encodeURIComponent(jobId)}/cancel`,
      { expectedVersion },
    );
  }

  getWorkCuration(workId: string) {
    return this.http.get<WorkCuration>(`/api/v1/curation/works/${encodeURIComponent(workId)}`);
  }

  updateWorkTitle(workId: string, value: string, reason: string, expectedVersion: number) {
    return this.http.put<WorkCuration>(
      `/api/v1/curation/works/${encodeURIComponent(workId)}/title`,
      { value, reason, expectedVersion },
    );
  }

  undoWorkTitle(workId: string, reason: string, expectedVersion: number) {
    return this.http.post<WorkCuration>(
      `/api/v1/curation/works/${encodeURIComponent(workId)}/title/undo`,
      { reason, expectedVersion },
    );
  }

  replaceWorkTags(workId: string, tags: string[], reason: string, expectedVersion: number) {
    return this.http.put<WorkCuration>(
      `/api/v1/curation/works/${encodeURIComponent(workId)}/tags`,
      { tags, reason, expectedVersion },
    );
  }

  listMetadataReviews(limit = 50) {
    return this.http.get<MetadataReviewItem[]>('/api/v1/curation/reviews', {
      params: new HttpParams().set('limit', limit),
    });
  }

  dismissMetadataReview(reviewId: string, reason: string) {
    return this.http.post<void>(
      `/api/v1/curation/reviews/${encodeURIComponent(reviewId)}/dismiss`,
      { reason },
    );
  }

  updateContributor(
    contributorId: string,
    displayName: string,
    aliases: string[],
    reason: string,
    expectedVersion: number,
  ) {
    return this.http.put<ContributorCuration>(
      `/api/v1/curation/contributors/${encodeURIComponent(contributorId)}`,
      { displayName, aliases, reason, expectedVersion },
    );
  }

  previewRecovery(subjectType: RecoverableSubjectType, survivorId: string, sourceId: string) {
    return this.http.get<RecoveryPreview>('/api/v1/curation/recovery/preview', {
      params: new HttpParams()
        .set('subjectType', subjectType)
        .set('survivorId', survivorId)
        .set('sourceId', sourceId),
    });
  }

  mergeSubjects(preview: RecoveryPreview, reason: string, idempotencyKey: string) {
    return this.http.post<MergeOperation>('/api/v1/curation/recovery/merges', {
      subjectType: preview.subjectType,
      survivorId: preview.survivor.id,
      sourceId: preview.source.id,
      survivorVersion: preview.survivor.version,
      sourceVersion: preview.source.version,
      idempotencyKey,
      reason,
    });
  }

  recoveryHistory(subjectType: RecoverableSubjectType, subjectId: string) {
    return this.http.get<MergeOperation[]>('/api/v1/curation/recovery/history', {
      params: new HttpParams().set('subjectType', subjectType).set('subjectId', subjectId),
    });
  }

  splitPreview(operationId: string) {
    return this.http.get<SplitPreview>(
      `/api/v1/curation/recovery/merges/${encodeURIComponent(operationId)}/split-preview`,
    );
  }

  undoMerge(operationId: string, reason: string) {
    return this.http.post<MergeOperation>(
      `/api/v1/curation/recovery/merges/${encodeURIComponent(operationId)}/undo`,
      { reason },
    );
  }

  markNotSame(
    subjectType: RecoverableSubjectType,
    firstId: string,
    secondId: string,
    reason: string,
  ) {
    return this.http.post('/api/v1/curation/recovery/not-same', {
      subjectType,
      firstId,
      secondId,
      ruleName: 'manual-duplicate-review',
      ruleVersion: '1',
      reason,
    });
  }

  personalLibraryState() {
    return this.http.get<PersonalLibraryState>('/api/v1/me/library-state');
  }

  addFavoriteContributor(contributorId: string) {
    return this.http.put<FavoriteContributor>(
      `/api/v1/me/favorite-contributors/${encodeURIComponent(contributorId)}`,
      undefined,
    );
  }

  removeFavoriteContributor(contributorId: string) {
    return this.http.delete<void>(
      `/api/v1/me/favorite-contributors/${encodeURIComponent(contributorId)}`,
    );
  }

  markWorkRead(workId: string, completedEditionId: string, expectedVersion: number) {
    return this.http.put<WorkReadState>(
      `/api/v1/me/works/${encodeURIComponent(workId)}/read-state`,
      { completedEditionId, completedAt: new Date().toISOString(), expectedVersion },
    );
  }

  markWorkUnread(workId: string, expectedVersion: number) {
    return this.http.delete<void>(`/api/v1/me/works/${encodeURIComponent(workId)}/read-state`, {
      params: new HttpParams().set('expectedVersion', expectedVersion),
    });
  }

  createCollection(name: string, ordered: boolean) {
    return this.http.post<PersonalCollection>('/api/v1/me/collections', { name, ordered });
  }

  updateCollection(collection: PersonalCollection, name: string, ordered: boolean) {
    return this.http.put<PersonalCollection>(
      `/api/v1/me/collections/${encodeURIComponent(collection.id)}`,
      { name, ordered, expectedVersion: collection.version },
    );
  }

  deleteCollection(collection: PersonalCollection) {
    return this.http.delete<void>(`/api/v1/me/collections/${encodeURIComponent(collection.id)}`, {
      params: new HttpParams().set('expectedVersion', collection.version),
    });
  }

  addWorkToCollection(collection: PersonalCollection, workId: string) {
    return this.http.put<PersonalCollection>(
      `/api/v1/me/collections/${encodeURIComponent(collection.id)}/works/${encodeURIComponent(workId)}`,
      { expectedVersion: collection.version },
    );
  }

  removeWorkFromCollection(collection: PersonalCollection, workId: string) {
    return this.http.delete<PersonalCollection>(
      `/api/v1/me/collections/${encodeURIComponent(collection.id)}/works/${encodeURIComponent(workId)}`,
      { params: new HttpParams().set('expectedVersion', collection.version) },
    );
  }
}

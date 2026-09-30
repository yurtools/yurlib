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
}

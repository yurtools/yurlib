import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import {
  CatalogPage,
  CreateLibraryRootRequest,
  LibraryMount,
  LibraryRoot,
  ScanJob,
} from './library.model';

@Injectable({ providedIn: 'root' })
export class LibraryApi {
  private readonly http = inject(HttpClient);

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

  searchCatalog(query: string, page: number, size: number) {
    let params = new HttpParams().set('page', page).set('size', size);
    if (query !== '') params = params.set('query', query);
    return this.http.get<CatalogPage>('/api/v1/catalog/works', { params });
  }

  downloadUrl(assetId: string) {
    return `/api/v1/assets/${encodeURIComponent(assetId)}/content`;
  }
}

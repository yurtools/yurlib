import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { LibraryApi } from './library-api';

describe('LibraryApi', () => {
  let api: LibraryApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(LibraryApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('uses relative URLs for every workflow operation', () => {
    api.listMounts().subscribe();
    api.listRoots().subscribe();
    api.startScan('root id').subscribe();
    api.getJob('job id').subscribe();
    api.searchCatalog('book', 2, 12).subscribe();

    expect(http.expectOne('/api/v1/library-mounts').request.url).not.toContain('://');
    expect(http.expectOne('/api/v1/library-roots').request.url).not.toContain('://');
    expect(http.expectOne('/api/v1/library-roots/root id/scans').request.url).not.toContain('://');
    expect(http.expectOne('/api/v1/jobs/job id').request.url).not.toContain('://');
    expect(
      http.expectOne((request) => request.url === '/api/v1/catalog/works').request.url,
    ).not.toContain('://');
    expect(api.downloadUrl('asset/id')).toBe('/api/v1/assets/asset%2Fid/content');
  });
});

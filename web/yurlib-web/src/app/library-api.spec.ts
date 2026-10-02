import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { LibraryApi } from './library-api';
import { RecoveryPreview } from './library.model';

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
    api.session().subscribe();
    api.login('owner', 'secret').subscribe();
    api.logout().subscribe();
    api.listMounts().subscribe();
    api.listRoots().subscribe();
    api.startScan('root id').subscribe();
    api.getJob('job id').subscribe();
    api.searchCatalog('book', 2, 12).subscribe();
    api.getWorkCuration('work/id').subscribe();
    api.updateWorkTitle('work/id', 'Title', 'Reason', 1).subscribe();
    api.undoWorkTitle('work/id', 'Reason', 2).subscribe();
    api.replaceWorkTags('work/id', ['classic'], 'Reason', 3).subscribe();
    api.listMetadataReviews().subscribe();
    api.dismissMetadataReview('review/id', 'Reason').subscribe();
    api.updateContributor('contributor/id', 'Name', ['Alias'], 'Reason', 4).subscribe();
    const preview: RecoveryPreview = {
      subjectType: 'WORK',
      survivor: { id: 'survivor/id', displayName: 'Survivor', version: 2 },
      source: { id: 'source/id', displayName: 'Source', version: 3 },
      impact: {
        editions: 1,
        assets: 1,
        observations: 2,
        contributors: 0,
        tags: 0,
        personalReadStates: 0,
        collectionMemberships: 0,
        favoriteUsers: 0,
      },
      mergeAllowed: true,
      conflicts: [],
    };
    api.previewRecovery('WORK', 'survivor/id', 'source/id').subscribe();
    api.mergeSubjects(preview, 'Reason', 'request-id').subscribe();
    api.recoveryHistory('WORK', 'survivor/id').subscribe();
    api.splitPreview('operation/id').subscribe();
    api.undoMerge('operation/id', 'Reason').subscribe();
    api.markNotSame('WORK', 'survivor/id', 'source/id', 'Reason').subscribe();

    expect(
      http.expectOne((request) => request.url === '/api/v1/session' && request.method === 'GET')
        .request.url,
    ).not.toContain('://');
    const login = http.expectOne(
      (request) => request.url === '/api/v1/session' && request.method === 'POST',
    );
    expect(login.request.headers.get('Content-Type')).toBe('application/x-www-form-urlencoded');
    expect(login.request.body.toString()).toBe('username=owner&password=secret');
    expect(http.expectOne('/api/v1/session/logout').request.url).not.toContain('://');
    expect(http.expectOne('/api/v1/library-mounts').request.url).not.toContain('://');
    expect(http.expectOne('/api/v1/library-roots').request.url).not.toContain('://');
    expect(http.expectOne('/api/v1/library-roots/root id/scans').request.url).not.toContain('://');
    expect(http.expectOne('/api/v1/jobs/job id').request.url).not.toContain('://');
    expect(
      http.expectOne((request) => request.url === '/api/v1/catalog/works').request.url,
    ).not.toContain('://');
    expect(http.expectOne('/api/v1/curation/works/work%2Fid').request.method).toBe('GET');
    expect(http.expectOne('/api/v1/curation/works/work%2Fid/title').request.method).toBe('PUT');
    expect(http.expectOne('/api/v1/curation/works/work%2Fid/title/undo').request.method).toBe(
      'POST',
    );
    expect(http.expectOne('/api/v1/curation/works/work%2Fid/tags').request.method).toBe('PUT');
    expect(
      http
        .expectOne((request) => request.url === '/api/v1/curation/reviews')
        .request.params.get('limit'),
    ).toBe('50');
    expect(http.expectOne('/api/v1/curation/reviews/review%2Fid/dismiss').request.method).toBe(
      'POST',
    );
    expect(http.expectOne('/api/v1/curation/contributors/contributor%2Fid').request.method).toBe(
      'PUT',
    );
    expect(
      http.expectOne((request) => request.url === '/api/v1/curation/recovery/preview').request
        .method,
    ).toBe('GET');
    expect(http.expectOne('/api/v1/curation/recovery/merges').request.method).toBe('POST');
    expect(
      http.expectOne((request) => request.url === '/api/v1/curation/recovery/history').request
        .method,
    ).toBe('GET');
    expect(
      http.expectOne('/api/v1/curation/recovery/merges/operation%2Fid/split-preview').request
        .method,
    ).toBe('GET');
    expect(
      http.expectOne('/api/v1/curation/recovery/merges/operation%2Fid/undo').request.method,
    ).toBe('POST');
    expect(http.expectOne('/api/v1/curation/recovery/not-same').request.method).toBe('POST');
    expect(api.downloadUrl('asset/id')).toBe('/api/v1/assets/asset%2Fid/content');
  });

  it('sends each selected catalog format as a repeated query parameter', () => {
    api.searchCatalog('', 0, 12, ['PDF', 'DOCX']).subscribe();

    const request = http.expectOne((candidate) => candidate.url === '/api/v1/catalog/works');
    expect(request.request.params.getAll('format')).toEqual(['PDF', 'DOCX']);
    request.flush({ items: [], page: 0, size: 12, totalElements: 0 });
  });
});

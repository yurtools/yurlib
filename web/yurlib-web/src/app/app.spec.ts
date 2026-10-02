import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { App } from './app';
import { CatalogPage, LibraryRoot, ScanJob } from './library.model';

describe('App', () => {
  let fixture: ComponentFixture<App>;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    fixture = TestBed.createComponent(App);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  it('loads only server-advertised mounts and renders accessible root fields', async () => {
    await initialize([], emptyCatalog(), [{ alias: 'archive' }, { alias: 'main' }]);

    const element = fixture.nativeElement as HTMLElement;
    const labels = [...element.querySelectorAll('label')].map((label) => label.textContent?.trim());
    const options = [...element.querySelectorAll('select option')].map((option) =>
      option.textContent?.trim(),
    );

    expect(labels.some((label) => label === 'Library name')).toBe(true);
    expect(labels.some((label) => label?.startsWith('Allowed mount'))).toBe(true);
    expect(labels.some((label) => label?.startsWith('Identity token'))).toBe(true);
    expect(options).toEqual(['Read-only source', 'Managed output', 'archive', 'main']);
    expect(element.querySelector<HTMLButtonElement>('button[type="submit"]')?.disabled).toBe(true);
  });

  it('gates the workspace behind owner sign-in and clears the password', async () => {
    http.expectOne('/api/v1/session').flush({
      mode: 'OWNER',
      authenticated: false,
      username: null,
    });
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('#workspace')).toBeNull();
    expect(element.querySelector('.access-panel')?.textContent).toContain('Library access');

    const password = element.querySelector<HTMLInputElement>('.login-form input[type="password"]')!;
    setInput(password, 'not-retained');
    await fixture.whenStable();
    element.querySelector<HTMLFormElement>('.login-form')?.dispatchEvent(new SubmitEvent('submit'));

    const login = http.expectOne('/api/v1/session');
    expect(login.request.body.toString()).toBe('username=owner&password=not-retained');
    login.flush(null, { status: 204, statusText: 'No Content' });
    await new Promise((resolve) => setTimeout(resolve, 0));
    http.expectOne('/api/v1/session').flush({
      mode: 'OWNER',
      authenticated: true,
      username: 'owner',
      owner: true,
      capabilities: ['MANAGE_INGESTION_SOURCES', 'CURATE_CATALOG'],
    });
    await new Promise((resolve) => setTimeout(resolve, 0));
    http.expectOne('/api/v1/library-mounts').flush([{ alias: 'main' }]);
    http.expectOne('/api/v1/library-roots').flush([]);
    await new Promise((resolve) => setTimeout(resolve, 0));
    http.expectOne((request) => request.url === '/api/v1/catalog/works').flush(emptyCatalog());
    http.expectOne('/api/v1/me/library-state').flush(emptyPersonalState());
    await new Promise((resolve) => setTimeout(resolve, 0));
    http.expectOne((request) => request.url === '/api/v1/curation/reviews').flush([]);
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();

    expect(element.querySelector('#workspace')).not.toBeNull();
    expect(element.querySelector('.owner-identity')?.textContent).toContain('owner');
    expect(element.textContent).not.toContain('not-retained');

    element.querySelector<HTMLButtonElement>('.sign-out')?.click();
    http.expectOne('/api/v1/session/logout').flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(
      element.querySelector<HTMLInputElement>('.login-form input[type="password"]')?.value,
    ).toBe('');
    expect(element.querySelector('.field-error')).toBeNull();
  });

  it('loads a reader catalog without disclosing source-management controls', async () => {
    http.expectOne('/api/v1/session').flush({
      mode: 'OWNER',
      authenticated: true,
      username: 'reader',
      owner: false,
      capabilities: [],
    });
    await new Promise((resolve) => setTimeout(resolve, 0));
    http.expectOne((request) => request.url === '/api/v1/catalog/works').flush(emptyCatalog());
    http.expectOne('/api/v1/me/library-state').flush(emptyPersonalState());
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('#workspace')).not.toBeNull();
    expect(element.querySelector('.setup-section')).toBeNull();
    http.expectNone('/api/v1/library-mounts');
    http.expectNone('/api/v1/library-roots');
  });

  it('submits by keyboard and presents a useful generic credential error', async () => {
    http.expectOne('/api/v1/session').flush({
      mode: 'OWNER',
      authenticated: false,
      username: null,
    });
    await fixture.whenStable();
    const element = fixture.nativeElement as HTMLElement;
    const password = element.querySelector<HTMLInputElement>('.login-form input[type="password"]')!;
    setInput(password, 'incorrect');
    await fixture.whenStable();

    password.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }),
    );
    http.expectOne('/api/v1/session').flush(
      {
        detail: 'Owner authentication is required.',
        code: 'AUTHENTICATION_REQUIRED',
      },
      { status: 401, statusText: 'Unauthorized' },
    );
    await fixture.whenStable();

    expect(element.querySelector('.notice-error')?.textContent).toContain(
      'Sign-in failed. Check the credentials and try again.',
    );
    expect(element.textContent).not.toContain('AUTHENTICATION_REQUIRED');
    expect(password.value).toBe('');
    expect(element.querySelector('.field-error')).toBeNull();

    setInput(password, 'incorrect-again');
    await fixture.whenStable();
    element
      .querySelector<HTMLButtonElement>('.login-form button')!
      .dispatchEvent(
        new KeyboardEvent('keydown', { key: ' ', code: 'Space', bubbles: true, cancelable: true }),
      );
    http.expectOne('/api/v1/session').flush(
      { detail: 'Owner authentication is required.', code: 'AUTHENTICATION_REQUIRED' },
      { status: 401, statusText: 'Unauthorized' },
    );
    await fixture.whenStable();
    expect(password.value).toBe('');
  });

  it('validates root input and sends the selected alias without a backend host', async () => {
    await initialize([], emptyCatalog(), [{ alias: 'main' }]);
    const element = fixture.nativeElement as HTMLElement;
    setInput(
      element.querySelectorAll<HTMLInputElement>('.root-form input')[2],
      'private-token-1234',
    );
    await fixture.whenStable();

    element.querySelector<HTMLFormElement>('.root-form')?.dispatchEvent(new SubmitEvent('submit'));
    const request = http.expectOne('/api/v1/library-roots');
    expect(request.request.body).toEqual({
      name: 'Main library',
      mountAlias: 'main',
      relativePath: '',
      identityToken: 'private-token-1234',
      mode: 'READ_ONLY_SOURCE',
      defaultForCovers: false,
    });
    request.flush(root());
    await fixture.whenStable();

    expect(element.querySelector('.source-name')?.textContent).toContain('Main library');
    expect(element.textContent).not.toContain('private-token-1234');
  });

  it('shows the queued job immediately and stops bounded polling when destroyed', async () => {
    await initialize([root()], emptyCatalog());
    vi.useFakeTimers();
    const element = fixture.nativeElement as HTMLElement;

    element.querySelector<HTMLButtonElement>('.source-actions button')?.click();
    http.expectOne('/api/v1/library-roots/root-1/scans').flush(job('QUEUED'));
    await vi.advanceTimersByTimeAsync(0);

    expect(element.querySelector('.scan-state')?.textContent).toContain('queued');
    await vi.advanceTimersByTimeAsync(750);
    http.expectOne('/api/v1/jobs/job-1').flush(job('RUNNING'));
    await vi.advanceTimersByTimeAsync(0);

    fixture.destroy();
    await vi.advanceTimersByTimeAsync(5_000);
    http.expectNone('/api/v1/jobs/job-1');
  });

  it('renders safe scan failures as counts without inventing a percentage', async () => {
    await initialize([root()], emptyCatalog());
    vi.useFakeTimers();
    const element = fixture.nativeElement as HTMLElement;
    element.querySelector<HTMLButtonElement>('.source-actions button')?.click();
    http.expectOne('/api/v1/library-roots/root-1/scans').flush(job('QUEUED'));
    await vi.advanceTimersByTimeAsync(750);
    http.expectOne('/api/v1/jobs/job-1').flush({
      ...job('COMPLETED_WITH_FAILURES'),
      discoveredCount: 4,
      processedCount: 3,
      failedCount: 1,
      failures: [
        {
          relativePath: 'broken/book.fb2',
          code: 'CORRUPT_ASSET',
          detail: 'Metadata could not be read.',
        },
      ],
    });
    await vi.advanceTimersByTimeAsync(0);
    http.expectOne((request) => request.url === '/api/v1/catalog/works').flush(emptyCatalog());
    await vi.advanceTimersByTimeAsync(0);

    expect(element.querySelector('.scan-counts')?.textContent).toContain('Discovered4');
    expect(element.querySelector('.failures')?.textContent).toContain('broken/book.fb2');
    expect(element.querySelector('.failures')?.textContent).toContain('CORRUPT_ASSET');
    expect(element.textContent).not.toContain('%');
  });

  it('shows provisional unknown catalog values and downloads by asset identifier', async () => {
    await initialize([], {
      items: [
        {
          id: 'work-1',
          title: null,
          contributors: [],
          provisional: true,
          assets: [
            {
              id: 'asset-1',
              format: 'EPUB',
              size: 2048,
              availability: 'AVAILABLE',
              original: true,
              metadataState: 'READY',
            },
          ],
        },
      ],
      page: 0,
      size: 12,
      totalElements: 1,
    });

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('.work-row')?.textContent).toContain('Untitled work');
    expect(element.querySelector('.work-row')?.textContent).toContain('Contributor unknown');
    expect(element.querySelector('.provisional')?.textContent).toContain('Provisional');
    expect(element.querySelector<HTMLAnchorElement>('.asset-list a')?.getAttribute('href')).toBe(
      '/api/v1/assets/asset-1/content',
    );
  });

  it('renders an accessible managed cover and retains the title fallback', async () => {
    await initialize([], {
      items: [
        {
          id: 'work-covered',
          title: 'The Left Hand of Darkness',
          contributors: ['Ursula K. Le Guin'],
          provisional: false,
          assets: [],
          coverAvailable: true,
        },
      ],
      page: 0,
      size: 12,
      totalElements: 1,
    });

    const element = fixture.nativeElement as HTMLElement;
    const cover = element.querySelector<HTMLImageElement>('.work-cover');
    expect(cover?.getAttribute('src')).toBe('/api/v1/catalog/works/work-covered/cover');
    expect(cover?.getAttribute('alt')).toBe('Cover of The Left Hand of Darkness');
    expect(element.querySelector('.work-cover-fallback')?.textContent).toContain('T');
  });

  it('marks a work as read and favorites a canonical contributor', async () => {
    await initialize([], {
      items: [
        {
          id: 'work-1',
          title: 'The Dispossessed',
          contributors: ['Ursula K. Le Guin'],
          contributorDetails: [{ id: 'contributor-1', displayName: 'Ursula K. Le Guin' }],
          provisional: false,
          assets: [],
        },
      ],
      page: 0,
      size: 12,
      totalElements: 1,
    });

    const element = fixture.nativeElement as HTMLElement;
    const personalActions = element.querySelectorAll<HTMLButtonElement>('.personal-work-actions button');
    personalActions[0].click();
    const readRequest = http.expectOne('/api/v1/me/works/work-1/read-state');
    expect(readRequest.request.body).toEqual({
      completedEditionId: null,
      completedAt: expect.any(String),
      expectedVersion: -1,
    });
    readRequest.flush({
      workId: 'work-1',
      completedEditionId: null,
      completedAt: '2026-10-02T12:00:00Z',
      version: 0,
    });
    await new Promise((resolve) => setTimeout(resolve, 0));
    http.expectOne('/api/v1/me/library-state').flush({
      ...emptyPersonalState(),
      readStates: [
        {
          workId: 'work-1',
          completedEditionId: null,
          completedAt: '2026-10-02T12:00:00Z',
          version: 0,
        },
      ],
    });
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(personalActions[0].textContent).toContain('Read');
    expect(personalActions[0].getAttribute('aria-pressed')).toBe('true');

    personalActions[1].click();
    http.expectOne('/api/v1/me/favorite-contributors/contributor-1').flush({
      contributorId: 'contributor-1',
      displayName: 'Ursula K. Le Guin',
      favoritedAt: '2026-10-02T12:00:00Z',
    });
    await new Promise((resolve) => setTimeout(resolve, 0));
    http.expectOne('/api/v1/me/library-state').flush({
      ...emptyPersonalState(),
      favoriteContributors: [
        {
          contributorId: 'contributor-1',
          displayName: 'Ursula K. Le Guin',
          favoritedAt: '2026-10-02T12:00:00Z',
        },
      ],
    });
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(personalActions[1].textContent).toContain('★ Ursula K. Le Guin');
    expect(personalActions[1].getAttribute('aria-pressed')).toBe('true');
  });

  it('searches and pages with bounded relative catalog requests', async () => {
    await initialize([], { ...emptyCatalog(), totalElements: 13 });
    const element = fixture.nativeElement as HTMLElement;
    setInput(element.querySelector<HTMLInputElement>('#catalog-query')!, 'Ursula');
    element.querySelector<HTMLFormElement>('.search')?.dispatchEvent(new SubmitEvent('submit'));
    http
      .expectOne(
        (request) =>
          request.url === '/api/v1/catalog/works' &&
          request.params.get('query') === 'Ursula' &&
          request.params.get('page') === '0' &&
          request.params.get('size') === '12',
      )
      .flush({ ...emptyCatalog(), totalElements: 13 });
    await fixture.whenStable();

    element.querySelectorAll<HTMLButtonElement>('.pagination button')[1].click();
    http
      .expectOne(
        (request) => request.url === '/api/v1/catalog/works' && request.params.get('page') === '1',
      )
      .flush({ ...emptyCatalog(), page: 1, totalElements: 13 });
  });

  it('renders Cyrillic metadata and sends Cyrillic search text unchanged', async () => {
    await initialize([], {
      items: [
        {
          id: 'work-cyrillic',
          title: 'Кириллическая книга',
          contributors: ['Анна Тестова'],
          provisional: true,
          assets: [],
        },
      ],
      page: 0,
      size: 12,
      totalElements: 1,
    });
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('.work-row')?.textContent).toContain('Кириллическая книга');
    expect(element.querySelector('.contributors')?.textContent).toContain('Анна Тестова');

    setInput(element.querySelector<HTMLInputElement>('#catalog-query')!, 'тестова');
    element.querySelector<HTMLFormElement>('.search')?.dispatchEvent(new SubmitEvent('submit'));
    http
      .expectOne(
        (request) =>
          request.url === '/api/v1/catalog/works' &&
          request.params.get('query') === 'тестова',
      )
      .flush(emptyCatalog());
    await fixture.whenStable();
  });

  it('exposes accessible title correction, tags, review, and audit state to curators', async () => {
    await initialize([], {
      items: [{ id: 'work-1', title: 'Observed', contributors: [], provisional: false, assets: [] }],
      page: 0,
      size: 12,
      totalElements: 1,
    });
    const element = fixture.nativeElement as HTMLElement;
    element.querySelector<HTMLButtonElement>('.curate-button')!.click();
    http.expectOne('/api/v1/curation/works/work-1').flush(curation('Observed', 0));
    await fixture.whenStable();

    const title = element.querySelector<HTMLInputElement>('.curation-fields input')!;
    const reason = element.querySelector<HTMLTextAreaElement>('.curation-fields textarea')!;
    setInput(title, 'Corrected title');
    reason.value = 'Verified against the cover';
    reason.dispatchEvent(new Event('input'));
    element.querySelector<HTMLButtonElement>('.curation-actions button')!.click();

    const update = http.expectOne('/api/v1/curation/works/work-1/title');
    expect(update.request.body).toEqual({
      value: 'Corrected title',
      reason: 'Verified against the cover',
      expectedVersion: 0,
    });
    update.flush(curation('Corrected title', 1));
    await new Promise((resolve) => setTimeout(resolve, 0));
    http.expectOne((request) => request.url === '/api/v1/catalog/works').flush(emptyCatalog());
    http.expectOne((request) => request.url === '/api/v1/curation/reviews').flush([]);
    await fixture.whenStable();

    expect(element.querySelector('.curation-editor')?.textContent).toContain('Corrected title');
    expect(element.querySelector('.curation-editor')?.textContent).toContain('Evidence and correction history');
    expect(element.querySelector('.review-queue')).not.toBeNull();
  });

  function setInput(input: HTMLInputElement, value: string) {
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  async function initialize(
    roots: LibraryRoot[],
    catalog: CatalogPage,
    mounts = [{ alias: 'main' }],
  ) {
    http.expectOne('/api/v1/session').flush({
      mode: 'OWNER',
      authenticated: true,
      username: 'owner',
      owner: true,
      capabilities: ['MANAGE_INGESTION_SOURCES', 'CURATE_CATALOG'],
    });
    await new Promise((resolve) => setTimeout(resolve, 0));
    http.expectOne('/api/v1/library-mounts').flush(mounts);
    http.expectOne('/api/v1/library-roots').flush(roots);
    await new Promise((resolve) => setTimeout(resolve, 0));
    http.expectOne((request) => request.url === '/api/v1/catalog/works').flush(catalog);
    http.expectOne('/api/v1/me/library-state').flush(emptyPersonalState());
    await new Promise((resolve) => setTimeout(resolve, 0));
    http.expectOne((request) => request.url === '/api/v1/curation/reviews').flush([]);
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();
  }

  function emptyCatalog(): CatalogPage {
    return { items: [], page: 0, size: 12, totalElements: 0 };
  }

  function emptyPersonalState() {
    return { favoriteContributors: [], readStates: [], collections: [] };
  }

  function root(): LibraryRoot {
    return {
      id: 'root-1',
      name: 'Main library',
      mountAlias: 'main',
      relativePath: 'books',
      mode: 'READ_ONLY_SOURCE',
      availability: 'AVAILABLE',
    };
  }

  function job(state: ScanJob['state']): ScanJob {
    return {
      id: 'job-1',
      rootId: 'root-1',
      state,
      discoveredCount: 0,
      processedCount: 0,
      skippedCount: 0,
      failedCount: 0,
      coverageComplete: false,
      failures: [],
      createdAt: '2026-09-29T12:00:00Z',
    };
  }

  function curation(title: string, version: number) {
    return {
      id: 'work-1',
      version: 0,
      title: {
        value: title,
        source: version === 0 ? 'RESOLVED' : 'CURATED',
        overrideVersion: version,
        observedValues: ['Observed'],
        history: [],
      },
      contributors: [],
      tags: ['classic'],
      reviews: [],
      audit: [],
    };
  }
});

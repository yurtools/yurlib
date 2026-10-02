import { HttpErrorResponse } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import {
  FormField,
  form,
  maxLength,
  minLength,
  pattern,
  required,
  submit,
  validate,
} from '@angular/forms/signals';
import { EMPTY, Observable, Subscription, expand, firstValueFrom, switchMap, timer } from 'rxjs';
import { LibraryApi } from './library-api';
import {
  CatalogPage,
  CatalogFormat,
  CreateLibraryRootRequest,
  LibraryMount,
  LibraryRoot,
  OwnerSession,
  ProblemDetails,
  ScanJob,
  ScanState,
  MetadataReviewItem,
  PersonalCollection,
  PersonalLibraryState,
  WorkCuration,
} from './library.model';

const TERMINAL_SCAN_STATES: ReadonlySet<ScanState> = new Set([
  'SUCCEEDED',
  'COMPLETED_WITH_FAILURES',
  'FAILED',
  'CANCELLED',
]);

const CATALOG_FORMATS: readonly CatalogFormat[] = ['EPUB', 'FB2', 'MOBI', 'PDF', 'DOCX', 'DJVU'];

@Component({
  imports: [FormField],
  selector: 'app-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {
  private static readonly INITIAL_POLL_DELAY_MS = 750;
  private static readonly MAXIMUM_POLL_DELAY_MS = 5_000;
  private static readonly CATALOG_PAGE_SIZE = 12;

  private readonly api = inject(LibraryApi);
  private readonly destroyRef = inject(DestroyRef);
  private pollSubscription?: Subscription;

  protected readonly rootModel = signal<CreateLibraryRootRequest>({
    name: 'Main library',
    mountAlias: '',
    relativePath: '',
    identityToken: '',
    mode: 'READ_ONLY_SOURCE',
    defaultForCovers: false,
  });
  protected readonly rootForm = form(this.rootModel, (schema) => {
    required(schema.name, { message: 'Enter a library name.' });
    maxLength(schema.name, 100, { message: 'Use 100 characters or fewer.' });
    required(schema.mountAlias, { message: 'Select an allowed mount.' });
    pattern(schema.mountAlias, /^[a-z][a-z0-9-]{0,62}$/, {
      message: 'Select a server-advertised mount.',
    });
    maxLength(schema.relativePath, 1024, { message: 'Use 1024 characters or fewer.' });
    validate(schema.relativePath, ({ value }) => {
      const path = value();
      const segments = path.split('/');
      if (
        path.startsWith('/') ||
        path.endsWith('/') ||
        path.includes('\\') ||
        path.includes('//') ||
        segments.some((segment) => segment === '.' || segment === '..')
      ) {
        return {
          kind: 'relativePath',
          message: 'Use a normalized path within the selected mount.',
        };
      }
      return undefined;
    });
    required(schema.identityToken, { message: 'Enter the root identity token.' });
    minLength(schema.identityToken, 16, { message: 'Use at least 16 characters.' });
    maxLength(schema.identityToken, 200, { message: 'Use 200 characters or fewer.' });
  });

  protected readonly searchModel = signal({ query: '' });
  protected readonly catalogFormats = CATALOG_FORMATS;
  protected readonly selectedCatalogFormats = signal<ReadonlySet<CatalogFormat>>(new Set());
  protected readonly searchForm = form(this.searchModel, (schema) => {
    maxLength(schema.query, 200, { message: 'Use 200 characters or fewer.' });
  });

  protected readonly collectionModel = signal({ name: '', ordered: false });
  protected readonly collectionForm = form(this.collectionModel, (schema) => {
    required(schema.name, { message: 'Enter a collection name.' });
    maxLength(schema.name, 200, { message: 'Use 200 characters or fewer.' });
  });

  protected readonly loginModel = signal({ username: 'owner', password: '' });
  protected readonly loginForm = form(this.loginModel, (schema) => {
    required(schema.username, { message: 'Enter the owner username.' });
    maxLength(schema.username, 100, { message: 'Use 100 characters or fewer.' });
    required(schema.password, { message: 'Enter the owner password.' });
    maxLength(schema.password, 1024, { message: 'Use 1024 characters or fewer.' });
  });

  protected readonly mounts = signal<LibraryMount[]>([]);
  protected readonly roots = signal<LibraryRoot[]>([]);
  protected readonly selectedRootId = signal('');
  protected readonly job = signal<ScanJob | undefined>(undefined);
  protected readonly catalog = signal<CatalogPage>({
    items: [],
    page: 0,
    size: 12,
    totalElements: 0,
  });
  protected readonly loadingWorkspace = signal(true);
  protected readonly savingRoot = signal(false);
  protected readonly startingScan = signal(false);
  protected readonly loadingCatalog = signal(false);
  protected readonly workspaceError = signal('');
  protected readonly rootError = signal('');
  protected readonly scanError = signal('');
  protected readonly catalogError = signal('');
  protected readonly session = signal<OwnerSession | undefined>(undefined);
  protected readonly checkingSession = signal(true);
  protected readonly signingIn = signal(false);
  protected readonly signingOut = signal(false);
  protected readonly accessError = signal('');
  protected readonly selectedCuration = signal<WorkCuration | undefined>(undefined);
  protected readonly reviewQueue = signal<MetadataReviewItem[]>([]);
  protected readonly loadingCuration = signal(false);
  protected readonly savingCuration = signal(false);
  protected readonly curationError = signal('');
  protected readonly curationTitle = signal('');
  protected readonly curationTags = signal('');
  protected readonly curationReason = signal('');
  protected readonly curationContributorId = signal('');
  protected readonly curationContributorName = signal('');
  protected readonly curationContributorAliases = signal('');
  protected readonly personalState = signal<PersonalLibraryState>({
    favoriteContributors: [],
    readStates: [],
    collections: [],
  });
  protected readonly loadingPersonalState = signal(false);
  protected readonly savingPersonalState = signal(false);
  protected readonly personalStateError = signal('');

  protected readonly selectedRoot = computed(() =>
    this.roots().find((root) => root.id === this.selectedRootId()),
  );
  protected readonly canManageSources = computed(
    () =>
      this.session()?.mode === 'LOOPBACK_DEVELOPMENT' ||
      this.session()?.capabilities?.includes('MANAGE_INGESTION_SOURCES'),
  );
  protected readonly canCurate = computed(
    () =>
      this.session()?.mode === 'LOOPBACK_DEVELOPMENT' ||
      this.session()?.capabilities?.includes('CURATE_CATALOG'),
  );
  protected readonly catalogStart = computed(() =>
    this.catalog().totalElements === 0 ? 0 : this.catalog().page * this.catalog().size + 1,
  );
  protected readonly catalogEnd = computed(() =>
    Math.min((this.catalog().page + 1) * this.catalog().size, this.catalog().totalElements),
  );
  protected readonly hasPreviousPage = computed(() => this.catalog().page > 0);
  protected readonly hasNextPage = computed(
    () => (this.catalog().page + 1) * this.catalog().size < this.catalog().totalElements,
  );

  constructor() {
    void this.loadSession();
  }

  protected signIn(event: Event) {
    event.preventDefault();
    if (this.signingIn()) return;
    submit(this.loginForm, async () => {
      this.signingIn.set(true);
      this.accessError.set('');
      const credentials = this.loginModel();
      try {
        await firstValueFrom(this.api.login(credentials.username.trim(), credentials.password));
        const session = await firstValueFrom(this.api.session());
        this.session.set(session);
        if (session.authenticated) await this.loadWorkspace();
      } catch (error) {
        this.accessError.set(this.signInMessage(error));
      } finally {
        this.loginModel.update((model) => ({ ...model, password: '' }));
        this.loginForm().reset();
        this.signingIn.set(false);
      }
    });
  }

  protected async signOut() {
    this.signingOut.set(true);
    this.accessError.set('');
    try {
      await firstValueFrom(this.api.logout());
      this.clearWorkspace();
      this.loginModel.update((model) => ({ ...model, password: '' }));
      this.loginForm().reset();
      this.session.set({
        mode: 'OWNER',
        authenticated: false,
        username: null,
        owner: false,
        capabilities: [],
      });
    } catch (error) {
      this.accessError.set(this.problemMessage(error, 'Sign-out failed. Try again.'));
    } finally {
      this.signingOut.set(false);
    }
  }

  protected configureRoot(event: SubmitEvent) {
    event.preventDefault();
    submit(this.rootForm, async () => {
      this.savingRoot.set(true);
      this.rootError.set('');
      try {
        const model = this.rootModel();
        const request = {
          ...model,
          defaultForCovers: model.mode === 'MANAGED_OUTPUT' && Boolean(model.defaultForCovers),
        };
        const root = await firstValueFrom(this.api.createRoot(request));
        this.roots.update((roots) => [...roots, root]);
        this.selectedRootId.set(root.id);
        this.rootModel.update((model) => ({ ...model, identityToken: '' }));
      } catch (error) {
        this.rootError.set(this.problemMessage(error, 'The library root could not be configured.'));
      } finally {
        this.savingRoot.set(false);
      }
    });
  }

  protected async startScan() {
    const root = this.selectedRoot();
    if (!root) return;
    this.startingScan.set(true);
    this.scanError.set('');
    try {
      const queued = await firstValueFrom(this.api.startScan(root.id));
      this.job.set(queued);
      this.pollJob(queued);
    } catch (error) {
      this.scanError.set(this.problemMessage(error, 'The scan could not be started.'));
    } finally {
      this.startingScan.set(false);
    }
  }

  protected searchCatalog(event: SubmitEvent) {
    event.preventDefault();
    submit(this.searchForm, async () => this.loadCatalog(0));
  }

  protected toggleCatalogFormat(format: CatalogFormat, selected: boolean) {
    this.selectedCatalogFormats.update((current) => {
      const next = new Set(current);
      if (selected) next.add(format);
      else next.delete(format);
      return next;
    });
  }

  protected previousPage() {
    if (this.hasPreviousPage()) void this.loadCatalog(this.catalog().page - 1);
  }

  protected nextPage() {
    if (this.hasNextPage()) void this.loadCatalog(this.catalog().page + 1);
  }

  protected downloadUrl(assetId: string) {
    return this.api.downloadUrl(assetId);
  }

  protected coverUrl(workId: string) {
    return this.api.coverUrl(workId);
  }

  protected hideFailedCover(event: Event) {
    const image = event.target;
    if (image instanceof HTMLImageElement) image.hidden = true;
  }

  protected setDefaultForCovers(enabled: boolean) {
    this.rootModel.update((model) => ({ ...model, defaultForCovers: enabled }));
  }

  protected isFavorite(contributorId: string) {
    return this.personalState().favoriteContributors.some(
      (favorite) => favorite.contributorId === contributorId,
    );
  }

  protected readState(workId: string) {
    return this.personalState().readStates.find((state) => state.workId === workId);
  }

  protected async toggleFavorite(contributorId: string) {
    if (this.savingPersonalState()) return;
    await this.runPersonalMutation(
      this.isFavorite(contributorId)
        ? this.api.removeFavoriteContributor(contributorId)
        : this.api.addFavoriteContributor(contributorId),
    );
  }

  protected async toggleRead(workId: string) {
    if (this.savingPersonalState()) return;
    const current = this.readState(workId);
    await this.runPersonalMutation(
      current
        ? this.api.markWorkUnread(workId, current.version)
        : this.api.markWorkRead(workId, -1),
    );
  }

  protected createCollection(event: SubmitEvent) {
    event.preventDefault();
    submit(this.collectionForm, async () => {
      const model = this.collectionModel();
      await this.runPersonalMutation(this.api.createCollection(model.name.trim(), model.ordered));
      if (!this.personalStateError()) {
        this.collectionModel.set({ name: '', ordered: false });
        this.collectionForm().reset();
      }
    });
  }

  protected async updateCollection(
    collection: PersonalCollection,
    name: string,
    ordered: boolean,
  ) {
    if (!name.trim()) {
      this.personalStateError.set('Enter a collection name.');
      return;
    }
    await this.runPersonalMutation(this.api.updateCollection(collection, name.trim(), ordered));
  }

  protected async deleteCollection(collection: PersonalCollection) {
    await this.runPersonalMutation(this.api.deleteCollection(collection));
  }

  protected async addWorkToCollection(workId: string, collectionId: string) {
    const collection = this.personalState().collections.find((item) => item.id === collectionId);
    if (!collection) return;
    await this.runPersonalMutation(this.api.addWorkToCollection(collection, workId));
  }

  protected async removeWorkFromCollection(collection: PersonalCollection, workId: string) {
    await this.runPersonalMutation(this.api.removeWorkFromCollection(collection, workId));
  }

  protected async openCuration(workId: string) {
    this.loadingCuration.set(true);
    this.curationError.set('');
    try {
      const detail = await firstValueFrom(this.api.getWorkCuration(workId));
      this.setCuration(detail);
    } catch (error) {
      this.curationError.set(this.problemMessage(error, 'The curation record could not be loaded.'));
    } finally {
      this.loadingCuration.set(false);
    }
  }

  protected closeCuration() {
    this.selectedCuration.set(undefined);
    this.curationError.set('');
    this.curationReason.set('');
  }

  protected async saveCuratedTitle() {
    const detail = this.selectedCuration();
    if (!detail || this.savingCuration()) return;
    const title = this.curationTitle().trim();
    const reason = this.curationReason().trim();
    if (!title || !reason) {
      this.curationError.set('Enter a title and a reason for the correction.');
      return;
    }
    await this.runCurationUpdate(
      this.api.updateWorkTitle(detail.id, title, reason, detail.title.overrideVersion),
    );
  }

  protected async undoCuratedTitle() {
    const detail = this.selectedCuration();
    if (!detail || this.savingCuration()) return;
    const reason = this.curationReason().trim();
    if (!reason) {
      this.curationError.set('Enter a reason for the undo.');
      return;
    }
    await this.runCurationUpdate(
      this.api.undoWorkTitle(detail.id, reason, detail.title.overrideVersion),
    );
  }

  protected async saveCuratedTags() {
    const detail = this.selectedCuration();
    if (!detail || this.savingCuration()) return;
    const reason = this.curationReason().trim();
    if (!reason) {
      this.curationError.set('Enter a reason for the tag change.');
      return;
    }
    const tags = this.curationTags()
      .split(',')
      .map((tag) => tag.trim())
      .filter((tag) => tag !== '');
    await this.runCurationUpdate(
      this.api.replaceWorkTags(detail.id, tags, reason, detail.version),
    );
  }

  protected selectCurationContributor(contributorId: string) {
    const contributor = this.selectedCuration()?.contributors.find(
      (candidate) => candidate.id === contributorId,
    );
    this.curationContributorId.set(contributor?.id ?? '');
    this.curationContributorName.set(contributor?.displayName ?? '');
    this.curationContributorAliases.set(contributor?.aliases.join(', ') ?? '');
  }

  protected async saveCuratedContributor() {
    const detail = this.selectedCuration();
    const contributor = detail?.contributors.find(
      (candidate) => candidate.id === this.curationContributorId(),
    );
    if (!detail || !contributor || this.savingCuration()) return;
    const displayName = this.curationContributorName().trim();
    const reason = this.curationReason().trim();
    if (!displayName || !reason) {
      this.curationError.set('Enter a contributor display name and a reason for the change.');
      return;
    }
    const aliases = this.curationContributorAliases()
      .split(',')
      .map((alias) => alias.trim())
      .filter((alias) => alias !== '');
    this.savingCuration.set(true);
    this.curationError.set('');
    try {
      await firstValueFrom(
        this.api.updateContributor(
          contributor.id,
          displayName,
          aliases,
          reason,
          contributor.version,
        ),
      );
      this.curationReason.set('');
      await Promise.all([this.openCuration(detail.id), this.loadCatalog(this.catalog().page)]);
    } catch (error) {
      this.curationError.set(this.problemMessage(error, 'The contributor change could not be saved.'));
    } finally {
      this.savingCuration.set(false);
    }
  }

  protected async dismissReview(reviewId: string) {
    const reason = this.curationReason().trim();
    if (!reason) {
      this.curationError.set('Enter a reason before dismissing a review item.');
      return;
    }
    this.savingCuration.set(true);
    this.curationError.set('');
    try {
      await firstValueFrom(this.api.dismissMetadataReview(reviewId, reason));
      await this.loadReviews();
      const detail = this.selectedCuration();
      if (detail) await this.openCuration(detail.id);
    } catch (error) {
      this.curationError.set(this.problemMessage(error, 'The review item could not be dismissed.'));
    } finally {
      this.savingCuration.set(false);
    }
  }

  protected formatBytes(bytes: number) {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  protected formatState(state: string) {
    return state.toLowerCase().replaceAll('_', ' ');
  }

  private async loadWorkspace() {
    this.loadingWorkspace.set(true);
    this.workspaceError.set('');
    try {
      if (this.canManageSources()) {
        const [mounts, roots] = await Promise.all([
          firstValueFrom(this.api.listMounts()),
          firstValueFrom(this.api.listRoots()),
        ]);
        this.mounts.set(mounts);
        this.roots.set(roots);
        if (this.rootModel().mountAlias === '' && mounts.length > 0) {
          this.rootModel.update((model) => ({ ...model, mountAlias: mounts[0].alias }));
        }
        if (roots.length > 0) this.selectedRootId.set(roots[0].id);
      }
    } catch (error) {
      this.workspaceError.set(
        this.problemMessage(error, 'The library workspace could not be loaded.'),
      );
    } finally {
      this.loadingWorkspace.set(false);
    }
    await Promise.all([this.loadCatalog(0), this.loadPersonalState()]);
    if (this.canCurate()) await this.loadReviews();
  }

  private async loadSession() {
    this.checkingSession.set(true);
    this.accessError.set('');
    try {
      const session = await firstValueFrom(this.api.session());
      this.session.set(session);
      if (session.authenticated) await this.loadWorkspace();
    } catch (error) {
      this.accessError.set(this.problemMessage(error, 'Yurlib access could not be checked.'));
    } finally {
      this.checkingSession.set(false);
    }
  }

  private clearWorkspace() {
    this.pollSubscription?.unsubscribe();
    this.mounts.set([]);
    this.roots.set([]);
    this.selectedRootId.set('');
    this.job.set(undefined);
    this.catalog.set({ items: [], page: 0, size: App.CATALOG_PAGE_SIZE, totalElements: 0 });
    this.selectedCuration.set(undefined);
    this.reviewQueue.set([]);
    this.personalState.set({ favoriteContributors: [], readStates: [], collections: [] });
  }

  private async loadCatalog(page: number) {
    this.loadingCatalog.set(true);
    this.catalogError.set('');
    try {
      this.catalog.set(
        await firstValueFrom(
          this.api.searchCatalog(
            this.searchModel().query.trim(),
            page,
            App.CATALOG_PAGE_SIZE,
            [...this.selectedCatalogFormats()],
          ),
        ),
      );
    } catch (error) {
      this.catalogError.set(this.problemMessage(error, 'The catalog could not be loaded.'));
    } finally {
      this.loadingCatalog.set(false);
    }
  }

  private async loadPersonalState() {
    this.loadingPersonalState.set(true);
    this.personalStateError.set('');
    try {
      this.personalState.set(await firstValueFrom(this.api.personalLibraryState()));
    } catch (error) {
      this.personalStateError.set(
        this.problemMessage(error, 'Your personal library state could not be loaded.'),
      );
    } finally {
      this.loadingPersonalState.set(false);
    }
  }

  private async runPersonalMutation(request: Observable<unknown>) {
    this.savingPersonalState.set(true);
    this.personalStateError.set('');
    try {
      await firstValueFrom(request);
      await this.loadPersonalState();
    } catch (error) {
      this.personalStateError.set(
        this.problemMessage(error, 'Your personal library change could not be saved.'),
      );
    } finally {
      this.savingPersonalState.set(false);
    }
  }

  private pollJob(queued: ScanJob) {
    this.pollSubscription?.unsubscribe();
    let delay = App.INITIAL_POLL_DELAY_MS;
    this.pollSubscription = timer(delay)
      .pipe(
        switchMap(() => this.api.getJob(queued.id)),
        expand((current) => {
          if (TERMINAL_SCAN_STATES.has(current.state)) return EMPTY;
          delay = Math.min(delay * 2, App.MAXIMUM_POLL_DELAY_MS);
          return timer(delay).pipe(switchMap(() => this.api.getJob(queued.id)));
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (current) => {
          this.job.set(current);
          if (TERMINAL_SCAN_STATES.has(current.state)) void this.loadCatalog(0);
        },
        error: (error) => {
          this.scanError.set(
            this.problemMessage(error, 'Scan progress is temporarily unavailable.'),
          );
        },
      });
  }

  private async runCurationUpdate(request: ReturnType<LibraryApi['updateWorkTitle']>) {
    this.savingCuration.set(true);
    this.curationError.set('');
    try {
      this.setCuration(await firstValueFrom(request));
      this.curationReason.set('');
      await Promise.all([this.loadCatalog(this.catalog().page), this.loadReviews()]);
    } catch (error) {
      this.curationError.set(this.problemMessage(error, 'The catalog change could not be saved.'));
    } finally {
      this.savingCuration.set(false);
    }
  }

  private setCuration(detail: WorkCuration) {
    this.selectedCuration.set(detail);
    this.curationTitle.set(detail.title.value ?? '');
    this.curationTags.set(detail.tags.join(', '));
    this.selectCurationContributor(detail.contributors[0]?.id ?? '');
  }

  private async loadReviews() {
    try {
      this.reviewQueue.set(await firstValueFrom(this.api.listMetadataReviews()));
    } catch (error) {
      this.curationError.set(this.problemMessage(error, 'The metadata review queue could not be loaded.'));
    }
  }

  private problemMessage(error: unknown, fallback: string) {
    if (!(error instanceof HttpErrorResponse)) return fallback;
    if (error.status === 401 && this.session()?.mode === 'OWNER') {
      this.clearWorkspace();
      this.session.set({
        mode: 'OWNER',
        authenticated: false,
        username: null,
        owner: false,
        capabilities: [],
      });
    }
    const problem = error.error as ProblemDetails | undefined;
    const detail = problem?.detail ?? problem?.title;
    const code = problem?.code;
    return detail && code ? `${detail} (${code})` : detail || fallback;
  }

  private signInMessage(error: unknown) {
    if (error instanceof HttpErrorResponse && error.status === 401) {
      return 'Sign-in failed. Check the credentials and try again.';
    }
    return this.problemMessage(error, 'Sign-in failed. Try again.');
  }
}

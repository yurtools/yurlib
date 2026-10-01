package org.yurlib.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.yurlib.server.security.Capability;
import org.yurlib.server.security.JdbcUserAccountStore;
import org.yurlib.server.security.UserAccountSummary;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        properties = {
            "yurlib.library.scan.worker-enabled=false",
            "yurlib.security.owner.username=owner",
            "yurlib.security.owner.password=correct horse battery staple"
        })
@AutoConfigureMockMvc
@ActiveProfiles("owner-test")
@Testcontainers(disabledWithoutDocker = true)
class M2AuthorizationAcceptanceTest {

    private static final Path MOUNT = createMount();
    private static final UUID ALLOWED_ROOT_ID = UUID.fromString("286916cb-8490-4d68-9fb0-277430ff6ef1");
    private static final UUID DENIED_ROOT_ID = UUID.fromString("bb67a234-a791-4e44-bce6-ad326c47fe40");
    private static final UUID ALLOWED_ASSET_ID = UUID.fromString("48bf5caf-3acc-4626-9143-3eb7fe0982ef");
    private static final UUID DENIED_ASSET_ID = UUID.fromString("41ae99dd-bac8-40cc-aea4-f927354b57eb");
    private static final UUID MIXED_ALLOWED_ASSET_ID = UUID.fromString("6f81d888-6cbb-46e1-803c-d3d263364a80");
    private static final UUID MIXED_DENIED_ASSET_ID = UUID.fromString("6696730e-35a0-4571-9d97-054b705de3e9");
    private static final UUID DENIED_JOB_ID = UUID.fromString("9b89a979-4a02-47c5-afc9-bfda31cc944a");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcUserAccountStore users;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @DynamicPropertySource
    static void mount(DynamicPropertyRegistry properties) {
        properties.add("yurlib.library.mounts[0].alias", () -> "m2-acceptance");
        properties.add("yurlib.library.mounts[0].path", MOUNT::toString);
    }

    @Test
    void enforcesTwoUserRootProjectionCapabilitiesAndImmediateSessionInvalidation() throws Exception {
        seedCatalog();
        var owner = users.findOwner().orElseThrow();
        assertThat(owner.capabilities()).containsExactlyInAnyOrder(Capability.values());

        var ownerSession = login("owner", "correct horse battery staple");
        var readerId = createReader(ownerSession);
        var readerSession = login("reader", "reader password 123");

        mockMvc.perform(get("/api/v1/catalog/works").session(readerSession.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
        mockMvc.perform(get("/api/v1/library-roots").session(readerSession.session()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/curation/reviews").session(readerSession.session()))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/admin/users/{userId}/root-denies/{rootId}", readerId, DENIED_ROOT_ID)
                        .session(ownerSession.session())
                        .cookie(ownerSession.csrfCookie())
                        .header("X-XSRF-TOKEN", ownerSession.csrfCookie().getValue()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/catalog/works").session(readerSession.session()))
                .andExpect(status().isUnauthorized());

        readerSession = login("reader", "reader password 123");
        mockMvc.perform(get("/api/v1/catalog/works").session(readerSession.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].title").value("Allowed work"))
                .andExpect(jsonPath("$.items[1].title").value("Mixed work"))
                .andExpect(jsonPath("$.items[1].assets.length()").value(1));
        mockMvc.perform(get("/api/v1/assets/{assetId}/content", DENIED_ASSET_ID).session(readerSession.session()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/assets/{assetId}/content", ALLOWED_ASSET_ID)
                        .session(readerSession.session()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(Files.readAllBytes(MOUNT.resolve("allowed/book.epub"))));

        mockMvc.perform(put("/api/v1/admin/users/{userId}/capabilities", readerId)
                        .session(ownerSession.session())
                        .cookie(ownerSession.csrfCookie())
                        .header("X-XSRF-TOKEN", ownerSession.csrfCookie().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"capabilities":["MANAGE_INGESTION_SOURCES","CURATE_CATALOG"]}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/library-roots").session(readerSession.session()))
                .andExpect(status().isUnauthorized());
        readerSession = login("reader", "reader password 123");
        mockMvc.perform(get("/api/v1/library-roots").session(readerSession.session()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/jobs/{jobId}", DENIED_JOB_ID).session(readerSession.session()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/curation/reviews").session(readerSession.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].detail").value("Allowed metadata review"));

        users.resetPassword(owner.userId(), owner.userId(), passwordEncoder.encode("recovered owner password"), true);
        mockMvc.perform(get("/api/v1/admin/users").session(ownerSession.session()))
                .andExpect(status().isUnauthorized());
        login("owner", "recovered owner password");
        assertThat(JdbcClient.create(dataSource)
                        .sql("SELECT count(*) FROM security_audit_event WHERE event_type = 'OWNER_RECOVERED'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    private UUID createReader(Session ownerSession) throws Exception {
        var response = mockMvc.perform(post("/api/v1/admin/users")
                        .session(ownerSession.session())
                        .cookie(ownerSession.csrfCookie())
                        .header("X-XSRF-TOKEN", ownerSession.csrfCookie().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"Reader","password":"reader password 123"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("Reader"))
                .andReturn();
        return objectMapper
                .readValue(response.getResponse().getContentAsString(), UserAccountSummary.class)
                .id();
    }

    private Session login(String username, String password) throws Exception {
        var token = mockMvc.perform(get("/api/v1/session")).andReturn();
        var csrfCookie = token.getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        var result = mockMvc.perform(post("/api/v1/session")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .param("username", username)
                        .param("password", password))
                .andExpect(status().isNoContent())
                .andReturn();
        return new Session((MockHttpSession) result.getRequest().getSession(false), csrfCookie);
    }

    private void seedCatalog() throws IOException {
        var jdbc = JdbcClient.create(dataSource);
        insertRoot(jdbc, ALLOWED_ROOT_ID, "Allowed root", "allowed");
        insertRoot(jdbc, DENIED_ROOT_ID, "Denied root", "denied");
        var allowedWorkId = insertWork(jdbc, "Allowed work", ALLOWED_ROOT_ID, ALLOWED_ASSET_ID, "allowed/book.epub");
        var deniedWorkId = insertWork(jdbc, "Denied work", DENIED_ROOT_ID, DENIED_ASSET_ID, "denied/book.pdf");
        insertMixedWork(jdbc);
        insertReview(jdbc, allowedWorkId, "Allowed metadata review");
        insertReview(jdbc, deniedWorkId, "Denied metadata review");
        jdbc.sql("""
                INSERT INTO scan_job (
                    id, library_root_id, state, correlation_id, extraction_version,
                    created_at, completed_at, completion_coverage
                ) VALUES (
                    :id, :rootId, 'SUCCEEDED', 'm2-authorization', 'm2-acceptance',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, TRUE
                )
                """).param("id", DENIED_JOB_ID).param("rootId", DENIED_ROOT_ID).update();
    }

    private static void insertRoot(JdbcClient jdbc, UUID id, String name, String relativePath) {
        jdbc.sql("""
                INSERT INTO library_root (
                    id, name, mount_alias, relative_base_path,
                    expected_identity_digest, mode, availability
                ) VALUES (
                    :id, :name, 'm2-acceptance', :path, :digest,
                    'READ_ONLY_SOURCE', 'AVAILABLE'
                )
                """)
                .param("id", id)
                .param("name", name)
                .param("path", relativePath)
                .param("digest", sha256("m2-root-token"))
                .update();
    }

    private static UUID insertWork(JdbcClient jdbc, String title, UUID rootId, UUID assetId, String path)
            throws IOException {
        var workId = UUID.randomUUID();
        var editionId = UUID.randomUUID();
        jdbc.sql("INSERT INTO work (id, provisional_title, content_kind) VALUES (:id, :title, 'BOOK')")
                .param("id", workId)
                .param("title", title)
                .update();
        jdbc.sql("INSERT INTO edition (id, work_id) VALUES (:id, :workId)")
                .param("id", editionId)
                .param("workId", workId)
                .update();
        insertAsset(jdbc, editionId, rootId, assetId, path);
        return workId;
    }

    private static void insertReview(JdbcClient jdbc, UUID workId, String detail) {
        jdbc.sql("""
                INSERT INTO metadata_review_item (
                    id, subject_id, subject_type, field_name, reason_code,
                    detail, rule_name, rule_version
                ) VALUES (
                    :id, :workId, 'WORK', 'title', 'CONFLICT',
                    :detail, 'authorization-test', '1'
                )
                """)
                .param("id", UUID.randomUUID())
                .param("workId", workId)
                .param("detail", detail)
                .update();
    }

    private static void insertMixedWork(JdbcClient jdbc) throws IOException {
        var workId = UUID.randomUUID();
        jdbc.sql("INSERT INTO work (id, provisional_title, content_kind) VALUES (:id, 'Mixed work', 'BOOK')")
                .param("id", workId)
                .update();
        var allowedEdition = UUID.randomUUID();
        var deniedEdition = UUID.randomUUID();
        for (var editionId : java.util.List.of(allowedEdition, deniedEdition)) {
            jdbc.sql("INSERT INTO edition (id, work_id) VALUES (:id, :workId)")
                    .param("id", editionId)
                    .param("workId", workId)
                    .update();
        }
        insertAsset(jdbc, allowedEdition, ALLOWED_ROOT_ID, MIXED_ALLOWED_ASSET_ID, "allowed/mixed.epub");
        insertAsset(jdbc, deniedEdition, DENIED_ROOT_ID, MIXED_DENIED_ASSET_ID, "denied/mixed.pdf");
    }

    private static void insertAsset(JdbcClient jdbc, UUID editionId, UUID rootId, UUID assetId, String path)
            throws IOException {
        var source = MOUNT.resolve(path);
        var attributes = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        jdbc.sql("""
                INSERT INTO asset (
                    id, edition_id, format, byte_size, derivation, extraction_version
                ) VALUES (
                    :id, :editionId, :format, :size, 'ORIGINAL', 'm2-acceptance'
                )
                """)
                .param("id", assetId)
                .param("editionId", editionId)
                .param("format", path.endsWith(".pdf") ? "PDF" : "EPUB")
                .param("size", attributes.size())
                .update();
        jdbc.sql("""
                INSERT INTO asset_location (
                    id, asset_id, library_root_id, normalized_relative_path,
                    byte_size, modified_at, file_key, availability
                ) VALUES (
                    :id, :assetId, :rootId, :path,
                    :size, :modifiedAt, :fileKey, 'AVAILABLE'
                )
                """)
                .param("id", UUID.randomUUID())
                .param("assetId", assetId)
                .param("rootId", rootId)
                .param("path", Path.of(path).getFileName().toString())
                .param("size", attributes.size())
                .param(
                        "modifiedAt",
                        java.sql.Timestamp.from(attributes.lastModifiedTime().toInstant()))
                .param(
                        "fileKey",
                        attributes.fileKey() == null
                                ? null
                                : attributes.fileKey().toString())
                .update();
    }

    private static Path createMount() {
        try {
            var mount = Files.createTempDirectory("yurlib-m2-auth-");
            Files.createDirectories(mount.resolve("allowed"));
            Files.createDirectories(mount.resolve("denied"));
            Files.writeString(mount.resolve("allowed/.yurlib-root-id"), "m2-root-token");
            Files.writeString(mount.resolve("denied/.yurlib-root-id"), "m2-root-token");
            Files.writeString(mount.resolve("allowed/book.epub"), "allowed bytes");
            Files.writeString(mount.resolve("denied/book.pdf"), "denied bytes");
            Files.writeString(mount.resolve("allowed/mixed.epub"), "mixed allowed bytes");
            Files.writeString(mount.resolve("denied/mixed.pdf"), "mixed denied bytes");
            return mount;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record Session(MockHttpSession session, Cookie csrfCookie) {}
}

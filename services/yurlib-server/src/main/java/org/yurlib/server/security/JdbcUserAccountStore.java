package org.yurlib.server.security;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
@Profile("!loopback-dev")
public class JdbcUserAccountStore implements UserDetailsService {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcUserAccountStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper.rebuild().build();
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return findByNormalizedUsername(normalize(username))
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    public boolean hasOwner() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM user_account WHERE owner)")
                .query(Boolean.class)
                .single();
    }

    @Transactional
    public PersistedUserPrincipal bootstrapOwner(String username, String passwordHash) {
        jdbc.sql("""
                SELECT 1
                FROM (SELECT pg_advisory_xact_lock(hashtext('yurlib-owner-bootstrap'))) locked
                """).query(Integer.class).single();
        var existing = findOwner();
        if (existing.isPresent()) {
            return existing.get();
        }
        var id = UUID.randomUUID();
        insertUser(id, username, passwordHash, true);
        for (var capability : Capability.values()) {
            insertCapability(id, capability);
        }
        audit(id, id, "OWNER_BOOTSTRAPPED", java.util.Map.of());
        return findById(id).orElseThrow();
    }

    @Transactional
    public UserAccountSummary createUser(UUID actorId, String username, String passwordHash) {
        var id = UUID.randomUUID();
        try {
            insertUser(id, username, passwordHash, false);
        } catch (DataIntegrityViolationException failure) {
            if (!containsConstraint(failure, "user_account_normalized_username_key")) {
                throw failure;
            }
            throw new UserAccountFailure(
                    UserAccountFailure.Code.USERNAME_EXISTS, "The username is already in use.", failure);
        }
        audit(actorId, id, "USER_CREATED", java.util.Map.of("username", username.strip()));
        return requireSummary(id);
    }

    public List<UserAccountSummary> listUsers() {
        return jdbc.sql("SELECT id FROM user_account ORDER BY normalized_username").query(UUID.class).list().stream()
                .map(this::requireSummary)
                .toList();
    }

    @Transactional
    public UserAccountSummary setEnabled(UUID actorId, UUID userId, boolean enabled) {
        var target = requirePrincipal(userId);
        if (target.owner() && !enabled) {
            throw new UserAccountFailure(
                    UserAccountFailure.Code.OWNER_MUTATION_FORBIDDEN, "The persisted owner cannot be disabled.");
        }
        jdbc.sql("""
                UPDATE user_account
                SET enabled = :enabled,
                    authorization_version = authorization_version + CASE WHEN enabled <> :enabled THEN 1 ELSE 0 END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """).param("enabled", enabled).param("id", userId).update();
        audit(actorId, userId, enabled ? "USER_ENABLED" : "USER_DISABLED", java.util.Map.of());
        return requireSummary(userId);
    }

    @Transactional
    public UserAccountSummary resetPassword(UUID actorId, UUID userId, String passwordHash, boolean recovery) {
        requirePrincipal(userId);
        jdbc.sql("""
                UPDATE user_account
                SET password_hash = :passwordHash,
                    authorization_version = authorization_version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """).param("passwordHash", passwordHash).param("id", userId).update();
        audit(actorId, userId, recovery ? "OWNER_RECOVERED" : "CREDENTIAL_RESET", java.util.Map.of());
        return requireSummary(userId);
    }

    @Transactional
    public UserAccountSummary replaceCapabilities(UUID actorId, UUID userId, Set<Capability> requested) {
        var target = requirePrincipal(userId);
        if (target.owner() && !requested.containsAll(Set.of(Capability.values()))) {
            throw new UserAccountFailure(
                    UserAccountFailure.Code.OWNER_MUTATION_FORBIDDEN,
                    "The owner must retain source-manager and curator capabilities.");
        }
        var previous = target.capabilities();
        jdbc.sql("DELETE FROM user_capability WHERE user_id = :id")
                .param("id", userId)
                .update();
        requested.forEach(capability -> insertCapability(userId, capability));
        if (!requested.equals(previous)) {
            incrementAuthorizationVersion(userId);
        }
        audit(
                actorId,
                userId,
                "CAPABILITIES_CHANGED",
                java.util.Map.of(
                        "capabilities",
                        requested.stream().map(Enum::name).sorted().toList()));
        return requireSummary(userId);
    }

    @Transactional
    public UserAccountSummary denyRoot(UUID actorId, UUID userId, UUID rootId) {
        requirePrincipal(userId);
        if (!rootExists(rootId)) {
            throw new UserAccountFailure(UserAccountFailure.Code.ROOT_NOT_FOUND, "The library root does not exist.");
        }
        var changed =
                jdbc.sql("""
                INSERT INTO user_root_deny (user_id, library_root_id)
                VALUES (:userId, :rootId)
                ON CONFLICT DO NOTHING
                """).param("userId", userId).param("rootId", rootId).update();
        if (changed > 0) {
            incrementAuthorizationVersion(userId);
            audit(actorId, userId, "ROOT_DENIED", java.util.Map.of("rootId", rootId));
        }
        return requireSummary(userId);
    }

    @Transactional
    public UserAccountSummary allowRoot(UUID actorId, UUID userId, UUID rootId) {
        requirePrincipal(userId);
        var changed = jdbc.sql("DELETE FROM user_root_deny WHERE user_id = :userId AND library_root_id = :rootId")
                .param("userId", userId)
                .param("rootId", rootId)
                .update();
        if (changed > 0) {
            audit(actorId, userId, "ROOT_ALLOWED", java.util.Map.of("rootId", rootId));
        }
        return requireSummary(userId);
    }

    public Optional<PersistedUserPrincipal> findById(UUID id) {
        return jdbc.sql("""
                SELECT id, username, password_hash, owner, enabled, authorization_version
                FROM user_account
                WHERE id = :id
                """).param("id", id).query(UserRow.class).optional().map(this::toPrincipal);
    }

    public Optional<PersistedUserPrincipal> findOwner() {
        return jdbc.sql("""
                SELECT id, username, password_hash, owner, enabled, authorization_version
                FROM user_account
                WHERE owner
                """).query(UserRow.class).optional().map(this::toPrincipal);
    }

    private Optional<PersistedUserPrincipal> findByNormalizedUsername(String username) {
        return jdbc.sql("""
                SELECT id, username, password_hash, owner, enabled, authorization_version
                FROM user_account
                WHERE normalized_username = :username
                """)
                .param("username", username)
                .query(UserRow.class)
                .optional()
                .map(this::toPrincipal);
    }

    private PersistedUserPrincipal requirePrincipal(UUID id) {
        return findById(id)
                .orElseThrow(() ->
                        new UserAccountFailure(UserAccountFailure.Code.USER_NOT_FOUND, "The user does not exist."));
    }

    private UserAccountSummary requireSummary(UUID id) {
        var principal = requirePrincipal(id);
        var denies = jdbc.sql("SELECT library_root_id FROM user_root_deny WHERE user_id = :id ORDER BY library_root_id")
                .param("id", id)
                .query(UUID.class)
                .set();
        return new UserAccountSummary(
                principal.userId(),
                principal.username(),
                principal.owner(),
                principal.enabled(),
                principal.authorizationVersion(),
                principal.capabilities(),
                denies);
    }

    private PersistedUserPrincipal toPrincipal(UserRow row) {
        var capabilities = jdbc
                .sql("SELECT capability FROM user_capability WHERE user_id = :id ORDER BY capability")
                .param("id", row.id())
                .query(String.class)
                .list()
                .stream()
                .map(Capability::valueOf)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return new PersistedUserPrincipal(
                row.id(),
                row.username(),
                row.passwordHash(),
                row.owner(),
                row.enabled(),
                row.authorizationVersion(),
                capabilities);
    }

    private void insertUser(UUID id, String username, String passwordHash, boolean owner) {
        var display = username.strip();
        jdbc.sql("""
                INSERT INTO user_account (
                    id, username, normalized_username, password_hash, owner
                ) VALUES (
                    :id, :username, :normalizedUsername, :passwordHash, :owner
                )
                """)
                .param("id", id)
                .param("username", display)
                .param("normalizedUsername", normalize(display))
                .param("passwordHash", passwordHash)
                .param("owner", owner)
                .update();
    }

    private void insertCapability(UUID userId, Capability capability) {
        jdbc.sql("INSERT INTO user_capability (user_id, capability) VALUES (:id, :capability)")
                .param("id", userId)
                .param("capability", capability.name())
                .update();
    }

    private void incrementAuthorizationVersion(UUID userId) {
        jdbc.sql("""
                UPDATE user_account
                SET authorization_version = authorization_version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """).param("id", userId).update();
    }

    private boolean rootExists(UUID rootId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM library_root WHERE id = :id)")
                .param("id", rootId)
                .query(Boolean.class)
                .single();
    }

    private void audit(UUID actorId, UUID targetId, String eventType, java.util.Map<String, ?> details) {
        jdbc.sql("""
                INSERT INTO security_audit_event (
                    id, actor_user_id, target_user_id, event_type, details
                ) VALUES (
                    :id, :actorId, :targetId, :eventType, CAST(:details AS jsonb)
                )
                """)
                .param("id", UUID.randomUUID())
                .param("actorId", actorId)
                .param("targetId", targetId)
                .param("eventType", eventType)
                .param("details", objectMapper.writeValueAsString(details))
                .update();
    }

    static String normalize(String username) {
        return username.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean containsConstraint(Throwable failure, String constraint) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(constraint)) {
                return true;
            }
        }
        return false;
    }

    private record UserRow(
            UUID id, String username, String passwordHash, boolean owner, boolean enabled, long authorizationVersion) {}
}

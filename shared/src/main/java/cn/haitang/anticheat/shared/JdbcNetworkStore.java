package cn.haitang.anticheat.shared;

import cn.haitang.anticheat.shared.NetworkModels.ActiveBan;
import cn.haitang.anticheat.shared.NetworkModels.Appeal;
import cn.haitang.anticheat.shared.NetworkModels.AppealStatus;
import cn.haitang.anticheat.shared.NetworkModels.AppealSubmitResult;
import cn.haitang.anticheat.shared.NetworkModels.AppealFilter;
import cn.haitang.anticheat.shared.NetworkModels.AppealView;
import cn.haitang.anticheat.shared.NetworkModels.DashboardOverview;
import cn.haitang.anticheat.shared.NetworkModels.DetectionEvidence;
import cn.haitang.anticheat.shared.NetworkModels.EnforcementDecision;
import cn.haitang.anticheat.shared.NetworkModels.EnforcementKind;
import cn.haitang.anticheat.shared.NetworkModels.EnforcementRequest;
import cn.haitang.anticheat.shared.NetworkModels.FilterOptions;
import cn.haitang.anticheat.shared.NetworkModels.HistoryEntry;
import cn.haitang.anticheat.shared.NetworkModels.NamedCount;
import cn.haitang.anticheat.shared.NetworkModels.Page;
import cn.haitang.anticheat.shared.NetworkModels.PardonResult;
import cn.haitang.anticheat.shared.NetworkModels.PlayerProfile;
import cn.haitang.anticheat.shared.NetworkModels.PlayerReference;
import cn.haitang.anticheat.shared.NetworkModels.Punishment;
import cn.haitang.anticheat.shared.NetworkModels.PunishmentFilter;
import cn.haitang.anticheat.shared.NetworkModels.PunishmentView;
import cn.haitang.anticheat.shared.NetworkModels.TimeBucket;
import cn.haitang.anticheat.shared.NetworkModels.WarningEvidence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

/** MariaDB-backed network state shared by every Paper server and the Velocity proxy. */
public final class JdbcNetworkStore {
    private final DatabaseConfig config;

    public JdbcNetworkStore(DatabaseConfig config) {
        this.config = config;
    }

    private Connection open() throws SQLException {
        try {
            Class.forName("org.mariadb.jdbc.Driver");
        } catch (ClassNotFoundException error) {
            throw new SQLException("MariaDB JDBC driver is not available", error);
        }
        return DriverManager.getConnection(config.jdbcUrl(), config.username(), config.password());
    }

    public void initialize() throws SQLException {
        List<String> ddl = List.of(
                "CREATE TABLE IF NOT EXISTS sayaka_players ("
                        + "player_uuid VARCHAR(36) PRIMARY KEY, player_name VARCHAR(64) NOT NULL, "
                        + "ban_count INT NOT NULL DEFAULT 0)",
                "CREATE TABLE IF NOT EXISTS sayaka_strikes ("
                        + "id BIGINT AUTO_INCREMENT PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, "
                        + "player_name VARCHAR(64) NOT NULL, created_at BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS sayaka_whitelist ("
                        + "player_uuid VARCHAR(36) PRIMARY KEY, player_name VARCHAR(64) NOT NULL)",
                "CREATE TABLE IF NOT EXISTS sayaka_history ("
                        + "id BIGINT AUTO_INCREMENT PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, "
                        + "created_at BIGINT NOT NULL, entry_text VARCHAR(2048) NOT NULL)",
                "CREATE TABLE IF NOT EXISTS sayaka_punishments ("
                        + "punishment_id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, "
                        + "player_name VARCHAR(64) NOT NULL, server_id VARCHAR(64) NOT NULL, "
                        + "banned_at BIGINT NOT NULL, expires_at BIGINT NOT NULL, check_id VARCHAR(64) NOT NULL, "
                        + "vl DOUBLE NOT NULL, hours INT NOT NULL, ban_number INT NOT NULL, "
                        + "warnings_json LONGTEXT NOT NULL, detections_json LONGTEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS sayaka_active_bans ("
                        + "player_uuid VARCHAR(36) PRIMARY KEY, punishment_id VARCHAR(36) NOT NULL, "
                        + "player_name VARCHAR(64) NOT NULL, reason_text VARCHAR(2048) NOT NULL, expires_at BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS sayaka_appeals ("
                        + "punishment_id VARCHAR(36) PRIMARY KEY, player_name VARCHAR(64) NOT NULL, "
                        + "reason_text VARCHAR(2000) NOT NULL, contact_text VARCHAR(200) NOT NULL, "
                        + "submitted_at BIGINT NOT NULL, status VARCHAR(16) NOT NULL, "
                        + "resolved_at BIGINT NOT NULL DEFAULT 0, note_text VARCHAR(2000) NOT NULL)"
        );
        List<String> indexes = List.of(
                "CREATE INDEX sayaka_strikes_player_at ON sayaka_strikes(player_uuid, created_at)",
                "CREATE INDEX sayaka_history_player_at ON sayaka_history(player_uuid, created_at)",
                "CREATE INDEX sayaka_punishments_banned_at ON sayaka_punishments(banned_at)",
                "CREATE INDEX sayaka_punishments_player_at ON sayaka_punishments(player_uuid, banned_at)",
                "CREATE INDEX sayaka_punishments_server_at ON sayaka_punishments(server_id, banned_at)",
                "CREATE INDEX sayaka_punishments_check_at ON sayaka_punishments(check_id, banned_at)",
                "CREATE INDEX sayaka_appeals_status_at ON sayaka_appeals(status, submitted_at)"
        );
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            for (String sql : ddl) statement.execute(sql);
            for (String sql : indexes) createIndex(statement, sql);
        }
    }

    private static void createIndex(Statement statement, String sql) throws SQLException {
        try {
            statement.execute(sql);
        } catch (SQLException error) {
            if (!isDuplicateIndex(error)) throw error;
        }
    }

    private static boolean isDuplicateIndex(SQLException error) {
        return "42S11".equals(error.getSQLState()) || error.getErrorCode() == 1061;
    }

    public boolean healthCheck() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            return true;
        } catch (SQLException ignored) {
            return false;
        }
    }

    /** Atomically records the next strike or creates a network-wide temporary ban. */
    public EnforcementDecision prepareEnforcement(EnforcementRequest request, long now) throws SQLException {
        SQLException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return prepareEnforcementOnce(request, now);
            } catch (SQLException error) {
                if (!"40001".equals(error.getSQLState())) throw error;
                last = error;
                LockSupport.parkNanos((attempt + 1L) * 10_000_000L);
            }
        }
        throw last;
    }

    private EnforcementDecision prepareEnforcementOnce(EnforcementRequest request, long now) throws SQLException {
        try (Connection connection = open()) {
            // Create the lock row in autocommit mode so concurrent first-time punishments can
            // both observe it before entering the serializable decision transaction.
            ensurePlayer(connection, request.playerId(), request.playerName());
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try {
                int banCount = lockBanCount(connection, request.playerId());
                Punishment existingBan = findActivePunishment(connection, request.playerId(), now);
                if (existingBan != null) {
                    connection.commit();
                    return new EnforcementDecision(EnforcementKind.TEMPBAN,
                            request.strikesToTempban(), request.strikesToTempban(), existingBan);
                }
                long cutoff = now - request.strikeWindowHours() * 3_600_000L;
                execute(connection, "DELETE FROM sayaka_strikes WHERE player_uuid=? AND created_at<?",
                        request.playerId().toString(), cutoff);
                int strikes = count(connection,
                        "SELECT COUNT(*) FROM sayaka_strikes WHERE player_uuid=?", request.playerId().toString());
                int next = strikes + 1;
                if (next < request.strikesToTempban()) {
                    execute(connection,
                            "INSERT INTO sayaka_strikes(player_uuid,player_name,created_at) VALUES(?,?,?)",
                            request.playerId().toString(), request.playerName(), now);
                    addHistory(connection, request.playerId(), now,
                            "[踢出] " + request.check() + " VL " + formatVl(request.vl())
                                    + " (strike " + next + "/" + request.strikesToTempban() + ")");
                    connection.commit();
                    return new EnforcementDecision(EnforcementKind.KICK, next,
                            request.strikesToTempban(), null);
                }

                int banNumber = banCount + 1;
                int hours = request.tempbanHours().get(Math.min(banCount, request.tempbanHours().size() - 1));
                String punishmentId = UUID.randomUUID().toString();
                long expiresAt = now + hours * 3_600_000L;
                Punishment punishment = new Punishment(
                        punishmentId, request.playerId(), request.playerName(), request.serverId(),
                        now, expiresAt, request.check(), request.vl(), hours, banNumber,
                        request.warnings(), request.detections());
                insertPunishment(connection, punishment);
                String reason = "Sayaka AntiCheat: " + request.check() + " (处罚 ID " + punishmentId + ")";
                execute(connection, "DELETE FROM sayaka_active_bans WHERE player_uuid=?",
                        request.playerId().toString());
                execute(connection, "INSERT INTO sayaka_active_bans"
                                + "(player_uuid,punishment_id,player_name,reason_text,expires_at) VALUES(?,?,?,?,?)",
                        request.playerId().toString(), punishmentId, request.playerName(), reason, expiresAt);
                execute(connection, "UPDATE sayaka_players SET ban_count=? WHERE player_uuid=?",
                        banNumber, request.playerId().toString());
                execute(connection, "DELETE FROM sayaka_strikes WHERE player_uuid=?",
                        request.playerId().toString());
                addHistory(connection, request.playerId(), now,
                        "[封禁] " + request.check() + "，时长 " + hours + " 小时（第 " + banNumber
                                + " 次，服务器 " + request.serverId() + "，处罚 ID " + punishmentId + "）");
                connection.commit();
                return new EnforcementDecision(EnforcementKind.TEMPBAN, next,
                        request.strikesToTempban(), punishment);
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public Optional<ActiveBan> findActiveBan(UUID playerId, long now) throws SQLException {
        try (Connection connection = open()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT punishment_id,player_name,reason_text,expires_at "
                            + "FROM sayaka_active_bans WHERE player_uuid=?")) {
                statement.setString(1, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) return Optional.empty();
                    long expiresAt = result.getLong("expires_at");
                    if (expiresAt <= now) {
                        execute(connection, "DELETE FROM sayaka_active_bans WHERE player_uuid=?",
                                playerId.toString());
                        return Optional.empty();
                    }
                    return Optional.of(new ActiveBan(playerId, result.getString("punishment_id"),
                            result.getString("player_name"), result.getString("reason_text"), expiresAt));
                }
            }
        }
    }

    public int activeBanCount(long now) throws SQLException {
        try (Connection connection = open()) {
            execute(connection, "DELETE FROM sayaka_active_bans WHERE expires_at<=?", now);
            return count(connection, "SELECT COUNT(*) FROM sayaka_active_bans");
        }
    }

    public boolean isPunishmentActive(String punishmentId, long now) throws SQLException {
        String id = canonicalId(punishmentId);
        return id != null && exists(
                "SELECT 1 FROM sayaka_active_bans WHERE punishment_id=? AND expires_at>?", id, now);
    }

    public boolean isWhitelisted(UUID playerId) throws SQLException {
        return exists("SELECT 1 FROM sayaka_whitelist WHERE player_uuid=?", playerId.toString());
    }

    public void addWhitelist(UUID playerId, String name) throws SQLException {
        try (Connection connection = open()) {
            execute(connection, "DELETE FROM sayaka_whitelist WHERE player_uuid=?", playerId.toString());
            execute(connection, "INSERT INTO sayaka_whitelist(player_uuid,player_name) VALUES(?,?)",
                    playerId.toString(), name);
        }
    }

    public boolean removeWhitelist(UUID playerId) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM sayaka_whitelist WHERE player_uuid=?")) {
            statement.setString(1, playerId.toString());
            return statement.executeUpdate() > 0;
        }
    }

    public List<Map.Entry<UUID, String>> listWhitelist() throws SQLException {
        List<Map.Entry<UUID, String>> entries = new ArrayList<>();
        try (Connection connection = open(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT player_uuid,player_name FROM sayaka_whitelist ORDER BY LOWER(player_name)")) {
            while (result.next()) {
                entries.add(Map.entry(UUID.fromString(result.getString(1)), result.getString(2)));
            }
        }
        return entries;
    }

    public int strikeCount(UUID playerId, int windowHours, long now) throws SQLException {
        try (Connection connection = open()) {
            execute(connection, "DELETE FROM sayaka_strikes WHERE player_uuid=? AND created_at<?",
                    playerId.toString(), now - windowHours * 3_600_000L);
            return count(connection, "SELECT COUNT(*) FROM sayaka_strikes WHERE player_uuid=?",
                    playerId.toString());
        }
    }

    public void addStrike(UUID playerId, String playerName, long now) throws SQLException {
        try (Connection connection = open()) {
            ensurePlayer(connection, playerId, playerName);
            execute(connection, "INSERT INTO sayaka_strikes(player_uuid,player_name,created_at) VALUES(?,?,?)",
                    playerId.toString(), playerName, now);
        }
    }

    public int banCount(UUID playerId) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT ban_count FROM sayaka_players WHERE player_uuid=?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    public void incrementBanCount(UUID playerId, String playerName) throws SQLException {
        try (Connection connection = open()) {
            ensurePlayer(connection, playerId, playerName);
            execute(connection, "UPDATE sayaka_players SET ban_count=ban_count+1 WHERE player_uuid=?",
                    playerId.toString());
        }
    }

    public void clearStrikes(UUID playerId) throws SQLException {
        try (Connection connection = open()) {
            execute(connection, "DELETE FROM sayaka_strikes WHERE player_uuid=?", playerId.toString());
        }
    }

    public void resetBanCount(UUID playerId) throws SQLException {
        try (Connection connection = open()) {
            execute(connection, "UPDATE sayaka_players SET ban_count=0 WHERE player_uuid=?", playerId.toString());
        }
    }

    public void resetPlayer(UUID playerId) throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, "DELETE FROM sayaka_strikes WHERE player_uuid=?", playerId.toString());
                execute(connection, "DELETE FROM sayaka_history WHERE player_uuid=?", playerId.toString());
                execute(connection, "DELETE FROM sayaka_active_bans WHERE player_uuid=?", playerId.toString());
                execute(connection, "DELETE FROM sayaka_players WHERE player_uuid=?", playerId.toString());
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public void pardon(UUID playerId, boolean resetBanCount) throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, "DELETE FROM sayaka_active_bans WHERE player_uuid=?", playerId.toString());
                execute(connection, "DELETE FROM sayaka_strikes WHERE player_uuid=?", playerId.toString());
                if (resetBanCount) {
                    execute(connection, "UPDATE sayaka_players SET ban_count=0 WHERE player_uuid=?",
                            playerId.toString());
                }
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public void addHistory(UUID playerId, String line) throws SQLException {
        try (Connection connection = open()) {
            addHistory(connection, playerId, System.currentTimeMillis(), line);
            execute(connection, "DELETE FROM sayaka_history WHERE player_uuid=? AND id NOT IN "
                            + "(SELECT id FROM (SELECT id FROM sayaka_history WHERE player_uuid=? "
                            + "ORDER BY created_at DESC,id DESC LIMIT 25) recent)",
                    playerId.toString(), playerId.toString());
        }
    }

    public List<String> history(UUID playerId) throws SQLException {
        List<String> entries = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT entry_text FROM sayaka_history WHERE player_uuid=? "
                        + "ORDER BY created_at ASC,id ASC LIMIT 25")) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) entries.add(result.getString(1));
            }
        }
        return entries;
    }

    public List<HistoryEntry> historyEntries(UUID playerId) throws SQLException {
        List<HistoryEntry> entries = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT created_at,entry_text FROM sayaka_history WHERE player_uuid=? "
                        + "ORDER BY created_at DESC,id DESC LIMIT 25")) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    entries.add(new HistoryEntry(result.getLong("created_at"), result.getString("entry_text")));
                }
            }
        }
        return entries;
    }

    public Optional<Punishment> getPunishment(String id) throws SQLException {
        String canonical = canonicalId(id);
        if (canonical == null) return Optional.empty();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM sayaka_punishments WHERE punishment_id=?")) {
            statement.setString(1, canonical);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readPunishment(result)) : Optional.empty();
            }
        }
    }

    public List<Punishment> listPunishments() throws SQLException {
        List<Punishment> records = new ArrayList<>();
        try (Connection connection = open(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT * FROM sayaka_punishments ORDER BY banned_at DESC")) {
            while (result.next()) records.add(readPunishment(result));
        }
        return records;
    }

    public Page<PunishmentView> queryPunishments(PunishmentFilter filter, int page, int pageSize, long now)
            throws SQLException {
        SqlParts parts = punishmentWhere(filter);
        List<Object> parameters = new ArrayList<>();
        parameters.add(now);
        parameters.addAll(parts.parameters());
        long total;
        List<PunishmentView> items = new ArrayList<>();
        String joins = " FROM sayaka_punishments p "
                + "LEFT JOIN sayaka_active_bans b ON b.punishment_id=p.punishment_id AND b.expires_at>? "
                + "LEFT JOIN sayaka_appeals a ON a.punishment_id=p.punishment_id ";
        try (Connection connection = open()) {
            total = countLong(connection, "SELECT COUNT(*)" + joins + parts.clause(), parameters.toArray());
            List<Object> pageParameters = new ArrayList<>(parameters);
            pageParameters.add(pageSize);
            pageParameters.add((page - 1L) * pageSize);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT p.*,CASE WHEN b.punishment_id IS NULL THEN 0 ELSE 1 END AS is_active,"
                            + "a.status AS appeal_status" + joins + parts.clause()
                            + " ORDER BY p.banned_at DESC,p.punishment_id DESC LIMIT ? OFFSET ?")) {
                bind(statement, pageParameters.toArray());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        String appealStatus = result.getString("appeal_status");
                        items.add(new PunishmentView(readPunishment(result), result.getBoolean("is_active"),
                                appealStatus == null ? null : AppealStatus.valueOf(appealStatus)));
                    }
                }
            }
        }
        return new Page<>(items, page, pageSize, total);
    }

    public AppealSubmitResult submitAppeal(String punishmentId, String reason, String contact, long now)
            throws SQLException {
        String id = canonicalId(punishmentId);
        if (id == null) return AppealSubmitResult.PUNISHMENT_NOT_FOUND;
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                String playerName = null;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT player_name FROM sayaka_punishments WHERE punishment_id=?")) {
                    statement.setString(1, id);
                    try (ResultSet result = statement.executeQuery()) {
                        if (result.next()) playerName = result.getString(1);
                    }
                }
                if (playerName == null) {
                    connection.rollback();
                    return AppealSubmitResult.PUNISHMENT_NOT_FOUND;
                }
                Appeal existing = getAppeal(connection, id).orElse(null);
                if (existing != null && existing.status() != AppealStatus.PENDING) {
                    connection.rollback();
                    return AppealSubmitResult.ALREADY_RESOLVED;
                }
                if (existing == null) {
                    execute(connection, "INSERT INTO sayaka_appeals"
                                    + "(punishment_id,player_name,reason_text,contact_text,submitted_at,status,resolved_at,note_text) "
                                    + "VALUES(?,?,?,?,?,'PENDING',0,'')",
                            id, playerName, reason, contact, now);
                } else {
                    execute(connection, "UPDATE sayaka_appeals SET reason_text=?,contact_text=? "
                            + "WHERE punishment_id=?", reason, contact, id);
                }
                connection.commit();
                return AppealSubmitResult.OK;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public Optional<Appeal> getAppeal(String punishmentId) throws SQLException {
        String id = canonicalId(punishmentId);
        if (id == null) return Optional.empty();
        try (Connection connection = open()) {
            return getAppeal(connection, id);
        }
    }

    public List<Appeal> listAppeals() throws SQLException {
        List<Appeal> appeals = new ArrayList<>();
        try (Connection connection = open(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT * FROM sayaka_appeals ORDER BY submitted_at DESC")) {
            while (result.next()) appeals.add(readAppeal(result));
        }
        return appeals;
    }

    public Page<AppealView> queryAppeals(AppealFilter filter, int page, int pageSize, long now)
            throws SQLException {
        SqlParts parts = appealWhere(filter);
        List<Object> parameters = new ArrayList<>();
        parameters.add(now);
        parameters.addAll(parts.parameters());
        String joins = " FROM sayaka_appeals a JOIN sayaka_punishments p ON p.punishment_id=a.punishment_id "
                + "LEFT JOIN sayaka_active_bans b ON b.punishment_id=p.punishment_id AND b.expires_at>? ";
        long total;
        List<AppealView> items = new ArrayList<>();
        try (Connection connection = open()) {
            total = countLong(connection, "SELECT COUNT(*)" + joins + parts.clause(), parameters.toArray());
            List<Object> pageParameters = new ArrayList<>(parameters);
            pageParameters.add(pageSize);
            pageParameters.add((page - 1L) * pageSize);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT a.punishment_id AS appeal_id,a.player_name AS appeal_player_name,"
                            + "a.reason_text AS appeal_reason,a.contact_text AS appeal_contact,"
                            + "a.submitted_at AS appeal_submitted_at,a.status AS appeal_status,"
                            + "a.resolved_at AS appeal_resolved_at,a.note_text AS appeal_note,"
                            + "p.*,CASE WHEN b.punishment_id IS NULL THEN 0 ELSE 1 END AS is_active"
                            + joins + parts.clause()
                            + " ORDER BY a.submitted_at DESC,a.punishment_id DESC LIMIT ? OFFSET ?")) {
                bind(statement, pageParameters.toArray());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        Appeal appeal = new Appeal(result.getString("appeal_id"),
                                result.getString("appeal_player_name"), result.getString("appeal_reason"),
                                result.getString("appeal_contact"), result.getLong("appeal_submitted_at"),
                                AppealStatus.valueOf(result.getString("appeal_status")),
                                result.getLong("appeal_resolved_at"), result.getString("appeal_note"));
                        items.add(new AppealView(appeal, readPunishment(result), result.getBoolean("is_active")));
                    }
                }
            }
        }
        return new Page<>(items, page, pageSize, total);
    }

    /** Resolves an appeal and removes the network ban in the same transaction when approved. */
    public boolean resolveAppeal(String punishmentId, boolean approved, String note, long now)
            throws SQLException {
        String id = canonicalId(punishmentId);
        if (id == null) return false;
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE sayaka_appeals SET status=?,resolved_at=?,note_text=? "
                                + "WHERE punishment_id=? AND status='PENDING'")) {
                    statement.setString(1, approved ? "APPROVED" : "REJECTED");
                    statement.setLong(2, now);
                    statement.setString(3, note);
                    statement.setString(4, id);
                    if (statement.executeUpdate() == 0) {
                        connection.rollback();
                        return false;
                    }
                }
                if (approved) {
                    execute(connection, "DELETE FROM sayaka_active_bans WHERE punishment_id=?", id);
                }
                connection.commit();
                return true;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    /** Pardons only the currently active row for the supplied punishment ID. */
    public PardonResult pardonPunishment(String punishmentId, boolean resetBanCount, String note, long now)
            throws SQLException {
        String id = canonicalId(punishmentId);
        if (id == null) return PardonResult.PUNISHMENT_NOT_FOUND;
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                UUID playerId;
                String playerName;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT player_uuid,player_name FROM sayaka_punishments WHERE punishment_id=?")) {
                    statement.setString(1, id);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            connection.rollback();
                            return PardonResult.PUNISHMENT_NOT_FOUND;
                        }
                        playerId = UUID.fromString(result.getString("player_uuid"));
                        playerName = result.getString("player_name");
                    }
                }
                if (execute(connection,
                        "DELETE FROM sayaka_active_bans WHERE punishment_id=? AND player_uuid=? AND expires_at>?",
                        id, playerId.toString(), now) == 0) {
                    connection.rollback();
                    return PardonResult.NOT_ACTIVE;
                }
                execute(connection, "DELETE FROM sayaka_strikes WHERE player_uuid=?", playerId.toString());
                if (resetBanCount) {
                    execute(connection, "UPDATE sayaka_players SET ban_count=0 WHERE player_uuid=?",
                            playerId.toString());
                }
                addHistory(connection, playerId, now, "[Web 解封] " + playerName + "，处罚 ID " + id
                        + (resetBanCount ? "（已重置封禁次数）" : "")
                        + (note.isBlank() ? "" : "（" + note + "）"));
                trimHistory(connection, playerId);
                connection.commit();
                return PardonResult.OK;
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public void addWhitelistAudited(UUID playerId, String playerName, long now) throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                ensurePlayer(connection, playerId, playerName);
                execute(connection, "DELETE FROM sayaka_whitelist WHERE player_uuid=?", playerId.toString());
                execute(connection, "INSERT INTO sayaka_whitelist(player_uuid,player_name) VALUES(?,?)",
                        playerId.toString(), playerName);
                addHistory(connection, playerId, now, "[Web 白名单] 管理员加入反作弊白名单");
                trimHistory(connection, playerId);
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public boolean removeWhitelistAudited(UUID playerId, long now) throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                if (execute(connection, "DELETE FROM sayaka_whitelist WHERE player_uuid=?",
                        playerId.toString()) == 0) {
                    connection.rollback();
                    return false;
                }
                addHistory(connection, playerId, now, "[Web 白名单] 管理员移出反作弊白名单");
                trimHistory(connection, playerId);
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public List<PlayerReference> searchPlayers(String query, int limit) throws SQLException {
        String normalized = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) return List.of();
        String pattern = "%" + normalized + "%";
        List<PlayerReference> players = new ArrayList<>();
        String sql = "SELECT player_uuid,MAX(player_name) AS player_name FROM ("
                + "SELECT player_uuid,player_name FROM sayaka_players UNION ALL "
                + "SELECT player_uuid,player_name FROM sayaka_whitelist UNION ALL "
                + "SELECT player_uuid,player_name FROM sayaka_punishments) known "
                + "WHERE LOWER(player_name) LIKE ? OR LOWER(player_uuid) LIKE ? "
                + "GROUP BY player_uuid ORDER BY LOWER(MAX(player_name)) LIMIT ?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, pattern, pattern, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    players.add(new PlayerReference(UUID.fromString(result.getString("player_uuid")),
                            result.getString("player_name")));
                }
            }
        }
        return players;
    }

    public Optional<PlayerProfile> playerProfile(UUID playerId, long now) throws SQLException {
        String playerName = null;
        int banCount = 0;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT player_name,ban_count FROM sayaka_players WHERE player_uuid=?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    playerName = result.getString("player_name");
                    banCount = result.getInt("ban_count");
                }
            }
        }
        if (playerName == null) {
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                    "SELECT player_name FROM sayaka_whitelist WHERE player_uuid=? UNION ALL "
                            + "SELECT player_name FROM sayaka_punishments WHERE player_uuid=? "
                            + "ORDER BY player_name LIMIT 1")) {
                bind(statement, playerId.toString(), playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) playerName = result.getString(1);
                }
            }
        }
        if (playerName == null) return Optional.empty();

        List<Punishment> punishments = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM sayaka_punishments WHERE player_uuid=? "
                        + "ORDER BY banned_at DESC,punishment_id DESC LIMIT 25")) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) punishments.add(readPunishment(result));
            }
        }
        return Optional.of(new PlayerProfile(playerId, playerName, banCount, isWhitelisted(playerId),
                findActiveBan(playerId, now).orElse(null), historyEntries(playerId), punishments));
    }

    public FilterOptions filterOptions() throws SQLException {
        return new FilterOptions(distinctValues("server_id"), distinctValues("check_id"));
    }

    public DashboardOverview dashboardOverview(long from, long to, long bucketMillis, long now)
            throws SQLException {
        List<TimeBucket> trend = new ArrayList<>();
        int buckets = Math.toIntExact((to - from + bucketMillis - 1) / bucketMillis);
        long[] counts = new long[buckets];
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT bucket_index,COUNT(*) AS item_count FROM ("
                        + "SELECT FLOOR((banned_at-?)/?) AS bucket_index FROM sayaka_punishments "
                        + "WHERE banned_at>=? AND banned_at<?) dashboard_buckets GROUP BY bucket_index")) {
            bind(statement, from, bucketMillis, from, to);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    int index = result.getInt("bucket_index");
                    if (index >= 0 && index < counts.length) counts[index] = result.getLong("item_count");
                }
            }
        }
        for (int i = 0; i < counts.length; i++) trend.add(new TimeBucket(from + i * bucketMillis, counts[i]));
        try (Connection connection = open()) {
            return new DashboardOverview(
                    countLong(connection, "SELECT COUNT(*) FROM sayaka_punishments"),
                    countLong(connection, "SELECT COUNT(*) FROM sayaka_punishments WHERE banned_at>=? AND banned_at<?",
                            from, to),
                    countLong(connection, "SELECT COUNT(*) FROM sayaka_active_bans WHERE expires_at>?", now),
                    countLong(connection, "SELECT COUNT(*) FROM sayaka_appeals"),
                    countLong(connection, "SELECT COUNT(*) FROM sayaka_appeals WHERE status='PENDING'"),
                    trend,
                    namedCounts(connection, "check_id", from, to),
                    namedCounts(connection, "server_id", from, to));
        }
    }

    private static SqlParts punishmentWhere(PunishmentFilter filter) {
        StringBuilder clause = new StringBuilder(" WHERE 1=1");
        List<Object> parameters = new ArrayList<>();
        addSearch(clause, parameters, filter.query(), "p.player_name", "p.player_uuid", "p.punishment_id");
        if (filter.active() != null) {
            clause.append(filter.active() ? " AND b.punishment_id IS NOT NULL" : " AND b.punishment_id IS NULL");
        }
        if (filter.appealStatus() != null) {
            clause.append(" AND a.status=?");
            parameters.add(filter.appealStatus().name());
        }
        if (filter.serverId() != null && !filter.serverId().isBlank()) {
            clause.append(" AND p.server_id=?");
            parameters.add(filter.serverId());
        }
        if (filter.check() != null && !filter.check().isBlank()) {
            clause.append(" AND p.check_id=?");
            parameters.add(filter.check());
        }
        addRange(clause, parameters, "p.banned_at", filter.from(), filter.to());
        return new SqlParts(clause.toString(), parameters);
    }

    private static SqlParts appealWhere(AppealFilter filter) {
        StringBuilder clause = new StringBuilder(" WHERE 1=1");
        List<Object> parameters = new ArrayList<>();
        addSearch(clause, parameters, filter.query(), "a.player_name", "a.punishment_id");
        if (filter.status() != null) {
            clause.append(" AND a.status=?");
            parameters.add(filter.status().name());
        }
        addRange(clause, parameters, "a.submitted_at", filter.from(), filter.to());
        return new SqlParts(clause.toString(), parameters);
    }

    private static void addSearch(StringBuilder clause, List<Object> parameters, String query,
                                  String... columns) {
        if (query == null || query.isBlank()) return;
        String pattern = "%" + query.trim().toLowerCase(java.util.Locale.ROOT) + "%";
        clause.append(" AND (");
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) clause.append(" OR ");
            clause.append("LOWER(").append(columns[i]).append(") LIKE ?");
            parameters.add(pattern);
        }
        clause.append(')');
    }

    private static void addRange(StringBuilder clause, List<Object> parameters, String column,
                                 long from, long to) {
        if (from > 0) {
            clause.append(" AND ").append(column).append(">=?");
            parameters.add(from);
        }
        if (to > 0) {
            clause.append(" AND ").append(column).append("<?");
            parameters.add(to);
        }
    }

    private List<String> distinctValues(String column) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = open(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT DISTINCT " + column
                     + " FROM sayaka_punishments ORDER BY " + column)) {
            while (result.next()) values.add(result.getString(1));
        }
        return values;
    }

    private static List<NamedCount> namedCounts(Connection connection, String column, long from, long to)
            throws SQLException {
        List<NamedCount> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + column
                + " AS item_name,COUNT(*) AS item_count FROM sayaka_punishments "
                + "WHERE banned_at>=? AND banned_at<? GROUP BY " + column
                + " ORDER BY item_count DESC,item_name LIMIT 10")) {
            bind(statement, from, to);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    values.add(new NamedCount(result.getString("item_name"), result.getLong("item_count")));
                }
            }
        }
        return values;
    }

    private record SqlParts(String clause, List<Object> parameters) {}

    private void ensurePlayer(Connection connection, UUID playerId, String playerName) throws SQLException {
        execute(connection, "INSERT INTO sayaka_players(player_uuid,player_name,ban_count) VALUES(?,?,0) "
                        + "ON DUPLICATE KEY UPDATE player_name=VALUES(player_name)",
                playerId.toString(), playerName);
    }

    private int lockBanCount(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ban_count FROM sayaka_players WHERE player_uuid=? FOR UPDATE")) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("player row disappeared during enforcement");
                return result.getInt(1);
            }
        }
    }

    private void insertPunishment(Connection connection, Punishment punishment) throws SQLException {
        execute(connection, "INSERT INTO sayaka_punishments"
                        + "(punishment_id,player_uuid,player_name,server_id,banned_at,expires_at,check_id,vl,hours,"
                        + "ban_number,warnings_json,detections_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                punishment.id(), punishment.playerId().toString(), punishment.playerName(), punishment.serverId(),
                punishment.bannedAt(), punishment.expiresAt(), punishment.check(), punishment.vl(),
                punishment.hours(), punishment.banNumber(), warningJson(punishment.warnings()),
                detectionJson(punishment.detections()));
    }

    private Punishment findActivePunishment(Connection connection, UUID playerId, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT p.* FROM sayaka_active_bans b JOIN sayaka_punishments p "
                        + "ON p.punishment_id=b.punishment_id WHERE b.player_uuid=? AND b.expires_at>?")) {
            statement.setString(1, playerId.toString());
            statement.setLong(2, now);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readPunishment(result) : null;
            }
        }
    }

    private static Punishment readPunishment(ResultSet result) throws SQLException {
        return new Punishment(result.getString("punishment_id"),
                UUID.fromString(result.getString("player_uuid")), result.getString("player_name"),
                result.getString("server_id"), result.getLong("banned_at"), result.getLong("expires_at"),
                result.getString("check_id"), result.getDouble("vl"), result.getInt("hours"),
                result.getInt("ban_number"), readWarnings(result.getString("warnings_json")),
                readDetections(result.getString("detections_json")));
    }

    private Optional<Appeal> getAppeal(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM sayaka_appeals WHERE punishment_id=?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readAppeal(result)) : Optional.empty();
            }
        }
    }

    private static Appeal readAppeal(ResultSet result) throws SQLException {
        return new Appeal(result.getString("punishment_id"), result.getString("player_name"),
                result.getString("reason_text"), result.getString("contact_text"),
                result.getLong("submitted_at"), AppealStatus.valueOf(result.getString("status")),
                result.getLong("resolved_at"), result.getString("note_text"));
    }

    private static String warningJson(List<WarningEvidence> warnings) {
        return Json.write(warnings.stream().map(value -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("at", value.at());
            map.put("check", value.check());
            map.put("stage", value.stage());
            map.put("vl", value.vl());
            return map;
        }).toList());
    }

    private static String detectionJson(List<DetectionEvidence> detections) {
        return Json.write(detections.stream().map(value -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("at", value.at());
            map.put("check", value.check());
            map.put("vl", value.vl());
            map.put("detail", value.detail());
            return map;
        }).toList());
    }

    @SuppressWarnings("unchecked")
    private static List<WarningEvidence> readWarnings(String json) {
        List<WarningEvidence> values = new ArrayList<>();
        Object parsed = Json.parse(json);
        if (!(parsed instanceof List<?> list)) return values;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            values.add(new WarningEvidence(longValue(map.get("at")), stringValue(map.get("check")),
                    intValue(map.get("stage")), doubleValue(map.get("vl"))));
        }
        return values;
    }

    private static List<DetectionEvidence> readDetections(String json) {
        List<DetectionEvidence> values = new ArrayList<>();
        Object parsed = Json.parse(json);
        if (!(parsed instanceof List<?> list)) return values;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            values.add(new DetectionEvidence(longValue(map.get("at")), stringValue(map.get("check")),
                    doubleValue(map.get("vl")), stringValue(map.get("detail"))));
        }
        return values;
    }

    private boolean exists(String sql, Object... values) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static int count(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    private static long countLong(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    private static int execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            return statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
    }

    private static void addHistory(Connection connection, UUID playerId, long at, String line)
            throws SQLException {
        execute(connection, "INSERT INTO sayaka_history(player_uuid,created_at,entry_text) VALUES(?,?,?)",
                playerId.toString(), at, line);
    }

    private static void trimHistory(Connection connection, UUID playerId) throws SQLException {
        execute(connection, "DELETE FROM sayaka_history WHERE player_uuid=? AND id NOT IN "
                        + "(SELECT id FROM (SELECT id FROM sayaka_history WHERE player_uuid=? "
                        + "ORDER BY created_at DESC,id DESC LIMIT 25) recent)",
                playerId.toString(), playerId.toString());
    }

    private static String canonicalId(String id) {
        if (id == null) return null;
        try {
            return UUID.fromString(id.trim()).toString();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String formatVl(double vl) {
        return String.format(java.util.Locale.ROOT, "%.1f", vl);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }
}

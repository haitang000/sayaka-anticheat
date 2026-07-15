package cn.haitang.anticheat.shared;

import java.util.List;
import java.util.UUID;

public final class NetworkModels {
    private NetworkModels() {}

    public record WarningEvidence(long at, String check, int stage, double vl) {}

    public record DetectionEvidence(long at, String check, double vl, String detail) {}

    public record Punishment(
            String id,
            UUID playerId,
            String playerName,
            String serverId,
            long bannedAt,
            long expiresAt,
            String check,
            double vl,
            int hours,
            int banNumber,
            List<WarningEvidence> warnings,
            List<DetectionEvidence> detections
    ) {
        public Punishment {
            warnings = List.copyOf(warnings);
            detections = List.copyOf(detections);
        }
    }

    public record EnforcementRequest(
            UUID playerId,
            String playerName,
            String serverId,
            String check,
            double vl,
            int strikeWindowHours,
            int strikesToTempban,
            List<Integer> tempbanHours,
            List<WarningEvidence> warnings,
            List<DetectionEvidence> detections
    ) {
        public EnforcementRequest {
            tempbanHours = List.copyOf(tempbanHours);
            warnings = List.copyOf(warnings);
            detections = List.copyOf(detections);
            if (tempbanHours.isEmpty()) throw new IllegalArgumentException("tempbanHours must not be empty");
        }
    }

    public enum EnforcementKind { KICK, TEMPBAN }

    public record EnforcementDecision(
            EnforcementKind kind,
            int strikes,
            int strikesToTempban,
            Punishment punishment
    ) {}

    public record ActiveBan(
            UUID playerId,
            String punishmentId,
            String playerName,
            String reason,
            long expiresAt
    ) {}

    public enum AppealStatus { PENDING, APPROVED, REJECTED }

    public record Appeal(
            String punishmentId,
            String playerName,
            String reason,
            String contact,
            long submittedAt,
            AppealStatus status,
            long resolvedAt,
            String note
    ) {}

    public enum AppealSubmitResult { OK, PUNISHMENT_NOT_FOUND, ALREADY_RESOLVED }

    public record Page<T>(List<T> items, int page, int pageSize, long total) {
        public Page {
            items = List.copyOf(items);
        }
    }

    public record PunishmentFilter(
            String query,
            Boolean active,
            AppealStatus appealStatus,
            String serverId,
            String check,
            long from,
            long to
    ) {}

    public record AppealFilter(String query, AppealStatus status, long from, long to) {}

    public record PunishmentView(Punishment punishment, boolean active, AppealStatus appealStatus) {}

    public record AppealView(Appeal appeal, Punishment punishment, boolean active) {}

    public record HistoryEntry(long at, String text) {}

    public record PlayerReference(UUID playerId, String playerName) {}

    public record PlayerProfile(
            UUID playerId,
            String playerName,
            int banCount,
            boolean whitelisted,
            ActiveBan activeBan,
            List<HistoryEntry> history,
            List<Punishment> punishments
    ) {
        public PlayerProfile {
            history = List.copyOf(history);
            punishments = List.copyOf(punishments);
        }
    }

    public record TimeBucket(long start, long count) {}

    public record NamedCount(String name, long count) {}

    public record DashboardOverview(
            long totalPunishments,
            long periodPunishments,
            long activeBans,
            long totalAppeals,
            long pendingAppeals,
            List<TimeBucket> trend,
            List<NamedCount> checks,
            List<NamedCount> servers
    ) {
        public DashboardOverview {
            trend = List.copyOf(trend);
            checks = List.copyOf(checks);
            servers = List.copyOf(servers);
        }
    }

    public record FilterOptions(List<String> servers, List<String> checks) {
        public FilterOptions {
            servers = List.copyOf(servers);
            checks = List.copyOf(checks);
        }
    }

    public enum PardonResult { OK, PUNISHMENT_NOT_FOUND, NOT_ACTIVE }
}

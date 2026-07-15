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
}

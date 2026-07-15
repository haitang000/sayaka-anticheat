package cn.haitang.anticheat.sync;

import cn.haitang.anticheat.data.PersistentStore;
import cn.haitang.anticheat.web.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class CrossServerCodec {

    record SyncedPunishment(PersistentStore.PunishmentRecord record, String screen) {}

    record Envelope(
            String type,
            String origin,
            long updatedAt,
            PersistentStore.PlayerPunishmentState state,
            SyncedPunishment punishment,
            boolean reset
    ) {}

    private CrossServerCodec() {}

    static String encodeEnvelope(Envelope envelope) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("type", envelope.type());
        values.put("origin", envelope.origin());
        values.put("updatedAt", envelope.updatedAt());
        values.put("state", envelope.state() == null ? null : stateMap(envelope.state()));
        values.put("punishment", envelope.punishment() == null
                ? null : punishmentMap(envelope.punishment()));
        values.put("reset", envelope.reset());
        return Json.write(values);
    }

    static Envelope decodeEnvelope(String encoded) {
        Map<String, Object> values = Json.parseObject(encoded);
        return new Envelope(
                string(values.get("type")),
                string(values.get("origin")),
                longValue(values.get("updatedAt")),
                optionalMap(values.get("state"), CrossServerCodec::readState),
                optionalMap(values.get("punishment"), CrossServerCodec::readPunishment),
                Boolean.TRUE.equals(values.get("reset")));
    }

    static String encodePunishment(SyncedPunishment punishment) {
        return Json.write(punishmentMap(punishment));
    }

    static SyncedPunishment decodePunishment(String encoded) {
        return readPunishment(Json.parseObject(encoded));
    }

    private static Map<String, Object> stateMap(PersistentStore.PlayerPunishmentState state) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("playerId", state.playerId().toString());
        values.put("playerName", state.playerName());
        values.put("strikes", state.strikes());
        values.put("banCount", state.banCount());
        values.put("history", state.history());
        return values;
    }

    private static PersistentStore.PlayerPunishmentState readState(Map<String, Object> values) {
        return new PersistentStore.PlayerPunishmentState(
                UUID.fromString(string(values.get("playerId"))),
                string(values.get("playerName")),
                longList(values.get("strikes")),
                intValue(values.get("banCount")),
                stringList(values.get("history")));
    }

    private static Map<String, Object> punishmentMap(SyncedPunishment synced) {
        PersistentStore.PunishmentRecord record = synced.record();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", record.id());
        values.put("playerId", record.playerId().toString());
        values.put("playerName", record.playerName());
        values.put("bannedAt", record.bannedAt());
        values.put("expiresAt", record.expiresAt());
        values.put("check", record.check());
        values.put("vl", record.vl());
        values.put("hours", record.hours());
        values.put("banNumber", record.banNumber());
        values.put("screen", synced.screen());
        values.put("warnings", record.warnings().stream().map(warning -> Map.of(
                "at", warning.at(),
                "check", warning.check(),
                "stage", warning.stage(),
                "vl", warning.vl())).toList());
        values.put("detections", record.detections().stream().map(detection -> Map.of(
                "at", detection.at(),
                "check", detection.check(),
                "vl", detection.vl(),
                "detail", detection.detail(),
                "ping", detection.ping())).toList());
        return values;
    }

    private static SyncedPunishment readPunishment(Map<String, Object> values) {
        List<PersistentStore.WarningEvidence> warnings = new ArrayList<>();
        for (Map<String, Object> warning : mapList(values.get("warnings"))) {
            warnings.add(new PersistentStore.WarningEvidence(
                    longValue(warning.get("at")), string(warning.get("check")),
                    intValue(warning.get("stage")), doubleValue(warning.get("vl"))));
        }
        List<PersistentStore.DetectionEvidence> detections = new ArrayList<>();
        for (Map<String, Object> detection : mapList(values.get("detections"))) {
            detections.add(new PersistentStore.DetectionEvidence(
                    longValue(detection.get("at")), string(detection.get("check")),
                    doubleValue(detection.get("vl")), string(detection.get("detail")),
                    intValue(detection.get("ping"))));
        }
        PersistentStore.PunishmentRecord record = new PersistentStore.PunishmentRecord(
                string(values.get("id")),
                UUID.fromString(string(values.get("playerId"))),
                string(values.get("playerName")),
                longValue(values.get("bannedAt")),
                longValue(values.get("expiresAt")),
                string(values.get("check")),
                doubleValue(values.get("vl")),
                intValue(values.get("hours")),
                intValue(values.get("banNumber")),
                warnings,
                detections);
        return new SyncedPunishment(record, string(values.get("screen")));
    }

    @SuppressWarnings("unchecked")
    private static <T> T optionalMap(Object value, MapReader<T> reader) {
        if (!(value instanceof Map<?, ?> map)) return null;
        return reader.read((Map<String, Object>) map);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object element : list) {
            if (element instanceof Map<?, ?> map) maps.add((Map<String, Object>) map);
        }
        return maps;
    }

    private static List<Long> longList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(CrossServerCodec::longValue).toList();
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(CrossServerCodec::string).toList();
    }

    private static String string(Object value) {
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

    @FunctionalInterface
    private interface MapReader<T> {
        T read(Map<String, Object> values);
    }
}

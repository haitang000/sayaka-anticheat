package cn.haitang.anticheat.check.chat;

import java.util.ArrayDeque;
import java.util.Deque;

/** 命令刷屏判定：高频命令 + 窗口内重复命令，逻辑与 {@code AntiSpamDetector} 一致。 */
final class CommandSpamDetector {

    enum Reason {
        FLOOD,
        DUPLICATE
    }

    record Settings(long floodWindowMs, int maxCommands, long duplicateWindowMs,
                    int maxDuplicates, int minDuplicateLength, long flagCooldownMs) {
    }

    record Result(Reason reason, boolean shouldFlag) {
    }

    private record Command(long at, String normalized) {
    }

    private final Deque<Command> commands = new ArrayDeque<>();
    private long lastFlagAt = Long.MIN_VALUE;

    synchronized Result inspect(String command, long now, Settings settings) {
        String normalized = ChatTextNormalizer.forSpam(command);
        long retention = Math.max(settings.floodWindowMs(), settings.duplicateWindowMs());
        while (!commands.isEmpty() && now - commands.peekFirst().at() > retention) {
            commands.removeFirst();
        }

        commands.addLast(new Command(now, normalized));
        int recentCommands = 0;
        int duplicates = 0;
        for (Command entry : commands) {
            long age = now - entry.at();
            if (age <= settings.floodWindowMs()) recentCommands++;
            if (!normalized.isEmpty()
                    && normalized.length() >= settings.minDuplicateLength()
                    && age <= settings.duplicateWindowMs()
                    && normalized.equals(entry.normalized())) {
                duplicates++;
            }
        }

        Reason reason = null;
        if (duplicates >= settings.maxDuplicates()) {
            reason = Reason.DUPLICATE;
        } else if (recentCommands > settings.maxCommands()) {
            reason = Reason.FLOOD;
        }
        if (reason == null) return null;

        boolean shouldFlag = lastFlagAt == Long.MIN_VALUE
                || now - lastFlagAt >= settings.flagCooldownMs();
        if (shouldFlag) lastFlagAt = now;
        return new Result(reason, shouldFlag);
    }
}

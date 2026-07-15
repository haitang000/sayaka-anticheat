package cn.haitang.anticheat.check.chat;

import java.util.ArrayDeque;
import java.util.Deque;

final class AntiSpamDetector {

    enum Reason {
        FLOOD,
        DUPLICATE
    }

    record Settings(long floodWindowMs, int maxMessages, long duplicateWindowMs,
                    int maxDuplicates, int minDuplicateLength, long flagCooldownMs) {
    }

    record Result(Reason reason, boolean shouldFlag) {
    }

    private record Message(long at, String normalized) {
    }

    private final Deque<Message> messages = new ArrayDeque<>();
    private long lastFlagAt = Long.MIN_VALUE;

    synchronized Result inspect(String rawMessage, long now, Settings settings) {
        String normalized = ChatTextNormalizer.forSpam(rawMessage);
        long retention = Math.max(settings.floodWindowMs(), settings.duplicateWindowMs());
        while (!messages.isEmpty() && now - messages.peekFirst().at() > retention) {
            messages.removeFirst();
        }

        messages.addLast(new Message(now, normalized));
        int recentMessages = 0;
        int duplicates = 0;
        for (Message message : messages) {
            long age = now - message.at();
            if (age <= settings.floodWindowMs()) recentMessages++;
            if (!normalized.isEmpty()
                    && normalized.length() >= settings.minDuplicateLength()
                    && age <= settings.duplicateWindowMs()
                    && normalized.equals(message.normalized())) {
                duplicates++;
            }
        }

        Reason reason = null;
        if (duplicates >= settings.maxDuplicates()) {
            reason = Reason.DUPLICATE;
        } else if (recentMessages > settings.maxMessages()) {
            reason = Reason.FLOOD;
        }
        if (reason == null) return null;

        boolean shouldFlag = lastFlagAt == Long.MIN_VALUE
                || now - lastFlagAt >= settings.flagCooldownMs();
        if (shouldFlag) lastFlagAt = now;
        return new Result(reason, shouldFlag);
    }
}

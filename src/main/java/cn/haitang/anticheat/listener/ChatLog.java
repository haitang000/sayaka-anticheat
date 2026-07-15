package cn.haitang.anticheat.listener;

import cn.haitang.anticheat.AntiCheatPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 保留最近的聊天消息，供管理面板"实时监控"查看。
 *
 * <p>{@link AsyncPlayerChatEvent} 在 Netty 线程异步触发，Web 线程读取，
 * 因此所有对缓冲区的访问都在 {@code this} 上同步。仅保留内存中的最近若干条，
 * 玩家隐私消息不落盘。</p>
 */
public class ChatLog implements Listener {

    /** 一条聊天记录：时间戳、玩家名、消息内容。 */
    public record Entry(long at, String player, String message) {}

    private static final int DEFAULT_CAPACITY = 100;
    private static final int MAX_MESSAGE_LENGTH = 256;

    private final AntiCheatPlugin plugin;
    private final Deque<Entry> entries = new ArrayDeque<>();

    public ChatLog(AntiCheatPlugin plugin) {
        this.plugin = plugin;
    }

    private int capacity() {
        int configured = plugin.config().getInt("web.chat-log-size", DEFAULT_CAPACITY);
        return Math.max(0, Math.min(1000, configured));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        record(event.getPlayer(), event.getMessage());
    }

    private void record(Player player, String message) {
        int cap = capacity();
        if (cap == 0 || message == null) return;
        String trimmed = message.length() > MAX_MESSAGE_LENGTH
                ? message.substring(0, MAX_MESSAGE_LENGTH) : message;
        Entry entry = new Entry(System.currentTimeMillis(), player.getName(), trimmed);
        synchronized (this) {
            entries.addLast(entry);
            while (entries.size() > cap) entries.removeFirst();
        }
    }

    /** 返回最近的至多 {@code max} 条消息，最新在前。 */
    public synchronized List<Entry> recent(int max) {
        List<Entry> out = new ArrayList<>(entries);
        java.util.Collections.reverse(out);
        if (max > 0 && out.size() > max) return out.subList(0, max);
        return out;
    }
}

package me.abol.livemarket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;

/**
 * Shared in-memory alert handling for every {@link Storage} backend.
 *
 * <p>Alerts live in a map while the server runs and are only written out
 * by a backend's save; the backends differ in where they write, not in
 * how alerts behave. The dirty flag is shared too: any alert change
 * marks the state dirty so the next scheduled save picks it up.
 */
public abstract class AbstractStore implements Storage {

    protected final LiveMarket plugin;
    protected final PriceEngine engine;
    protected final Debug debug;
    protected final Map<UUID, List<Alert>> alerts = new HashMap<>();
    protected final Map<UUID, List<TradeRecord>> trades = new HashMap<>();
    private volatile boolean dirty = false;

    protected AbstractStore(LiveMarket plugin, PriceEngine engine, Debug debug) {
        this.plugin = plugin;
        this.engine = engine;
        this.debug = debug;
    }

    @Override
    public void markDirty() {
        dirty = true;
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    /** Clear the dirty flag; backends call this when a save starts. */
    protected void clearDirty() {
        dirty = false;
    }

    @Override
    public List<Alert> alertsOf(UUID player) {
        return alerts.getOrDefault(player, List.of());
    }

    @Override
    public void addAlert(UUID player, Alert alert) {
        alerts.computeIfAbsent(player, k -> new ArrayList<>())
                .removeIf(a -> a.material() == alert.material());
        alerts.get(player).add(alert);
        debug.log("ALERT", "SET player=" + player + " item=" + alert.material().name()
                + " condition=" + (alert.above() ? "above" : "below") + " target=" + Debug.f(alert.target()));
        markDirty();
    }

    @Override
    public boolean removeAlert(UUID player, Material material) {
        List<Alert> list = alerts.get(player);
        if (list == null) {
            return false;
        }
        boolean removed = list.removeIf(a -> a.material() == material);
        if (removed) {
            markDirty();
        }
        return removed;
    }

    @Override
    public List<Alert> collectTriggered(UUID player, Material material, double price) {
        List<Alert> list = alerts.get(player);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<Alert> fired = new ArrayList<>();
        list.removeIf(a -> {
            if (a.material() != material) {
                return false;
            }
            boolean hit = a.above() ? price >= a.target() : price <= a.target();
            if (hit) {
                fired.add(a);
            }
            return hit;
        });
        if (!fired.isEmpty()) {
            markDirty();
        }
        return fired;
    }

    @Override
    public Map<UUID, List<Alert>> allAlerts() {
        return alerts;
    }

    /** Restore one persisted alert row into the in-memory map. */
    protected void restoreAlert(UUID player, Material material, boolean above, double target) {
        alerts.computeIfAbsent(player, k -> new ArrayList<>())
                .add(new Alert(material, above, target));
    }

    // ---- trade history ----

    /** How many trades to keep per player (config, read live so reloads apply). */
    protected int tradeHistoryLimit() {
        int limit = plugin.getConfig().getInt("settings.trade-history-entries", 100);
        return Math.max(1, limit);
    }

    @Override
    public void recordTrade(UUID player, TradeRecord record) {
        List<TradeRecord> list = trades.computeIfAbsent(player, k -> new ArrayList<>());
        list.add(0, record); // newest first
        int limit = tradeHistoryLimit();
        while (list.size() > limit) {
            list.remove(list.size() - 1);
        }
        debug.log("TRADE", "HISTORY player=" + player + " item=" + record.material().name()
                + " side=" + (record.buy() ? "BUY" : "SELL") + " amount=" + record.amount()
                + " unit=" + Debug.f(record.unit()) + " total=" + Debug.f(record.total()));
        markDirty();
    }

    @Override
    public List<TradeRecord> tradesOf(UUID player) {
        return trades.getOrDefault(player, List.of());
    }

    @Override
    public Map<UUID, List<TradeRecord>> allTrades() {
        return trades;
    }

    /** Restore one persisted trade row into the in-memory map. */
    protected void restoreTrade(UUID player, TradeRecord record) {
        List<TradeRecord> list = trades.computeIfAbsent(player, k -> new ArrayList<>());
        list.add(record);
        int limit = tradeHistoryLimit();
        while (list.size() > limit) {
            list.remove(list.size() - 1);
        }
    }

    /**
     * Encode one trade as a single line for the YAML backend:
     * {@code epoch|BUY|WHEAT|64|5.8571|375.05}. Decode returns null for a
     * malformed line instead of failing the load.
     */
    protected static String encodeTrade(TradeRecord record) {
        return record.time() + "|" + (record.buy() ? "BUY" : "SELL") + "|"
                + record.material().name() + "|" + record.amount() + "|"
                + record.unit() + "|" + record.total();
    }

    protected static TradeRecord decodeTrade(String encoded) {
        try {
            String[] parts = encoded.split("\\|");
            if (parts.length != 6) {
                return null;
            }
            Material material = Material.matchMaterial(parts[2]);
            if (material == null) {
                return null;
            }
            return new TradeRecord(Long.parseLong(parts[0]), material,
                    "BUY".equals(parts[1]), Integer.parseInt(parts[3]),
                    Double.parseDouble(parts[4]), Double.parseDouble(parts[5]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

package com.nextnodes.plugin;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Lee la configuración de límite de jugadores y el estado de rangos/online que gestiona el panel web del mod. */
public final class PlayerLimitMongo {
    private static final String COL_USERS = "users";
    private static final String COL_RANKS = "ranks";
    private static final String COL_SETTINGS = "settings";
    private static final String KEY_LIMIT_SETTINGS = "limitSettings";
    private static final String DEFAULT_KICK_MESSAGE = "El servidor está lleno ({online}/{max}).";

    private final MongoDatabase db;

    public PlayerLimitMongo(MongoDatabase db) {
        this.db = db;
    }

    public static final class Settings {
        public final boolean enabled;
        public final int max;
        public final String kickMessage;

        public Settings(boolean enabled, int max, String kickMessage) {
            this.enabled = enabled;
            this.max = max;
            this.kickMessage = kickMessage;
        }
    }

    /** Lee settings/limitSettings; si no existe, por defecto está desactivado (igual que en el mod). */
    public Settings loadSettings() {
        Document doc = db.getCollection(COL_SETTINGS).find(Filters.eq("_id", KEY_LIMIT_SETTINGS)).first();
        if (doc == null) {
            return new Settings(false, 20, DEFAULT_KICK_MESSAGE);
        }
        boolean enabled = Boolean.TRUE.equals(doc.getBoolean("enabled", false));
        int max = doc.getInteger("max", 20);
        if (max <= 0) {
            max = 20;
        }
        String kickMessage = doc.getString("kickMessage");
        if (kickMessage == null || kickMessage.isBlank()) {
            kickMessage = DEFAULT_KICK_MESSAGE;
        }
        return new Settings(enabled, max, kickMessage);
    }

    /** Nombres de rango (ya en minúscula) marcados como bypass en el panel web. */
    public Set<String> bypassRankNames() {
        Set<String> names = new HashSet<>();
        for (Document doc : db.getCollection(COL_RANKS).find(Filters.eq("bypassPlayerLimit", true))) {
            Object id = doc.get("_id");
            if (id != null) names.add(id.toString());
        }
        return names;
    }

    /** True si los rangos guardados del jugador intersectan con el set de bypass. Jugador desconocido -> false. */
    public boolean hasBypassRank(String uuid, Set<String> bypassRanks) {
        if (bypassRanks.isEmpty()) return false;
        Document doc = db.getCollection(COL_USERS).find(Filters.eq("_id", uuid)).first();
        if (doc == null) return false;
        List<String> ranks = doc.getList("ranks", String.class);
        if (ranks == null) return false;
        for (String rank : ranks) {
            if (bypassRanks.contains(rank)) return true;
        }
        return false;
    }

    /** Cuenta, de los UUIDs dados, cuántos NO tienen rango de bypass. */
    public long countOnlineWithoutBypass(Collection<String> onlineUuids, Set<String> bypassRanks) {
        if (onlineUuids.isEmpty()) return 0;
        Set<String> withBypass = new HashSet<>();
        if (!bypassRanks.isEmpty()) {
            for (Document doc : db.getCollection(COL_USERS).find(Filters.in("_id", onlineUuids))) {
                List<String> ranks = doc.getList("ranks", String.class);
                if (ranks == null) continue;
                for (String rank : ranks) {
                    if (bypassRanks.contains(rank)) {
                        Object id = doc.get("_id");
                        if (id != null) withBypass.add(id.toString());
                        break;
                    }
                }
            }
        }
        return onlineUuids.size() - withBypass.size();
    }
}

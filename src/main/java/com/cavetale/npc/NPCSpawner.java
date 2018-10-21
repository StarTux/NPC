package com.cavetale.npc;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

@Getter
public final class NPCSpawner {
    private final NPCPlugin plugin;
    private final String name; // unique
    NPC npc;
    // Configuration
    private ConfigurationSection config;
    private String world;
    private double x, y, z;
    private float pitch, yaw;
    NPC.Type type;
    private boolean spawning;
    @Setter private boolean valid;

    NPCSpawner(NPCPlugin plugin, String name) {
        this.plugin = plugin;
        this.name = name;
    }

    void setConfig(ConfigurationSection cfg) {
        this.config = cfg;
    }

    void loadConfig() {
        this.world = config.getString("world");
        this.x = config.getDouble("x");
        this.y = config.getDouble("y");
        this.z = config.getDouble("z");
        this.pitch = (float)config.getDouble("pitch");
        this.yaw = (float)config.getDouble("yaw");
        this.type = NPC.Type.valueOf(config.getString("type").toUpperCase());
        this.valid = true;
    }

    void tick() {
        if (!valid) return;
        if (npc != null && !npc.isValid()) {
            npc = null;
        }
        if (npc == null && !spawning) {
            try {
                spawn();
            } catch (Exception e) {
                e.printStackTrace();
                valid = false;
            }
        }
    }

    public void setLocation(Location location) {
        this.world = location.getWorld().getName();
        this.x = location.getX();
        this.y = location.getY();
        this.z = location.getZ();
        this.pitch = location.getPitch();
        this.yaw = location.getYaw();
        config.set("world", world);
        config.set("x", x);
        config.set("y", y);
        config.set("z", z);
        config.set("pitch", pitch);
        config.set("yaw", yaw);
    }

    public void setType(NPC.Type type) {
        this.type = type;
        config.set("type", type.name());
    }

    public Location getLocation() {
        World bw = Bukkit.getWorld(world);
        if (bw == null) return null;
        return new Location(bw, x, y, z, yaw, pitch);
    }

    boolean enableNPC(NPC newNPC) {
        if (this.npc != null) this.npc.setValid(false);
        try {
            if (config.isSet("job")) newNPC.setJob(NPC.Job.valueOf(config.getString("job").toUpperCase()));
            if (config.isSet("conversation")) newNPC.setConversationDelegate(new SimpleConversationDelegate(config.getConfigurationSection("conversation")));
            if (config.isSet("chat_display_name")) newNPC.setChatDisplayName(config.getString("chat_display_name"));
            if (config.isSet("announcements")) {
                newNPC.setAnnouncements(config.getStringList("announcements"));
                newNPC.setAnnouncementRandom(config.getBoolean("announcement_random"));
                newNPC.setAnnouncementInterval(config.getInt("announcement_interval", 20));
            }
            if (config.isSet("chat_color")) newNPC.setChatColor(ChatColor.valueOf(config.getString("chat_color").toUpperCase()));
        } catch (Exception e) {
            return false;
        }
        this.npc = newNPC;
        return plugin.enableNPC(newNPC);
    }

    boolean spawn() {
        if (spawning) return false;
        World bw = Bukkit.getWorld(world);
        if (bw.getPlayers().isEmpty()) return false;
        if (bw == null) return false;
        if (!bw.isChunkLoaded((int)Math.floor(x) >> 4, (int)Math.floor(z) >> 4)) return false;
        Location location = new Location(bw, x, y, z, yaw, pitch);
        switch (type) {
        case PLAYER:
            if (config.isSet("skin")) {
                return enableNPC(new NPC(plugin, NPC.Type.PLAYER, location, name, plugin.getNamedSkins().get(config.getString("skin"))));
            } else if (config.isSet("skin_id") || config.isSet("skin_name")) {
                spawning = true;
                String id = config.getString("skin_id");
                if (id != null && id.contains("-")) id = id.replace("-", "");
                String name = config.getString("skin_name");
                plugin.getPlayerSkinAsync(id, name, (skin) -> {
                        spawning = false;
                        if (!valid) return;
                        enableNPC(new NPC(plugin, NPC.Type.PLAYER, location, name, skin));
                    });
                return true;
            } else {
                return enableNPC(new NPC(plugin, NPC.Type.PLAYER, location, name, new PlayerSkin("", "", "", "")));
            }
        case MOB: {
            EntityType et;
            if (config.isSet("entity_type")) {
                String val = config.getString("entity_type");
                try {
                    et = EntityType.valueOf(val.toUpperCase());
                } catch (IllegalArgumentException iae) {
                    plugin.getLogger().info(name + " has unknown entity type: " + val);
                    return false;
                }
            } else {
                et = EntityType.ZOMBIE;
            }
            return enableNPC(new NPC(plugin, NPC.Type.MOB, location, et));
        }
        case BLOCK:
            return false;
        case ITEM:
            return false;
        default: return false;
        }
    }
}

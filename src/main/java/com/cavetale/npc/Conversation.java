package com.cavetale.npc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.Value;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

@Getter
public final class Conversation {
    private final NPCManager manager;
    private final Plugin plugin;
    final List<NPC> npcs = new ArrayList<>();
    final List<Player> players = new ArrayList<>();
    private final static String META_CONVO = "npc.conversation";
    private long ticksLived;
    private long timer;
    private int timeouts;
    @Setter Delegate delegate = () -> 0;
    private String state;
    private final Map<String, Option> options = new LinkedHashMap<>();
    private double maxDistance = 16.0, maxDistanceSquared = 256.0;
    @Setter private boolean exclusive;
    @Setter private boolean valid;

    public Conversation(NPCManager manager) {
        this.manager = manager;
        this.plugin = manager.getPlugin();
    }

    static interface Delegate {
        int onTimeout();
        default void onTick() { }
        default void onEnable() { }
        default void onDisable() { }
        default void onNPCAdd(NPC npc) { }
        default void onNPCRemove(NPC npc) { }
        default void onPlayerAdd(Player player) { }
        default void onPlayerRemove(Player player) { }
    }

    public void add(Player player) {
        players.add(player);
    }

    public void add(NPC npc) {
        npcs.add(npc);
    }

    public void remove(Player player) {
        disablePlayer(player);
        players.remove(player);
    }

    public void remove(NPC npc) {
        disableNPC(npc);
        npcs.remove(npc);
    }

    public void simple(List<String> texts) {
        this.delegate = () -> {
            if (timeouts < texts.size()) {
                return (int)npcs.get(0).addSpeechBubble(manager, texts.get(timeouts), null).getLifespan();
            } else {
                return 0;
            }
        };
    }

    @Value
    public final class Option {
        String key, text, newState;
    }

    public void enable() {
        delegate.onEnable();
        for (Player player: players) enablePlayer(player);
        for (NPC npc: npcs) enableNPC(npc);
        valid = true;
    }

    public void disable() {
        valid = false;
        delegate.onDisable();
        for (Player player: players) disablePlayer(player);
        for (NPC npc: npcs) disableNPC(npc);
        players.clear();
        npcs.clear();
    }

    public void setMaxDistance(double maxDist) {
        this.maxDistance = maxDist;
        this.maxDistanceSquared = maxDist * maxDist;
    }

    public void tick() {
        if (players.isEmpty() || npcs.isEmpty()) {
            disable();
            return;
        }
        // Weed out players
        for (Iterator<Player> iter = players.iterator(); iter.hasNext();) {
            Player player = iter.next();
            if (!player.isValid() || of(player, plugin) != this || !player.getLocation().getWorld().getName().equals(npcs.get(0).getLocation().getWorld().getName()) || player.getLocation().distanceSquared(npcs.get(0).getLocation()) > maxDistanceSquared) {
                delegate.onPlayerRemove(player);
                iter.remove();
                if (of(player, plugin) == this) player.removeMetadata(META_CONVO, plugin);
                continue;
            }
        }
        // Weed out NPCs
        for (Iterator<NPC> iter = npcs.iterator(); iter.hasNext();) {
            NPC npc = iter.next();
            if (!npc.isValid() || of(npc) != this) {
                iter.remove();
                if (npc.getConversation() == this) npc.setConversation(null);
                continue;
            }
        }
        if (players.isEmpty() || npcs.isEmpty()) {
            disable();
            return;
        }
        if (timer == 0) {
            timer = delegate.onTimeout();
            timeouts += 1;
        }
        if (timer <= 0) {
            disable();
            return;
        }
        ticksLived += 1;
        timer -= 1;
    }

    public void enableNPC(NPC npc) {
        delegate.onNPCAdd(npc);
        npc.beginConversation(this);
    }

    public void disableNPC(NPC npc) {
        delegate.onNPCRemove(npc);
        if (npc.getConversation() == this) npc.setConversation(null);
    }

    private void enablePlayer(Player player) {
        player.setMetadata(META_CONVO, new FixedMetadataValue(plugin, this));
        delegate.onPlayerAdd(player);
    }

    private void disablePlayer(Player player) {
        if (of(player, plugin) == this) player.removeMetadata(META_CONVO, plugin);
        delegate.onPlayerRemove(player);
    }

    public static Conversation of(Player player, Plugin plugin) {
        for (MetadataValue meta: player.getMetadata(META_CONVO)) {
            if (meta.getOwningPlugin() == plugin) return (Conversation)meta.value();
        }
        return null;
    }

    public static Conversation of(Player player, NPCManager manager) {
        return of(player, manager.getPlugin());
    }

    public static Conversation of(NPC npc) {
        return npc.getConversation();
    }
}

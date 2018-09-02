package com.cavetale.npc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
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
    private boolean enabled, disabled;
    private static final String CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final List<ChatColor> COLORS = Arrays.asList(ChatColor.BLUE, ChatColor.AQUA, ChatColor.GOLD, ChatColor.GRAY, ChatColor.GREEN, ChatColor.RED, ChatColor.YELLOW);
    private static final String HLINE = "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500";

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
        default void onQuestionHeader(int npcIndex) { }
        default void onQuestionFooter(int npcIndex) { }
    }

    @RequiredArgsConstructor
    public static final class Option {
        public final String text, state;
        public ChatColor color;
        public transient String key;
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

    public void simple(List<Object> texts) {
        this.delegate = () -> {
            if (timeouts < texts.size()) {
                Object o = texts.get(timeouts);
                if (o instanceof List) {
                    return say(0, (List<String>)o);
                } else if (o instanceof String) {
                    return say(0, Arrays.asList((String)o));
                }
            }
            return 0;
        };
    }

    public int say(int npcIndex, List<String> texts) {
        if (npcIndex < 0 || npcIndex >= npcs.size()) return 0;
        NPC npc = npcs.get(npcIndex);
        int result = 0;
        BaseComponent[] msg = npc.formatChat(texts);
        for (Player player: players) {
            player.spigot().sendMessage(msg);
        }
        int lifespan = 0;
        for (String text: texts) {
            lifespan += (npc.getChatSpeed() * text.length()) / 16;
        }
        for (String text: texts) {
            NPC bubble = npc.addSpeechBubble(manager, text, null);
            bubble.setLifespan((long)lifespan);
        }
        npc.updateSpeechBubbles();
        return lifespan;
    }

    public int say(List<String> text) {
        return say(0, text);
    }

    public int sayOptions(int npcIndex, String question, List<Option> opts) {
        if (npcIndex < 0 || npcIndex >= npcs.size()) return 0;
        NPC npc = npcs.get(npcIndex);
        npc.addSpeechBubble(manager, question, null).setLifespan(0L);
        BaseComponent[] questionMsg = npc.formatChat(Arrays.asList(question));
        for (Player player: players) {
            player.spigot().sendMessage(questionMsg);
        }
        options.clear();
        Collections.shuffle(COLORS);
        int optIndex = 0;
        for (Option opt: opts) {
            opt.key = generateOptionKey(optIndex);
            options.put(opt.key, opt);
            if (opt.color == null) opt.color = COLORS.get(optIndex % COLORS.size());
            optIndex += 1;
        }
        delegate.onQuestionHeader(npcIndex);
        optIndex = 0;
        for (Option opt: opts) {
            String cmd = "/npc option " + opt.key;
            ComponentBuilder cb = new ComponentBuilder("    ");
            cb.append((optIndex + 1) + ") ");
            cb.append(opt.text).color(opt.color);
            cb.event(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd));
            cb.event(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(opt.text).color(opt.color).create()));
            BaseComponent[] chatMessage = cb.create();
            for (Player player: players) {
                player.spigot().sendMessage(chatMessage);
            }
            NPC bubble = npc.addSpeechBubble(manager, "" + ChatColor.WHITE + + (optIndex + 1) + ") " + opt.color + opt.text, null);
            bubble.setLifespan(0L);
            optIndex += 1;
        }
        delegate.onQuestionFooter(npcIndex);
        for (Player player: players) player.sendMessage("");
        npc.updateSpeechBubbles();
        return 400;
    }

    public int sayOptions(String question, List<Option> opts) {
        return sayOptions(0, question, opts);
    }

    private static String generateOptionKey(int index) {
        StringBuilder sb = new StringBuilder(CHARS.charAt(index));
        ThreadLocalRandom tlr = ThreadLocalRandom.current();
        for (int i = 0; i < 5; i += 1) {
            sb.append(CHARS.charAt(tlr.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    public void enable() {
        if (enabled) return;
        enabled = true;
        delegate.onEnable();
        for (Player player: players) enablePlayer(player);
        for (NPC npc: npcs) enableNPC(npc);
        valid = true;
    }

    public void disable() {
        if (disabled) return;
        disabled = true;
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
            valid = false;
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
            valid = false;
            return;
        }
        if (timer == 0) {
            options.clear();
            timer = delegate.onTimeout();
            timeouts += 1;
        }
        if (timer <= 0) {
            valid = false;
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
        npc.clearSpeechBubbles();
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

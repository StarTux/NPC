package com.cavetale.npc;

import org.bukkit.plugin.Plugin;

/**
 * Implemented by NPCPlugin
 */
public interface NPCManager {
    default Plugin getPlugin() {
        return (Plugin)this;
    }
    boolean enableNPC(NPC npc);
    boolean enableConversation(Conversation conversation);
}

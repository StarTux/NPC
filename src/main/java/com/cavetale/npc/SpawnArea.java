package com.cavetale.npc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.Value;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

@Data
final class SpawnArea {
    private final NPCPlugin plugin;
    private final String id;
    private String world;
    private final Set<Vec> blocks = new HashSet<>();
    private int amount;
    private final List<NPC> npcs = new ArrayList<>();
    private final Random random = new Random(System.nanoTime());
    private YamlConfiguration config;

    @Value
    static class Vec {
        public final int x, y, z;
    }

    void importConfig(YamlConfiguration cfg) {
        this.config = cfg;
        world = config.getString("World");
        amount = config.getInt("Amount");
        for (List<Number> ls: (List<List<Number>>)config.getList("Blocks")) {
            blocks.add(new Vec(ls.get(0).intValue(), ls.get(1).intValue(), ls.get(2).intValue()));
        }
    }

    public YamlConfiguration exportConfig() {
        config.set("World", world);
        config.set("Amount", amount);
        config.set("Blocks", blocks.stream().map(b -> Arrays.asList(b.x, b.y, b.z)).collect(Collectors.toList()));
        return config;
    }

    void addBlock(int x, int y, int z) {
        blocks.add(new Vec(x, y, z));
    }

    void removeBlock(int x, int y, int z) {
        blocks.remove(new Vec(x, y, z));
    }

    void onTick() {
        World bWorld = Bukkit.getWorld(world);
        if (bWorld == null) return;
        List<Player> players = bWorld.getPlayers();
        if (players.isEmpty()) return;
        for (Iterator<NPC> iter = npcs.iterator(); iter.hasNext();) {
            if (!iter.next().isValid()) iter.remove();
        }
        if (npcs.size() >= amount) return;
        // Pick a random block
        if (blocks.isEmpty()) return;
        Vec spawnVec = new ArrayList<>(blocks).get(random.nextInt(blocks.size()));
        Block spawnBlock = bWorld.getBlockAt(spawnVec.x, spawnVec.y, spawnVec.z);
        Chunk spawnChunk = spawnBlock.getChunk();
        if (!spawnBlock.getWorld().isChunkLoaded(spawnChunk.getX(), spawnChunk.getZ())) return;
        Location location = spawnBlock.getLocation().add(0.5, 1.0, 0.5);
        ConfigurationSection section = config.getConfigurationSection("RandomVillagers");
        List<String> npckeys = new ArrayList<>(section.getKeys(false));
        if (npckeys.isEmpty()) return;
        String npckey = npckeys.get(random.nextInt(npckeys.size()));
        section = section.getConfigurationSection(npckey);
        String npcname = npckey;
        int npcIndex = 0;
        while (plugin.findNPCWithUniqueName(npcname) != null) {
            npcIndex += 1;
            npcname = String.format("%s%02d", npckey, npcIndex);
        }
        final NPC npc = spawnNPC(npcname, location);
    }

    NPC spawnNPC(String name, Location location) {
        ConfigurationSection section = config.getConfigurationSection("RandomVillagers." + name);
        if (section == null) return null;
        NPC npc;
        if (random.nextInt(3) > 0) {
            List<PlayerSkin> skins = new ArrayList<>(plugin.getNamedSkins().values());
            PlayerSkin playerSkin = skins.get(random.nextInt(skins.size()));
            StringBuilder sb = new StringBuilder();
            sb.append(ChatColor.RESET.toString());
            sb.append(ChatColor.RESET.toString());
            for (int i = 0; i < 6; i += 1) {
                sb.append(ChatColor.values()[random.nextInt(ChatColor.values().length)]);
            }
            npc = new NPC(plugin, NPC.Type.PLAYER, location, sb.toString(), playerSkin);
            npc.setJob(random.nextInt(5) == 0 ? NPC.Job.DANCE : NPC.Job.WANDER);
        } else {
            npc = new NPC(plugin, NPC.Type.MOB, location, EntityType.VILLAGER);
            npc.setData(NPC.DataVar.VILLAGER_PROFESSION, random.nextInt(6));
            if (random.nextBoolean()) {
                npc.setBaby(true);
                npc.setData(NPC.DataVar.AGEABLE_BABY, true);
            }
            npc.setJob(NPC.Job.WANDER);
        }
        if (npc.isBlockedAt(location)) return null;
        if (npc.collidesWithOther()) return null;
        npc.setUniqueName(name);
        npc.setChatDisplayName(section.getString("DisplayName"));
        npc.setConversationDelegate(new SimpleConversationDelegate(section.getConfigurationSection("Conversation")));
        npc.setDelegate(new NPC.Delegate() {
                @Override public void onTick(NPC n) { }
                @Override public boolean canMoveIn(NPC n, Block b) {
                    if (blocks.contains(new Vec(b.getX(), b.getY() - 1, b.getZ()))) return true;
                    if (blocks.contains(new Vec(b.getX(), b.getY(), b.getZ()))) return true;
                    if (b.isEmpty() && blocks.contains(new Vec(b.getX(), b.getY() - 2, b.getZ()))) return true;
                    return false;
                }
                @Override public boolean canMoveOn(NPC n, Block b) {
                    if (blocks.contains(new Vec(b.getX(), b.getY(), b.getZ()))) return true;
                    if (b.isEmpty() && blocks.contains(new Vec(b.getX(), b.getY() - 1, b.getZ()))) return true;
                    return false;
                }
            });
        if (plugin.enableNPC(npc)) {
            this.npcs.add(npc);
            return npc;
        } else {
            return null;
        }
    }
}

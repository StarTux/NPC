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
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

@Data
final class SpawnArea {
    private final NPCPlugin plugin;
    private final String id;
    private String world;
    private final Set<Chunk> chunks = new HashSet<>();
    private int amount;
    private final List<NPC> npcs = new ArrayList<>();
    private final Random random = new Random(System.nanoTime());

    @Value
    static class Chunk {
        public final int x, z;
    }

    void importConfig(ConfigurationSection config) {
        world = config.getString("World");
        amount = config.getInt("Amount");
        for (List<Number> ls: (List<List<Number>>)config.getList("Chunks")) {
            chunks.add(new Chunk(ls.get(0).intValue(), ls.get(1).intValue()));
        }
    }

    void exportConfig(ConfigurationSection config) {
        config.set("World", world);
        config.set("Amount", amount);
        config.set("Chunks", chunks.stream().map(c -> Arrays.asList(c.x, c.z)).collect(Collectors.toList()));
    }

    void addChunk(int x, int z) {
        chunks.add(new Chunk(x, z));
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
        // Collect player chunks and remove chunks which are too close
        // to players
        Set<Chunk> playerChunks = new HashSet<>();
        Set<Chunk> validChunks = new HashSet<>(chunks);
        for (Player player: players) {
            org.bukkit.Chunk playerChunk = player.getLocation().getChunk();
            int px = playerChunk.getX();
            int pz = playerChunk.getZ();
            playerChunks.add(new Chunk(px, pz));
            for (int z = -1; z <= 1; z += 1) {
                for (int x = -1; x <= 1; x += 1) {
                    validChunks.remove(new Chunk(px + x, pz + z));
                }
            }
        }
        // Find chunks close enough to players
        Set<Chunk> spawnChunks = new HashSet<>();
        for (Chunk playerChunk: playerChunks) {
            for (int z = -3; z <= 3; z += 1) {
                for (int x = -3; x <= 3; x += 1) {
                    if (Math.abs(x) < 2 && Math.abs(z) < 2) continue;
                    Chunk chunk = new Chunk(playerChunk.getX() + x, playerChunk.getZ() + z);
                    if (validChunks.contains(chunk)) spawnChunks.add(chunk);
                }
            }
        }
        if (spawnChunks.isEmpty()) return;
        for (Chunk chunk: spawnChunks) {
            int x = (chunk.getX() << 4) + random.nextInt(16);
            int z = (chunk.getZ() << 4) + random.nextInt(16);
            Block block = bWorld.getHighestBlockAt(x, z);
            Location location = block.getLocation().add(0.5, 0.0, 0.5);
            NPC npc = new NPC(NPC.Type.PLAYER, location, "Insert Name", null);
            if (npc.isBlockedAt(location)) {
                continue;
            }
            npc.setJob(NPC.Job.WANDER);
            plugin.enableNPC(npc);
            this.npcs.add(npc);
        }
    }
}

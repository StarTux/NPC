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
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Slab;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
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
            int chunkNPCCount = 0;
            for (NPC other: npcs) {
                int x = other.getLocation().getBlockX() >> 4;
                int z = other.getLocation().getBlockZ() >> 4;
                if (x == chunk.getX() && z == chunk.getZ()) {
                    chunkNPCCount += 1;
                }
            }
            if (chunkNPCCount > 2) continue;
            int x = (chunk.getX() << 4) + random.nextInt(16);
            int z = (chunk.getZ() << 4) + random.nextInt(16);
            Block block = bWorld.getHighestBlockAt(x, z);
            while (block.getY() >= 32 && !canSpawnAt(block)) block = block.getRelative(0, -1, 0);
            if (block.getY() < 32) continue;
            if (!Tag.STONE_BRICKS.isTagged(block.getRelative(0, -1, 0).getType())) continue;
            Location location = block.getLocation().add(0.5, 0.0, 0.5);
            final NPC npc;
            if (random.nextInt(3) > 0) {
                List<PlayerSkin> skins = new ArrayList<>(plugin.getNamedSkins().values());
                PlayerSkin playerSkin = skins.get(random.nextInt(skins.size()));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 8; i += 1) {
                    sb.append(ChatColor.values()[random.nextInt(ChatColor.values().length)]);
                }
                npc = new NPC(NPC.Type.PLAYER, location, sb.toString(), playerSkin);
                npc.setJob(random.nextInt(5) == 0 ? NPC.Job.DANCE : NPC.Job.WANDER);
            } else {
                npc = new NPC(NPC.Type.MOB, location, EntityType.VILLAGER);
                npc.setData(NPC.DataVar.VILLAGER_PROFESSION, random.nextInt(6));
                if (random.nextBoolean()) npc.setData(NPC.DataVar.AGEABLE_BABY, true);
                npc.setJob(NPC.Job.WANDER);
            }
            if (npc.isBlockedAt(location)) continue;
            if (npc.collidesWithOther()) continue;
            npc.setDelegate(new NPC.Delegate() {
                    @Override public void onTick() {
                    }
                    @Override public boolean canMoveIn(Block block) {
                        switch (block.getType()) {
                        case GRASS:
                        case WHEAT:
                        case POTATOES:
                        case CARROTS:
                        case BEETROOTS:
                        case TALL_GRASS:
                        case TALL_SEAGRASS:
                        case LARGE_FERN:
                        case ROSE_BUSH:
                        case SUNFLOWER:
                        case LILAC:
                        case PEONY:
                            return false;
                        default:
                            return true;
                        }
                    }
                    @Override public boolean canMoveOn(Block block) {
                        Material mat = block.getType();
                        if (Tag.LEAVES.isTagged(mat)) return false;
                        if (Tag.LOGS.isTagged(mat)) return false;
                        if (Tag.SLABS.isTagged(mat) && ((Slab)block.getBlockData()).getType() == Slab.Type.TOP) {
                            return false;
                        }
                        if (Tag.TRAPDOORS.isTagged(mat)) return false;
                        switch (mat) {
                        case OAK_FENCE:
                        case SPRUCE_FENCE:
                        case BIRCH_FENCE:
                        case DARK_OAK_FENCE:
                        case JUNGLE_FENCE:
                        case ACACIA_FENCE:
                        case OAK_FENCE_GATE:
                        case SPRUCE_FENCE_GATE:
                        case BIRCH_FENCE_GATE:
                        case DARK_OAK_FENCE_GATE:
                        case JUNGLE_FENCE_GATE:
                        case ACACIA_FENCE_GATE:
                        case FARMLAND:
                        case SIGN:
                        case WALL_SIGN:
                        case HAY_BLOCK:
                            return false;
                        default:
                            return true;
                        }
                    }
                    @Override public boolean onInteract(Player player, boolean rightClick) {
                        return true;
                    }
                });
            if (plugin.enableNPC(npc)) {
                this.npcs.add(npc);
            }
            return;
        }
    }

    private boolean canSpawnAt(Block block) {
        if (!block.isEmpty()) return false;
        if (!block.getRelative(0, 1, 0).isEmpty()) return false;
        Block floor = block.getRelative(0, -1, 0);
        if (floor.isEmpty()) return false;
        Material floorMat = floor.getType();
        if (!floorMat.isOccluding() || !floorMat.isSolid() || floorMat.isTransparent()) return false;
        return true;
    }
}

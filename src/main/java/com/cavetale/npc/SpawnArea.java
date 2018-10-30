package com.cavetale.npc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.Value;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
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
    private static final int VIEW_DISTANCE = 80;
    private int villagerIndex = 0;

    @Value
    static class Vec {
        public final int x, y, z;
        static Vec v(int x, int y, int z) {
            return new Vec(x, y, z);
        }
        @Override
        public String toString() {
            return String.format("(%d,%d,%d)", x, y, z);
        }
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
        for (Iterator<NPC> iter = npcs.iterator(); iter.hasNext();) {
            if (!iter.next().isValid()) iter.remove();
        }
        if (npcs.size() >= amount) return;
        World bWorld = Bukkit.getWorld(world);
        if (bWorld == null) return;
        List<Player> players = bWorld.getPlayers();
        if (players.isEmpty()) return;
        // Pick a random block
        if (blocks.isEmpty()) return;
        Vec spawnVec = new ArrayList<>(blocks).get(random.nextInt(blocks.size()));
        if (!bWorld.isChunkLoaded(spawnVec.x >> 4, spawnVec.z >> 4)) return;
        int inRange = 0;
        for (Player player: players) {
            Block pb = player.getLocation().getBlock();
            int dx = Math.abs(pb.getX() - spawnVec.x);
            int dz = Math.abs(pb.getZ() - spawnVec.z);
            if (dx < 16 || dz < 16) return;
            if (dx < VIEW_DISTANCE && dz < VIEW_DISTANCE) inRange += 1;
        }
        if (inRange == 0) return;
        Block spawnBlock = bWorld.getBlockAt(spawnVec.x, spawnVec.y, spawnVec.z);
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
        final NPC npc = spawnRandomVillager(npcname, location);
    }

    NPC spawnRandomVillager(String name, Location location) {
        ConfigurationSection section = config.getConfigurationSection("RandomVillagers." + name);
        if (section == null) return null;
        NPC npc;
        if (random.nextInt(5) > 0) {
            List<String> skinNames = new ArrayList<>(plugin.getNamedSkins().keySet());
            String skinName = skinNames.get(random.nextInt(skinNames.size()));
            PlayerSkin playerSkin = plugin.getNamedSkins().get(skinName);
            String playerName = "%villager" + villagerIndex++;
            if (playerName.length() > 16) playerName = playerName.substring(0, 16);
            npc = new NPC(plugin, NPC.Type.PLAYER, location, playerName, playerSkin);
        } else {
            npc = new NPC(plugin, NPC.Type.MOB, location, EntityType.VILLAGER);
            npc.setData(NPC.DataVar.VILLAGER_PROFESSION, random.nextInt(6));
            if (random.nextBoolean()) {
                npc.setBaby(true);
                npc.setData(NPC.DataVar.AGEABLE_BABY, true);
            }
        }
        npc.setJob(NPC.Job.WALK_PATH);
        npc.setMovementSpeed(2.0 + random.nextDouble() * 2.0);
        npc.setViewDistance((double)VIEW_DISTANCE);
        if (npc.isBlockedAt(location)) return null;
        if (npc.collidesWithOther()) return null;
        npc.setUniqueName(name);
        npc.setChatDisplayName(section.getString("DisplayName"));
        npc.setConversationDelegate(new SimpleConversationDelegate(section.getConfigurationSection("Conversation")));
        npc.setDelegate(new NPC.Delegate() {
                @Override public void onTick(NPC n) { }
                @Override public boolean canMoveIn(NPC n, Block b) {
                    if (npc.getJob() == NPC.Job.WALK_PATH) return true;
                    if (blocks.contains(new Vec(b.getX(), b.getY() - 1, b.getZ()))) return true;
                    if (blocks.contains(new Vec(b.getX(), b.getY(), b.getZ()))) return true;
                    if (b.isEmpty() && blocks.contains(new Vec(b.getX(), b.getY() - 2, b.getZ()))) return true;
                    return false;
                }
                @Override public boolean canMoveOn(NPC n, Block b) {
                    if (npc.getJob() == NPC.Job.WALK_PATH) return true;
                    if (blocks.contains(new Vec(b.getX(), b.getY(), b.getZ()))) return true;
                    if (b.isEmpty() && blocks.contains(new Vec(b.getX(), b.getY() - 1, b.getZ()))) return true;
                    return false;
                }
                @Override public void didFinishPath(NPC npc) {
                    npc.setJob(NPC.Job.IDLE);
                    findPath(npc);
                }
            });
        if (plugin.enableNPC(npc)) {
            this.npcs.add(npc);
            return npc;
        } else {
            return null;
        }
    }

    private void findPath(NPC npc) {
        npc.getPath().clear();
        Vec s = new Vec(npc.getLocation().getBlockX(), npc.getLocation().getBlockY(), npc.getLocation().getBlockZ());
        if (!blocks.contains(s)) s = Vec.v(s.x, s.y - 1, s.z);
        if (!blocks.contains(s)) {
            npc.setValid(false);
            return;
        }
        final Vec goal = new ArrayList<>(blocks).get(random.nextInt(blocks.size()));
        final Vec start = s;
        plugin.getAsyncTasks().add(() -> {
                findPathAsync(npc, start, goal, (newPath) -> {
                        if (newPath != null) {
                            for (Vec vec: newPath) {
                                npc.getPath().add(new NPC.Vec3i(vec.x, vec.y, vec.z));
                                npc.setJob(NPC.Job.WALK_PATH);
                            }
                        } else {
                            findPath(npc);
                        }
                    });
            });
    }

    void findPathAsync(NPC npc, Vec start, Vec goal, Consumer<List<Vec>> callback) {
        World bw = Bukkit.getWorld(world);
        if (bw == null) return;
        Map<Vec, Vec> path = new HashMap<>();
        List<Vec> todo = new ArrayList<>();
        Set<Vec> done = new HashSet<>();
        Map<Vec, Integer> dist = new HashMap<>();
        dist.put(goal, 0);
        todo.add(goal);
        boolean goalReached = false;
        int rcount = 0;
        while (!todo.isEmpty() && npc.isValid()) {
            if (rcount++ > 999) break;
            Vec cur = todo.remove(0);
            if (done.contains(cur)) continue;
            done.add(cur);
            int curdist = dist.get(cur);
            NBORS:
            for (int dz = -1; dz <= 1; dz += 1) {
                for (int dx = -1; dx <= 1; dx += 1) {
                    for (int dy = -1; dy <= 1; dy += 1) {
                        Vec nbor = Vec.v(cur.x + dx, cur.y + dy, cur.z + dz);
                        Integer nbordist = dist.get(nbor);
                        if (blocks.contains(nbor)) {
                            if (dz != 0 && dx != 0) {
                                if (!blocks.contains(Vec.v(cur.x, cur.y + dy, cur.z + dz))
                                    || !blocks.contains(Vec.v(cur.x + dx, cur.y + dy, cur.z))) {
                                    continue;
                                }
                            }
                            if (!done.contains(nbor) && (nbordist == null || nbordist > curdist)) {
                                todo.add(nbor);
                                path.put(nbor, cur);
                                dist.put(nbor, curdist + (dx != 0 && dz != 0 ? 18 : 10));
                                if (nbor.equals(start)) {
                                    goalReached = true;
                                    break NBORS;
                                }
                            }
                            break;
                        }
                    }
                }
            }
        }
        if (!plugin.isEnabled()) return;
        if (!npc.isValid()) return;
        if (goalReached) {
            List<Vec> newPath = new ArrayList<>();
            Vec now = start;
            while (!now.equals(goal)) {
                newPath.add(now);
                now = path.get(now);
            }
            newPath.add(goal);
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(newPath));
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(null));
        }
    }
}

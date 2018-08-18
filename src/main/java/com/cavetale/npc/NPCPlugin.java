package com.cavetale.npc;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import lombok.Getter;
import net.minecraft.server.v1_13_R1.PacketPlayInUseEntity;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.inventivetalent.packetlistener.PacketListenerAPI;
import org.inventivetalent.packetlistener.handler.PacketHandler;
import org.inventivetalent.packetlistener.handler.ReceivedPacket;
import org.inventivetalent.packetlistener.handler.SentPacket;

public final class NPCPlugin extends JavaPlugin {
    private final List<NPC> npcs = new ArrayList<>();
    private Random random = new Random(System.nanoTime());
    private PacketHandler packetHandler;
    @Getter private static NPCPlugin instance;
    private SpawnArea spawnArea;
    @Getter private final List<PlayerSkin> playerSkins = new ArrayList<>();
    private long ticksLived;

    @Override
    public void onEnable() {
        instance = this;
        reloadConfig();
        saveDefaultConfig();
        saveResource("skins.yml", false);
        getServer().getScheduler().runTaskTimer(this, this::onTick, 1, 1);
        packetHandler = new PacketHandler() {
                @Override
                public void onSend(SentPacket packet) {
                }

                @Override
                public void onReceive(ReceivedPacket packet) {
                    if (packet.getPacketName().equals("PacketPlayInUseEntity")) {
                        int id = (Integer)packet.getPacketValue(0);
                        for (NPC npc: npcs) {
                            if (npc.getId() == id) {
                                Player player = packet.getPlayer();
                                PacketPlayInUseEntity ppiue = (PacketPlayInUseEntity)packet.getPacket();
                                boolean rightClick;
                                switch (ppiue.b()) {
                                case INTERACT: case INTERACT_AT:
                                    rightClick = true;
                                    break;
                                case ATTACK: default:
                                    rightClick = false;
                                }
                                if (player != null) {
                                    npc.interact(player, rightClick);
                                }
                                break;
                            }
                        }
                    }
                }
            };
        PacketListenerAPI.addPacketHandler(packetHandler);
        importConfig();
        loadPlayerSkins();
    }

    void importConfig() {
        SpawnArea oldSpawnArea = spawnArea;
        spawnArea = new SpawnArea(this, "spawn");
        spawnArea.importConfig(getConfig().getConfigurationSection("spawn"));
        if (oldSpawnArea != null) spawnArea.getNpcs().addAll(oldSpawnArea.getNpcs());
    }

    void loadPlayerSkins() {
        playerSkins.clear();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "skins.yml"));
        for (Map<String, Object> map: (List<Map<String, Object>>)config.getList("skins")) {
            String texture = (String)map.get("texture");
            String signature = (String)map.get("signature");
            List<String> tags = (List<String>)map.get("tags");
            playerSkins.add(new PlayerSkin(texture, signature, tags));
        }
    }

    @Override
    public void onDisable() {
        for (NPC npc: npcs) {
            try {
                npc.disable();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        npcs.clear();
        PacketListenerAPI.removePacketHandler(packetHandler);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) return false;
        Player player = (Player)sender;
        Iterator<String> argIter = Arrays.asList(args).iterator();
        switch (argIter.next()) {
        case "spawn":
            if (args.length >= 3) {
                NPC npc;
                Location location = player.getLocation();
                location.setPitch(0.0f);
                switch (argIter.next()) {
                case "player":
                    npc = new NPC(NPC.Type.PLAYER, location, argIter.next(), null);
                    break;
                case "mob":
                    npc = new NPC(NPC.Type.MOB, location, EntityType.valueOf(argIter.next().toUpperCase()));
                    break;
                case "block":
                    npc = new NPC(NPC.Type.BLOCK, location, getServer().createBlockData(argIter.next()));
                    break;
                case "item":
                    npc = new NPC(NPC.Type.ITEM, location, new ItemStack(Material.valueOf(argIter.next().toUpperCase()), argIter.hasNext() ? Integer.parseInt(argIter.next()) : 1));
                    break;
                default:
                    return false;
                }
                if (argIter.hasNext()) {
                    npc.setJob(NPC.Job.valueOf(argIter.next().toUpperCase()));
                }
                try {
                    npc.enable();
                } catch (Throwable t) {
                    t.printStackTrace();
                    sender.sendMessage("An error occured. See console.");
                    return true;
                }
                npcs.add(npc);
                sender.sendMessage("spawned");
                return true;
            }
            break;
        case "count":
            if (args.length == 1) {
                sender.sendMessage("Count: " + npcs.size());
                return true;
            }
            break;
        case "list":
            if (args.length == 1) {
                int i = 0;
                for (NPC npc: npcs) {
                    i += 1;
                    Location location  = npc.getLocation();
                    sender.sendMessage(i + ") " + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ());
                }
                sender.sendMessage("Total: " + npcs.size());
                return true;
            }
            break;
        case "clear":
            if (args.length == 1) {
                for (NPC npc: npcs) {
                    try {
                        npc.disable();
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }
                npcs.clear();
                sender.sendMessage("cleared");
                return true;
            }
            break;
        case "addchunk":
            if (args.length == 1 && player != null) {
                Chunk chunk = player.getLocation().getChunk();
                spawnArea.addChunk(chunk.getX(), chunk.getZ());
                spawnArea.exportConfig(getConfig().getConfigurationSection("spawn"));
                saveConfig();
                sender.sendMessage("Chunk added to spawn area");
                return true;
            }
            break;
        default:
            break;
        }
        return false;
    }

    public boolean enableNPC(NPC npc) {
        npcs.add(npc);
        try {
            npc.enable();
        } catch (Throwable t) {
            t.printStackTrace();
            npcs.remove(npc);
            return false;
        }
        return true;
    }

    void onTick() {
        for (Iterator<NPC> iter = npcs.iterator(); iter.hasNext();) {
            NPC npc = iter.next();
            if (npc.isValid()) {
                try {
                    npc.tick();
                } catch (Throwable t) {
                    npc.setValid(false);
                    t.printStackTrace();
                }
            }
            if (!npc.isValid()) {
                try {
                    npc.disable();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                iter.remove();
            }
        }
        spawnArea.onTick();
        ticksLived += 1;
    }
}

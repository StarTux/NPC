package com.cavetale.npc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.inventivetalent.packetlistener.PacketListenerAPI;
import org.inventivetalent.packetlistener.handler.PacketHandler;
import org.inventivetalent.packetlistener.handler.ReceivedPacket;
import org.inventivetalent.packetlistener.handler.SentPacket;

public final class NPCPlugin extends JavaPlugin implements Listener {
    private final List<NPC> npcs = new ArrayList<>();
    public boolean autoMove = false;
    private Random random = new Random(System.nanoTime());
    private PacketHandler packetHandler;
    @Getter private static NPCPlugin instance;

    @Override
    public void onEnable() {
        instance = this;
        getServer().getPluginManager().registerEvents(this, this);
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
                                if (player != null) {
                                    npc.onInteract(player);
                                }
                                break;
                            }
                        }
                    }
                }
            };
        PacketListenerAPI.addPacketHandler(packetHandler);
     }

    @Override
    public void onDisable() {
        for (NPC npc: npcs) npc.onDisable();
        npcs.clear();
        PacketListenerAPI.removePacketHandler(packetHandler);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) return false;
        Player player = (Player)sender;
        switch (args[0]) {
        case "spawn":
            if (args.length >= 2) {
                NPC npc;
                Location location = player.getLocation();
                location.setPitch(0.0f);
                switch (args[1]) {
                case "player":
                    npc = new NPC(NPC.Type.PLAYER, location);
                    break;
                case "mob":
                    npc = new NPC(NPC.Type.MOB, location, EntityType.valueOf(args[2].toUpperCase()));
                    break;
                case "block":
                    npc = new NPC(NPC.Type.BLOCK, location, Material.valueOf(args[2].toUpperCase()));
                    break;
                default:
                    return false;
                }
                if (args.length >= 4) {
                    npc.setJob(NPC.Job.valueOf(args[3].toUpperCase()));
                }
                npcs.add(npc);
                npc.onEnable();
                sender.sendMessage("spawned");
            }
            break;
        case "count":
            if (args.length == 1) {
                sender.sendMessage("Count: " + npcs.size());
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
            }
            break;
        case "clear":
            if (args.length == 1) {
                for (NPC npc: npcs) npc.onDisable();
                npcs.clear();
                sender.sendMessage("cleared");
            }
            break;
        case "start":
            if (args.length == 1) {
                autoMove = true;
                sender.sendMessage("started");
            }
            break;
        case "stop":
            if (args.length == 1) {
                autoMove = false;
                sender.sendMessage("stopped");
            }
            break;
        default:
            break;
        }
        return true;
    }

    void onTick() {
        for (Iterator<NPC> iter = npcs.iterator(); iter.hasNext(); ) {
            NPC npc = iter.next();
            try {
                if (npc.isValid()) npc.onTick();
            } catch (Throwable t) {
                npc.setValid(false);
                t.printStackTrace();
            }
            if (!npc.isValid()) {
                try {
                    npc.onDisable();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                iter.remove();
            }
        }
    }
}

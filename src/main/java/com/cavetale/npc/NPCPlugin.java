package com.cavetale.npc;

import com.winthier.generic_events.GenericEvents;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import lombok.Getter;
import net.md_5.bungee.api.ChatColor;
import net.minecraft.server.v1_13_R2.BlockPosition;
import net.minecraft.server.v1_13_R2.PacketPlayInUseEntity;
import net.minecraft.server.v1_13_R2.PacketPlayOutOpenSignEditor;
import net.minecraft.server.v1_13_R2.PlayerConnection;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.v1_13_R2.entity.CraftPlayer;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.annotation.command.Command;
import org.bukkit.plugin.java.annotation.command.Commands;
import org.bukkit.plugin.java.annotation.dependency.Dependency;
import org.bukkit.plugin.java.annotation.dependency.DependsOn;
import org.bukkit.plugin.java.annotation.permission.Permission;
import org.bukkit.plugin.java.annotation.permission.Permissions;
import org.bukkit.plugin.java.annotation.plugin.ApiVersion;
import org.bukkit.plugin.java.annotation.plugin.Description;
import org.bukkit.plugin.java.annotation.plugin.Plugin;
import org.bukkit.plugin.java.annotation.plugin.Website;
import org.bukkit.plugin.java.annotation.plugin.author.Author;
import org.inventivetalent.packetlistener.PacketListenerAPI;
import org.inventivetalent.packetlistener.handler.PacketHandler;
import org.inventivetalent.packetlistener.handler.ReceivedPacket;
import org.inventivetalent.packetlistener.handler.SentPacket;
import org.json.simple.JSONValue;

@Plugin(name = "NPC", version = "0.1")
@Description("Custom entities and more via custom packets")
@ApiVersion(ApiVersion.Target.v1_13)
@DependsOn({
    @Dependency("PacketListenerApi"),
    @Dependency("GenericEvents")})
@Author("StarTux")
@Website("https://cavetale.com")
@Commands({
    @Command(name = "npc",
             desc = "NPC debug commands",
             permission = "npc.npc",
             usage = "/<command>"),
    @Command(name = "npcanswer",
             aliases = {"npca"},
             desc = "Reply to NPC questions",
             permission = "npc.answer",
             usage = "/<command> <code>")})
@Permissions({
    @Permission(name = "npc.npc",
                desc = "Use /npc",
                defaultValue = PermissionDefault.OP),
    @Permission(name = "npc.answer",
                desc = "Use /npca",
                defaultValue = PermissionDefault.TRUE)})
@Getter
public final class NPCPlugin extends JavaPlugin implements NPCManager {
    private final List<NPC> npcs = new ArrayList<>();
    private final List<Conversation> conversations = new ArrayList<>();
    private Random random = new Random(System.nanoTime());
    private PacketHandler packetHandler;
    private static NPCPlugin instance;
    private final Map<String, SpawnArea> spawnAreas = new HashMap<>();
    private final Map<String, PlayerSkin> namedSkins = new HashMap<>();
    private final Map<String, PlayerSkin> nameSkinCache = new HashMap<>();
    private final Map<String, PlayerSkin> uuidSkinCache = new HashMap<>();
    private final Map<String, NPCSpawner> spawners = new HashMap<>();
    private YamlConfiguration spawnersConfig;
    private long ticksLived;
    private LinkedBlockingQueue<Runnable> asyncTasks = new LinkedBlockingQueue<>();

    @Override
    public void onEnable() {
        instance = this;
        saveResource("skins.yml", false);
        saveResource("spawnareas/spawn.yml", false);
        getServer().getScheduler().runTaskTimer(this, this::onTick, 1, 1);
        packetHandler = new PacketHandler() {
                @Override
                public void onSend(SentPacket packet) {
                }

                @Override
                public void onReceive(ReceivedPacket packet) {
                    if (packet.getPacketName().equals("PacketPlayInUseEntity")) {
                        int id = (Integer)packet.getPacketValue(0);
                        NPC npc = null;
                        for (NPC n: npcs) {
                            if (n.getId() == id) {
                                npc = n;
                                break;
                            }
                        }
                        if (npc == null) return;
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
                        final NPC npc2 = npc;
                        if (player != null) {
                            getServer().getScheduler().runTask(NPCPlugin.this, () -> npc2.interact(player, rightClick));
                        }
                    }
                }
            };
        PacketListenerAPI.addPacketHandler(packetHandler);
        loadSpawnAreas();
        loadPlayerSkins();
        loadSpawners();
        getCommand("npcanswer").setExecutor((cs, c, a, args) -> onNPCAnswer(cs, args));
        getServer().getScheduler().runTaskAsynchronously(this, this::asyncWorker);
    }

    void loadSpawnAreas() {
        Map<String, SpawnArea> oldSpawnAreas = new HashMap<>(spawnAreas);
        spawnAreas.clear();
        File dir = new File(getDataFolder(), "spawnareas");
        dir.mkdirs();
        for (File file: dir.listFiles()) {
            if (!file.getName().endsWith(".yml")) continue;
            String name = file.getName();
            name = name.substring(0, name.length() - 4);
            SpawnArea spawnArea = new SpawnArea(this, name);
            spawnArea.importConfig(YamlConfiguration.loadConfiguration(file));
            if (oldSpawnAreas.containsKey(name)) {
                spawnArea.getNpcs().addAll(oldSpawnAreas.get(name).getNpcs());
            }
            spawnAreas.put(name, spawnArea);
            getLogger().info("Spawn area loaded: " + name);
        }
    }

    void loadPlayerSkins() {
        namedSkins.clear();
        uuidSkinCache.clear();
        nameSkinCache.clear();
        for (int i = 0; i < 2; i += 1) {
            String fn;
            switch (i) {
            case 0:
                fn = "skins.yml";
                break;
            case 1: default:
                fn = "skin_cache.yml";
            }
            YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(getDataFolder(), fn));
            for (String key: config.getKeys(false)) {
                ConfigurationSection section = config.getConfigurationSection(key);
                if (section == null) continue;
                String id = section.getString("id");
                String name = section.getString("name");
                String texture = section.getString("texture");
                if (id == null || name == null) {
                    Map<String, String> map2 = (Map<String, String>)JSONValue.parse(new String(Base64.getDecoder().decode(texture)));
                    if (id == null) id = (String)map2.get("profileId");
                    if (name == null) name = (String)map2.get("profileName");
                }
                String signature = section.getString("signature");
                PlayerSkin playerSkin = new PlayerSkin(id, name, texture, signature);
                switch (i) {
                case 0:
                    namedSkins.put(key, playerSkin);
                    break;
                case 1: default:
                    uuidSkinCache.put(playerSkin.id, playerSkin);
                    nameSkinCache.put(playerSkin.name, playerSkin);
                }
            }
        }
    }

    void loadSpawners() {
        for (NPCSpawner spawner: spawners.values()) {
            spawner.setValid(false);
            if (spawner.getNpc() != null) spawner.getNpc().setValid(false);
        }
        spawners.clear();
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "spawners.yml"));
        spawnersConfig = cfg;
        for (String key: cfg.getKeys(false)) {
            ConfigurationSection section = cfg.getConfigurationSection(key);
            NPCSpawner spawner = new NPCSpawner(this, key);
            try {
                spawner.setConfig(section);
                spawner.loadConfig();
            } catch (Exception e) {
                getLogger().warning("Error loading spawner.yml/" + key + ":");
                e.printStackTrace();
                continue;
            }
            spawners.put(key, spawner);
        }
    }

    void saveSpawners() {
        if (spawnersConfig == null) return;
        try {
            spawnersConfig.save(new File(getDataFolder(), "spawners.yml"));
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    void saveSkinCache() {
        if (uuidSkinCache.isEmpty()) return;
        YamlConfiguration config = new YamlConfiguration();
        for (PlayerSkin playerSkin: uuidSkinCache.values()) {
            ConfigurationSection section = config.createSection(playerSkin.id);
            section.set("id", playerSkin.id);
            section.set("name", playerSkin.name);
            section.set("texture", playerSkin.texture);
            section.set("signature", playerSkin.signature);
        }
        try {
            config.save(new File(getDataFolder(), "skin_cache.yml"));
        } catch (IOException ioe) {
            ioe.printStackTrace();
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
    public boolean onCommand(CommandSender sender, org.bukkit.command.Command command, String alias, String[] args) {
        if (args.length == 0) return false;
        Player player = sender instanceof Player ? (Player)sender : null;
        Iterator<String> argIter = Arrays.asList(args).iterator();
        switch (argIter.next()) {
        case "spawn":
            if (args.length >= 3) {
                NPC npc;
                Location location = player.getLocation();
                location.setPitch(0.0f);
                switch (argIter.next()) {
                case "player":
                    npc = new NPC(this, NPC.Type.PLAYER, location, argIter.next(), argIter.hasNext() ? namedSkins.get(argIter.next()) : null);
                    break;
                case "mob":
                    npc = new NPC(this, NPC.Type.MOB, location, EntityType.valueOf(argIter.next().toUpperCase()));
                    break;
                case "block":
                    npc = new NPC(this, NPC.Type.BLOCK, location, getServer().createBlockData(argIter.next()));
                    break;
                case "item":
                    npc = new NPC(this, NPC.Type.ITEM, location, new ItemStack(Material.valueOf(argIter.next().toUpperCase()), argIter.hasNext() ? Integer.parseInt(argIter.next()) : 1));
                    break;
                case "marker":
                    long lifespan = Long.parseLong(argIter.next());
                    StringBuilder sb = new StringBuilder(argIter.next());
                    while (argIter.hasNext()) sb.append(" ").append(argIter.next());
                    npc = new NPC(this, NPC.Type.MARKER, location, sb.toString());
                    npc.setLifespan(lifespan);
                    break;
                case "realplayer":
                    player.sendMessage("Attempting to spawn...");
                    final String name = argIter.next();
                    getPlayerSkinAsync(null, name, (playerSkin) -> {
                            if (!player.isValid()) return;
                            if (playerSkin == null) {
                                player.sendMessage("Skin not found: " + name);
                                return;
                            }
                            NPC npc2 = new NPC(this, NPC.Type.PLAYER, player.getLocation(), ChatColor.MAGIC + name, playerSkin);
                            if (argIter.hasNext()) npc2.setJob(NPC.Job.valueOf(argIter.next().toUpperCase()));
                            enableNPC(npc2);
                            player.sendMessage("Spawned " + npc2.getName());
                        });
                    return true;
                default:
                    return false;
                }
                if (argIter.hasNext()) {
                    npc.setJob(NPC.Job.valueOf(argIter.next().toUpperCase()));
                }
                if (enableNPC(npc)) {
                    sender.sendMessage("spawned");
                }
                return true;
            }
            break;
        case "create": {
            if (args.length != 3) {
                player.sendMessage("/npc create <name> <type>");
                return true;
            }
            String name = args[1];
            NPC.Type type;
            String arg = args[2];
            try {
                type = NPC.Type.valueOf(arg.toUpperCase());
            } catch (IllegalArgumentException iae) {
                sender.sendMessage("Invalid type: " + arg);
                return true;
            }
            if (spawners.containsKey(name)) {
                sender.sendMessage(name + " already exists.");
                return true;
            }
            NPCSpawner spawner = new NPCSpawner(this, name);
            ConfigurationSection cfg = spawnersConfig.createSection(name);
            spawner.setConfig(cfg);
            spawner.setLocation(player.getLocation());
            spawner.setType(type);
            spawners.put(name, spawner);
            spawner.setValid(true);
            saveSpawners();
            break;
        }
        case "modify": { // /npc modify name key value
            if (args.length != 3 && args.length != 4) {
                sender.sendMessage("/npc modify <name> <key> <value>");
                return true;
            }
            String name = args[1];
            NPCSpawner spawner = spawners.get(name);
            if (spawner == null) {
                sender.sendMessage("Spawner not found: " + name);
                return true;
            }
            String key = args[2];
            if (args.length == 3) {
                switch (key) {
                case "here":
                    if (player != null) spawner.setLocation(player.getLocation());
                    saveSpawners();
                    sender.sendMessage("Location set to " + spawner.getLocation());
                    break;
                }
            }
            if (args.length == 4) {
                String value = args[3];
                spawner.getConfig().set(key, value);
                spawner.loadConfig();
                saveSpawners();
                sender.sendMessage("Set " + key + " to " + value + " for " + name + "!");
            }
            NPC npc = spawner.getNpc();
            if (npc != null) npc.setValid(false);
            return true;
        }
        case "count":
            if (args.length == 1) {
                sender.sendMessage("Count: " + npcs.size());
                return true;
            }
            break;
        case "list":
            if (args.length == 1) {
                if (player == null) {
                    sender.sendMessage("Player expected");
                    return true;
                }
                final int radius = 16;
                Location playerLocation = player.getLocation();
                int x = playerLocation.getBlockX();
                int y = playerLocation.getBlockY();
                int z = playerLocation.getBlockZ();
                List<NPC> result = new ArrayList<>();
                for (NPC npc: npcs) {
                    if (Math.abs(npc.getLocation().getBlockX() - x) <= radius
                        && Math.abs(npc.getLocation().getBlockY() - y) <= radius
                        && Math.abs(npc.getLocation().getBlockZ() - z) <= radius) {
                        result.add(npc);
                    }
                }
                sender.sendMessage("" + result.size() + "/" + npcs.size() + " NPCs within radius " + radius);
                int index = 0;
                for (NPC npc: result) {
                    sender.sendMessage("" + index + ") " + npc.getDescription());
                    if (npc.getType() != NPC.Type.MARKER) {
                        NPC bubble = npc.addSpeechBubble("#" + npc.getId());
                        bubble.getExclusive().add(player.getUniqueId());
                    }
                    index += 1;
                }
                return true;
            }
            break;
        case "info":
            if (args.length == 2) {
                int npcId;
                String arg = args[1];
                try {
                    npcId = Integer.parseInt(arg);
                } catch (NumberFormatException nfe) {
                    sender.sendMessage("Invalid id: " + arg);
                    return true;
                }
                NPC npc = findNPCWithId(npcId);
                if (npc == null) {
                    sender.sendMessage("NPC with ID not found: " + npcId);
                    return true;
                }
                sender.sendMessage("NPC #" + npc.getId() + "name=" + npc.getName() + " type=" + npc.getType());
                PlayerSkin skin = npc.getPlayerSkin();
                if (skin != null) sender.sendMessage("Skin=" + skin);
                for (NPC.DataVar dv: NPC.DataVar.values()) {
                    if (npc.getEntityData().isSet(dv)) {
                        sender.sendMessage(dv + "=" + npc.getEntityData().get(dv));
                    }
                }
                return true;
            }
            break;
        case "clear":
            if (args.length == 1) {
                for (NPC npc: npcs) npc.setValid(false);
                sender.sendMessage("cleared");
                return true;
            }
            break;
        case "addblocks":
        case "removeblocks":
            if (args.length == 2 && player != null) {
                boolean doAdd = args[0].equals("addblocks");
                String name = args[1];
                SpawnArea spawnArea = spawnAreas.get(name);
                if (spawnArea == null) {
                    sender.sendMessage("Spawn area not found: " + name);
                    return true;
                }
                int ax = player.getMetadata("SelectionAX").get(0).asInt();
                int ay = player.getMetadata("SelectionAY").get(0).asInt();
                int az = player.getMetadata("SelectionAZ").get(0).asInt();
                int bx = player.getMetadata("SelectionBX").get(0).asInt();
                int by = player.getMetadata("SelectionBY").get(0).asInt();
                int bz = player.getMetadata("SelectionBZ").get(0).asInt();
                for (int y = Math.min(ay, by); y <= Math.max(ay, by); y += 1) {
                    for (int z = Math.min(az, bz); z <= Math.max(az, bz); z += 1) {
                        for (int x = Math.min(ax, bx); x <= Math.max(ax, bx); x += 1) {
                            if (doAdd) {
                                Block block = player.getWorld().getBlockAt(x, y, z);
                                if (block.getType().isSolid() && !block.getRelative(0, 1, 0).getType().isSolid() && !block.getRelative(0, 2, 0).getType().isSolid()) {
                                    spawnArea.addBlock(x, y, z);
                                }
                            } else {
                                spawnArea.removeBlock(x, y, z);
                            }
                        }
                    }
                }
                YamlConfiguration config = spawnArea.exportConfig();
                try {
                    config.save(new File(new File(getDataFolder(), "spawnareas"), spawnArea.getId() + ".yml"));
                    if (doAdd) {
                        sender.sendMessage("Blocks added to spawn area: " + spawnArea.getId());
                    } else {
                        sender.sendMessage("Blocks removed from spawn area: " + spawnArea.getId());
                    }
                } catch (IOException ioe) {
                    ioe.printStackTrace();
                    sender.sendMessage("Error saving spawn area. See console.");
                }
                return true;
            }
            break;
        case "hi":
            if (args.length == 2 && player != null) {
                boolean doAdd = args[0].equals("addblocks");
                String name = args[1];
                SpawnArea spawnArea = spawnAreas.get(name);
                if (spawnArea == null) {
                    sender.sendMessage("Spawn area not found: " + name);
                    return true;
                }
                for (SpawnArea.Vec vec: spawnArea.getBlocks()) {
                    Block block = player.getWorld().getBlockAt(vec.getX(), vec.getY(), vec.getZ());
                    player.sendBlockChange(block.getLocation(), Material.GLOWSTONE.createBlockData());
                }
                return true;
            }
            break;
        case "sign":
            if (args.length == 1 && player != null) {
                PlayerConnection connection = ((CraftPlayer)player).getHandle().playerConnection;
                Location loc = player.getLocation();
                player.sendBlockChange(loc, Material.SIGN.createBlockData());
                connection.sendPacket(new PacketPlayOutOpenSignEditor(new BlockPosition(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())));
                return true;
            }
            break;
        case "uuid":
            if (args.length == 2) {
                String uuid = fetchPlayerId(args[1]);
                sender.sendMessage("UUID of " + args[1] + ": " + uuid);
                return true;
            }
            break;
        case "skin":
            if (args.length == 2) {
                final String name = args[1];
                getPlayerSkinAsync(null, name, (playerSkin) -> {
                        if (playerSkin == null) {
                            sender.sendMessage("Player not found: " + name);
                            return;
                        }
                        sender.sendMessage("Name: " + playerSkin.name);
                        sender.sendMessage("UUID: " + playerSkin.id);
                        sender.sendMessage("Texture: " + playerSkin.texture);
                        sender.sendMessage("Signature: " + playerSkin.signature);
                    });
                return true;
            }
            break;
        case "path":
            if (args.length == 2) {
                int id = Integer.parseInt(args[1]);
                NPC npc = null;
                for (NPC n: npcs) {
                    if (n.getId() == id) {
                        npc = n;
                        break;
                    }
                }
                if (npc == null) {
                    player.sendMessage("Not found: #" + id);
                    return true;
                }
                for (NPC.Vec3i hist: npc.getPath()) {
                    Block block = player.getWorld().getBlockAt(hist.x, hist.y, hist.z);
                    player.getWorld().spawnParticle(Particle.END_ROD, block.getLocation().add(0.5, 1.5, 0.5), 1, 0, 0, 0, 0);
                }
                player.sendMessage("Showing movement path of NPC #" + id);
                return true;
            }
            break;
        case "tp":
            if (args.length == 2) {
                int id = Integer.parseInt(args[1]);
                NPC npc = null;
                for (NPC n: npcs) {
                    if (n.getId() == id) {
                        npc = n;
                        break;
                    }
                }
                if (npc == null) {
                    player.sendMessage("Not found: #" + id);
                    return true;
                }
                player.teleport(npc.getLocation());
                player.sendMessage("Teleported to NPC #" + id);
                return true;
            }
            break;
        case "debug":
            if (args.length == 2) {
                int id = Integer.parseInt(args[1]);
                NPC npc = null;
                for (NPC n: npcs) {
                    if (n.getId() == id) {
                        npc = n;
                        break;
                    }
                }
                if (npc == null) {
                    player.sendMessage("Not found: #" + id);
                    return true;
                }
                boolean dbg = !npc.isDebug();
                npc.setDebug(dbg);
                if (dbg) {
                    sender.sendMessage("Debug mode enabled for #" + id);
                } else {
                    sender.sendMessage("Debug mode disabled for #" + id);
                }
                return true;
            }
            break;
        case "spawnrandomvillager":
            if (args.length == 3) {
                SpawnArea spawnArea = spawnAreas.get(args[1]);
                spawnArea.spawnRandomVillager(args[2], player.getLocation());
            }
            break;
        case "reload":
            if (args.length == 1) {
                loadSpawnAreas();
                loadPlayerSkins();
                loadSpawners();
                sender.sendMessage("Spawn areas, player skins, spawners reloaded.");
                return true;
            }
            break;
        default:
            break;
        }
        return false;
    }

    boolean onNPCAnswer(CommandSender sender, String[] args) {
        if (args.length != 1) return true;
        if (!(sender instanceof Player)) return true;
        Player player = (Player)sender;
        Conversation convo = Conversation.of(player, (org.bukkit.plugin.Plugin)this);
        if (convo == null) return true;
        convo.answer(player, args[0]);
        return true;
    }

    @Override
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

    @Override
    public boolean enableConversation(Conversation convo) {
        conversations.add(convo);
        if (convo.getPlayers().isEmpty() || convo.getNpcs().isEmpty()) throw new IllegalStateException("Neither players nor npcs may be empty.");
        try {
            // This will set the new Conversation for all NPCs and Playres
            convo.enable();
        } catch (Throwable t) {
            t.printStackTrace();
            conversations.remove(convo);
            return false;
        }
        return true;
    }

    @Override
    public NPC findNPCWithUniqueName(String uniqueName) {
        for (NPC npc: npcs) {
            if (uniqueName.equals(npc.getUniqueName())) return npc;
        }
        return null;
    }

    @Override
    public NPC findNPCWithId(int id) {
        for (NPC npc: npcs) {
            if (npc.getId() == id) return npc;
        }
        return null;
    }

    void onTick() {
        for (NPC npc: new ArrayList<>(npcs)) {
            if (npc.isValid()) {
                try {
                    npc.tick();
                } catch (Throwable t) {
                    npc.setValid(false);
                    t.printStackTrace();
                }
            }
        }
        for (Iterator<NPC> iter = new ArrayList<>(npcs).iterator(); iter.hasNext();) {
            NPC npc = iter.next();
            if (!npc.isValid()) {
                try {
                    npc.disable();
                } catch (Throwable t) {
                    try {
                        getLogger().warning("Disabling NPC #" + npc.getId() + ": " + npc.getDescription());
                    } catch (Throwable e) { }
                    t.printStackTrace();
                }
                iter.remove();
            }
        }
        for (Iterator<Conversation> iter = conversations.iterator(); iter.hasNext();) {
            Conversation convo = iter.next();
            if (convo.isValid()) {
                try {
                    convo.tick();
                } catch (Throwable t) {
                    try {
                        convo.disable();
                    } catch (Throwable u) { }
                    convo.setValid(false);
                    t.printStackTrace();
                }
            }
            if (!convo.isValid()) {
                convo.disable();
                iter.remove();
            }
        }
        for (SpawnArea spawnArea: spawnAreas.values()) {
            spawnArea.onTick();
        }
        if ((ticksLived % 20) == 0) {
            for (NPCSpawner spawner: spawners.values()) {
                spawner.tick();
            }
        }
        ticksLived += 1;
    }

    void getPlayerSkinAsync(String id, String name, Consumer<PlayerSkin> consumer) {
        if (id == null) {
            if (name == null) throw new NullPointerException("Player name cannot be null!");
            UUID uuid = GenericEvents.cachedPlayerUuid(name);
            if (uuid != null) id = uuid.toString().replace("-", "");
        }
        PlayerSkin playerSkin = null;
        if (id != null) playerSkin = uuidSkinCache.get(id);
        if (playerSkin == null && name != null) playerSkin = nameSkinCache.get(name);
        final String finalId = id;
        final String finalName = name;
        final PlayerSkin finalSkin = playerSkin;
        if (finalSkin != null) {
            getServer().getScheduler().runTask(this, () -> consumer.accept(finalSkin));
            return;
        }
        asyncTasks.add(() -> {
                PlayerSkin useSkin = finalSkin;
                if (useSkin == null) {
                    String useId;
                    if (finalId != null) {
                        useId = finalId;
                    } else {
                        useId = fetchPlayerId(name);
                    }
                    if (useId != null) {
                        useSkin = fetchPlayerSkin(useId);
                    } else {
                        useSkin = null;
                    }
                }
                final PlayerSkin finalUseSkin = useSkin;
                getServer().getScheduler().runTask(NPCPlugin.this, () -> {
                        if (finalUseSkin != null) {
                            uuidSkinCache.put(finalUseSkin.id, finalUseSkin);
                            nameSkinCache.put(finalUseSkin.name, finalUseSkin);
                            saveSkinCache();
                        }
                        consumer.accept(finalUseSkin);
                    });
            });
    }

    // Blocking. Do not call from main thread!
    private static String fetchPlayerId(String name) {
        Object o;
        try {
            o = fetchJsonPost("https://api.mojang.com/profiles/minecraft", Arrays.asList(name));
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        }
        if (o == null) return null;
        if (!(o instanceof List)) return null;
        List<Object> list = (List<Object>)o;
        if (list.isEmpty()) return null;
        Map<String, String> map = (Map<String, String>)list.get(0);
        return map.get("id");
    }

    // Blocking. Do not call from main thread!
    private static PlayerSkin fetchPlayerSkin(String id) {
        Object o;
        try {
            o = fetchJsonGet("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false");
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        }
        if (o == null) return null;
        if (!(o instanceof Map)) return null;
        Map<String, Object> map = (Map<String, Object>)o;
        id = (String)map.get("id");
        String name = (String)map.get("name");
        List<Object> list = (List<Object>)map.get("properties");
        if (list.isEmpty()) return null;
        String texture = null, signature = null;
        for (Object p: list) {
            if (!(p instanceof Map)) continue;
            map = (Map<String, Object>)p;
            texture = (String)map.get("value");
            signature = (String)map.get("signature");
            if (texture != null) break;
        }
        return new PlayerSkin(id, name, texture, signature);
    }

    // Blocking. Do not call from main thread!
    private static Object fetchJsonPost(String u, Object post) throws IOException {
        URL url = new URL(u);
        HttpURLConnection con = (HttpURLConnection)url.openConnection();
        con.setDoOutput(true);
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json");
        PrintStream out = new PrintStream(con.getOutputStream());
        out.println(JSONValue.toJSONString(post));
        out.flush();
        out.close();
        BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while (null != (line = in.readLine())) {
            sb.append(line);
        }
        in.close();
        return JSONValue.parse(sb.toString());
    }

    // Blocking. Do not call from main thread!
    private static Object fetchJsonGet(String u) throws IOException {
        URL url = new URL(u);
        HttpURLConnection con = (HttpURLConnection)url.openConnection();
        con.setRequestMethod("GET");
        con.setRequestProperty("Content-Type", "application/json");
        BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while (null != (line = in.readLine())) {
            sb.append(line);
        }
        in.close();
        return JSONValue.parse(sb.toString());
    }

    private void asyncWorker() {
        while (isEnabled()) {
            try {
                Runnable task = asyncTasks.poll(1, TimeUnit.SECONDS);
                if (task != null) task.run();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}

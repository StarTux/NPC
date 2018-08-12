package com.cavetale.npc;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.server.v1_13_R1.Block;
import net.minecraft.server.v1_13_R1.ChatComponentText;
import net.minecraft.server.v1_13_R1.DataWatcherObject;
import net.minecraft.server.v1_13_R1.DataWatcherRegistry;
import net.minecraft.server.v1_13_R1.Entity;
import net.minecraft.server.v1_13_R1.EntityFallingBlock;
import net.minecraft.server.v1_13_R1.EntityLiving;
import net.minecraft.server.v1_13_R1.EntityPlayer;
import net.minecraft.server.v1_13_R1.EntityTypes;
import net.minecraft.server.v1_13_R1.MinecraftServer;
import net.minecraft.server.v1_13_R1.Packet;
import net.minecraft.server.v1_13_R1.PacketPlayOutAnimation;
import net.minecraft.server.v1_13_R1.PacketPlayOutEntity;
import net.minecraft.server.v1_13_R1.PacketPlayOutEntityDestroy;
import net.minecraft.server.v1_13_R1.PacketPlayOutEntityHeadRotation;
import net.minecraft.server.v1_13_R1.PacketPlayOutEntityMetadata;
import net.minecraft.server.v1_13_R1.PacketPlayOutEntityTeleport;
import net.minecraft.server.v1_13_R1.PacketPlayOutNamedEntitySpawn;
import net.minecraft.server.v1_13_R1.PacketPlayOutPlayerInfo;
import net.minecraft.server.v1_13_R1.PacketPlayOutSpawnEntity;
import net.minecraft.server.v1_13_R1.PacketPlayOutSpawnEntityLiving;
import net.minecraft.server.v1_13_R1.PlayerConnection;
import net.minecraft.server.v1_13_R1.PlayerInteractManager;
import net.minecraft.server.v1_13_R1.WorldServer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.craftbukkit.v1_13_R1.CraftServer;
import org.bukkit.craftbukkit.v1_13_R1.CraftWorld;
import org.bukkit.craftbukkit.v1_13_R1.entity.CraftPlayer;
import org.bukkit.craftbukkit.v1_13_R1.util.CraftMagicNumbers;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

@Getter
public class NPC {
    private Type type;
    @Setter private boolean valid;
    // Location
    @Setter private Location location;
    @Setter private double headYaw;
    private double lastHeadYaw;
    private Location lastLocation, trackLocation;
    private double locationError = 0.0, locationMoved = 0.0;
    private boolean forceLookUpdate;
    @Setter private boolean onGround;
    // Identity
    private int id;
    private Entity entity;
    @Setter private String displayName;
    // Mob Type
    private EntityType entityType; // Only for MOB
    private org.bukkit.Material material; // Only for BLOCK
    private PlayerSkin playerSkin;
    // Player info
    private String texture, signature;
    // State
    private Random random = new Random(System.nanoTime());
    private final List<Player> playerWatchers = new ArrayList<>();
    private final List<Packet> packets = new ArrayList<>();
    private long ticksLived;
    // Job
    @Setter private Job job;
    // Task
    private Task task;
    private int turn; // Countdown for current task duration
    private double speed, direction;
    private LivingEntity followEntity;
    private Location followLocation;

    enum Type {
        PLAYER, MOB, BLOCK;
    }
    enum Job {
        NONE, WANDER, WIGGLE;
    }
    enum Task {
        NONE, WALK, TURN, LOOK_AROUND, LOOK_AT, FOLLOW;
    }
    @Data
    final class PlayerSkin {
        String texture, signature;
    }

    public NPC(Type type, Location location, String displayName, PlayerSkin playerSkin) {
        this.type = type;
        this.location = location;
        this.displayName = displayName;
        this.playerSkin = null;
        final MinecraftServer minecraftServer = ((CraftServer)Bukkit.getServer()).getServer();
        final WorldServer worldServer = ((CraftWorld)location.getWorld()).getHandle();
        switch (type) {
        case PLAYER:
            UUID uuid;
            if (true) {
                long lower = random.nextLong();
                long upper = random.nextLong();
                long versionMask = 0x000000000000F000;
                long versionFlag = 0x0000000000002000;
                upper &= ~versionMask;
                upper |= versionFlag;
                uuid = new UUID(upper, lower);
            } else {
                uuid = UUID.fromString("00000000-0000-2000-0000-000000000000");
            }
            final GameProfile profile = new GameProfile(uuid, displayName);
            entity = new EntityPlayer(minecraftServer, worldServer, profile, new PlayerInteractManager(worldServer));
            entity.setPositionRotation(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
            if (playerSkin != null) {
                profile.getProperties().put("texture", new Property("textures", playerSkin.texture, playerSkin.signature));
            }
            // Set all skin layers visible
            entity.getDataWatcher().set(new DataWatcherObject(13, DataWatcherRegistry.a(0)), (byte)0x7e);
            break;
        default:
            throw new IllegalArgumentException("NPC(Type, Location): wrong constructor for type " + type);
        }
    }

    public NPC(Type type, Location location, EntityType entityType) {
        this.type = type;
        this.location = location;
        this.entityType = entityType;
        final WorldServer worldServer = ((CraftWorld)location.getWorld()).getHandle();
        switch (type) {
        case MOB:
            entity = EntityTypes.a(entityType.getName()).a(worldServer);
            entity.setPositionRotation(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
            // Name
            if (displayName != null) {
                entity.getDataWatcher().set(new DataWatcherObject(2, DataWatcherRegistry.a(3)), Optional.of(new ChatComponentText(displayName)));
            }
            if (entityType == EntityType.BAT) {
                entity.getDataWatcher().set(new DataWatcherObject(12, DataWatcherRegistry.a(0)), (byte)0x0);
            }
            break;
        default:
            throw new IllegalArgumentException("NPC(Type, Location, EntityType): wrong constructor for type " + type);
        }
    }

    public NPC(Type type, Location location, org.bukkit.Material material) {
        this.type = type;
        this.location = location;
        this.material = material;
        final WorldServer worldServer = ((CraftWorld)location.getWorld()).getHandle();
        switch (type) {
        case BLOCK:
            entity = new EntityFallingBlock(worldServer, location.getX(), location.getY(), location.getZ(), CraftMagicNumbers.getBlock(material).getBlockData());
            // No Gravity
            entity.getDataWatcher().set(new DataWatcherObject(5, DataWatcherRegistry.i), Boolean.TRUE);
            entity.setPosition(location.getX(), location.getY(), location.getZ());
            break;
        default:
            throw new IllegalArgumentException("NPC(Type, Location, Material): wrong constructor for type " + type);
        }
    }

    // Overridable API methods

    // Called when the NPC is added to the list of NPCs, before it
    // is ticked for the first time
    public void onEnable() { }

    // Called when the NPC is removed from the list of NPCs, after it
    // was ticked for the final time.
    public void onDisable() { }

    public void onTick() { }

    public final void interact(Player player) {
        if (job == Job.WANDER) {
            if (task != Task.FOLLOW) {
                setTask(Task.FOLLOW);
                followEntity = player;
                speed = 1.0;
            } else {
                setTask(Task.LOOK_AROUND);
            }
        }
    }

    // Internal methods

    final void enable() {
        id = entity.getId();
        lastLocation = location.clone();
        trackLocation = location.clone();
        headYaw = location.getYaw();
        lastHeadYaw = headYaw;
        onEnable();
        valid = true;
    }

    final void disable() {
        for (Player player: playerWatchers) {
            if (!player.isValid()) continue;
            stopPlayerWatch(player);
        }
        playerWatchers.clear();
        valid = false;
        onDisable();
    }

    final void setTask(Task newTask) {
        this.task = newTask;
        this.turn = 0;
        this.direction = 0.0;
        this.speed = 0.0;
        this.followEntity = null;
        this.followLocation = null;
    }

    final void startPlayerWatch(Player player) {
        PlayerConnection connection = ((CraftPlayer)player).getHandle().playerConnection;
        switch (type) {
        case PLAYER:
            connection.sendPacket(new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER, (EntityPlayer)entity));
            connection.sendPacket(new PacketPlayOutNamedEntitySpawn((EntityPlayer)entity));
            connection.sendPacket(new PacketPlayOutEntityHeadRotation(entity, (byte)((int)((headYaw % 360.0f) * 256.0f / 360.0f))));
            Bukkit.getScheduler().runTaskLater(NPCPlugin.getInstance(), () ->
                                               connection.sendPacket(new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER, (EntityPlayer)entity)), 1L);
            break;
        case MOB:
            connection.sendPacket(new PacketPlayOutSpawnEntityLiving((EntityLiving)entity));
            connection.sendPacket(new PacketPlayOutEntityHeadRotation(entity, (byte)((int)((headYaw % 360.0f) * 256.0f / 360.0f))));
            break;
        case BLOCK:
            connection.sendPacket(new PacketPlayOutSpawnEntity(entity, 70, Block.REGISTRY_ID.getId(CraftMagicNumbers.getBlock(material).getBlockData())));
            connection.sendPacket(new PacketPlayOutEntityMetadata(entity.getId(), entity.getDataWatcher(), true));
            break;
        default:
            throw new IllegalStateException("Unhandled type: " + type);
        }
    }

    final void stopPlayerWatch(Player player) {
        PlayerConnection connection = ((CraftPlayer)player).getHandle().playerConnection;
        switch (type) {
        case PLAYER:
            connection.sendPacket(new PacketPlayOutEntityDestroy(new int[] {entity.getId()}));
            break;
        case MOB:
            connection.sendPacket(new PacketPlayOutEntityDestroy(new int[] {entity.getId()}));
            break;
        case BLOCK:
            connection.sendPacket(new PacketPlayOutEntityDestroy(new int[] {entity.getId()}));
            break;
        default:
            throw new IllegalStateException("Unhandled type: " + type);
        }
    }

    public final boolean isBlockedAt(Location entityLocation) {
        float width2 = entity.width * 0.5f;
        float height = entity.length;
        Location locA = entityLocation.clone().add(-width2, 0.0, -width2);
        Location locB = entityLocation.clone().add(width2, height, width2);
        int ax = locA.getBlockX();
        int bx = locB.getBlockX();
        int ay = locA.getBlockY();
        int by = locB.getBlockY();
        int az = locA.getBlockZ();
        int bz = locB.getBlockZ();
        for (int y = ay; y <= by; y += 1) {
            for (int z = az; z <= bz; z += 1) {
                for (int x = ax; x <= bx; x += 1) {
                    org.bukkit.block.Block block = entityLocation.getWorld().getBlockAt(x, y, z);
                    if (block.isEmpty()) continue;
                    org.bukkit.Material mat = block.getType();
                    if (Tag.SLABS.isTagged(mat)
                        && ((Slab)block.getBlockData()).getType() == Slab.Type.BOTTOM
                        && entityLocation.getY() - (double)y >= 0.5) {
                        continue;
                    }
                    if (Tag.STAIRS.isTagged(mat)
                        && ((Stairs)block.getBlockData()).getHalf() == Bisected.Half.BOTTOM
                        && entityLocation.getY() - (double)y >= 0.5) {
                        continue;
                    }
                    if (mat.isOccluding()) return true;
                    if (mat.isSolid()) return true;
                }
            }
        }
        return false;
    }

    public final void tick() {
        onTick();
        if (job != null) performJob();
        updateMovement();
        updateWatchers();
        ticksLived += 1;
    }

    public final void performJob() {
        switch (job) {
        case NONE: return;
        case WANDER:
            if (task == Task.WALK) {
                if (turn <= 0) {
                    direction = random.nextFloat() * 4.0f - 2.0f;
                    speed = Math.max(0.25, random.nextDouble());
                    turn = 20 + random.nextInt(100);
                } else if (turn == 1) {
                    if (random.nextBoolean()) {
                        setTask(Task.LOOK_AROUND);
                    } else {
                        setTask(Task.TURN);
                    }
                } else {
                    turn -= 1;
                }
                boolean didMoveUp = fightGravity();
                boolean didFall = didMoveUp ? false : fall();
                boolean didWalk = didMoveUp ? false : walkForward();
                if (onGround) {
                    if (didWalk) {
                        location.setYaw(location.getYaw() + (float)direction);
                        headYaw = (double)location.getYaw();
                        if (turn % 16 == 0) {
                            location.setPitch(random.nextFloat() * 0.30f);
                        }
                    } else {
                        setTask(Task.TURN);
                    }
                }
            } else if (task == Task.TURN) {
                if (turn <= 0) {
                    turn = 20 + random.nextInt(20);
                    direction = random.nextBoolean() ? -1 : 1;
                    speed = 2.5 + random.nextDouble() * 5.0;
                } else if (turn == 1) {
                    setTask(Task.WALK);
                } else {
                    turn -= 1;
                }
                if (!fightGravity() && !fall()) {
                    headYaw = headYaw + direction * speed;
                    location.setYaw((float)headYaw);
                }
            } else if (task == Task.LOOK_AROUND) {
                if (turn <= 0) {
                    turn = 100;
                } else if (turn == 1) {
                    setTask(Task.WALK);
                } else {
                    turn -= 1;
                }
                if (turn % 16 == 0) lookRandom();
            } else if (task == Task.LOOK_AT) {
                if (followEntity == null) {
                    setTask(Task.LOOK_AROUND);
                    return;
                }
                boolean didMoveVertical = fightGravity() || fall();
                location.setDirection(followEntity.getEyeLocation().subtract(((LivingEntity)entity.getBukkitEntity()).getEyeLocation()).toVector().normalize());
                headYaw = location.getYaw();
                forceLookUpdate = true;
            } else if (task == Task.FOLLOW) {
                if (followEntity == null) {
                    setTask(Task.LOOK_AROUND);
                    return;
                }
                boolean didMoveVertical = fightGravity() || fall();
                location.setDirection(followEntity.getEyeLocation().subtract(((LivingEntity)entity.getBukkitEntity()).getEyeLocation()).toVector().normalize());
                headYaw = location.getYaw();
                forceLookUpdate = true;
                if (!didMoveVertical && followEntity.getLocation().distanceSquared(location) >= 4.0) walkForward();
            } else {
                setTask(Task.WALK);
            }
            break;
        case WIGGLE:
            if (turn <= 0) {
                if (followLocation == null) followLocation = location.clone();
                turn = 1;
            }
            double linear = (double)ticksLived * 0.1;
            location = followLocation.clone().add(0.125 * Math.cos(linear), 0.0, 0.125 * Math.sin(linear));
            break;
        default:
            throw new IllegalStateException("Unhandled Job: " + job);
        }
    }

    public final void lookRandom() {
        double yaw = random.nextDouble() * 120.0 - 60.0;
        double pitch = random.nextDouble() * 120.0 - 60.0;
        headYaw = (double)location.getYaw() + yaw;
        location.setPitch((float)pitch);
    }

    public final void swingArm(boolean mainHand) {
        int val = mainHand ? 0 : 3;
        packets.add(new PacketPlayOutAnimation(entity, 0));
    }

    /** Return true if npc moved upward */
    public final boolean fightGravity() {
        if (isBlockedAt(location) || location.getBlock().isLiquid()) {
            location = location.add(0.0, 0.1, 0.0);
            return true;
        }
        return false;
    }

    /** Return true if npc moved downward */
    public final boolean fall() {
        for (int i = 0; i < 6; i += 1) {
            Location downward = location.clone().add(0.0, -0.1, 0.0);
            if (!isBlockedAt(downward)) {
                location = downward;
                onGround = false;
            } else {
                onGround = true;
                return i != 0;
            }
        }
        return true;
    }

    public final boolean walkForward() {
        Vector vec = location.getDirection();
        vec.setY(0.0);
        vec = vec.normalize();
        if (onGround) {
            vec = vec.multiply(0.25 * speed);
        } else {
            vec = vec.multiply(0.125);
        }
        Location forward = location.clone().add(vec);
        if (!isBlockedAt(forward)) {
            location = forward;
            return true;
        } else if (!isBlockedAt(forward.add(0.0, 0.5, 0.0))) {
            location = forward;
            return true;
        }
        return false;
    }

    public final void updateMovement() {
        switch (type) {
        case PLAYER: case MOB: case BLOCK:
            boolean didMove =
                location.getX() != lastLocation.getX()
                || location.getY() != lastLocation.getY()
                || location.getZ() != lastLocation.getZ();
            boolean didTurn =
                forceLookUpdate
                || location.getPitch() != lastLocation.getPitch()
                || location.getYaw() != lastLocation.getYaw();
            forceLookUpdate = false;
            if (didTurn) {
                if (location.getYaw() > 360.0f) {
                    location.setYaw(location.getYaw() - 360.0f);
                } else if (location.getYaw() < 0.0f) {
                    location.setYaw(location.getYaw() + 360.0f);
                }
            }
            boolean didMoveHead = headYaw != lastHeadYaw;
            boolean doTeleport = false;
            double distance = lastLocation.distanceSquared(location);
            doTeleport |= ticksLived == 0;
            doTeleport |= didMove && distance >= 64;
            doTeleport |= locationError >= 0.125;
            doTeleport |= locationMoved > 1024.0;
            doTeleport |= !didMove && locationMoved > 16.0;
            if (doTeleport) {
                entity.setPositionRotation(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
                packets.add(new PacketPlayOutEntityTeleport(entity));
                trackLocation = location.clone();
                locationError = 0.0;
                locationMoved = 0.0;
            } else if (didMove && didTurn) {
                long dx = (long)((location.getX() * 32.0 - lastLocation.getX() * 32.0) * 128.0);
                long dy = (long)((location.getY() * 32.0 - lastLocation.getY() * 32.0) * 128.0);
                long dz = (long)((location.getZ() * 32.0 - lastLocation.getZ() * 32.0) * 128.0);
                packets.add(new PacketPlayOutEntity.PacketPlayOutRelEntityMoveLook(entity.getId(), dx, dy, dz,
                                                                                   (byte)((int)(location.getYaw() * 256.0f / 360.0f)),
                                                                                   (byte)((int)(location.getPitch() * 256.0f / 360.0f)),
                                                                                   onGround)); // onGround
                trackLocation = trackLocation.add((double)dx / 4096.0, (double)dy / 4096.0, (double)dz / 4096.0);
                entity.setPositionRotation(trackLocation.getX(), trackLocation.getY(), trackLocation.getZ(), location.getYaw(), location.getPitch());
                locationError = trackLocation.distanceSquared(location);
                locationMoved += Math.sqrt(distance);
            } else if (didMove) {
                long dx = (long)((location.getX() * 32.0 - lastLocation.getX() * 32.0) * 128.0);
                long dy = (long)((location.getY() * 32.0 - lastLocation.getY() * 32.0) * 128.0);
                long dz = (long)((location.getZ() * 32.0 - lastLocation.getZ() * 32.0) * 128.0);
                packets.add(new PacketPlayOutEntity.PacketPlayOutRelEntityMove(entity.getId(), dx, dy, dz,
                                                                               onGround)); // onGround
                trackLocation = trackLocation.add((double)dx / 4096.0, (double)dy / 4096.0, (double)dz / 4096.0);
                entity.setPosition(trackLocation.getX(), trackLocation.getY(), trackLocation.getZ());
                locationError = trackLocation.distanceSquared(location);
                locationMoved += Math.sqrt(distance);
            } else if (didTurn) {
                entity.setPositionRotation(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
                packets.add(new PacketPlayOutEntity.PacketPlayOutEntityLook(entity.getId(),
                                                                            (byte)((int)(location.getYaw() * 256.0f / 360.0f)),
                                                                            (byte)((int)(location.getPitch() * 256.0f / 360.0f)),
                                                                            onGround)); // onGround
            } else {
                packets.add(new PacketPlayOutEntity(entity.getId()));
            }
            if (headYaw != lastHeadYaw) {
                packets.add(new PacketPlayOutEntityHeadRotation(entity, (byte)((int)((headYaw % 360.0f) * 256.0f / 360.0f))));
                lastHeadYaw = headYaw;
            }
            break;
        default:
            throw new IllegalStateException("Unhandled type: " + type);
        }
        lastLocation = location.clone();
    }

    public final void updateWatchers() {
        Set<UUID> watcherIds = new HashSet<>();
        for (Iterator<Player> iter = playerWatchers.iterator(); iter.hasNext();) {
            Player player = iter.next();
            // Weed out players
            // Note: We are adding even players which will have been removed
            watcherIds.add(player.getUniqueId());
            if (!player.isValid()) {
                iter.remove();
                continue;
            }
            if (!player.getWorld().equals(location.getWorld())
                || player.getLocation().distanceSquared(location) > 16384.0) { // 128^2
                stopPlayerWatch(player);
                iter.remove();
                continue;
            }
            // Send packets
            PlayerConnection connection = ((CraftPlayer)player).getHandle().playerConnection;
            for (Packet packet: packets) {
                connection.sendPacket(packet);
            }
            packets.clear();
        }
        // Find new players
        for (Player player: location.getWorld().getPlayers()) {
            if (!watcherIds.contains(player.getUniqueId())
                && player.getLocation().distanceSquared(location) < 16384.0) { // 128^2
                startPlayerWatch(player);
                playerWatchers.add(player);
            }
        }
        if (playerWatchers.isEmpty()) valid = false;
    }
}

package com.cavetale.npc;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import net.minecraft.server.v1_13_R1.Block;
import net.minecraft.server.v1_13_R1.ChatComponentText;
import net.minecraft.server.v1_13_R1.DataWatcherObject;
import net.minecraft.server.v1_13_R1.DataWatcherRegistry;
import net.minecraft.server.v1_13_R1.Entity;
import net.minecraft.server.v1_13_R1.EntityFallingBlock;
import net.minecraft.server.v1_13_R1.EntityItem;
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
import net.minecraft.server.v1_13_R1.PacketPlayOutEntityVelocity;
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
import org.bukkit.block.data.type.Slab;
import org.bukkit.craftbukkit.v1_13_R1.CraftServer;
import org.bukkit.craftbukkit.v1_13_R1.CraftWorld;
import org.bukkit.craftbukkit.v1_13_R1.block.data.CraftBlockData;
import org.bukkit.craftbukkit.v1_13_R1.entity.CraftPlayer;
import org.bukkit.craftbukkit.v1_13_R1.inventory.CraftItemStack;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

@Getter
public final class NPC {
    private final Type type;
    private final int id;
    private final String name;
    @Setter private boolean valid;
    // Location
    @Setter private Location location;
    @Setter private double headYaw;
    private double lastHeadYaw;
    private Location lastLocation, trackLocation, fromLocation, toLocation;
    private double locationError = 0.0, locationMoved = 0.0;
    private boolean forceLookUpdate, forceTeleport;
    @Setter private boolean onGround;
    // Identity
    private Entity entity;
    // Mob Type
    private EntityType entityType; // Only for MOB
    private org.bukkit.block.data.BlockData blockData; // Only for BLOCK
    private PlayerSkin playerSkin;
    // State
    private final List<Watcher> watchers = new ArrayList<>();
    private final ScheduledPacketList packets = new ScheduledPacketList();
    private long ticksLived;
    private long lastInteract;
    @Setter private boolean removeWhenUnwatched;
    // Job
    @Setter private Job job;
    @Setter private API api = () -> { };
    // Task
    private Task task;
    private int turn, turnTotal; // Countdown for current task duration
    private double speed, direction;
    private LivingEntity followEntity;
    private Location followLocation;
    // Constants
    private static final String TEAM_NAME = "cavetale.npc";

    @RequiredArgsConstructor
    class Watcher {
        final Player player;
        final ScheduledPacketList packets = new ScheduledPacketList();
        long ticksLived;
        long setPlayerSkin = -1;
        long unsetPlayerSkin = -1;
    }

    @RequiredArgsConstructor
    static class ScheduledPacket {
        final Packet packet;
        final long tick;
    }

    class ScheduledPacketList {
        final List<ScheduledPacket> packets = new ArrayList<>();
        void add(Packet packet, long delay) {
            ScheduledPacket entry = new ScheduledPacket(packet, ticksLived + delay);
            ListIterator<ScheduledPacket> iter = packets.listIterator();
            while (true) {
                if (!iter.hasNext()) {
                    iter.add(entry);
                    break;
                } else if (iter.next().tick > entry.tick) {
                    iter.previous();
                    iter.add(entry);
                    break;
                }
            }
        }
        void add(Packet packet) {
            add(packet, 0L);
        }
        Packet deal() {
            if (packets.isEmpty()) return null;
            ScheduledPacket result = packets.get(0);
            if (result.tick > ticksLived) return null;
            packets.remove(0);
            return result.packet;
        }
    }

    enum Type {
        PLAYER, MOB, BLOCK, ITEM;
    }

    enum Job {
        NONE, WANDER, WIGGLE;
    }

    enum Task {
        NONE, WALK, TURN, LOOK_AROUND, LOOK_AT, FOLLOW;
    }

    public enum EntityFlag {
        ON_FIRE(0x01),
        CROUCHING(0x02),
        //RIDING(0x04),
        SPRINTING(0x08),
        //HAND(0x10),
        INVISIBLE(0x20),
        GLOWING(0x40),
        FLYING_ELYTRA(0x80);
        public final int bitMask;
        EntityFlag(int bitMask) {
            this.bitMask = bitMask;
        }
    }

    public NPC(Type type, Location location, String name, PlayerSkin playerSkin) {
        this.type = type;
        this.location = location;
        this.playerSkin = null;
        final MinecraftServer minecraftServer = ((CraftServer)Bukkit.getServer()).getServer();
        final WorldServer worldServer = ((CraftWorld)location.getWorld()).getHandle();
        switch (type) {
        case PLAYER:
            UUID uuid;
            if (true) {
                long lower = ThreadLocalRandom.current().nextLong();
                long upper = ThreadLocalRandom.current().nextLong();
                long versionMask = 0x000000000000F000;
                long versionFlag = 0x0000000000002000;
                upper &= ~versionMask;
                upper |= versionFlag;
                uuid = new UUID(upper, lower);
            } else {
                uuid = UUID.fromString("00000000-0000-2000-0000-000000000000");
            }
            final GameProfile profile = new GameProfile(uuid, name);
            entity = new EntityPlayer(minecraftServer, worldServer, profile, new PlayerInteractManager(worldServer));
            id = entity.getId();
            this.name = name;
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
            id = entity.getId();
            name = entity.getUniqueID().toString().replace("-", "");
            entity.setPositionRotation(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
            // Name
            if (entityType == EntityType.BAT) {
                entity.getDataWatcher().set(new DataWatcherObject(12, DataWatcherRegistry.a(0)), (byte)0x0);
            }
            break;
        default:
            throw new IllegalArgumentException("NPC(Type, Location, EntityType): wrong constructor for type " + type);
        }
    }

    public NPC(Type type, Location location, org.bukkit.block.data.BlockData blockData) {
        this.type = type;
        this.location = location;
        this.blockData = blockData;
        final WorldServer worldServer = ((CraftWorld)location.getWorld()).getHandle();
        switch (type) {
        case BLOCK:
            entity = new EntityFallingBlock(worldServer, location.getX(), location.getY(), location.getZ(), ((CraftBlockData)blockData).getState());
            id = entity.getId();
            name = entity.getUniqueID().toString().replace("-", "");
            // No Gravity
            entity.getDataWatcher().set(new DataWatcherObject(5, DataWatcherRegistry.i), Boolean.TRUE);
            entity.setPosition(location.getX(), location.getY(), location.getZ());
            break;
        default:
            throw new IllegalArgumentException("NPC(Type, Location, BlockData): wrong constructor for type " + type);
        }
    }

    public NPC(Type type, Location location, org.bukkit.inventory.ItemStack itemStack) {
        this.type = type;
        this.location = location;
        final WorldServer worldServer = ((CraftWorld)location.getWorld()).getHandle();
        switch (type) {
        case ITEM:
            entity = new EntityItem(worldServer, location.getX(), location.getY(), location.getZ(), CraftItemStack.asNMSCopy(itemStack));
            id = entity.getId();
            name = entity.getUniqueID().toString().replace("-", "");
            entity.motX = 0.0;
            entity.motY = 0.0;
            entity.motZ = 0.0;
            // No gravity
            entity.getDataWatcher().set(new DataWatcherObject(5, DataWatcherRegistry.i), Boolean.TRUE);
            break;
        default:
            throw new IllegalArgumentException("NPC(Type, Location, ItemStack): wrong constructor for type " + type);
        }
    }

    // Overridable API methods

    public interface API {
        void onTick();

        /**
         * Called when the NPC is added to the list of NPCs, before it is
         * ticked for the first time
         */
        default void onEnable() { }

        /**
         * Called when the NPC is removed from the list of NPCs, after it
         * was ticked for the final time.
         */
        default void onDisable() { }

        default boolean canWalkIn(org.bukkit.block.Block block) {
            return true;
        }

        default boolean canWalkOn(org.bukkit.block.Block block) {
            return true;
        }
    }

    // Internal methods

    void enable() {
        lastLocation = location.clone();
        trackLocation = location.clone();
        headYaw = location.getYaw();
        lastHeadYaw = headYaw;
        api.onEnable();
        valid = true;
    }

    void disable() {
        for (Watcher watcher: watchers) {
            if (!watcher.player.isValid()) continue;
            stopWatch(watcher);
        }
        watchers.clear();
        valid = false;
        api.onDisable();
    }

    public void interact(Player player, boolean rightClick) {
        if (lastInteract == ticksLived) return;
        lastInteract = ticksLived;
        setEntityFlag(EntityFlag.GLOWING, !getEntityFlag(EntityFlag.GLOWING));
        sendMetadata();
    }

    void setTask(Task newTask) {
        this.task = newTask;
        this.turn = 0;
        this.direction = 0.0;
        this.speed = 0.0;
        this.followEntity = null;
        this.followLocation = null;
    }

    void startWatch(Watcher watcher) {
        PlayerConnection connection = ((CraftPlayer)watcher.player).getHandle().playerConnection;
        switch (type) {
        case PLAYER:
            connection.sendPacket(new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER, (EntityPlayer)entity));
            connection.sendPacket(new PacketPlayOutNamedEntitySpawn((EntityPlayer)entity));
            watcher.setPlayerSkin = 0;
            Scoreboard scoreboard = watcher.player.getScoreboard();
            Team team = scoreboard.getTeam(TEAM_NAME);
            if (team == null) {
                team = scoreboard.registerNewTeam(TEAM_NAME);
                team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            }
            if (!team.hasEntry(name)) team.addEntry(name);
            break;
        case MOB:
            connection.sendPacket(new PacketPlayOutSpawnEntityLiving((EntityLiving)entity));
            connection.sendPacket(new PacketPlayOutEntityHeadRotation(entity, (byte)((int)((headYaw % 360.0f) * 256.0f / 360.0f))));
            break;
        case BLOCK:
            connection.sendPacket(new PacketPlayOutSpawnEntity(entity, 70, Block.REGISTRY_ID.getId(((CraftBlockData)blockData).getState())));
            connection.sendPacket(new PacketPlayOutEntityMetadata(entity.getId(), entity.getDataWatcher(), true));
            connection.sendPacket(new PacketPlayOutEntityVelocity(entity.getId(), 0, 0, 0));
            break;
        case ITEM:
            connection.sendPacket(new PacketPlayOutSpawnEntity(entity, 2));
            connection.sendPacket(new PacketPlayOutEntityMetadata(entity.getId(), entity.getDataWatcher(), true));
            connection.sendPacket(new PacketPlayOutEntityVelocity(entity.getId(), 0, 0, 0));
            break;
        default:
            throw new IllegalStateException("Unhandled type: " + type);
        }
    }

    void stopWatch(Watcher watcher) {
        PlayerConnection connection = ((CraftPlayer)watcher.player).getHandle().playerConnection;
        switch (type) {
        case PLAYER:
            connection.sendPacket(new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER, (EntityPlayer)entity));
            connection.sendPacket(new PacketPlayOutEntityDestroy(new int[] {entity.getId()}));
            break;
        case MOB:
            connection.sendPacket(new PacketPlayOutEntityDestroy(new int[] {entity.getId()}));
            break;
        case BLOCK:
            connection.sendPacket(new PacketPlayOutEntityDestroy(new int[] {entity.getId()}));
            break;
        case ITEM:
            connection.sendPacket(new PacketPlayOutEntityDestroy(new int[] {entity.getId()}));
            break;
        default:
            throw new IllegalStateException("Unhandled type: " + type);
        }
    }

    public boolean isBlockedAt(Location entityLocation) {
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
                    // if (Tag.STAIRS.isTagged(mat)
                    //     && ((Stairs)block.getBlockData()).getHalf() == Bisected.Half.BOTTOM
                    //     && entityLocation.getY() - (double)y >= 0.5) {
                    //     continue;
                    // }
                    if (mat.isOccluding()) return true;
                    if (mat.isSolid()) return true;
                }
            }
        }
        return false;
    }

    public void tick() {
        api.onTick();
        if (job != null) performJob();
        updateMovement();
        updateWatchers();
        ticksLived += 1;
    }

    public void performJob() {
        switch (job) {
        case NONE: return;
        case WANDER:
            if (turn <= 0) {
                if (task == null) task = Task.NONE;
                switch (task) {
                case WALK:
                    if (ThreadLocalRandom.current().nextBoolean()) {
                        task = Task.LOOK_AROUND;
                        turn = 100;
                    } else {
                        task = Task.TURN;
                        turn = 20 + ThreadLocalRandom.current().nextInt(20);
                        direction = ThreadLocalRandom.current().nextBoolean() ? -1 : 1;
                        speed = 2.5 + ThreadLocalRandom.current().nextDouble() * 5.0;
                    }
                    break;
                case LOOK_AROUND:
                case TURN:
                default:
                    task = Task.WALK;
                    turn = 20 + ThreadLocalRandom.current().nextInt(100);
                    direction = ThreadLocalRandom.current().nextFloat() * 4.0f - 2.0f;
                    speed = Math.max(0.25, ThreadLocalRandom.current().nextDouble());
                }
            }
            performTask();
            turn -= 1;
            break;
        case WIGGLE:
            if (turn <= 0) {
                if (followLocation == null) followLocation = location.clone();
                fromLocation = followLocation.clone();
                toLocation = followLocation.clone();
                turn = 1;
            } else if (turn == 1) {
                fromLocation = toLocation;
                toLocation = followLocation.clone().add(0.125 * Math.random(), 0.0, 0.125 * Math.random());
                turnTotal = ThreadLocalRandom.current().nextInt(6) + 5;
                turn = turnTotal;
            } else {
                double a = (double)turn / (double)turnTotal;
                double b = 1.0 - a;
                location = fromLocation.toVector().multiply(a).add(toLocation.toVector().multiply(b)).toLocation(location.getWorld());
                turn -= 1;
            }
            break;
        default:
            throw new IllegalStateException("Unhandled Job: " + job);
        }
    }

    public void performTask() {
        switch (task) {
        case WALK:
            boolean didMoveUp = fightGravity();
            boolean didFall = didMoveUp ? false : fall();
            boolean didWalk = didMoveUp ? false : walkForward();
            if (onGround) {
                if (didWalk) {
                    location.setYaw(location.getYaw() + (float)direction);
                    headYaw = (double)location.getYaw();
                    if (turn % 16 == 0) {
                        location.setPitch(ThreadLocalRandom.current().nextFloat() * 0.30f);
                    }
                } else {
                    turn = 0;
                }
            }
            break;
        case TURN:
            if (!fightGravity() && !fall()) {
                headYaw = headYaw + direction * speed;
                location.setYaw((float)headYaw);
            }
            break;
        case LOOK_AROUND:
            if (!fightGravity() && !fall() && (turn % 16) == 0) {
                lookRandom();
            }
            break;
        case LOOK_AT:
            if (followEntity == null) {
                turn = 0;
                return;
            }
            boolean didMoveVertical = fightGravity() || fall();
            location.setDirection(followEntity.getEyeLocation().subtract(((LivingEntity)entity.getBukkitEntity()).getEyeLocation()).toVector().normalize());
            headYaw = location.getYaw();
            forceLookUpdate = true;
            break;
        case FOLLOW:
            if (followEntity == null) {
                turn = 0;
                return;
            }
            didMoveVertical = fightGravity() || fall();
            location.setDirection(followEntity.getEyeLocation().subtract(((LivingEntity)entity.getBukkitEntity()).getEyeLocation()).toVector().normalize());
            headYaw = location.getYaw();
            forceLookUpdate = true;
            if (!didMoveVertical && followEntity.getLocation().distanceSquared(location) >= 4.0) walkForward();
            break;
        default:
            throw new IllegalStateException("Unhandled Task: " + task);
        }
    }

    public void lookRandom() {
        double yaw = ThreadLocalRandom.current().nextDouble() * 120.0 - 60.0;
        double pitch = ThreadLocalRandom.current().nextDouble() * 120.0 - 60.0;
        headYaw = (double)location.getYaw() + yaw;
        location.setPitch((float)pitch);
    }

    public void swingArm(boolean mainHand) {
        packets.add(new PacketPlayOutAnimation(entity, mainHand ? 0 : 3));
    }

    public boolean getEntityFlag(EntityFlag flag) {
        return (((Byte)entity.getDataWatcher().get(new DataWatcherObject(0, DataWatcherRegistry.a))).intValue() & flag.bitMask) > 0;
    }

    public void setEntityFlag(EntityFlag flag, boolean value) {
        int v = ((Byte)entity.getDataWatcher().get(new DataWatcherObject(0, DataWatcherRegistry.a))).intValue();
        if (value) {
            v |= flag.bitMask;
        } else {
            v &= ~flag.bitMask;
        }
        entity.getDataWatcher().set(new DataWatcherObject(0, DataWatcherRegistry.a), (byte)v);
    }

    public void sendMetadata() {
        packets.add(new PacketPlayOutEntityMetadata(entity.getId(), entity.getDataWatcher(), false));
    }

    public void setVillagerProfession(int prof) {
        entity.getDataWatcher().set(new DataWatcherObject(13, DataWatcherRegistry.b), prof);
    }

    public void setCustomName(String customName) {
        entity.getDataWatcher().set(new DataWatcherObject(2, DataWatcherRegistry.f), Optional.of(new ChatComponentText(customName)));
    }

    /** Return true if npc moved upward */
    public boolean fightGravity() {
        if (isBlockedAt(location) || location.getBlock().isLiquid()) {
            location = location.add(0.0, 0.1, 0.0);
            return true;
        }
        return false;
    }

    /** Return true if npc moved downward */
    public boolean fall() {
        double y = location.getY() % 1.0;
        if (y < 0.0) y += 1.0;
        if (y > 0.0) {
            Location downward = location.clone();
            downward.setY(Math.floor(location.getY()));
            Location below = downward.clone().add(0, -0.1, 0);
            if (!isBlockedAt(downward) && isBlockedAt(below)) {
                location = downward;
                onGround = false;
                return true;
            }
        }
        for (int i = 0; i < 5; i += 1) {
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

    public boolean walkForward() {
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
            if (onGround) {
                // Some heuristic fall checks
                org.bukkit.block.Block downward = forward.getBlock().getRelative(0, -1, 0);
                if (downward.isLiquid()) return false;
                if (downward.isEmpty()) {
                    downward = downward.getRelative(0, -1, 0);
                    if (downward.isLiquid()) return false;
                    if (downward.isEmpty()) return false;
                }
            }
            if (canWalkFromTo(location, forward)) {
                location = forward;
                return true;
            } else {
                return false;
            }
        } else if (!isBlockedAt(forward.add(0.0, 0.5, 0.0))) {
            if (canWalkFromTo(location, forward)) {
                location = forward;
                return true;
            } else {
                return false;
            }
        } else if (!isBlockedAt(forward.add(0.0, 0.5, 0.0))) {
            if (canWalkFromTo(location, forward)) {
                location = forward;
                onGround = false;
                forceTeleport = true;
                return true;
            } else {
                return false;
            }
        }
        return false;
    }

    boolean canWalkFromTo(Location from, Location to) {
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return true;
        double width2 = (double)entity.width * 0.5;
        Location a = to.clone().add(-width2, 0.0, -width2);
        Location b = to.clone().add(width2, 0.0, width2);
        final int y = to.getBlockY();
        final int ax = a.getBlockX();
        final int bx = b.getBlockX();
        final int az = a.getBlockZ();
        final int bz = b.getBlockZ();
        for (int z = az; z <= bz; z += 1) {
            for (int x = ax; x <= bx; x += 1) {
                org.bukkit.block.Block block = to.getWorld().getBlockAt(x, y, z);
                if (!api.canWalkIn(block)) return false;
                if (!api.canWalkOn(block.getRelative(0, -1, 0))) return false;
            }
        }
        return true;
    }

    public void updateMovement() {
        switch (type) {
        case PLAYER: case MOB: case BLOCK: case ITEM:
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
            double distance = lastLocation.distanceSquared(location);
            boolean doTeleport = forceTeleport
                || ticksLived == 0
                || didMove && distance >= 64
                || locationError >= 0.125
                || locationMoved > 1024.0
                || !didMove && locationMoved > 16.0;
            if (doTeleport) {
                forceTeleport = false;
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

    public void updateWatchers() {
        Set<UUID> watcherIds = new HashSet<>();
        for (Iterator<Watcher> iter = watchers.iterator(); iter.hasNext();) {
            Watcher watcher = iter.next();
            // Weed out players
            // Note: We are adding even players which will have been removed
            watcherIds.add(watcher.player.getUniqueId());
            if (!watcher.player.isValid()) {
                iter.remove();
                continue;
            }
            if (!watcher.player.getWorld().equals(location.getWorld())
                || watcher.player.getLocation().distanceSquared(location) > 16384.0) { // 128^2
                stopWatch(watcher);
                iter.remove();
                continue;
            }
            // Update scoreboard if necessary
            if (type == Type.PLAYER) {
                Scoreboard scoreboard = watcher.player.getScoreboard();
                Team team = scoreboard.getTeam(TEAM_NAME);
                if (team == null) {
                    team = scoreboard.registerNewTeam(TEAM_NAME);
                    team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
                }
                if (!team.hasEntry(name)) team.addEntry(name);
            }
            // Send packets
            PlayerConnection connection = ((CraftPlayer)watcher.player).getHandle().playerConnection;
            if (watcher.setPlayerSkin == watcher.ticksLived) {
                watcher.setPlayerSkin = Math.max(watcher.setPlayerSkin * 2, 20);
                watcher.unsetPlayerSkin = watcher.ticksLived + 5L;
                packets.add(new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER, (EntityPlayer)entity));
            }
            if (watcher.unsetPlayerSkin == watcher.ticksLived) {
                watcher.unsetPlayerSkin = -1;
                packets.add(new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER, (EntityPlayer)entity));
            }
            Packet packet;
            while (null != (packet = watcher.packets.deal())) {
                connection.sendPacket(packet);
            }
            while (null != (packet = packets.deal())) {
                connection.sendPacket(packet);
            }
            watcher.ticksLived += 1;
        }
        // Find new players
        for (Player player: location.getWorld().getPlayers()) {
            if (!watcherIds.contains(player.getUniqueId())
                && player.getLocation().distanceSquared(location) < 16384.0) { // 128^2
                Watcher watcher = new Watcher(player);
                startWatch(watcher);
                watchers.add(watcher);
            }
        }
        if (watchers.isEmpty() && removeWhenUnwatched) valid = false;
    }
}

package com.cavetale.npc;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.Value;
import net.minecraft.server.v1_13_R1.Block;
import net.minecraft.server.v1_13_R1.BlockPosition;
import net.minecraft.server.v1_13_R1.ChatComponentText;
import net.minecraft.server.v1_13_R1.DataWatcher;
import net.minecraft.server.v1_13_R1.DataWatcherObject;
import net.minecraft.server.v1_13_R1.DataWatcherRegistry;
import net.minecraft.server.v1_13_R1.DataWatcherSerializer;
import net.minecraft.server.v1_13_R1.Entity;
import net.minecraft.server.v1_13_R1.EntityArmorStand;
import net.minecraft.server.v1_13_R1.EntityFallingBlock;
import net.minecraft.server.v1_13_R1.EntityItem;
import net.minecraft.server.v1_13_R1.EntityLiving;
import net.minecraft.server.v1_13_R1.EntityPlayer;
import net.minecraft.server.v1_13_R1.EntityTypes;
import net.minecraft.server.v1_13_R1.EnumDirection;
import net.minecraft.server.v1_13_R1.IBlockData;
import net.minecraft.server.v1_13_R1.IChatBaseComponent;
import net.minecraft.server.v1_13_R1.ItemStack;
import net.minecraft.server.v1_13_R1.MinecraftServer;
import net.minecraft.server.v1_13_R1.NBTTagCompound;
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
import net.minecraft.server.v1_13_R1.ParticleParam;
import net.minecraft.server.v1_13_R1.Particles;
import net.minecraft.server.v1_13_R1.PlayerConnection;
import net.minecraft.server.v1_13_R1.PlayerInteractManager;
import net.minecraft.server.v1_13_R1.Vector3f;
import net.minecraft.server.v1_13_R1.WorldServer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
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
    private static final GlobalCache GLOBAL_CACHE = new GlobalCache();
    private final Type type;
    private final int id;
    private final String name;
    @Setter private boolean valid;
    // Location
    @Setter private Location location;
    private Chunk chunkLocation;
    @Setter private double headYaw;
    private double lastHeadYaw;
    private Location lastLocation, trackLocation, fromLocation, toLocation;
    private double locationError = 0.0, locationMoved = 0.0;
    private boolean forceLookUpdate, forceTeleport;
    @Setter private boolean onGround;
    // Identity
    private Entity entity;
    private final EntityData entityData = new EntityData();
    // Mob Type
    private EntityType entityType; // Only for MOB
    private org.bukkit.block.data.BlockData blockData; // Only for BLOCK
    private org.bukkit.inventory.ItemStack itemStack;
    private PlayerSkin playerSkin;
    // State
    private final Map<UUID, Watcher> watchers = new HashMap<>();
    private final Set<UUID> exclusive = new HashSet<>();
    private final ScheduledPacketList packets = new ScheduledPacketList();
    private long ticksLived;
    @Setter private long lifespan = -1;
    private long lastInteract;
    @Setter private boolean removeWhenUnwatched;
    // Job
    @Setter private Job job;
    @Setter private Delegate delegate = () -> { };
    // Task
    private Task task;
    private int turn, turnTotal; // Countdown for current task duration
    private double movementSpeed = 3.0; // Blocks per second
    private double direction;
    @Setter private LivingEntity followEntity;
    @Setter private NPC followNPC;
    @Setter private Vector followOffset;
    private Location followLocation;
    // Constants
    private static final String TEAM_NAME = "cavetale.npc";

    @RequiredArgsConstructor
    class Watcher {
        final Player player;
        final ScheduledPacketList packets = new ScheduledPacketList();
        final EntityData entityData = new EntityData();
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

    @Value
    static class NPCChunk {
        public final int x, z;
    }

    static class GlobalCache {
        private final Map<String, ChunkNPCMap> worldMap = new HashMap<>();
        ChunkNPCMap getChunkNPCMap(String key) {
            ChunkNPCMap result = worldMap.get(key);
            if (result == null) {
                result = new ChunkNPCMap();
                worldMap.put(key, result);
            }
            return result;
        }
        List<NPC> getNPCsIn(Chunk chunk) {
            ChunkNPCMap cnp = getChunkNPCMap(chunk.getWorld().getName());
            NPCChunk nchunk = new NPCChunk(chunk.getX(), chunk.getZ());
            List<NPC> result = cnp.npcs.get(nchunk);
            if (result == null) {
                result = new ArrayList<>();
                cnp.npcs.put(nchunk, result);
            } else {
                Iterator<NPC> iter = result.iterator();
                while (iter.hasNext()) {
                    if (!iter.next().valid) {
                        iter.remove();
                    }
                }
            }
            return result;
        }
    }

    static class ChunkNPCMap {
        final Map<NPCChunk, List<NPC>> npcs = new HashMap<>();
    }

    enum Type {
        PLAYER, MOB, BLOCK, ITEM, MARKER;
    }

    enum Job {
        NONE, WANDER, WIGGLE, DANCE, RELATIVE;
    }

    enum Task {
        NONE, WALK, TURN, LOOK_AROUND, LOOK_AT, FOLLOW;
    }

    public enum EntityFlag {
        ENTITY_ON_FIRE(0x01),
        ENTITY_CROUCHING(0x02),
        ENTITY_UNUSED_RIDING(0x04),
        ENTITY_SPRINTING(0x08),
        ENTITY_SWIMMING(0x10),
        ENTITY_INVISIBLE(0x20),
        ENTITY_GLOWING(0x40),
        ENTITY_FLYING_ELYTRA(0x80),
        ARROW_CRIT(0x01),
        LIVING_HAND_ACTIVE(0x01),
        LIVING_OFF_HAND(0x02),
        PLAYER_SKIN_CAPE(0x01),
        PLAYER_SKIN_JACKET(0x02),
        PLAYER_SKIN_LEFT_SLEEVE(0x04),
        PLAYER_SKIN_RIGHT_SLEEVE(0x08),
        PLAYER_SKIN_LEFT_PANTS(0x10),
        PLAYER_SKIN_RIGHT_PANTS(0x20),
        PLAYER_SKIN_HAT(0x40),
        PLAYER_SKIN_UNUSED(0x80),
        PLAYER_SKIN_ALL(0x7e),
        ARMOR_STAND_SMALL(0x01),
        ARMOR_STAND_ARMS(0x04),
        ARMOR_STAND_NO_BASEPLATE(0x08),
        ARMOR_STAND_MARKER(0x10),
        INSENTIENT_NO_AI(0x01),
        INSENTIENT_LEFT_HANDED(0x02),
        BAT_HANGING(0x01),
        ABSTRACT_HORSE_UNUSED(0x01),
        ABSTRACT_HORSE_TAME(0x02),
        ABSTRACT_HORSE_SADDLED(0x04),
        ABSTRACT_HORSE_BRED(0x08),
        ABSTRACT_HORSE_EATING(0x10),
        ABSTRACT_HORSE_REARING(0x20),
        ABSTRACT_HORSE_MOUTH_OPEN(0x40),
        ABSTRACT_HORSE_UNUSED2(0x80),
        SHEEP_COLOR_MASK(0x0F),
        SHEEP_SHEARED(0x10),
        TAMEABLE_SITTING(0x01),
        TAMEABLE_ANGRY(0x02),
        TAMEABLE_TAMED(0x04),
        IRON_GOLEM_PLAYER_CREATED(0x01),
        SNOWMAN_HAS_PUMPKIN_HAT(0x10),
        BLAZE_ON_FIRE(0x01),
        ABSTRACT_ILLAGER_HAS_TARGET(0x01),
        VEX_ATTACK_MODE(0x01),
        SPIDER_CLIMBING(0x01);
        public final int bitMask;
        EntityFlag(int bitMask) {
            this.bitMask = bitMask;
        }
    }

    static final class DataType<T> {
        public static final DataType<Byte> BYTE = new DataType<>(DataWatcherRegistry.a);
        public static final DataType<Integer> INTEGER = new DataType<>(DataWatcherRegistry.b);
        public static final DataType<Float> FLOAT = new DataType<>(DataWatcherRegistry.c);
        public static final DataType<String> STRING = new DataType<>(DataWatcherRegistry.d);
        public static final DataType<IChatBaseComponent> CHAT = new DataType<>(DataWatcherRegistry.e);
        public static final DataType<Optional<IChatBaseComponent>> OPT_CHAT = new DataType<Optional<IChatBaseComponent>>(DataWatcherRegistry.f);
        public static final DataType<ItemStack> ITEM_STACK = new DataType<>(DataWatcherRegistry.g);
        public static final DataType<Optional<IBlockData>> OPT_BLOCK = new DataType<>(DataWatcherRegistry.h);
        public static final DataType<Boolean> BOOLEAN = new DataType<>(DataWatcherRegistry.i);
        public static final DataType<ParticleParam> PARTICLE_PARAM = new DataType<>(DataWatcherRegistry.j);
        public static final DataType<Vector3f> ROTATION = new DataType<>(DataWatcherRegistry.k);
        public static final DataType<BlockPosition> POSITION = new DataType<>(DataWatcherRegistry.l);
        public static final DataType<Optional<BlockPosition>> OPT_POSITION = new DataType<>(DataWatcherRegistry.m);
        public static final DataType<EnumDirection> DIRECTION = new DataType<>(DataWatcherRegistry.n);
        public static final DataType<Optional<UUID>> OPT_UUID = new DataType<>(DataWatcherRegistry.o);
        public static final DataType<NBTTagCompound> COMPOUND = new DataType<>(DataWatcherRegistry.p);
        public final DataWatcherSerializer<T> serializer;
        DataType(DataWatcherSerializer<T> serializer) {
            this.serializer = serializer;
        }
    }

    public enum DataVar {
        ENTITY_FLAGS(0, DataType.BYTE, (byte)0), //mask
        ENTITY_AIR(1, DataType.INTEGER, (int)300),
        ENTITY_CUSTOM_NAME(2, DataType.OPT_CHAT, Optional.empty()),
        ENTITY_CUSTOM_NAME_VISIBLE(3, DataType.BOOLEAN, false),
        ENTITY_SILENT(4, DataType.BOOLEAN, false),
        ENTITY_NO_GRAVITY(5, DataType.BOOLEAN, false),
        POTION_TYPE(6, DataType.ITEM_STACK, ItemStack.a),
        FALLING_BLOCK_SPAWN_POSITION(6, DataType.POSITION, BlockPosition.ZERO),
        AREA_EFFECT_CLOUD_RADIUS(6, DataType.FLOAT, 0.5F),
        AREA_EFFECT_CLOUD_COLOR(7, DataType.INTEGER, 0),
        AREA_EFFECT_CLOUD_POINT(8, DataType.BOOLEAN, false),
        AREA_EFFECT_CLOUD_PARTICLE(9, DataType.PARTICLE_PARAM, Particles.s),
        FISHING_HOOK_ENTITY(6, DataType.INTEGER, 0),
        ARROW_FLAGS(6, DataType.BYTE, (byte)0), //mask
        ARROW_SHOOTER(7, DataType.OPT_UUID, Optional.empty()),
        TIPPED_ARROW_COLOR(8, DataType.INTEGER, -1),
        TRIDENT_LOYALTY(8, DataType.INTEGER, 0),
        BOAT_HIT_TIME(6, DataType.INTEGER, 0),
        BOAT_FORWARD(7, DataType.INTEGER, 0),
        BOAT_DAMAGE(8, DataType.FLOAT, 0.0F),
        BOAT_TYPE(9, DataType.INTEGER, 0),
        BOAT_RIGHT_PADDLE(10, DataType.BOOLEAN, false),
        BOAT_LEFT_PADDLE(11, DataType.BOOLEAN, false),
        BOAT_SPLASH_TIMER(12, DataType.INTEGER, 0),
        ENDER_CRYSTAL_BEAM_TARGET(6, DataType.OPT_POSITION, Optional.empty()),
        ENDER_CRYSTAL_SHOW_BOTTOM(7, DataType.BOOLEAN, false),
        WITHER_SKULL_INVULNERABLE(6, DataType.BOOLEAN, false),
        FIREWORKS_INFO(6, DataType.ITEM_STACK, ItemStack.a),
        FIREWORKS_SHOOTER(7, DataType.INTEGER, 0),
        ITEM_FRAME_ITEM(6, DataType.ITEM_STACK, ItemStack.a),
        ITEM_FRAME_ROTATION(7, DataType.INTEGER, 0),
        ITEM_ITEM(6, DataType.ITEM_STACK, ItemStack.a),
        LIVING_HAND_STATE(6, DataType.BYTE, (byte)0), //mask
        LIVING_HEALTH(7, DataType.FLOAT, 0F),
        LIVING_POTION_EFFECT_COLOR(8, DataType.INTEGER, 0),
        LIVING_POTION_EFFECT_AMBIENT(9, DataType.BOOLEAN, false),
        LIVING_ARROWS(10, DataType.INTEGER, 0),
        PLAYER_ADDITIONAL_HEARTS(11, DataType.FLOAT, 0.0f),
        PLAYER_SCORE(12, DataType.INTEGER, 0),
        PLAYER_SKIN_PARTS(13, DataType.BYTE, (byte)0),
        PLAYER_MAIN_HAND(14, DataType.BYTE, (byte)1),
        PLAYER_LEFT_SHOULDER(15, DataType.COMPOUND, new NBTTagCompound()),
        PLAYER_RIGHT_SHOULDER(16, DataType.COMPOUND, new NBTTagCompound()),
        ARMOR_STAND_FLAGS(11, DataType.BYTE, (byte)0), //mask
        ARMOR_STAND_HEAD_ROTATION(12, DataType.ROTATION, new Vector3f(0, 0, 0)),
        ARMOR_STAND_BODY_ROTATION(13, DataType.ROTATION, new Vector3f(0, 0, 0)),
        ARMOR_STAND_LEFT_ARM_ROTATION(14, DataType.ROTATION, new Vector3f(0, 0, 0)),
        ARMOR_STAND_RIGHT_ARM_ROTATION(15, DataType.ROTATION, new Vector3f(0, 0, 0)),
        ARMOR_STAND_LEFT_LEG_ROTATION(16, DataType.ROTATION, new Vector3f(0, 0, 0)),
        ARMOR_STAND_RIGHT_LEG_ROTATION(17, DataType.ROTATION, new Vector3f(0, 0, 0)),
        INSENTIENT_FLAGS(11, DataType.BYTE, (byte)0), //mask
        BAT_FLAGS(12, DataType.BYTE, (byte)0), //mask
        AGEABLE_BABY(12, DataType.BOOLEAN, false),
        ABSTRACT_HORSE_FLAGS(13, DataType.BYTE, (byte)0), //mask
        ABSTRACT_HORSE_OWNER(14, DataType.OPT_UUID, Optional.empty()),
        HORSE_VARIANT(15, DataType.INTEGER, 0),
        HORSE_ARMOR(16, DataType.INTEGER, 0),
        HORSE_ARMOR_ITEM(17, DataType.ITEM_STACK, ItemStack.a),
        CHESTED_HORSE_HAS_CHEST(15, DataType.BOOLEAN, false),
        LLAMA_STRENGTH(16, DataType.INTEGER, 0),
        LLAMA_CARPET_COLOR(17, DataType.INTEGER, -1),
        LLAMA_VARIANT(18, DataType.INTEGER, 0),
        PIG_SADDLE(13, DataType.BOOLEAN, false),
        PIG_BOOST(14, DataType.INTEGER, 0),
        RABBIT_TYPE(13, DataType.INTEGER, 0),
        POLAR_BEAR_STANDING(13, DataType.BOOLEAN, false),
        SHEEP_FLAGS(13, DataType.BYTE, (byte)0), //mask
        TAMEABLE_FLAGS(13, DataType.BYTE, (byte)0), //mask
        TAMEABLE_OWNER(14, DataType.OPT_UUID, Optional.empty()),
        OCELOT_TYPE(15, DataType.INTEGER, 0),
        WOLF_DAMAGE_TAKEN(15, DataType.FLOAT, 1.0f),
        WOLF_BEGGING(16, DataType.BOOLEAN, false),
        WOLF_COLLAR_COLOR(17, DataType.INTEGER, 14),
        PARROT_VARIANT(15, DataType.INTEGER, 0),
        VILLAGER_PROFESSION(15, DataType.INTEGER, 0),
        IRON_GOLEM_FLAGS(12, DataType.BYTE, (byte)0), //mask
        SNOWMAN_FLAGS(12, DataType.BYTE, (byte)0x10), //mask
        SHULKER_DIRECTION(12, DataType.DIRECTION, EnumDirection.DOWN),
        SHULKER_ATTACHMENT(13, DataType.OPT_POSITION, Optional.empty()),
        SHULKER_SHIELD_HEIGHT(14, DataType.BYTE, (byte)0),
        SHULKER_COLOR(15, DataType.BYTE, (byte)10),
        BLAZE_FLAGS(12, DataType.BYTE, (byte)0), //mask
        CREEPER_STATE(12, DataType.INTEGER, -1),
        CREEPER_CHARGED(13, DataType.BOOLEAN, false),
        CREEPER_IGNITED(14, DataType.BOOLEAN, false),
        GUARDIAN_RETRACTING_SPIKES(12, DataType.BOOLEAN, false),
        GUARDIAN_TARGET(13, DataType.INTEGER, 0),
        ABSTRACT_ILLAGER_FLAGS(12, DataType.BYTE, (byte)0), //mask
        SPELLCASTER_ILLAGER_SPELL(13, DataType.BYTE, (byte)0),
        VEX_FLAGS(12, DataType.BYTE, (byte)0), //mask
        ABSTRACT_SKELETON_SWINGING_ARMS(12, DataType.BOOLEAN, false),
        SPIDER_FLAGS(12, DataType.BYTE, (byte)0), //mask
        WITCH_DRINKING_POTION(12, DataType.BOOLEAN, false),
        WITHER_CENTER_HEAD_TARGET(12, DataType.INTEGER, 0),
        WITHER_LEFT_HEAD_TARGET(13, DataType.INTEGER, 0),
        WITHER_RIGHT_HEAD_TARGET(14, DataType.INTEGER, 0),
        WITHER_INVULNERABLE_TIME(15, DataType.INTEGER, 0),
        PHANTOM_SIZE(12, DataType.INTEGER, 0),
        DOLPHIN_TREASURE_POSITION(12, DataType.POSITION, BlockPosition.ZERO),
        DOLPHIN_CAN_FIND_TREASURE(13, DataType.BOOLEAN, false),
        DOLPHIN_HAS_FISH(14, DataType.BOOLEAN, false),
        FISH_FROM_BUCKET(12, DataType.BOOLEAN, false),
        PUFFERFISH_PUFF(13, DataType.INTEGER, 0),
        TROPICAL_FISH_VARIANT(13, DataType.INTEGER, 0),
        TURTLE_HOME_POS(13, DataType.POSITION, BlockPosition.ZERO),
        TURTLE_HAS_EGG(14, DataType.BOOLEAN, false),
        TURTLE_LAYING_EGG(15, DataType.BOOLEAN, false),
        TURTLE_TRAVEL_POS(16, DataType.POSITION, BlockPosition.ZERO),
        TURTLE_GOING_HOME(17, DataType.BOOLEAN, false),
        TURTLE_TRAVELING(18, DataType.BOOLEAN, false),
        ZOMBIE_IS_BABY(12, DataType.BOOLEAN, false),
        ZOMBIE_UNUSED_TYPE(13, DataType.INTEGER, 0),
        ZOMBIE_HANDS_UP(14, DataType.BOOLEAN, false),
        ZOMBIE_BECOMING_DROWNED(15, DataType.BOOLEAN, false),
        ZOMBIE_VILLAGER_CONVERTING(15, DataType.BOOLEAN, false),
        ZOMBIE_VILLAGER_PROFESSION(16, DataType.INTEGER, 0),
        ENDERMAN_CARRIED_BLOCK(12, DataType.OPT_BLOCK, Optional.empty()),
        ENDERMAN_SCREAMING(13, DataType.BOOLEAN, false),
        ENDER_DRAGON_PHASE(12, DataType.INTEGER, 10),
        GHAST_ATTACKING(12, DataType.BOOLEAN, false),
        SLIME_SIZE(12, DataType.INTEGER, 1),
        MINECART_SHAKING_POWER(6, DataType.INTEGER, 0),
        MINECART_SHAKING_DIRECTION(7, DataType.INTEGER, 1),
        MINECART_SHAKING_MULTIPLIER(8, DataType.FLOAT, 0.0f),
        MINECART_CUSTOM_BLOCK_ID_DMG(9, DataType.INTEGER, 0),
        MINECART_CUSTOM_BLOCK_Y(10, DataType.INTEGER, 6),
        MINECART_SHOW_CUSTOM_BLOCK(11, DataType.BOOLEAN, false),
        MINECART_FURNACE_POWERED(12, DataType.BOOLEAN, false),
        MINECART_COMMAND_COMMAND(12, DataType.STRING, ""),
        MINECART_LAST_OUTPUT(13, DataType.CHAT, new ChatComponentText("")),
        TNT_PRIMED_FUSE_TIME(6, DataType.INTEGER, 80);
        public final DataType dataType;
        public final int index;
        public final Object defaultValue;
        public final DataWatcherObject dataWatcherObject;
        <T> DataVar(int index, DataType<T> dataType, T defaultValue) {
            this.index = index;
            this.dataType = dataType;
            this.defaultValue = defaultValue;
            this.dataWatcherObject = new DataWatcherObject(index, dataType.serializer);
        }
    }

    @RequiredArgsConstructor
    public static class DataValue {
        public final DataVar variable;
        public final Object value;
    }

    final class EntityData implements Cloneable {
        private final Map<Integer, DataValue> data = new HashMap<>();

        @Override
        public EntityData clone() {
            EntityData result = new EntityData();
            for (Map.Entry<Integer, DataValue> entry: data.entrySet()) {
                result.data.put(entry.getKey(), entry.getValue());
            }
            return result;
        }

        public boolean isEmpty() {
            return data.isEmpty();
        }

        /**
         * Override data in this instance with another, retaining
         * native entries
         */
        public void overwriteWith(EntityData other) {
            for (Map.Entry<Integer, DataValue> entry: other.data.entrySet()) {
                data.put(entry.getKey(), entry.getValue());
            }
        }

        public void set(DataVar variable, Object value) {
            data.put(variable.index, new DataValue(variable, value));
        }

        public void unset(DataVar variable) {
            data.remove(variable.index);
        }

        public void reset(DataVar variable) {
            data.put(variable.index, new DataValue(variable, variable.defaultValue));
        }

        public void reset() {
            data.clear();
        }

        public boolean isSet(DataVar variable) {
            return data.containsKey(variable.index);
        }

        public Object get(DataVar variable) {
            DataValue value = data.get(variable.index);
            if (value == null) return variable.defaultValue;
            return value.value;
        }

        public PacketPlayOutEntityMetadata makeMetadataPacket() {
            List<DataWatcher.Item<?>> list = new ArrayList<>();
            for (DataValue value: data.values()) {
                list.add(new DataWatcher.Item(value.variable.dataWatcherObject, value.value));
            }
            DummyDataWatcher dummy = new DummyDataWatcher(list);
            return new PacketPlayOutEntityMetadata(id, dummy, false);
        }

        public boolean getFlag(DataVar variable, EntityFlag flag) {
            return (((Byte)get(variable)).intValue() & flag.bitMask) > 0;
        }

        public void setFlag(DataVar variable, EntityFlag flag, boolean value) {
            int v = ((Byte)get(variable)).intValue();
            if (value) {
                v |= flag.bitMask;
            } else {
                v &= ~flag.bitMask;
            }
            set(variable, (byte)v);
        }

        public void setFlags(DataVar variable, EntityFlag... flags) {
            int bitMask = 0;
            for (EntityFlag flag: flags) bitMask |= flag.bitMask;
            set(variable, (byte)bitMask);
        }
    }

    private static class DummyDataWatcher extends DataWatcher {
        final List<DataWatcher.Item<?>> list;

        DummyDataWatcher(List<DataWatcher.Item<?>> list) {
            super(null);
            this.list = list;
        }

        // Original: Fetch all dirty values and set undirty
        @Override public List<DataWatcher.Item<?>> b() {
            return list;
        }

        // Original: Fetch all values
        @Override public List<DataWatcher.Item<?>> c() {
            return list;
        }

        // Original: Set all values undirty
        @Override public void e() { }
    };

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
            entityData.setFlag(DataVar.PLAYER_SKIN_PARTS, EntityFlag.PLAYER_SKIN_ALL, true);
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
            if (entityType == EntityType.BAT) {
                entityData.setFlag(DataVar.BAT_FLAGS, EntityFlag.BAT_HANGING, false);
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
        switch (type) {
        case BLOCK:
            final WorldServer worldServer = ((CraftWorld)location.getWorld()).getHandle();
            entity = new EntityFallingBlock(worldServer, location.getX(), location.getY(), location.getZ(), ((CraftBlockData)blockData).getState());
            id = entity.getId();
            name = entity.getUniqueID().toString().replace("-", "");
            entityData.set(DataVar.ENTITY_NO_GRAVITY, true);
            entity.setPosition(location.getX(), location.getY(), location.getZ());
            break;
        default:
            throw new IllegalArgumentException("NPC(Type, Location, BlockData): wrong constructor for type " + type);
        }
    }

    public NPC(Type type, Location location, org.bukkit.inventory.ItemStack itemStack) {
        this.type = type;
        this.location = location;
        switch (type) {
        case ITEM:
            final WorldServer worldServer = ((CraftWorld)location.getWorld()).getHandle();
            this.itemStack = itemStack;
            ItemStack nmsItem = CraftItemStack.asNMSCopy(itemStack);
            entity = new EntityItem(worldServer, location.getX(), location.getY(), location.getZ(), nmsItem);
            id = entity.getId();
            name = entity.getUniqueID().toString().replace("-", "");
            entity.motX = 0.0;
            entity.motY = 0.0;
            entity.motZ = 0.0;
            entityData.set(DataVar.ENTITY_NO_GRAVITY, true);
            entityData.set(DataVar.ITEM_ITEM, nmsItem);
            break;
        default:
            throw new IllegalArgumentException("NPC(Type, Location, ItemStack): wrong constructor for type " + type);
        }
    }

    public NPC(Type type, Location location, String text, long lifespan) {
        this.type = type;
        this.location = location;
        switch (type) {
        case MARKER:
            final WorldServer worldServer = ((CraftWorld)location.getWorld()).getHandle();
            entity = new EntityArmorStand(worldServer, location.getX(), location.getY(), location.getZ());
            id = entity.getId();
            name = entity.getUniqueID().toString().replace("-", "");
            entityData.set(DataVar.ENTITY_NO_GRAVITY, true);
            entityData.set(DataVar.ENTITY_CUSTOM_NAME_VISIBLE, true);
            entityData.setFlags(DataVar.ENTITY_FLAGS, EntityFlag.ENTITY_INVISIBLE);
            entityData.setFlags(DataVar.ARMOR_STAND_FLAGS, EntityFlag.ARMOR_STAND_NO_BASEPLATE, EntityFlag.ARMOR_STAND_MARKER);
            updateCustomName(text);
            job = Job.RELATIVE;
            this.lifespan = lifespan;
            break;
        default:
            throw new IllegalArgumentException("NPC(Type, Location, String): wrong consturctor for type " + type);
        }
    }

    public String getDescription() {
        StringBuilder sb = new StringBuilder("id#" + id + " " + type.name());
        sb.append(" ").append(location.getBlockX());
        sb.append(",").append(location.getBlockY());
        sb.append(",").append(location.getBlockZ());
        switch (type) {
        case MOB:
            sb.append(" ").append(entityType.name());
            break;
        case BLOCK:
            sb.append(" ").append(blockData.getAsString());
            break;
        case ITEM:
            sb.append(" ").append(itemStack.getType().name());
            break;
        case PLAYER:
            sb.append(" ").append(name.replace("" + ChatColor.COLOR_CHAR, "&"));
            break;
        case MARKER:
            sb.append(" ").append(((Optional<IChatBaseComponent>)entityData.get(DataVar.ENTITY_CUSTOM_NAME)).orElse(null));
            break;
        default:
            sb.append(" ???");
        }
        return sb.toString();
    }

    // Overridable Delegate methods

    public interface Delegate {
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

        default boolean canMoveIn(org.bukkit.block.Block block) {
            return true;
        }

        default boolean canMoveOn(org.bukkit.block.Block block) {
            return true;
        }

        default boolean onInteract(Player player, boolean rightClick) {
            return true;
        }

        default boolean onPlayerAdd(Player player) {
            return true;
        }

        default void onPlayerRemove(Player player) { }
    }

    // Internal methods

    void enable() {
        lastLocation = location.clone();
        trackLocation = location.clone();
        headYaw = location.getYaw();
        lastHeadYaw = headYaw;
        updateChunkLocation();
        valid = true;
        delegate.onEnable();
    }

    void disable() {
        delegate.onDisable();
        for (Watcher watcher: watchers.values()) {
            if (!watcher.player.isValid()) continue;
            stopWatch(watcher);
        }
        watchers.clear();
        valid = false;
    }

    public void interact(Player player, boolean rightClick) {
        if (lastInteract == ticksLived) return;
        lastInteract = ticksLived;
        Watcher watcher = watchers.get(player.getUniqueId());
        if (watcher == null) return;
        boolean res = delegate.onInteract(player, rightClick);
        if (type == Type.PLAYER) {
            if (watcher.unsetPlayerSkin < 0) watcher.setPlayerSkin = watcher.ticksLived;
        }
    }

    private void startWatch(Watcher watcher) {
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
            connection.sendPacket(entityData.makeMetadataPacket());
            connection.sendPacket(new PacketPlayOutEntityVelocity(entity.getId(), 0, 0, 0));
            break;
        case MOB: case MARKER:
            connection.sendPacket(new PacketPlayOutSpawnEntityLiving((EntityLiving)entity));
            connection.sendPacket(new PacketPlayOutEntityHeadRotation(entity, (byte)((int)((headYaw % 360.0f) * 256.0f / 360.0f))));
            if (!entityData.isEmpty()) {
                if (watcher.entityData.isEmpty()) {
                    connection.sendPacket(entityData.makeMetadataPacket());
                } else {
                    EntityData newEntityData = entityData.clone();
                    newEntityData.overwriteWith(watcher.entityData);
                    connection.sendPacket(newEntityData.makeMetadataPacket());
                }
            }
            break;
        case BLOCK:
            connection.sendPacket(new PacketPlayOutSpawnEntity(entity, 70, Block.REGISTRY_ID.getId(((CraftBlockData)blockData).getState())));
            connection.sendPacket(entityData.makeMetadataPacket());
            connection.sendPacket(new PacketPlayOutEntityVelocity(entity.getId(), 0, 0, 0));
            break;
        case ITEM:
            connection.sendPacket(new PacketPlayOutSpawnEntity(entity, 2));
            connection.sendPacket(entityData.makeMetadataPacket());
            connection.sendPacket(new PacketPlayOutEntityVelocity(entity.getId(), 0, 0, 0));
            break;
        default:
            throw new IllegalStateException("Unhandled type: " + type);
        }
    }

    private void stopWatch(Watcher watcher) {
        PlayerConnection connection = ((CraftPlayer)watcher.player).getHandle().playerConnection;
        switch (type) {
        case PLAYER:
            connection.sendPacket(new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER, (EntityPlayer)entity));
            connection.sendPacket(new PacketPlayOutEntityDestroy(new int[] {entity.getId()}));
            break;
        case MOB: case MARKER: case BLOCK: case ITEM:
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
        delegate.onTick();
        if (job != null) performJob();
        updateMovement();
        updateWatchers();
        ticksLived += 1;
        if (lifespan > 0 && lifespan < ticksLived) valid = false;
    }

    public void performJob() {
        switch (job) {
        case NONE: return;
        case WANDER:
            if (turn <= 0) {
                if (task == null) task = Task.NONE;
                switch (task) {
                case WALK:
                    if (ThreadLocalRandom.current().nextInt(4) == 0) {
                        task = Task.LOOK_AROUND;
                        turn = 20 + ThreadLocalRandom.current().nextInt(40);
                    } else {
                        task = Task.TURN;
                        turn = 20 + ThreadLocalRandom.current().nextInt(40);
                        direction = ThreadLocalRandom.current().nextBoolean() ? -1 : 1;
                        direction *= 2.5 + ThreadLocalRandom.current().nextDouble() * 2.5;
                    }
                    break;
                case LOOK_AROUND:
                case TURN:
                default:
                    task = Task.WALK;
                    turn = 20 + ThreadLocalRandom.current().nextInt(100);
                    direction = ThreadLocalRandom.current().nextFloat() * 4.0f - 2.0f;
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
        case DANCE:
            if (turn <= 0) {
                entityData.setFlag(DataVar.ENTITY_FLAGS, EntityFlag.ENTITY_SPRINTING, true);
                turn = 1;
            }
            switch ((int)(ticksLived % 20L)) {
            case 0: case 6:
                setFlag(DataVar.ENTITY_FLAGS, EntityFlag.ENTITY_CROUCHING, true);
                updateEntityData(0L);
                break;
            case 3: case 9:
                setFlag(DataVar.ENTITY_FLAGS, EntityFlag.ENTITY_CROUCHING, false);
                updateEntityData(0L);
                break;
            case 10: swingArm(false); break;
            case 15: swingArm(true); break;
            default: break;
            }
            headYaw += 15.0;
            location.setYaw((float)headYaw);
            break;
        case RELATIVE:
            if (followEntity != null) {
                location = followEntity.getLocation();
            } else if (followNPC != null && followNPC.valid) {
                location = followNPC.location.clone();
            } else {
                job = Job.NONE;
                return;
            }
            if (followOffset != null) location = location.add(followOffset);
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
                headYaw = headYaw + direction;
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

    public void updateEntityData(long delay) {
        PacketPlayOutEntityMetadata packet = null;
        for (Watcher watcher: watchers.values()) {
            if (watcher.entityData.isEmpty()) {
                if (packet == null) packet = entityData.makeMetadataPacket();
                watcher.packets.add(packet, delay);
            } else {
                EntityData newEntityData = entityData.clone();
                newEntityData.overwriteWith(watcher.entityData);
                watcher.packets.add(newEntityData.makeMetadataPacket(), delay);
            }
        }
    }

    public void updateEntityData(Player player, long delay) {
        Watcher watcher = watchers.get(player.getUniqueId());
        if (watcher == null) return;
        if (watcher.entityData.isEmpty()) {
            watcher.packets.add(entityData.makeMetadataPacket(), delay);
        } else {
            EntityData newEntityData = entityData.clone();
            newEntityData.overwriteWith(watcher.entityData);
            watcher.packets.add(newEntityData.makeMetadataPacket(), delay);
        }
    }

    PacketPlayOutEntityMetadata makeMetadataPacket(DataVar variable, Object value) {
        List<DataWatcher.Item<?>> list = Arrays.asList(new DataWatcher.Item(variable.dataWatcherObject, value));
        DummyDataWatcher dummy = new DummyDataWatcher(list);
        return new PacketPlayOutEntityMetadata(id, dummy, false);
    }

    // Data getters and setters

    public void setData(DataVar variable, Object value) {
        if (value == null) {
            entityData.unset(variable);
            for (Watcher watcher: watchers.values()) {
                watcher.entityData.unset(variable);
            }
        } else {
            entityData.set(variable, value);
            for (Watcher watcher: watchers.values()) {
                if (watcher.entityData.isSet(variable)) {
                    watcher.entityData.set(variable, value);
                }
            }
        }
    }

    public void setData(Player player, DataVar variable, Object value) {
        Watcher watcher = watchers.get(player.getUniqueId());
        if (watcher == null) return;
        if (value == null) {
            watcher.entityData.unset(variable);
        } else {
            watcher.entityData.set(variable, value);
        }
    }

    public boolean getFlag(DataVar variable, EntityFlag entityFlag) {
        return entityData.getFlag(variable, entityFlag);
    }

    public void setFlag(DataVar variable, EntityFlag entityFlag, boolean value) {
        entityData.setFlag(variable, entityFlag, value);
        for (Watcher watcher: watchers.values()) {
            if (watcher.entityData.isSet(variable)) {
                watcher.entityData.setFlag(variable, entityFlag, value);
            }
        }
    }

    public void setFlag(Player player, DataVar variable, EntityFlag entityFlag, boolean value) {
        Watcher watcher = watchers.get(player.getUniqueId());
        if (watcher == null) return;
        if (!watcher.entityData.isSet(variable)) {
            // Initialize with global value
            watcher.entityData.set(variable, entityData.get(variable));
        }
        watcher.entityData.setFlag(variable, entityFlag, value);
    }

    public void sendData(DataVar variable, Object value, long delay) {
        if (watchers.isEmpty()) return;
        packets.add(makeMetadataPacket(variable, value), delay);
    }

    public void sendData(Player player, DataVar variable, Object value, long delay) {
        if (watchers.isEmpty()) return;
        Watcher watcher = watchers.get(player.getUniqueId());
        if (watcher == null) return;
        watcher.packets.add(makeMetadataPacket(variable, value), delay);
    }

    public void resetData() {
        entityData.reset();
        for (Watcher watcher: watchers.values()) watcher.entityData.reset();
    }

    public void resetData(Player player) {
        Watcher watcher = watchers.get(player.getUniqueId());
        if (watcher != null) watcher.entityData.reset();
    }

    public void sendFlag(DataVar variable, EntityFlag flag, boolean value, long delay) {
        if (watchers.isEmpty()) return;
        final int baseValue = ((Byte)entityData.get(variable)).intValue();
        for (Watcher watcher: watchers.values()) {
            int bitMask;
            if (watcher.entityData.isSet(variable)) {
                bitMask = ((Byte)watcher.entityData.get(variable)).intValue();
            } else {
                bitMask = baseValue;
            }
            if (value) {
                bitMask |= flag.bitMask;
            } else {
                bitMask &= ~flag.bitMask;
            }
            watcher.packets.add(makeMetadataPacket(variable, (byte)bitMask), delay);
        }
    }

    public void updateCustomName(String name) {
        ChatComponentText txt = new ChatComponentText(ChatColor.translateAlternateColorCodes('&', name));
        setData(DataVar.ENTITY_CUSTOM_NAME, Optional.of(txt));
        if (watchers.isEmpty()) return;
        sendData(DataVar.ENTITY_CUSTOM_NAME, Optional.of(txt), 0L);
    }

    /** Return true if npc moved upward */
    public boolean fightGravity() {
        if (isBlockedAt(location) || location.getBlock().isLiquid()) {
            location = location.add(0.0, 0.1, 0.0);
            return true;
        }
        return false;
    }

    public Location getHeadLocation() {
        return location.clone().add(0.0, entity.length, 0.0);
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
            vec = vec.multiply(movementSpeed * 0.05);
        } else {
            vec = vec.multiply(movementSpeed * 0.01);
        }
        Location forward = location.clone().add(vec);
        boolean collides = collidesWithOther();
        boolean isBlocked = isBlockedAt(forward) || (!collides && collidesWithOtherAt(forward));
        if (isBlocked) {
            Location forwardStep = forward.clone().add(0.0, 0.5, 0.0);
            if (!isBlockedAt(forwardStep) && (collides || !collidesWithOtherAt(forwardStep))) {
                forward = forwardStep;
                isBlocked = false;
            }
        }
        if (isBlocked) {
            Location forwardJump = forward.clone().add(0.0, 1.0, 0.0);
            if (!isBlockedAt(forwardJump) && (collides || !collidesWithOtherAt(forwardJump))) {
                forward = forwardJump;
                isBlocked = false;
            }
        }
        if (isBlocked) {
            Location forwardX = location.clone().add(vec.getX(), 0.0, 0.0);
            if (!isBlockedAt(forwardX) && (collides || !collidesWithOtherAt(forwardX))) {
                forward = forwardX;
                isBlocked = false;
            }
        }
        if (isBlocked) {
            Location forwardZ = location.clone();
            forwardZ = forwardZ.add(0.0, 0.0, vec.getZ());
            if (!isBlockedAt(forwardZ) && (collides || !collidesWithOtherAt(forwardZ))) {
                forward = forwardZ;
                isBlocked = false;
            }
        }
        if (!isBlocked) {
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
                if (!delegate.canMoveIn(block)) return false;
                if (!delegate.canMoveOn(block.getRelative(0, -1, 0))) return false;
            }
        }
        return true;
    }

    public void updateMovement() {
        boolean didMove =
            location.getX() != lastLocation.getX()
            || location.getY() != lastLocation.getY()
            || location.getZ() != lastLocation.getZ();
        boolean didTurn;
        switch (type) {
        case PLAYER: case MOB:
            didTurn = forceLookUpdate
                || location.getPitch() != lastLocation.getPitch()
                || location.getYaw() != lastLocation.getYaw();
            forceLookUpdate = false;
            break;
        case BLOCK: case ITEM: case MARKER: default:
            didTurn = false;
        }
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
        if (didMove) updateChunkLocation();
        lastLocation = location.clone();
    }

    public void updateWatchers() {
        Set<UUID> watcherIds = new HashSet<>();
        for (Iterator<Map.Entry<UUID, Watcher>> iter = watchers.entrySet().iterator(); iter.hasNext();) {
            Watcher watcher = iter.next().getValue();
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
            PlayerConnection connection = ((CraftPlayer)watcher.player).getHandle().playerConnection;
            // Update scoreboard if necessary
            if (type == Type.PLAYER) {
                Scoreboard scoreboard = watcher.player.getScoreboard();
                Team team = scoreboard.getTeam(TEAM_NAME);
                if (team == null) {
                    team = scoreboard.registerNewTeam(TEAM_NAME);
                    team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
                }
                if (!team.hasEntry(name)) team.addEntry(name);
                // Update skin every now and then
                if (watcher.setPlayerSkin == watcher.ticksLived) {
                    watcher.setPlayerSkin = Math.max(watcher.setPlayerSkin * 2, 20);
                    watcher.unsetPlayerSkin = watcher.ticksLived + 4L;
                    packets.add(new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER, (EntityPlayer)entity));
                }
                if (watcher.unsetPlayerSkin == watcher.ticksLived) {
                    watcher.unsetPlayerSkin = -1;
                    packets.add(new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER, (EntityPlayer)entity));
                }
            }
            // Send scheduled packets
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
                if (!exclusive.isEmpty() && !exclusive.contains(player.getUniqueId())) continue;
                if (!delegate.onPlayerAdd(player)) continue;
                Watcher watcher = new Watcher(player);
                watchers.put(player.getUniqueId(), watcher);
                startWatch(watcher);
            }
        }
        if (watchers.isEmpty() && removeWhenUnwatched) valid = false;
    }

    private void updateChunkLocation() {
        if (chunkLocation != null) {
            GLOBAL_CACHE.getNPCsIn(chunkLocation).remove(this);
        }
        chunkLocation = location.getChunk();
        GLOBAL_CACHE.getNPCsIn(chunkLocation).add(this);
    }

    public boolean collidesWithOtherAt(Location at) {
        List<Chunk> chunks = new ArrayList<>();
        Chunk mainChunk = at.getChunk();
        chunks.add(mainChunk);
        int cx = at.getBlockX() % 16;
        int cz = at.getBlockZ() % 16;
        if (cx < 0) cx += 16;
        if (cz < 0) cz += 16;
        if (cx == 0) chunks.add(mainChunk.getWorld().getChunkAt(mainChunk.getX() - 1, mainChunk.getZ()));
        if (cx == 15) chunks.add(mainChunk.getWorld().getChunkAt(mainChunk.getX() + 1, mainChunk.getZ()));
        if (cz == 0) chunks.add(mainChunk.getWorld().getChunkAt(mainChunk.getX(), mainChunk.getZ() - 1));
        if (cz == 15) chunks.add(mainChunk.getWorld().getChunkAt(mainChunk.getX(), mainChunk.getZ() + 1));
        for (Chunk chunk: chunks) {
            for (NPC other: GLOBAL_CACHE.getNPCsIn(chunk)) {
                if (other != this) {
                    double x = other.location.getX() - at.getX();
                    double y = other.location.getY() - at.getY();
                    double z = other.location.getZ() - at.getZ();
                    double width = other.entity.width * 0.5 + entity.width * 0.5;
                    if (Math.abs(x) > width) continue;
                    if (Math.abs(z) > width) continue;
                    if (y > entity.length || y < -other.entity.length) continue;
                    return true;
                }
            }
        }
        return false;
    }

    public boolean collidesWithOther() {
        return collidesWithOtherAt(location);
    }

    public List<NPC> getNearbyNPCs() {
        if (chunkLocation == null) updateChunkLocation();
        return GLOBAL_CACHE.getNPCsIn(chunkLocation);
    }
}

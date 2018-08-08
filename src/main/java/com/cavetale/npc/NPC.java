package com.cavetale.npc;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.server.v1_13_R1.Block;
import net.minecraft.server.v1_13_R1.ChatComponentText;
import net.minecraft.server.v1_13_R1.DataWatcherObject;
import net.minecraft.server.v1_13_R1.DataWatcherRegistry;
import net.minecraft.server.v1_13_R1.Entity;
import net.minecraft.server.v1_13_R1.EntityFallingBlock;
import net.minecraft.server.v1_13_R1.EntityHuman;
import net.minecraft.server.v1_13_R1.EntityLiving;
import net.minecraft.server.v1_13_R1.EntityPlayer;
import net.minecraft.server.v1_13_R1.EntityTypes;
import net.minecraft.server.v1_13_R1.MinecraftServer;
import net.minecraft.server.v1_13_R1.Packet;
import net.minecraft.server.v1_13_R1.PacketDataSerializer;
import net.minecraft.server.v1_13_R1.PacketListenerPlayOut;
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
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.v1_13_R1.CraftServer;
import org.bukkit.craftbukkit.v1_13_R1.CraftWorld;
import org.bukkit.craftbukkit.v1_13_R1.entity.CraftPlayer;
import org.bukkit.craftbukkit.v1_13_R1.util.CraftMagicNumbers;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

@Getter
public final class NPC {
    private Type type;
    @Setter private boolean valid;
    // Location
    @Setter private Location location;
    @Setter private double headYaw;
    private double lastHeadYaw;
    private Location lastLocation, trackLocation;
    private double locationError = 0.0, locationMoved = 0.0;
    @Setter private boolean onGround;
    // Identity
    private int id;
    private Entity entity;
    @Setter private String displayName;
    // Mob Type
    private EntityType entityType; // Only for MOB
    private org.bukkit.Material material; // Only for BLOCK
    // Player info
    private String texture, signature;
    // State
    private Random random = new Random(System.nanoTime());
    private final List<Player> playerWatchers = new ArrayList<>();
    private final List<Packet> packets = new ArrayList<>();
    private long ticksLived;
    // Job
    @Setter private Job job;
    private int turn;
    private float dir;
    private double speed;

    enum Type {
        PLAYER, MOB, BLOCK;
    }
    enum Job {
        NONE, WANDER;
    }

    NPC(Type type, Location location) {
        this.type = type;
        this.location = location;
    }

    NPC(Type type, Location location, EntityType entityType) {
        this(type, location);
        this.entityType = entityType;
    }

    NPC(Type type, Location location, org.bukkit.Material material) {
        this(type, location);
        this.material = material;
    }

    void setup() {
        MinecraftServer minecraftServer = ((CraftServer)Bukkit.getServer()).getServer();
        WorldServer worldServer = ((CraftWorld)location.getWorld()).getHandle();
        switch (type) {
        case PLAYER:
            switch (random.nextInt(3)) {
            case 0:
                texture = "eyJ0aW1lc3RhbXAiOjE1MzM2Njk4MDUwMTMsInByb2ZpbGVJZCI6IjQyZjUzN2RkMzBlNTQzODg5NjZjMWI3ZWI1NGM1NDVkIiwicHJvZmlsZU5hbWUiOiJTdGFyVHV4Iiwic2lnbmF0dXJlUmVxdWlyZWQiOnRydWUsInRleHR1cmVzIjp7IlNLSU4iOnsidXJsIjoiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS8yNTU4ODNmZDExNjQzMzM1OGM2YjJjMWRkYzEwYzcxNmM1YzA4Mjg2MGQzMzBiOGIwZjI3NTM3N2Y1Njg1ZGJjIn19fQ==";
                signature = "py5t2/mjqXSMYYaw94keXdkzjNKQCoJBPXgs4dVc9kzJB2zBieiIt5/L2QHmIePf2q/2Roj0VBpT1MN5Fyq7mzIMlXllKc2GzVEK/LFl4iwjltOhubXCVL2xzQwS7Iv662ExL9l5+fYDSBnSI6hcVqVXItyyu1YaPRLTkKRGH9V3y1dPYtJQ59btlgkfuQc6eaLi5ogr7lSmDA0+xVfCdSgu5SRDY7+EUKsRfPJiACGGP4KDQ2O+hnv/tJMML/LlzJ7TnrEz0ajb4TwYshDXuXvF6sAWef6bJIfyWyfSghJP/wQGJYBpBnzOvDhjSTOP8kuTfSFhvSz9blTMwr8Ar7Cjc7qhKHrFRcaxbeUSqaBTQrUcGvMQWsPDyam5yRE7+kUw/6NbamiVyU+lPYawfqSGqLl9WjHkhBdpPG972Phd7n0Dz779U0WB3cLQTLwD7FsQaUUNIu8wZu0yBOg1+NnQHSIfJ29DTdvjPXaYD8nGHQQFy8Htvgdlnie4/vlzbB15GcTZ2t4XJ4FWINgkbXHSnFx4/JsrIGmh8nwkGuROCLUPOYkBCDhtndiAUoLslx12HfOJnBXSc9+bruNo+6I6s6eoHPb8+FkniVsTzNREJARAsEtcsGzQj7jARSV/xedF+lmthVaZ/AA4PzgDJk32O0IYD6sy6zTbKvbhOac=";
                displayName = "The King";
                break;
            case 1:
                texture = "eyJ0aW1lc3RhbXAiOjE1MzM2NjkyODU5NjIsInByb2ZpbGVJZCI6Ijc5ZTZhZDhhOTJjNjRjNDg4ZTNhMDhmYTNlOTEyMTViIiwicHJvZmlsZU5hbWUiOiJSZWRBRFYiLCJzaWduYXR1cmVSZXF1aXJlZCI6dHJ1ZSwidGV4dHVyZXMiOnsiU0tJTiI6eyJ1cmwiOiJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2QwMWMwMWM3MzdiMjcyOTE0Yjk4Njk2NzFlNzViM2IzNTFhZmQzODUwMDU0OTJjOTllZDU5NDVmNzc3MzBjOTMiLCJtZXRhZGF0YSI6eyJtb2RlbCI6InNsaW0ifX19fQ==";
                signature = "IuoKwQuu5VRr5nxq5kF+uRFTc9Qq0hLSmc9td0bYaGBfxIjTHlsks8J4eskE6AtqHDJpTRXvwlfa00MuoSjFvXBQ15l6yHaCtz9Vxx56WoP7b2bDPD59qYbR6jn2GPLmaUzW/DPJW4IZfiUUA+aDXFHA3iyg38b2FQYVvUqiQ6zMKUm0gEIzLEGKVg9meLBiPtDUDTM0Eyurn4l7CQzqCblk7FkJ/J8xIHunrYtSOi8ABzjnkjd5aBU3y2orGkm/VrEnWTgQmgsA2lIERQamWlfcDvnpy2rXi7I7YLo3TujkZSzM+h30uvz8qYwIMWuwzoNjf0ppA7d6XP+dcyfVhYIkJbR1WDbswi2KG9xI/9EO6drzOveKYFW3sbQ6OeLglth3CGjXN/TJ8K34iqlI+FIuA9glJexFeJGx3mIN+nhFDbd77Ei+KGf0rH8LqdXYKPNHJyiCRW+3CgtTvYMNWFAXlmbIwKWHTnwjvwh0I+HfcQIMGdoyHxmfs2joTVzzKcOI1SqID248esqrccHPKftV+qCPUSvKlyWMGEqVFyah530yVVUYPSlmO3fkgMlnZsXE/MceQ9iIjcfm7+PlZgZAITnkH46cAbjYqLOEATiG0P3/qn/DAmY/xnBrarrzJi4iMjqj8cEvFQM8E3BTDHulbkbl6lIdcXWMGH1uVA0=";
                displayName = "Princess Blondie";
                break;
            case 2:
                texture = "eyJ0aW1lc3RhbXAiOjE1MzM2NTI1MDk4ODcsInByb2ZpbGVJZCI6IjBiZTU2MmUxNzIyODQ3YmQ5MDY3MWYxNzNjNjA5NmNhIiwicHJvZmlsZU5hbWUiOiJ4Y29vbHgzIiwic2lnbmF0dXJlUmVxdWlyZWQiOnRydWUsInRleHR1cmVzIjp7IlNLSU4iOnsidXJsIjoiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS80NjhhMDY2MTBlMTIyZTQ3M2RlZGQyZmVhMjA4NjkwZTE3M2I4MWQzMzYwODJjMDA2NzM1YzUwMWI3ODU1Y2M0In19fQ==";
                signature = "qTpK7ZNAHvKAXqE3ChVEkVvPNH0keaUKP6WPbnK7fSnU7ylYJNVBf/0uf09Y0vJzED61pkspXCpPzn2kKKF8co0jbAJl91N+8/ejorBBnUUrc4xxaPTyATnHoi5ouaYCaV7CJJ52Fm5cegcfSAM34AqAnoXazuvU24weeD1wszdEpBiHIwuJstnnCiROzkeyrDkr6bP87LDmDjMGToqFuturMDAPBYP7sPJYJpIbKfD8Zoqr7NRioAMEDSzuGfbk3TeRea9X6joyUavkrYHkb5sPaXavYYTfRhYHTnh2magtilyni6P7MJI4c9qjIwQbBHLqoQN8KceBdgwC3VgyXkr/SMMJtsuZ0bUgcLFlYufDwyr4y04r5cFCqtiIOPmllD4QoGArxEMkwy/LlncmZgkKC6Wi4to+gfzAe0ncZjvx6Jvs3rkNlIQsTRsUPcSis+vNl+jjtzK5/5Sp5UjeHTCeRfp28n+vr8rxKRPsN4eUBERhMfsMScSXB+tSY992RbeVV8d7wTHCe1YUiDIEwEOp6clRASLP/opHpgTWT4XunoUbKrjoiXODwMMofnVouK7/T5kj/7ySl4fmFJlCggHtDKPFGujbSCcfyw5rf9u5tkCJs/TUpJf5Zd3H+RFwZuF4DLeEkdRrcip2B/+njLKxR8I7JoYNeZXY+5GH8HA=";
                displayName = "Dumbledore";
                break;
            default: return;
            }
            UUID uuid;
            if (false) {
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
            GameProfile profile = new GameProfile(uuid, displayName);
            if (texture != null) {
                profile.getProperties().put("texture", new Property("textures", texture, signature));
            }
            entity = new EntityPlayer(minecraftServer, worldServer, profile, new PlayerInteractManager(worldServer));
            entity.setPosition(location.getX(), location.getY(), location.getZ());
            // All skin layers
            entity.getDataWatcher().set(new DataWatcherObject(13, DataWatcherRegistry.a(0)), (byte)0x7e);
            this.valid = true;
            break;
        case MOB:
            entity = EntityTypes.a(entityType.getName()).a(worldServer);
            entity.setPosition(location.getX(), location.getY(), location.getZ());
            // Name
            if (displayName != null) {
                entity.getDataWatcher().set(new DataWatcherObject(2, DataWatcherRegistry.a(3)), Optional.of(new ChatComponentText(displayName)));
            }
            if (entityType == EntityType.BAT) {
                entity.getDataWatcher().set(new DataWatcherObject(12, DataWatcherRegistry.a(0)), (byte)0x0);
            }
            this.valid = true;
            break;
        case BLOCK:
            entity = new EntityFallingBlock(worldServer, location.getX(), location.getY(), location.getZ(), CraftMagicNumbers.getBlock(material).getBlockData());
            entity.getDataWatcher().set(new DataWatcherObject(2, DataWatcherRegistry.d), Optional.of(new ChatComponentText(material.name())));
            // No Gravity
            entity.getDataWatcher().set(new DataWatcherObject(5, DataWatcherRegistry.i), Boolean.TRUE);
            entity.setPosition(location.getX(), location.getY(), location.getZ());
            this.valid = true;
            break;
        default:
            this.valid = false;
            throw new IllegalStateException("Unhandled type: " + type);
        }
        this.id = entity.getId();
        lastLocation = location.clone();
        trackLocation = location.clone();
        this.headYaw = location.getYaw();
        this.lastHeadYaw = headYaw;
    }

    void startPlayerWatch(Player player) {
        PlayerConnection connection = ((CraftPlayer)player).getHandle().playerConnection;
        switch (type) {
        case PLAYER:
            connection.sendPacket(new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER, (EntityPlayer)entity));
            connection.sendPacket(new PacketPlayOutNamedEntitySpawn((EntityPlayer)entity));
            connection.sendPacket(new PacketPlayOutEntityHeadRotation(entity, (byte)((int)((headYaw % 360.0f) * 256.0f / 360.0f))));
            Bukkit.getScheduler().runTaskLater(NPCPlugin.getInstance(), () ->
                                               connection.sendPacket(new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER, (EntityPlayer)entity)), 1l);
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

    void stopPlayerWatch(Player player) {
        PlayerConnection connection = ((CraftPlayer)player).getHandle().playerConnection;
        switch(type) {
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

    boolean isBlockedAt(Location location) {
        float width2 = entity.width * 0.5f;
        float height = entity.length;
        Location locA = location.clone().add(-width2, 0.0, -width2);
        Location locB = location.clone().add(width2, height, width2);
        int ax = locA.getBlockX();
        int bx = locB.getBlockX();
        int ay = locA.getBlockY();
        int by = locB.getBlockY();
        int az = locA.getBlockZ();
        int bz = locB.getBlockZ();
        for (int y = ay; y <= by; y += 1) {
            for (int z = az; z <= bz; z += 1) {
                for (int x = ax; x <= bx; x += 1) {
                    org.bukkit.block.Block block = location.getWorld().getBlockAt(x, y, z);
                    if (block.isEmpty()) continue;
                    org.bukkit.Material mat = block.getType();
                    if (Tag.SLABS.isTagged(mat) && location.getY() - (double)y >= 0.5) continue;
                    if (Tag.STAIRS.isTagged(mat) && location.getY() - (double)y >= 0.5) continue;
                    if (mat.isOccluding()) return true;
                    if (mat.isSolid()) return true;
                }
            }
        }
        return false;
    }

    // Called when the NPC is added to the list of NPCs, before it
    // is ticked for the first time
    public void onEnable() {
        setup();
    }

    // Called when the NPC is removed from the list of NPCs, after it
    // was ticked for the final time.
    public void onDisable() {
        switch (type) {
        case PLAYER: case MOB: case BLOCK:
            for (Player player: playerWatchers) {
                if (!player.isValid()) continue;
                stopPlayerWatch(player);
            }
            playerWatchers.clear();
            break;
        default:
            throw new IllegalStateException("Unhandled type: " + type);
        }
    }

    public void onTick() {
        if (job != null) performJob();
        movement();
        updateWatchers();
        ticksLived += 1;
    }

    void performJob() {
        switch (job) {
        case NONE: return;
        case WANDER:
            if (turn == 0) {
                dir = random.nextFloat() * 2.0f - 1.0f;
                speed = Math.max(0.25, random.nextDouble());
                turn = random.nextInt(100) + random.nextInt(100);
                for (Player player: playerWatchers) {
                    PlayerConnection connection = ((CraftPlayer)player).getHandle().playerConnection;
                    connection.sendPacket(new PacketPlayOutAnimation(entity, 0));
                }
            }
            turn -= 1;
            if (isBlockedAt(location) || location.getBlock().isLiquid()) {
                location = location.add(0.0, 0.1, 0.0);
            } else {
                for (int i = 0; i < 6; i += 1) {
                    Location downward = location.clone().add(0.0, -0.1, 0.0);
                    if (!isBlockedAt(downward)) {
                        location = downward;
                        onGround = false;
                    } else {
                        onGround = true;
                        break;
                    }
                }
            }
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
                location.setYaw(location.getYaw() + 3.0f * dir);
            } else if (!isBlockedAt(forward.add(0.0, 0.5, 0.0))) {
                location = forward;
            } else {
                location.setYaw(location.getYaw() + 10.0f * dir);
            }
            if (location.getYaw() > 360.0f) location.setYaw(location.getYaw() - 360.0f);
            if (location.getYaw() < 0.0f) location.setYaw(location.getYaw() + 360.0f);
            headYaw = location.getYaw();
            headYaw = location.getYaw() + Math.sin((double)System.nanoTime() * 0.000000001) * 45.0;
            location.setPitch((float)(Math.sin((double)System.nanoTime() * 0.000000001) * 22.5));
            onGround = true;
            break;
        default:
            throw new IllegalStateException("Unhandled Job: " + job);
        }
    }

    void movement() {
        switch (type) {
        case PLAYER: case MOB: case BLOCK:
            boolean didMove =
                location.getX() != lastLocation.getX()
                || location.getY() != lastLocation.getY()
                || location.getZ() != lastLocation.getZ();
            boolean didTurn =
                location.getPitch() != lastLocation.getPitch()
                || location.getYaw() != lastLocation.getYaw();
            boolean didMoveHead = headYaw != lastHeadYaw;
            boolean doTeleport = false;
            double distance = lastLocation.distanceSquared(location);
            doTeleport |= ticksLived == 0;
            doTeleport |= didMove && distance >= 64;
            doTeleport |= locationError >= 0.125;
            doTeleport |= locationMoved > 1024.0;
            doTeleport |= !didMove && locationMoved > 16.0;
            if (doTeleport) {
                System.out.println("TELEPORT");
                entity.setPosition(location.getX(), location.getY(), location.getZ());
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
                entity.setPosition(trackLocation.getX(), trackLocation.getY(), trackLocation.getZ());
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

    void updateWatchers() {
        Set<UUID> watcherIds = new HashSet<>();
        for (Iterator<Player> iter = playerWatchers.iterator(); iter.hasNext(); ) {
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

    public void onInteract(Player player) {
        player.sendMessage("Hello World");
    }
}

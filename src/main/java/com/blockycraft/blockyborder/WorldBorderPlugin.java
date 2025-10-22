package com.blockycraft.blockyborder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Event.Priority;
import org.bukkit.event.player.PlayerListener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class WorldBorderPlugin extends JavaPlugin {

    private static final Logger LOG = Logger.getLogger("Minecraft");
    private final PlayerListener playerListener = new BorderPlayerListener();
    private Properties cfg = new Properties();

    // Configuration values
    private boolean enabled;
    private boolean loopEnabled;
    private double minX, maxX, minZ, maxZ;
    private double buffer;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        loadConfig();

        if (enabled) {
            getServer().getPluginManager().registerEvent(Event.Type.PLAYER_MOVE, playerListener, Priority.Normal, this);
            LOG.info("[WorldBorder] Enabled. Border is at (" + minX + ", " + minZ + ") to (" + maxX + ", " + maxZ + "). Loop mode: " + loopEnabled);
        } else {
            LOG.info("[WorldBorder] Plugin is disabled in config.properties.");
        }
    }

    @Override
    public void onDisable() {
        LOG.info("[WorldBorder] Disabled.");
    }
    
    /**
     * Teleports a player safely to a new location.
     * It finds the highest solid block at the destination X/Z to prevent both
     * suffocation and fall damage.
     * @param player The player to teleport.
     * @param location The desired destination location.
     */
    private void safeTeleport(Player player, Location location) {
        World world = location.getWorld();
        int x = location.getBlockX();
        int z = location.getBlockZ();

        // Always find the highest solid block to land on.
        // This prevents both fall damage (high to low) and suffocation (low to high).
        int highestY = world.getHighestBlockYAt(x, z);
        
        // Set the new location to be safely on top of that block.
        location.setY(highestY + 1.2); // 1.2 provides a small buffer above the ground
        
        player.teleport(location);
    }

    private void loadConfig() {
        File configFile = new File(getDataFolder(), "config.properties");

        if (!configFile.exists()) {
            try {
                cfg.setProperty("enabled", "true");
                cfg.setProperty("loop", "false");
                cfg.setProperty("x1", "-1000");
                cfg.setProperty("z1", "-1000");
                cfg.setProperty("x2", "1000");
                cfg.setProperty("z2", "1000");
                cfg.setProperty("buffer", "2.0");

                FileOutputStream os = new FileOutputStream(configFile);
                cfg.store(os, "WorldBorder Plugin Configuration\n" +
                              "# 'enabled': true/false - Turn the plugin on or off.\n" +
                              "# 'loop': true/false - If true, players teleport to the opposite border. If false, they are blocked.\n" +
                              "# 'x1', 'z1', 'x2', 'z2': The two corners of the border rectangle.\n" +
                              "# 'buffer': The distance from the border a player is teleported to when looping.");
                os.close();
            } catch (Exception e) {
                LOG.warning("[WorldBorder] Could not write default config: " + e.getMessage());
            }
        }

        try {
            FileInputStream is = new FileInputStream(configFile);
            cfg.load(is);
            is.close();
        } catch (Exception e) {
            LOG.warning("[WorldBorder] Could not read config: " + e.getMessage());
        }

        this.enabled = boolProp("enabled", true);
        this.loopEnabled = boolProp("loop", false);
        
        double x1 = doubleProp("x1", -1000.0);
        double z1 = doubleProp("z1", -1000.0);
        double x2 = doubleProp("x2", 1000.0);
        double z2 = doubleProp("z2", 1000.0);

        this.minX = Math.min(x1, x2);
        this.maxX = Math.max(x1, x2);
        this.minZ = Math.min(z1, z2);
        this.maxZ = Math.max(z1, z2);
        
        this.buffer = doubleProp("buffer", 2.0);
    }

    private boolean boolProp(String key, boolean def) {
        String val = cfg.getProperty(key, String.valueOf(def));
        return val.equalsIgnoreCase("true");
    }

    private double doubleProp(String key, double def) {
        try {
            return Double.parseDouble(cfg.getProperty(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private class BorderPlayerListener extends PlayerListener {
        @Override
        public void onPlayerMove(PlayerMoveEvent event) {
            Location to = event.getTo();
            double toX = to.getX();
            double toZ = to.getZ();

            if (toX < minX || toX > maxX || toZ < minZ || toZ > maxZ) {
                Player player = event.getPlayer();

                if (loopEnabled) {
                    Location newLoc = to.clone();
                    
                    if (toX < minX) newLoc.setX(maxX - buffer);
                    else if (toX > maxX) newLoc.setX(minX + buffer);

                    if (toZ < minZ) newLoc.setZ(maxZ - buffer);
                    else if (toZ > maxZ) newLoc.setZ(minZ + buffer);
                    
                    // --- BEGIN FIX ---
                    // We MUST load the chunk before calling safeTeleport.
                    // safeTeleport queries the chunk for the highest block,
                    // which will crash the server if the chunk isn't generated.
                    
                    World world = newLoc.getWorld();
                    
                    // Convert block coordinates to chunk coordinates
                    int chunkX = newLoc.getBlockX() >> 4; // (same as / 16)
                    int chunkZ = newLoc.getBlockZ() >> 4; // (same as / 16)

                    // Check if the chunk is loaded. If not, load (and generate) it.
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        LOG.info("[WorldBorder] Player " + player.getName() + 
                                 " is looping to an ungenerated chunk. Forcing generation at (" + 
                                 chunkX + ", " + chunkZ + ")...");
                        
                        // This is the magic call. It will load OR generate the chunk.
                        world.loadChunk(chunkX, chunkZ); 
                    }
                    // --- END FIX ---
                    
                    // Now it's safe to teleport
                    safeTeleport(player, newLoc);

                } else {
                    // Your original 'block' logic (no changes needed)
                    Location from = event.getFrom();
                    Location safePos = from.clone();
                    safePos.setX(Math.max(minX, Math.min(maxX, from.getX())));
                    safePos.setZ(Math.max(minZ, Math.min(maxZ, from.getZ())));

                    if (safePos.getX() != from.getX() || safePos.getZ() != from.getZ()) {
                        safeTeleport(player, safePos);
                    } else {
                        safeTeleport(player, from);
                    }
                }
            }
        }
    }
}
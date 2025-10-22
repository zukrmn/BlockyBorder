package com.blockycraft.blockyborder;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerListener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class BlockyBorder extends JavaPlugin {
    private static final Logger LOG = Logger.getLogger("Minecraft");
    private final BorderPlayerListener playerListener = new BorderPlayerListener();
    private Properties cfg = new Properties();

    private boolean enabled;
    private boolean loopEnabled;
    private double minX, maxX, minZ, maxZ;
    private double buffer;
    private static final int IGNORE_TICKS = 3;
    private final Map<UUID, Integer> ignoreBorderTicks = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        loadConfig();
        getServer().getPluginManager().registerEvent(
            org.bukkit.event.Event.Type.PLAYER_MOVE,
            playerListener,
            org.bukkit.event.Event.Priority.Normal,
            this
        );
        LOG.info("[BlockyBorder] Enabled. Border is at (" + minX + "," + minZ + ") to (" + maxX + "," + maxZ + "). Loop mode: " + loopEnabled);
        getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            public void run() { borderIgnoreTick(); }
        }, 1L, 1L);
    }

    @Override
    public void onDisable() {
        LOG.info("[BlockyBorder] Disabled.");
    }

    private void loadConfig() {
        File configFile = new File(getDataFolder(), "config.properties");
        if (!configFile.exists()) {
            try {
                cfg.setProperty("enabled", "true");
                cfg.setProperty("loop", "true");
                cfg.setProperty("x1", "-5333");
                cfg.setProperty("z1", "-2647");
                cfg.setProperty("x2", "5291");
                cfg.setProperty("z2", "2595");
                cfg.setProperty("buffer", "2.0");
                FileOutputStream os = new FileOutputStream(configFile);
                cfg.store(os, "BlockyBorder Config");
                os.close();
            } catch (Exception e) {
                LOG.warning("[BlockyBorder] Could not write default config: " + e.getMessage());
            }
        }
        try {
            FileInputStream is = new FileInputStream(configFile);
            cfg.load(is);
            is.close();
        } catch (Exception e) {
            LOG.warning("[BlockyBorder] Could not read config: " + e.getMessage());
        }
        this.enabled = boolProp("enabled", true);
        this.loopEnabled = boolProp("loop", true);

        double x1 = doubleProp("x1", -5333.0);
        double z1 = doubleProp("z1", -2647.0);
        double x2 = doubleProp("x2", 5291.0);
        double z2 = doubleProp("z2", 2595.0);

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

    private void safeTeleport(Player player, Location location) {
        World world = location.getWorld();
        int x = location.getBlockX();
        int z = location.getBlockZ();
        int highestY = world.getHighestBlockYAt(x, z);
        location.setY(highestY + 1.2);
        ignoreBorderTicks.put(player.getUniqueId(), IGNORE_TICKS);
        player.teleport(location);
    }

    public void borderIgnoreTick() {
        Iterator<Map.Entry<UUID, Integer>> it = ignoreBorderTicks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            int ticks = entry.getValue() - 1;
            if (ticks <= 0) {
                it.remove();
            } else {
                entry.setValue(ticks);
            }
        }
    }

    private class BorderPlayerListener extends PlayerListener {
        @Override
        public void onPlayerMove(PlayerMoveEvent event) {
            if (!enabled) return;
            Player player = event.getPlayer();
            if (ignoreBorderTicks.containsKey(player.getUniqueId())) return;

            Location to = event.getTo();
            double toX = to.getX(), toZ = to.getZ();
            if (toX < minX || toX > maxX || toZ < minZ || toZ > maxZ) {
                if (loopEnabled) {
                    Location newLoc = to.clone();
                    if (toX < minX) newLoc.setX(maxX - buffer);
                    else if (toX > maxX) newLoc.setX(minX + buffer);
                    if (toZ < minZ) newLoc.setZ(maxZ - buffer);
                    else if (toZ > maxZ) newLoc.setZ(minZ + buffer);

                    World world = newLoc.getWorld();
                    int chunkX = newLoc.getBlockX() >> 4;
                    int chunkZ = newLoc.getBlockZ() >> 4;
                    world.loadChunk(chunkX, chunkZ);
                    safeTeleport(player, newLoc);
                } else {
                    Location from = event.getFrom();
                    Location safePos = from.clone();
                    safePos.setX(Math.max(minX, Math.min(maxX, from.getX())));
                    safePos.setZ(Math.max(minZ, Math.min(maxZ, from.getZ())));
                    safeTeleport(player, safePos);
                }
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("fill")) return false;
        World world = getServer().getWorlds().get(0);
        int pad = 0;
        if (args.length >= 1) {
            try { pad = Integer.parseInt(args[0]); } catch (Exception ignored) {}
        }

        int cminX = ((int) minX >> 4) - pad, cmaxX = ((int) maxX >> 4) + pad;
        int cminZ = ((int) minZ >> 4) - pad, cmaxZ = ((int) maxZ >> 4) + pad;

        BukkitScheduler scheduler = getServer().getScheduler();
        int taskId = scheduler.scheduleSyncRepeatingTask(this,
            new PrePopulateTask(world, cminX, cmaxX, cminZ, cmaxZ, sender), 0L, 2L);
        PrePopulateTask.setTaskId(taskId);
        sender.sendMessage("[BlockyBorder] Iniciando geração automática de todo o território!");
        return true;
    }

    // Task que percorre todas as faixas Z, automatizando o populate
    static class PrePopulateTask implements Runnable {
        private final World world;
        private final int cminX, cmaxX, cminZ, cmaxZ;
        private final CommandSender sender;
        private int curZ, curX;
        private static int taskId = -1;

        public static void setTaskId(int id) { taskId = id; }

        PrePopulateTask(World w, int cminX, int cmaxX, int cminZ, int cmaxZ, CommandSender sender) {
            this.world = w; this.cminX = cminX; this.cmaxX = cmaxX; this.cminZ = cminZ; this.cmaxZ = cmaxZ; this.sender = sender;
            this.curZ = cminZ; this.curX = cminX;
        }

        public void run() {
            if (curZ > cmaxZ) {
                sender.sendMessage("[BlockyBorder] Geração populada COMPLETA em todas as faixas!");
                Bukkit.getScheduler().cancelTask(taskId);
                return;
            }
            Player fakePlayer = buscaFakePlayerOnline(world);
            if (fakePlayer == null) {
                sender.sendMessage("[BlockyBorder] Nenhum jogador online! Execute com pelo menos 1 player.");
                Bukkit.getScheduler().cancelTask(taskId);
                return;
            }
            if (curX > cmaxX) {
                curX = cminX;
                curZ++;
                return;
            }
            int blockX = (curX << 4) + 8;
            int blockZ = (curZ << 4) + 8;
            Location target = new Location(world, blockX, 200, blockZ);
            fakePlayer.teleport(target);
            sender.sendMessage("[BlockyBorder] Populating chunk ("+curX+","+curZ+") via teleport.");
            curX++;
        }

        private Player buscaFakePlayerOnline(World w) {
            for (Player p : w.getPlayers()) return p;
            return null;
        }
    }
}

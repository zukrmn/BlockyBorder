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

    // Bordas e modo loop
    private boolean enabled;
    private boolean loopEnabled;
    private double minX, maxX, minZ, maxZ;
    private double buffer;
    private static final int IGNORE_TICKS = 3; // ticks para ignorar checagem pós-teleporte
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
                // Coordenadas corretas:
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

        // Agora, usa os valores corretos do arquivo
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

    // Teleporte seguro, sempre no topo
    private void safeTeleport(Player player, Location location) {
        World world = location.getWorld();
        int x = location.getBlockX();
        int z = location.getBlockZ();
        int highestY = world.getHighestBlockYAt(x, z);
        location.setY(highestY + 1.2); // buffer
        ignoreBorderTicks.put(player.getUniqueId(), IGNORE_TICKS); // ignora por alguns ticks
        player.teleport(location);
    }

    // Tick handler para remover ignorados
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

    // Listener Corrigido
    private class BorderPlayerListener extends PlayerListener {
        @Override
        public void onPlayerMove(PlayerMoveEvent event) {
            if (!enabled) return;
            Player player = event.getPlayer();
            if (ignoreBorderTicks.containsKey(player.getUniqueId())) return; // ignora checagem pós-teleporte

            Location to = event.getTo();
            double toX = to.getX(), toZ = to.getZ();
            // Fora da borda?
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
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        world.loadChunk(chunkX, chunkZ);
                    }
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

    // Comando /fill
    // Sintaxe: /fill <freq> <pad>
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("fill")) return false;
        int freq = 5; // chunks por tick
        int pad = 0;
        if (args.length >= 1) {
            try { freq = Math.max(1, Integer.parseInt(args[0])); } catch (Exception ignored) {}
        }
        if (args.length >= 2) {
            try { pad = Integer.parseInt(args[1]); } catch (Exception ignored) {}
        }
        sender.sendMessage("[BlockyBorder] Pré-geração dos chunks iniciada...");
        World world = getServer().getWorlds().get(0);

        int cminX = ((int) minX >> 4) - pad, cmaxX = ((int) maxX >> 4) + pad;
        int cminZ = ((int) minZ >> 4) - pad, cmaxZ = ((int) maxZ >> 4) + pad;

        BukkitScheduler scheduler = getServer().getScheduler();
        scheduler.scheduleSyncRepeatingTask(this,
            new FillTask(world, cminX, cmaxX, cminZ, cmaxZ, freq, sender),
            0L, 1L
        );
        return true;
    }

    // Task para geração dos chunks (usando Runnable padrão)
    class FillTask implements Runnable {
        private final World world;
        private final int maxX, minZ, maxZ, freq;
        private final CommandSender sender;
        private int curX, curZ;
        private final int total;
        private int done;

        FillTask(World w, int minX, int maxX, int minZ, int maxZ, int freq, CommandSender sender) {
            this.world = w; this.maxX = maxX; this.minZ = minZ; this.maxZ = maxZ; this.freq = freq; this.sender = sender;
            this.curX = minX; this.curZ = minZ;
            this.total = (maxX - minX + 1) * (maxZ - minZ + 1); this.done = 0;
        }

        public void run() {
            int count = 0;
            while (count < freq && curX <= maxX) {
                if (curZ > maxZ) { curZ = minZ; curX++; continue; }
                if (!world.isChunkLoaded(curX, curZ)) { world.loadChunk(curX, curZ); }
                done++; count++;
                curZ++;
            }
            if (done % 100 == 0) sender.sendMessage("[BlockyBorder] Chunks gerados: " + done + "/" + total);
            if (curX > maxX) {
                sender.sendMessage("[BlockyBorder] Pré-geração concluída. Chunks gerados: " + done);
            }
        }
    }
}

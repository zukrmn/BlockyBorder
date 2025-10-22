package com.blockycraft.blockyborder;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerListener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

public class BlockyBorder extends JavaPlugin {
    private static final Logger LOG = Logger.getLogger("Minecraft");
    private final BorderPlayerListener playerListener = new BorderPlayerListener();
    private Properties cfg = new Properties();

    private boolean enabled, loopEnabled;
    private double minX, maxX, minZ, maxZ, buffer;
    private static final int IGNORE_TICKS = 3;
    private final Map<UUID, Integer> ignoreBorderTicks = new ConcurrentHashMap<>();

    private File progressFile;

    public void onEnable() {
        getDataFolder().mkdirs();
        loadConfig();
        progressFile = new File(getDataFolder(), "progress_step.dat");
        getServer().getPluginManager().registerEvent(Event.Type.PLAYER_MOVE, (Listener)this.playerListener, Event.Priority.Normal, (Plugin)this);
        LOG.info("[BlockyBorder] Enabled. Border is at (" + this.minX + "," + this.minZ + ") to (" + this.maxX + "," + this.maxZ + "). Loop mode: " + this.loopEnabled);
        getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            public void run() { BlockyBorder.this.borderIgnoreTick(); }
        }, 1L, 1L);
    }

    public void onDisable() {
        LOG.info("[BlockyBorder] Disabled.");
    }

    private void loadConfig() {
        File configFile = new File(getDataFolder(), "config.properties");
        if (!configFile.exists())
            try {
                this.cfg.setProperty("enabled", "true");
                this.cfg.setProperty("loop", "true");
                this.cfg.setProperty("x1", "-5333");
                this.cfg.setProperty("z1", "-2647");
                this.cfg.setProperty("x2", "5291");
                this.cfg.setProperty("z2", "2595");
                this.cfg.setProperty("buffer", "2.0");
                FileOutputStream os = new FileOutputStream(configFile);
                this.cfg.store(os, "BlockyBorder Config");
                os.close();
            } catch (Exception e) {
                LOG.warning("[BlockyBorder] Could not write default config: " + e.getMessage());
            }
        try {
            FileInputStream is = new FileInputStream(configFile);
            this.cfg.load(is);
            is.close();
        } catch (Exception e) {
            LOG.warning("[BlockyBorder] Could not read config: " + e.getMessage());
        }
        this.enabled = boolProp("enabled", true);
        this.loopEnabled = boolProp("loop", true);
        double x1 = doubleProp("x1", -5333.0D), z1 = doubleProp("z1", -2647.0D);
        double x2 = doubleProp("x2", 5291.0D), z2 = doubleProp("z2", 2595.0D);
        this.minX = Math.min(x1, x2);
        this.maxX = Math.max(x1, x2);
        this.minZ = Math.min(z1, z2);
        this.maxZ = Math.max(z1, z2);
        this.buffer = doubleProp("buffer", 2.0D);
    }

    private boolean boolProp(String key, boolean def) {
        String val = this.cfg.getProperty(key, String.valueOf(def));
        return val.equalsIgnoreCase("true");
    }

    private double doubleProp(String key, double def) {
        try {
            return Double.parseDouble(this.cfg.getProperty(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void safeTeleport(Player player, Location location) {
        World world = location.getWorld();
        int x = location.getBlockX(), z = location.getBlockZ();
        int highestY = world.getHighestBlockYAt(x, z);
        location.setY(highestY + 1.2D);
        this.ignoreBorderTicks.put(player.getUniqueId(), Integer.valueOf(3));
        forcePopulate(world, x >> 4, z >> 4);
        player.teleport(location);
    }

    public void borderIgnoreTick() {
        Iterator<Map.Entry<UUID, Integer>> it = this.ignoreBorderTicks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            int ticks = ((Integer)entry.getValue()).intValue() - 1;
            if (ticks <= 0) {
                it.remove();
                continue;
            }
            entry.setValue(Integer.valueOf(ticks));
        }
    }

    private void forcePopulate(World world, int chunkX, int chunkZ) {
        if (!world.isChunkLoaded(chunkX, chunkZ))
            world.loadChunk(chunkX, chunkZ);
        Chunk c = world.getChunkAt(chunkX, chunkZ);
        Random rand = new Random(world.getSeed());
        rand.setSeed(chunkX * 341873128712L + chunkZ * 132897987541L);
        for (BlockPopulator pop : world.getPopulators())
            pop.populate(world, rand, c);
    }

    private class BorderPlayerListener extends PlayerListener {
        private BorderPlayerListener() {}
        public void onPlayerMove(PlayerMoveEvent event) {
            if (!BlockyBorder.this.enabled) return;
            Player player = event.getPlayer();
            if (BlockyBorder.this.ignoreBorderTicks.containsKey(player.getUniqueId())) return;
            Location to = event.getTo();
            double toX = to.getX(), toZ = to.getZ();
            if (toX < BlockyBorder.this.minX || toX > BlockyBorder.this.maxX || toZ < BlockyBorder.this.minZ || toZ > BlockyBorder.this.maxZ)
                if (BlockyBorder.this.loopEnabled) {
                    Location newLoc = to.clone();
                    if (toX < BlockyBorder.this.minX) newLoc.setX(BlockyBorder.this.maxX - BlockyBorder.this.buffer);
                    else if (toX > BlockyBorder.this.maxX) newLoc.setX(BlockyBorder.this.minX + BlockyBorder.this.buffer);
                    if (toZ < BlockyBorder.this.minZ) newLoc.setZ(BlockyBorder.this.maxZ - BlockyBorder.this.buffer);
                    else if (toZ > BlockyBorder.this.maxZ) newLoc.setZ(BlockyBorder.this.minZ + BlockyBorder.this.buffer);
                    World world = newLoc.getWorld();
                    int chunkX = newLoc.getBlockX() >> 4;
                    int chunkZ = newLoc.getBlockZ() >> 4;
                    if (!world.isChunkLoaded(chunkX, chunkZ))
                        world.loadChunk(chunkX, chunkZ);
                    BlockyBorder.this.forcePopulate(world, chunkX, chunkZ);
                    BlockyBorder.this.safeTeleport(player, newLoc);
                } else {
                    Location from = event.getFrom(), safePos = from.clone();
                    safePos.setX(Math.max(BlockyBorder.this.minX, Math.min(BlockyBorder.this.maxX, from.getX())));
                    safePos.setZ(Math.max(BlockyBorder.this.minZ, Math.min(BlockyBorder.this.maxZ, from.getZ())));
                    BlockyBorder.this.safeTeleport(player, safePos);
                }
        }
    }

    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("fill"))
            return false;
        int freq = 5, pad = 0, step = 50000;
        if (args.length >= 1)
            try { freq = Math.max(1, Integer.parseInt(args[0])); } catch (Exception ignored) {}
        if (args.length >= 2)
            try { pad = Integer.parseInt(args[1]); } catch (Exception ignored) {}
        if (args.length >= 3)
            try { step = Math.max(freq, Integer.parseInt(args[2])); } catch (Exception ignored) {}
        sender.sendMessage("[BlockyBorder] Pré-geração dos chunks iniciada em steps!");

        World world = getServer().getWorlds().get(0);
        int cminX = ((int)this.minX >> 4) - pad, cmaxX = ((int)this.maxX >> 4) + pad;
        int cminZ = ((int)this.minZ >> 4) - pad, cmaxZ = ((int)this.maxZ >> 4) + pad;

        int total = (cmaxX - cminX + 1) * (cmaxZ - cminZ + 1);
        int totalSteps = (int)Math.ceil((double)total / step);

        int[] restore = readProgress(cminX, cminZ);
        FillStepTask fillTask = new FillStepTask(world, cminX, cmaxX, cminZ, cmaxZ, freq, sender, step, total, totalSteps, restore[0], restore[1], 0);
        int taskId = getServer().getScheduler().scheduleSyncRepeatingTask(this, fillTask, 0L, 1L);
        fillTask.setTaskId(taskId);
        return true;
    }

    private int[] readProgress(int defaultX, int defaultZ) {
        try (DataInputStream dis = new DataInputStream(new FileInputStream(progressFile))) {
            int curX = dis.readInt();
            int curZ = dis.readInt();
            return new int[]{curX, curZ};
        } catch (Exception e) {
            return new int[]{defaultX, defaultZ};
        }
    }

    private void saveProgress(int x, int z) {
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(progressFile))) {
            dos.writeInt(x);
            dos.writeInt(z);
        } catch (Exception e) {}
    }

    class FillStepTask implements Runnable {
        private final World world;
        private final int minX, maxX, minZ, maxZ, freq, step, total, totalSteps;
        private final CommandSender sender;
        private int curX, curZ, done, thisStep;
        private int taskId;

        FillStepTask(World w, int minX, int maxX, int minZ, int maxZ, int freq, CommandSender sender, int step, int total, int totalSteps, int curX, int curZ, int done) {
            this.world = w;
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.freq = freq;
            this.sender = sender;
            this.step = step;
            this.total = total;
            this.totalSteps = totalSteps;
            this.curX = curX;
            this.curZ = curZ;
            this.done = done;
            this.thisStep = 0;
        }

        public void setTaskId(int id) { this.taskId = id; }

        public void run() {
            int count = 0;
            while (count < this.freq && this.curX <= this.maxX && thisStep < step && done < total) {
                if (this.curZ > this.maxZ) {
                    this.curZ = this.minZ;
                    this.curX++;
                    continue;
                }
                if (this.curX > this.maxX) break;
                if (!this.world.isChunkLoaded(this.curX, this.curZ))
                    this.world.loadChunk(this.curX, this.curZ);
                BlockyBorder.this.forcePopulate(this.world, this.curX, this.curZ);
                this.done++;
                count++;
                this.curZ++;
                this.thisStep++;
            }
            BlockyBorder.this.saveProgress(this.curX, this.curZ);

            if (this.curX > this.maxX || this.done >= this.total) {
                this.sender.sendMessage("[BlockyBorder] Mapa completo! Todos os steps executados.");
                if (progressFile.exists()) progressFile.delete();
                org.bukkit.Bukkit.getScheduler().cancelTask(this.taskId);
                return;
            }

            if (thisStep >= step) {
                this.sender.sendMessage("[BlockyBorder] Fim do step! Execute /fill novamente para continuar. Próxima coord: (" + this.curX + ", " + this.curZ + ")");
                org.bukkit.Bukkit.getScheduler().cancelTask(this.taskId);
            }
        }
    }
}

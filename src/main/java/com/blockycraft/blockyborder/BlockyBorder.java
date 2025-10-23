package com.blockycraft.blockyborder;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
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

public class BlockyBorder extends JavaPlugin {
    private static final Logger LOG = Logger.getLogger("Minecraft");
    private final BorderPlayerListener playerListener = new BorderPlayerListener();
    private Properties cfg = new Properties();

    private boolean enabled, loopEnabled;
    private double minX, maxX, minZ, maxZ, buffer;
    private static final int IGNORE_TICKS = 3;
    private final Map<UUID, Integer> ignoreBorderTicks = new ConcurrentHashMap<>();

    private File fillJobFile; // Usado para salvar progresso e resumir
    private boolean isFilling = false; 

    // --- Constantes Simplificadas ---
    private static final int DEFAULT_FILL_FREQ = 5;
    private static final int DEFAULT_FILL_PAD = 0;
    private static final int TASK_START_DELAY_TICKS = 20;
    private static final int TASK_REPEAT_TICKS = 1;
    private static final int LOG_FREQUENCY = 1000;

    public void onEnable() {
        getDataFolder().mkdirs();
        loadConfig(); 
        fillJobFile = new File(getDataFolder(), "fill_job.properties"); 

        getServer().getPluginManager().registerEvent(Event.Type.PLAYER_MOVE, (Listener)this.playerListener, Event.Priority.Normal, (Plugin)this);
        LOG.info("[BlockyBorder] Enabled. Border is at (" + this.minX + "," + this.minZ + ") to (" + this.maxX + "," + this.maxZ + "). Loop mode: " + this.loopEnabled);
        
        getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            public void run() { BlockyBorder.this.borderIgnoreTick(); }
        }, 1L, 1L);

        // Resume automaticamente se o servidor crashar
        resumeFillJob();
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
                this.cfg.setProperty("x1", "-5376");
                this.cfg.setProperty("z1", "-2688");
                this.cfg.setProperty("x2", "5376");
                this.cfg.setProperty("z2", "2688");
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
        double x1 = doubleProp("x1", -5376.0D), z1 = doubleProp("z1", -2688.0D);
        double x2 = doubleProp("x2", 5376.0D), z2 = doubleProp("z2", 2688.0D);
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
        this.ignoreBorderTicks.put(player.getUniqueId(), Integer.valueOf(BlockyBorder.IGNORE_TICKS));
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
        Chunk c = world.getChunkAt(chunkX, chunkZ); 
        if (c == null) {
            LOG.warning("[BlockyBorder] Falha ao carregar/gerar chunk em " + chunkX + "," + chunkZ);
            return; 
        }
        Random rand = new Random(world.getSeed());
        rand.setSeed(chunkX * 341873128712L + chunkZ * 132897987541L);
        for (BlockPopulator pop : world.getPopulators()) {
            pop.populate(world, rand, c);
        }
    }


    private class BorderPlayerListener extends PlayerListener {
        private BorderPlayerListener() {}
        public void onPlayerMove(PlayerMoveEvent event) {
            // ... (Código do Listener é o mesmo, sem alterações) ...
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
                    BlockyBorder.this.safeTeleport(player, newLoc);
                } else {
                    Location from = event.getFrom(), safePos = from.clone();
                    safePos.setX(Math.max(BlockyBorder.this.minX, Math.min(BlockyBorder.this.maxX, from.getX())));
                    safePos.setZ(Math.max(BlockyBorder.this.minZ, Math.min(BlockyBorder.this.maxZ, from.getZ())));
                    BlockyBorder.this.safeTeleport(player, safePos);
                }
        }
    }

    private void startFillTask(Properties jobProps) {
        World world = getServer().getWorlds().get(0);

        int freq = Integer.parseInt(jobProps.getProperty("freq"));
        int cmaxX = Integer.parseInt(jobProps.getProperty("cmaxX"));
        int cminZ = Integer.parseInt(jobProps.getProperty("cminZ"));
        int cmaxZ = Integer.parseInt(jobProps.getProperty("cmaxZ"));
        int total = Integer.parseInt(jobProps.getProperty("total"));
        int curX = Integer.parseInt(jobProps.getProperty("curX"));
        int curZ = Integer.parseInt(jobProps.getProperty("curZ"));
        int done = Integer.parseInt(jobProps.getProperty("done"));

        this.isFilling = true;
        
        FillTaskContinuous fillTask = new FillTaskContinuous(world, jobProps, cmaxX, cminZ, cmaxZ, freq, total, curX, curZ, done);
        int taskId = getServer().getScheduler().scheduleSyncRepeatingTask(this, fillTask, TASK_START_DELAY_TICKS, TASK_REPEAT_TICKS);
        fillTask.setTaskId(taskId);
    }

    private void resumeFillJob() {
        if (!fillJobFile.exists()) {
            return; 
        }

        Properties jobProps = new Properties();
        try (FileInputStream fis = new FileInputStream(fillJobFile)) {
            jobProps.load(fis);
        } catch (Exception e) {
            LOG.warning("[BlockyBorder] Falha ao ler arquivo de job: " + e.getMessage());
            return;
        }

        if (jobProps.getProperty("running", "false").equals("true")) {
            LOG.info("[BlockyBorder] Continuando trabalho de pré-geração contínua...");
            startFillTask(jobProps);
        }
    }

    public void saveFillJob(Properties jobProps) {
        try (FileOutputStream fos = new FileOutputStream(fillJobFile)) {
            jobProps.store(fos, "BlockyBorder Fill Job State");
        } catch (Exception e) {
            LOG.warning("[BlockyBorder] Falha ao salvar progresso do job: " + e.getMessage());
        }
    }


    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("fill"))
            return false;

        if (this.isFilling) {
            sender.sendMessage("§c[BlockyBorder] Um trabalho de pré-geração já está em andamento!");
            return true;
        }

        // Removido o argumento [step]
        int freq = DEFAULT_FILL_FREQ, pad = DEFAULT_FILL_PAD;
        if (args.length >= 1)
            try { freq = Math.max(1, Integer.parseInt(args[0])); } catch (Exception ignored) {}
        if (args.length >= 2)
            try { pad = Integer.parseInt(args[1]); } catch (Exception ignored) {}

        int cminX = ((int)this.minX >> 4) - pad, cmaxX = ((int)this.maxX >> 4) + pad;
        int cminZ = ((int)this.minZ >> 4) - pad, cmaxZ = ((int)this.maxZ >> 4) + pad;

        int total = (cmaxX - cminX + 1) * (cmaxZ - cminZ + 1);

        Properties jobProps = new Properties();
        jobProps.setProperty("running", "true");
        jobProps.setProperty("freq", String.valueOf(freq));
        jobProps.setProperty("pad", String.valueOf(pad));
        jobProps.setProperty("cminX", String.valueOf(cminX));
        jobProps.setProperty("cmaxX", String.valueOf(cmaxX));
        jobProps.setProperty("cminZ", String.valueOf(cminZ));
        jobProps.setProperty("cmaxZ", String.valueOf(cmaxZ));
        jobProps.setProperty("total", String.valueOf(total));
        jobProps.setProperty("curX", String.valueOf(cminX)); 
        jobProps.setProperty("curZ", String.valueOf(cminZ)); 
        jobProps.setProperty("done", "0"); 
        // Removidas propriedades de "step"

        saveFillJob(jobProps);
        sender.sendMessage("§a[BlockyBorder] Iniciando pré-geração contínua.");
        sender.sendMessage("§aTotal de " + total + " chunks. O servidor pode congelar por longos períodos!");
        startFillTask(jobProps);

        return true;
    }


    /**
     * Tarefa de preenchimento contínua (sem steps)
     * Tenta descarregar chunks para aliviar a memória.
     */
    class FillTaskContinuous implements Runnable {
        private final World world;
        private final Properties jobProps;
        private final int maxX, minZ, maxZ, freq, total;
        private int curX, curZ, done;
        private int logCounter = 0;
        private int taskId;

        FillTaskContinuous(World w, Properties jobProps, int maxX, int minZ, int maxZ, int freq, int total, int curX, int curZ, int done) {
            this.world = w;
            this.jobProps = jobProps; 
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.freq = freq;
            this.total = total;
            this.curX = curX;
            this.curZ = curZ;
            this.done = done;
        }

        public void setTaskId(int id) { this.taskId = id; }

        public void run() {
            int count = 0;
            // Loop contínuo, sem verificação de 'thisStep'
            while (count < this.freq && this.curX <= this.maxX && done < total) {
                if (this.curZ > this.maxZ) {
                    this.curZ = this.minZ;
                    this.curX++;
                    continue;
                }
                if (this.curX > this.maxX) break;

                // 1. Popula o chunk
                BlockyBorder.this.forcePopulate(this.world, this.curX, this.curZ);
                
                // 2. Tenta descarregar o chunk anterior (se possível)
                // (Descarrega o chunk que acabamos de popular)
                this.world.unloadChunk(this.curX, this.curZ);

                this.done++;
                count++;
                this.curZ++;
                this.logCounter++;

                if (this.logCounter >= LOG_FREQUENCY) {
                    double percent = (double)this.done / this.total * 100.0;
                    LOG.info(String.format("[BlockyBorder] Progresso: %d / %d chunks (%.2f%%)", this.done, this.total, percent));
                    this.logCounter = 0;
                    
                    // Salva o progresso no log, para reduzir I/O
                    this.jobProps.setProperty("curX", String.valueOf(this.curX));
                    this.jobProps.setProperty("curZ", String.valueOf(this.curZ));
                    this.jobProps.setProperty("done", String.valueOf(this.done));
                    BlockyBorder.this.saveFillJob(this.jobProps);
                }
            }
            
            // Salva o progresso final do tick (se não foi salvo no log)
            if (this.logCounter > 0) {
                this.jobProps.setProperty("curX", String.valueOf(this.curX));
                this.jobProps.setProperty("curZ", String.valueOf(this.curZ));
                this.jobProps.setProperty("done", String.valueOf(this.done));
                BlockyBorder.this.saveFillJob(this.jobProps);
            }


            // O TRABALHO INTEIRO TERMINOU?
            if (this.curX > this.maxX || this.done >= this.total) {
                LOG.info("[BlockyBorder] Mapa completo! Todos os " + this.total + " chunks foram gerados.");
                
                this.jobProps.setProperty("running", "false"); // Marca como não-rodando
                BlockyBorder.this.saveFillJob(this.jobProps);
                // NOTA: Deixamos o arquivo de progresso, não deletamos.
                
                BlockyBorder.this.isFilling = false; 
                Bukkit.getScheduler().cancelTask(this.taskId);
                return;
            }

            // Não há mais lógica de "step", então a task continua indefinidamente.
        }
    }
}
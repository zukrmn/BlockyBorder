package com.blockycraft.blockygen;

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

public class BlockyGen extends JavaPlugin {
    private static final Logger LOG = Logger.getLogger("Minecraft");
    private final BorderPlayerListener playerListener = new BorderPlayerListener();
    private Properties cfg = new Properties();

    private boolean enabled, loopEnabled;
    private double minX, maxX, minZ, maxZ, buffer;
    private static final int IGNORE_TICKS = 3;
    private final Map<UUID, Integer> ignoreBorderTicks = new ConcurrentHashMap<>();

    private File fillJobFile;
    private boolean isFilling = false; 

    // --- Constantes para Polimento ---
    private static final int DEFAULT_FILL_FREQ = 5;
    private static final int DEFAULT_FILL_PAD = 0;
    private static final int DEFAULT_FILL_STEP = 40000;
    private static final int TASK_START_DELAY_TICKS = 20; // 1 segundo
    private static final int TASK_REPEAT_TICKS = 1;
    private static final int LOG_FREQUENCY = 1000;
    private static final int SHUTDOWN_SAVE_DELAY_TICKS = 20; // 1 segundo (Delay ANTES de salvar)
    private static final int SHUTDOWN_FINAL_DELAY_TICKS = 100; // 5 segundos (Delay DEPOIS de salvar)
    
    private static final int STEP_OVERLAP_COLUMNS = 10; // "Rebobina" 10 colunas (Chunks no eixo X)

    public void onEnable() {
        getDataFolder().mkdirs();
        loadConfig(); 
        fillJobFile = new File(getDataFolder(), "fill_job.properties"); 

        getServer().getPluginManager().registerEvent(Event.Type.PLAYER_MOVE, (Listener)this.playerListener, Event.Priority.Normal, (Plugin)this);
        LOG.info("[BlockyGen] Enabled. Border is at (" + this.minX + "," + this.minZ + ") to (" + this.maxX + "," + this.maxZ + "). Loop mode: " + this.loopEnabled);
        
        getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            public void run() { BlockyGen.this.borderIgnoreTick(); }
        }, 1L, 1L);

        resumeFillJob();
    }

    public void onDisable() {
        LOG.info("[BlockyGen] Disabled.");
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
                this.cfg.store(os, "BlockyGen Config");
                os.close();
            } catch (Exception e) {
                LOG.warning("[BlockyGen] Could not write default config: " + e.getMessage());
            }
        try {
            FileInputStream is = new FileInputStream(configFile);
            this.cfg.load(is);
            is.close();
        } catch (Exception e) {
            LOG.warning("[BlockyGen] Could not read config: " + e.getMessage());
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
        this.ignoreBorderTicks.put(player.getUniqueId(), Integer.valueOf(BlockyGen.IGNORE_TICKS));
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
            LOG.warning("[BlockyGen] Falha ao carregar/gerar chunk em " + chunkX + "," + chunkZ);
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
            if (!BlockyGen.this.enabled) return;
            Player player = event.getPlayer();
            if (BlockyGen.this.ignoreBorderTicks.containsKey(player.getUniqueId())) return;
            Location to = event.getTo();
            double toX = to.getX(), toZ = to.getZ();
            if (toX < BlockyGen.this.minX || toX > BlockyGen.this.maxX || toZ < BlockyGen.this.minZ || toZ > BlockyGen.this.maxZ)
                if (BlockyGen.this.loopEnabled) {
                    Location newLoc = to.clone();
                    if (toX < BlockyGen.this.minX) newLoc.setX(BlockyGen.this.maxX - BlockyGen.this.buffer);
                    else if (toX > BlockyGen.this.maxX) newLoc.setX(BlockyGen.this.minX + BlockyGen.this.buffer);
                    if (toZ < BlockyGen.this.minZ) newLoc.setZ(BlockyGen.this.maxZ - BlockyGen.this.buffer);
                    else if (toZ > BlockyGen.this.maxZ) newLoc.setZ(BlockyGen.this.minZ + BlockyGen.this.buffer);
                    BlockyGen.this.safeTeleport(player, newLoc);
                } else {
                    Location from = event.getFrom(), safePos = from.clone();
                    safePos.setX(Math.max(BlockyGen.this.minX, Math.min(BlockyGen.this.maxX, from.getX())));
                    safePos.setZ(Math.max(BlockyGen.this.minZ, Math.min(BlockyGen.this.maxZ, from.getZ())));
                    BlockyGen.this.safeTeleport(player, safePos);
                }
        }
    }

    private void startFillTask(Properties jobProps) {
        World world = getServer().getWorlds().get(0);

        int freq = Integer.parseInt(jobProps.getProperty("freq"));
        int step = Integer.parseInt(jobProps.getProperty("step"));
        int cmaxX = Integer.parseInt(jobProps.getProperty("cmaxX"));
        int cminZ = Integer.parseInt(jobProps.getProperty("cminZ"));
        int cmaxZ = Integer.parseInt(jobProps.getProperty("cmaxZ"));
        int total = Integer.parseInt(jobProps.getProperty("total"));
        int totalSteps = Integer.parseInt(jobProps.getProperty("totalSteps"));
        int curX = Integer.parseInt(jobProps.getProperty("curX"));
        int curZ = Integer.parseInt(jobProps.getProperty("curZ"));
        int done = Integer.parseInt(jobProps.getProperty("done"));
        int currentStep = Integer.parseInt(jobProps.getProperty("currentStep"));
        int thisStep = Integer.parseInt(jobProps.getProperty("thisStep", "0"));

        this.isFilling = true;
        
        FillStepTask fillTask = new FillStepTask(world, jobProps, cmaxX, cminZ, cmaxZ, freq, step, total, totalSteps, curX, curZ, done, currentStep, thisStep);
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
            LOG.warning("[BlockyGen] Falha ao ler arquivo de job: " + e.getMessage());
            return;
        }

        if (jobProps.getProperty("running", "false").equals("true")) {
            int currentStep = Integer.parseInt(jobProps.getProperty("currentStep", "1"));
            int totalSteps = Integer.parseInt(jobProps.getProperty("totalSteps", "1"));
            LOG.info("[BlockyGen] Continuando trabalho de pré-geração. Iniciando step " + currentStep + " de " + totalSteps + "...");
            startFillTask(jobProps);
        }
    }

    public void saveFillJob(Properties jobProps) {
        try (FileOutputStream fos = new FileOutputStream(fillJobFile)) {
            jobProps.store(fos, "BlockyGen Fill Job State");
        } catch (Exception e) {
            LOG.warning("[BlockyGen] Falha ao salvar progresso do job: " + e.getMessage());
        }
    }


    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("fill"))
            return false;

        if (this.isFilling) {
            sender.sendMessage("§c[BlockyGen] Um trabalho de pré-geração já está em andamento!");
            return true;
        }

        int freq = DEFAULT_FILL_FREQ, pad = DEFAULT_FILL_PAD, step = DEFAULT_FILL_STEP;
        if (args.length >= 1)
            try { freq = Math.max(1, Integer.parseInt(args[0])); } catch (Exception ignored) {}
        if (args.length >= 2)
            try { pad = Integer.parseInt(args[1]); } catch (Exception ignored) {}
        if (args.length >= 3)
            try { step = Math.max(freq, Integer.parseInt(args[2])); } catch (Exception ignored) {}

        int cminX = ((int)this.minX >> 4) - pad, cmaxX = ((int)this.maxX >> 4) + pad;
        int cminZ = ((int)this.minZ >> 4) - pad, cmaxZ = ((int)this.maxZ >> 4) + pad;

        int total = (cmaxX - cminX + 1) * (cmaxZ - cminZ + 1);
        int totalSteps = (int)Math.ceil((double)total / step);
        if (totalSteps == 0) totalSteps = 1;

        Properties jobProps = new Properties();
        jobProps.setProperty("running", "true");
        jobProps.setProperty("freq", String.valueOf(freq));
        jobProps.setProperty("pad", String.valueOf(pad));
        jobProps.setProperty("step", String.valueOf(step));
        jobProps.setProperty("cminX", String.valueOf(cminX));
        jobProps.setProperty("cmaxX", String.valueOf(cmaxX));
        jobProps.setProperty("cminZ", String.valueOf(cminZ));
        jobProps.setProperty("cmaxZ", String.valueOf(cmaxZ));
        jobProps.setProperty("total", String.valueOf(total));
        jobProps.setProperty("totalSteps", String.valueOf(totalSteps));
        jobProps.setProperty("curX", String.valueOf(cminX)); 
        jobProps.setProperty("curZ", String.valueOf(cminZ)); 
        jobProps.setProperty("done", "0"); 
        jobProps.setProperty("currentStep", "1"); 
        jobProps.setProperty("thisStep", "0"); 

        saveFillJob(jobProps);
        sender.sendMessage("§a[BlockyGen] Iniciando pré-geração automática.");
        sender.sendMessage("§aTotal de " + total + " chunks em " + totalSteps + " steps de " + step + " chunks.");
        startFillTask(jobProps);

        return true;
    }


    class FillStepTask implements Runnable {
        private final World world;
        private final Properties jobProps;
        private final int maxX, minZ, maxZ, freq, step, total, totalSteps;
        private int curX, curZ, done, thisStep, currentStep;
        private int logCounter = 0;
        private int taskId;

        FillStepTask(World w, Properties jobProps, int maxX, int minZ, int maxZ, int freq, int step, int total, int totalSteps, int curX, int curZ, int done, int currentStep, int thisStep) {
            this.world = w;
            this.jobProps = jobProps; 
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.freq = freq;
            this.step = step;
            this.total = total;
            this.totalSteps = totalSteps;
            this.curX = curX;
            this.curZ = curZ;
            this.done = done;
            this.currentStep = currentStep;
            this.thisStep = thisStep; 
        }

        public void setTaskId(int id) { this.taskId = id; }

        public void run() {
            int count = 0;
            
            // --- INÍCIO DA CORREÇÃO (Bug do "Travamento Zumbi") ---
            //
            // A condição "done < total" foi REMOVIDA do loop.
            // O contador 'done' não é mais confiável por causa da sobreposição (overlap).
            // O loop agora SÓ depende das coordenadas (curX <= maxX) e do step.
            //
            while (count < this.freq && this.curX <= this.maxX && thisStep < step) {
            // --- FIM DA CORREÇÃO ---
                
                if (this.curZ > this.maxZ) {
                    this.curZ = this.minZ;
                    this.curX++;
                    continue;
                }
                
                // Precisamos checar curX > maxX AQUI TAMBÉM, caso o curX++ acima
                // tenha sido o último.
                if (this.curX > this.maxX) break;

                BlockyGen.this.forcePopulate(this.world, this.curX, this.curZ);
                
                // 'done' agora é apenas para o log, não para a lógica.
                if (this.done < this.total) {
                    this.done++;
                }
                count++;
                this.curZ++;
                this.thisStep++;
                this.logCounter++;

                if (this.logCounter >= LOG_FREQUENCY) {
                    double percent = (double)this.done / this.total * 100.0;
                    // Limita a porcentagem a 99.99% se ainda não tiver terminado
                    if (percent >= 100.0 && this.curX <= this.maxX) {
                        percent = 99.99;
                    }
                    LOG.info(String.format("[BlockyGen] Progresso: %d / %d chunks (%.2f%%)", this.done, this.total, percent));
                    this.logCounter = 0;
                }
            }

            this.jobProps.setProperty("curX", String.valueOf(this.curX));
            this.jobProps.setProperty("curZ", String.valueOf(this.curZ));
            this.jobProps.setProperty("done", String.valueOf(this.done));
            this.jobProps.setProperty("thisStep", String.valueOf(this.thisStep));
            BlockyGen.this.saveFillJob(this.jobProps);

            // 1. O TRABALHO INTEIRO TERMINOU? (Baseado em Coordenadas)
            if (this.curX > this.maxX) {
                LOG.info("[BlockyGen] Mapa completo! Todas as colunas X foram processadas.");
                if (BlockyGen.this.fillJobFile.exists()) {
                    BlockyGen.this.fillJobFile.delete(); 
                }
                BlockyGen.this.isFilling = false; 
                Bukkit.getScheduler().cancelTask(this.taskId);
                return;
            }

            // 2. ESTE STEP TERMINOU (E O TRABALHO AINDA NÃO)?
            if (thisStep >= step) {
                LOG.info("[BlockyGen] Fim do step " + this.currentStep + "/" + this.totalSteps + ".");
                LOG.info("[BlockyGen] Agendando reinício gracioso com sobreposição (overlap)...");

                BlockyGen.this.isFilling = false;
                Bukkit.getScheduler().cancelTask(this.taskId);

                Bukkit.getScheduler().scheduleSyncDelayedTask(BlockyGen.this, 
                    new ShutdownTask(BlockyGen.this, this.jobProps, this.currentStep + 1), 
                    SHUTDOWN_SAVE_DELAY_TICKS); // Espera 1 segundo
                
                return; 
            }
        }
    }

    /**
     * Tarefa de Desligamento (Corrigida para o bug do 'done')
     */
    class ShutdownTask implements Runnable {
        private final BlockyGen plugin;
        private final Properties jobProps;
        private final int nextStep;

        ShutdownTask(BlockyGen plugin, Properties jobProps, int nextStep) {
            this.plugin = plugin;
            this.jobProps = jobProps;
            this.nextStep = nextStep;
        }

        public void run() {
            LOG.info("[BlockyGen] ShutdownTask: Iniciando processo de reinício.");

            // --- LÓGICA DE SOBREPOSIÇÃO (OVERLAP) ---
            int curX = Integer.parseInt(this.jobProps.getProperty("curX"));
            int cminX = Integer.parseInt(this.jobProps.getProperty("cminX"));
            int cminZ = Integer.parseInt(this.jobProps.getProperty("cminZ"));
            int cmaxZ = Integer.parseInt(this.jobProps.getProperty("cmaxZ"));

            int newCurX = curX - STEP_OVERLAP_COLUMNS;
            if (newCurX < cminX) {
                newCurX = cminX;
            }

            int colsRewound = curX - newCurX;
            int chunksInColumn = (cmaxZ - cminZ + 1);
            int chunksToSubtract = colsRewound * chunksInColumn;

            int done = Integer.parseInt(this.jobProps.getProperty("done"));
            int newDone = done - chunksToSubtract;
            if (newDone < 0) {
                newDone = 0;
            }

            LOG.info("[BlockyGen] ShutdownTask: Rebobinando " + colsRewound + " colunas (" + chunksToSubtract + " chunks).");
            LOG.info("[BlockyGen] ShutdownTask: Ajustando 'done' de " + done + " para " + newDone + ".");
            LOG.info("[BlockyGen] ShutdownTask: Próximo step começará em X=" + newCurX + " Z=" + cminZ);

            this.jobProps.setProperty("currentStep", String.valueOf(this.nextStep));
            this.jobProps.setProperty("thisStep", "0");
            this.jobProps.setProperty("curX", String.valueOf(newCurX)); 
            this.jobProps.setProperty("curZ", String.valueOf(cminZ));
            this.jobProps.setProperty("done", String.valueOf(newDone)); 

            // --- FIM DA LÓGICA DE SOBREPOSIÇÃO ---

            LOG.info("[BlockyGen] ShutdownTask: Forçando salvamento de todos os mundos e jogadores...");
            Bukkit.getServer().savePlayers();
            for (World w : Bukkit.getServer().getWorlds()) {
                w.save();
            }
            
            this.plugin.saveFillJob(this.jobProps);
            LOG.info("[BlockyGen] ShutdownTask: Salvamento completo. Desligando em 5 segundos...");

            this.plugin.getServer().getScheduler().scheduleSyncDelayedTask(this.plugin, new Runnable() {
                public void run() {
                    LOG.info("[BlockyGen] ShutdownTask: Desligando agora.");
                    Bukkit.getServer().shutdown();
                }
            }, SHUTDOWN_FINAL_DELAY_TICKS);
        }
    }
}
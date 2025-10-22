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

    // --- Variáveis de estado do Job ---
    private File fillJobFile;
    private boolean isFilling = false; // Trava para impedir /fill concorrente

    public void onEnable() {
        getDataFolder().mkdirs();
        loadConfig(); // Carrega config.properties da borda
        fillJobFile = new File(getDataFolder(), "fill_job.properties"); // Arquivo de estado do trabalho

        getServer().getPluginManager().registerEvent(Event.Type.PLAYER_MOVE, (Listener)this.playerListener, Event.Priority.Normal, (Plugin)this);
        LOG.info("[BlockyBorder] Enabled. Border is at (" + this.minX + "," + this.minZ + ") to (" + this.maxX + "," + this.maxZ + "). Loop mode: " + this.loopEnabled);
        
        getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            public void run() { BlockyBorder.this.borderIgnoreTick(); }
        }, 1L, 1L);

        // --- Lógica de Auto-Resume ---
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
                // --- COORDENADAS ATUALIZADAS AQUI ---
                this.cfg.setProperty("x1", "-5376");
                this.cfg.setProperty("z1", "-2688");
                this.cfg.setProperty("x2", "5376");
                this.cfg.setProperty("z2", "2688");
                // --- FIM DA ATUALIZAÇÃO ---
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
        
        // --- VALORES DE FALLBACK ATUALIZADOS AQUI ---
        double x1 = doubleProp("x1", -5376.0D), z1 = doubleProp("z1", -2688.0D);
        double x2 = doubleProp("x2", 5376.0D), z2 = doubleProp("z2", 2688.0D);
        // --- FIM DA ATUALIZAÇÃO ---

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

    // --- Listener de Borda ---
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
    // --- Fim do Listener de Borda ---


    /**
     * Inicia ou continua uma tarefa de preenchimento.
     * @param jobProps Propriedades da tarefa (lidas do arquivo ou novas)
     */
    private void startFillTask(Properties jobProps) {
        World world = getServer().getWorlds().get(0);

        // Extrai todos os parâmetros do jobProps
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

        this.isFilling = true;
        
        FillStepTask fillTask = new FillStepTask(world, jobProps, cmaxX, cminZ, cmaxZ, freq, step, total, totalSteps, curX, curZ, done, currentStep);
        int taskId = getServer().getScheduler().scheduleSyncRepeatingTask(this, fillTask, 20L, 1L); // 20L = delay de 1 seg para iniciar
        fillTask.setTaskId(taskId);
    }

    /**
     * Tenta resumir um trabalho de preenchimento ao iniciar o plugin.
     */
    private void resumeFillJob() {
        if (!fillJobFile.exists()) {
            return; // Nenhum trabalho para resumir
        }

        Properties jobProps = new Properties();
        try (FileInputStream fis = new FileInputStream(fillJobFile)) {
            jobProps.load(fis);
        } catch (Exception e) {
            LOG.warning("[BlockyBorder] Falha ao ler arquivo de job: " + e.getMessage());
            return;
        }

        if (jobProps.getProperty("running", "false").equals("true")) {
            int currentStep = Integer.parseInt(jobProps.getProperty("currentStep", "1"));
            int totalSteps = Integer.parseInt(jobProps.getProperty("totalSteps", "1"));
            LOG.info("[BlockyBorder] Continuando trabalho de pré-geração. Iniciando step " + currentStep + " de " + totalSteps + "...");
            startFillTask(jobProps);
        }
    }

    /**
     * Salva o estado atual do trabalho no arquivo .properties
     */
    private void saveFillJob(Properties jobProps) {
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

        // Parâmetros do comando
        int freq = 5, pad = 0, step = 40000;
        if (args.length >= 1)
            try { freq = Math.max(1, Integer.parseInt(args[0])); } catch (Exception ignored) {}
        if (args.length >= 2)
            try { pad = Integer.parseInt(args[1]); } catch (Exception ignored) {}
        if (args.length >= 3)
            try { step = Math.max(freq, Integer.parseInt(args[2])); } catch (Exception ignored) {}

        // Coordenadas do Mundo
        int cminX = ((int)this.minX >> 4) - pad, cmaxX = ((int)this.maxX >> 4) + pad;
        int cminZ = ((int)this.minZ >> 4) - pad, cmaxZ = ((int)this.maxZ >> 4) + pad;

        // Cálculos Iniciais
        int total = (cmaxX - cminX + 1) * (cmaxZ - cminZ + 1);
        int totalSteps = (int)Math.ceil((double)total / step);
        if (totalSteps == 0) totalSteps = 1;

        // Criar o novo arquivo de properties do Job
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
        jobProps.setProperty("curX", String.valueOf(cminX)); // Começa do início
        jobProps.setProperty("curZ", String.valueOf(cminZ)); // Começa do início
        jobProps.setProperty("done", "0"); // Começa do zero
        jobProps.setProperty("currentStep", "1"); // Começa do step 1

        // Salva e inicia
        saveFillJob(jobProps);
        sender.sendMessage("§a[BlockyBorder] Iniciando pré-geração automática.");
        sender.sendMessage("§aTotal de " + total + " chunks em " + totalSteps + " steps de " + step + " chunks.");
        startFillTask(jobProps);

        return true;
    }


    /**
     * Tarefa de preenchimento modificada para auto-restart e correções
     */
    class FillStepTask implements Runnable {
        private final World world;
        private final Properties jobProps; // Mantém o estado atual
        private final int maxX, minZ, maxZ, freq, step, total, totalSteps;
        private int curX, curZ, done, thisStep, currentStep;
        private int logCounter = 0; // Contador para log a cada 1000
        private int taskId;

        FillStepTask(World w, Properties jobProps, int maxX, int minZ, int maxZ, int freq, int step, int total, int totalSteps, int curX, int curZ, int done, int currentStep) {
            this.world = w;
            this.jobProps = jobProps; // Referência direta
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
            this.thisStep = 0; // Chunks feitos *neste step*
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

                BlockyBorder.this.forcePopulate(this.world, this.curX, this.curZ);
                
                this.done++;
                count++;
                this.curZ++;
                this.thisStep++;
                this.logCounter++;

                if (this.logCounter >= 1000) {
                    double percent = (double)this.done / this.total * 100.0;
                    LOG.info(String.format("[BlockyBorder] Progresso: %d / %d chunks (%.2f%%)", this.done, this.total, percent));
                    this.logCounter = 0;
                }
            }

            // Salva o progresso deste tick (curX, curZ, done)
            this.jobProps.setProperty("curX", String.valueOf(this.curX));
            this.jobProps.setProperty("curZ", String.valueOf(this.curZ));
            this.jobProps.setProperty("done", String.valueOf(this.done));
            BlockyBorder.this.saveFillJob(this.jobProps);

            // --- Verificação de Conclusão ---

            // 1. O TRABALHO INTEIRO TERMINOU?
            if (this.curX > this.maxX || this.done >= this.total) {
                LOG.info("[BlockyBorder] Mapa completo! Todos os " + this.total + " chunks foram gerados.");
                if (BlockyBorder.this.fillJobFile.exists()) {
                    BlockyBorder.this.fillJobFile.delete(); // Limpa o arquivo do job
                }
                BlockyBorder.this.isFilling = false; // Destrava
                Bukkit.getScheduler().cancelTask(this.taskId);
                return;
            }

            // 2. ESTE STEP TERMINOU (E O TRABALHO AINDA NÃO)?
            if (thisStep >= step) {
                LOG.info("[BlockyBorder] Fim do step " + this.currentStep + "/" + this.totalSteps + ".");
                
                this.currentStep++; // Prepara para o próximo step
                this.jobProps.setProperty("currentStep", String.valueOf(this.currentStep));
                BlockyBorder.this.saveFillJob(this.jobProps); // Salva o incremento do step

                LOG.info("[BlockFBorder] Reiniciando o servidor para continuar no próximo step...");
                BlockyBorder.this.isFilling = false; // Destrava (para o próximo onEnable)
                Bukkit.getScheduler().cancelTask(this.taskId);

                Bukkit.getServer().shutdown();
            }
        }
    }
}
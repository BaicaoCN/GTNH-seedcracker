package com.hermes.gtnhseed;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.opengl.GL11;
import net.minecraft.world.World;
import org.lwjgl.input.Keyboard;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;

/**
 * GTNH 种子证据采集器（纯客户端）
 * F9 开始/暂停扫描；扫描已加载区块的村庄/要塞特征，写入 config/gtnhseed/evidence.json
 */
@Mod(modid = "gtnhseed", name = "GTNH Seed Collector", version = "1.0.11", acceptableRemoteVersions = "*")
public class GTNHSeedCollector {

    public static final String MODID = "gtnhseed";
    private static final int PLANKS = 5, PANE = 102, TABLE = 58, BOOKSHELF = 47, STONEBRICK = 98, ENDFRAME = 120;

    /**
     * counts 数组布局：
     * 0 planks, 1 pane, 2 table, 3 bookshelf, 4 stonebrick, 5 endframe,
     * 6 LootGames 谜题大师, 7 蓝史莱姆泥土(空岛主体), 8 AE2 陨石箱, 9 AE2 天石
     */
    private static final int N_FIELDS = 10;

    /** 方块 id+meta -> 特征类型（6..9），-1 表示无关；按注册名解析，只解一次 */
    private static final Map<Integer, Integer> idType = new HashMap<>();

    private static int typeOf(Block b, int meta) {
        if (b == null) return -1;
        int ck = (Block.getIdFromBlock(b) << 4) | (meta & 15);
        Integer cached = idType.get(ck);
        if (cached != null) return cached;
        int t = -1;
        try {
            String n = Block.blockRegistry.getNameForObject(b);
            if (n != null) {
                String s = n.toLowerCase();
                if (s.contains("lootgamesmaster")) t = 6;                     // lootgames:LootGamesMasterBlock
                else if (s.contains("checked")) t = -1;
                else if (s.contains("craftedsoil") && (meta & 15) == 5) t = 7; // TConstruct:craftedSoil meta5 = 蓝史莱姆泥土
                else if (s.contains("slime.grass") || s.contains("slime.leaves") || s.contains("slime.gel")) t = 7;
                else if (s.contains("skychest")) t = 8;                       // appliedenergistics2:tile.BlockSkyChest
                else if (s.contains("skystone")) t = 9;                       // 天石（兜底）
            }
        } catch (Throwable ignored) { }
        idType.put(ck, t);
        return t;
    }

    private static KeyBinding toggleKey;
    private static KeyBinding hudKey;
    private static KeyBinding hudPosKey;
    private static int hudPos = 0;   // 0 左中 1 左上 2 右中 3 右上
    private static boolean hudVisible = true;
    private static int hudTick = 0;
    private static long lastHudToggle = 0, lastScanToggle = 0;
    /** 结构站点计数（跨区块结构只算一个） */
    private static final java.util.List<Set<Long>> siteKeys = new java.util.ArrayList<>();
    private static final int[] siteCount = new int[5];

    /** 实时计数缓存：0 村庄站点, 1 要塞, 2 LootGames, 3 空岛站点, 4 陨石 */
    private static final int[] live = new int[5];
    private static final int[] lastReport = new int[5];
    private static boolean scanning = false;
    private static int radius = 8;                   // 扫描半径（区块）
    private static int chunkBudgetPerTick = 1;       // 每 tick 扫几个区块

    /** chunkKey -> {planks, pane, table, bookshelf, stonebrick, endframe} */
    private static final Map<Long, int[]> counts = new HashMap<>();
    private static final Set<Long> scanned = new HashSet<>();
    private static final Deque<long[]> queue = new ArrayDeque<>();
    private static String lastWorld = "";
    private static String worldId = "unknown";
    private static String worldSig = "unknown";
    /** 手动锁定的证据文件（/gtnhseed use），非 null 时不再按指纹自动切换 */
    private static File pinnedFile = null;
    private static final Set<String> servers = new java.util.LinkedHashSet<>();
    private static int tickCounter = 0;
    private static int lastWrittenSize = -1;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (FMLCommonHandler.instance().getSide() != Side.CLIENT) return;
        toggleKey = new KeyBinding("key.gtnhseed.scan", Keyboard.KEY_F9, "key.categories.misc");
        ClientRegistry.registerKeyBinding(toggleKey);
        hudKey = new KeyBinding("key.gtnhseed.hud", Keyboard.KEY_F8, "key.categories.misc");
        ClientRegistry.registerKeyBinding(hudKey);
        hudPosKey = new KeyBinding("key.gtnhseed.hudpos", Keyboard.KEY_F7, "key.categories.misc");
        ClientRegistry.registerKeyBinding(hudPosKey);
        FMLCommonHandler.instance().bus().register(this);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);   // HUD 事件在 Forge 总线
        try {
            net.minecraftforge.client.ClientCommandHandler.instance.registerCommand(new CommandSeed());
            loadPin();
        } catch (Throwable ignored) { }
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent event) {
        if (hudPosKey != null && hudPosKey.isPressed()) {
            long now = System.currentTimeMillis();
            if (now - lastHudToggle < 300) return;
            lastHudToggle = now;
            hudPos = (hudPos + 1) % 4;
            String[] nm = {"左中", "左上", "右中", "右上"};
            message("§b[GTNH Seed] HUD 位置：" + nm[hudPos]);
            return;
        }
        if (hudKey != null && hudKey.isPressed()) {
            long now = System.currentTimeMillis();
            if (now - lastHudToggle < 300) return;
            lastHudToggle = now;
            hudVisible = !hudVisible;
            message(hudVisible ? "§a[GTNH Seed] HUD 已开启（F8 关闭）" : "§7[GTNH Seed] HUD 已关闭（F8 开启）");
            return;
        }
        if (toggleKey == null || !toggleKey.isPressed()) return;
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastScanToggle < 300) return;
        lastScanToggle = nowMs;
        scanning = !scanning;
        EntityPlayer p = Minecraft.getMinecraft().thePlayer;
        if (scanning) {
            queue.clear();
            refreshLive();
            System.arraycopy(live, 0, lastReport, 0, live.length);
            enqueueAround(p);
            message("§a[GTNH Seed] 开始扫描（半径 " + radius + " 区块），已加载区块 " + queue.size() + " 个");
        } else {
            message("§e[GTNH Seed] 暂停：已扫描 " + scanned.size() + " 区块 · LootGames " + typeCount(6)
                    + " · 空岛 " + typeCount(7) + " · 陨石 " + typeCount(8));
            writeEvidence();
            writeEvidence();
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !scanning) return;
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.theWorld;
        if (world == null || mc.thePlayer == null) return;
        if (world.provider.dimensionId != 0) return;   // 只统计主世界，避免和下界/末地混在一起

        String sig = mapSignature();
        if (pinnedFile != null) {
            if (!worldSig.equals("unknown") && !evidenceFile().getParentFile().exists()) evidenceFile().getParentFile().mkdirs();
        } else if (!sig.equals(worldSig)) {
            worldSig = sig;
            servers.clear();
            resetForWorld(sig);
            File f = evidenceFile();
            inheritExisting(f);
            servers.add(currentAddress());
            message("§b[GTNH Seed] 地图指纹 " + sig + " → " + f.getName()
                    + (scanned.isEmpty() ? "（新地图）" : ""));
        }

        // 持续扫描：队列空了就按玩家当前位置补一批；定期写盘
        tickCounter++;
        if (queue.isEmpty() && (tickCounter % 20 == 0)) {
            enqueueAround(mc.thePlayer);
            // 周期进度改由 HUD 显示（多人服不再刷聊天栏）
        }

        int n = 0;
        while (n < chunkBudgetPerTick && !queue.isEmpty()) {
            long[] c = queue.poll();
            scanChunk(world, (int) c[0], (int) c[1]);
            n++;
        }

        if (n > 0) {
            refreshLive();
            reportNewStructures();
        }

        if (tickCounter % 600 == 0 && scanned.size() != lastWrittenSize) {
            writeEvidence();
            lastWrittenSize = scanned.size();
        }
    }

    private static void enqueueAround(EntityPlayer p) {
        int pcx = (int) Math.floor(p.posX) >> 4;
        int pcz = (int) Math.floor(p.posZ) >> 4;
        World world = p.worldObj;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = pcx + dx, cz = pcz + dz;
                long key = key(cx, cz);
                if (scanned.contains(key)) continue;
                if (!world.getChunkProvider().chunkExists(cx, cz)) continue;   // 只看已加载
                queue.add(new long[]{cx, cz});
            }
        }
    }

    /** 当前世界的唯一标识：单人=存档文件夹，多人=服务器地址 */
    private static String detectWorldId() {
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (mc.isSingleplayer()) {
                String folder = null;
                if (mc.getIntegratedServer() != null) folder = mc.getIntegratedServer().getFolderName();
                if (folder == null && mc.theWorld != null) folder = mc.theWorld.getWorldInfo().getWorldName();
                return "sp-" + (folder == null ? "world" : folder);
            }
            ServerData sd = mc.func_147104_D();
            if (sd != null && sd.serverIP != null) return "mp-" + sd.serverIP;
            if (mc.theWorld != null) return "mp-" + mc.theWorld.getWorldInfo().getWorldName();
        } catch (Throwable ignored) { }
        return "unknown";
    }

    /** 文件名安全化（保留字母数字、下划线、短横线、中文） */
    private static String fileNameOf(String id) {
        StringBuilder sb = new StringBuilder();
        for (char ch : id.toCharArray()) {
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')
                    || ch == '-' || ch == '_' || (ch >= 0x4E00 && ch <= 0x9FFF)) sb.append(ch);
            else sb.append('_');
        }
        String r = sb.toString();
        while (r.contains("__")) r = r.replace("__", "_");
        return r.length() > 64 ? r.substring(0, 64) : r;
    }

    private static void resetForWorld(String id) {
        counts.clear();
        scanned.clear();
        queue.clear();
        lastWrittenSize = -1;
        written = false;
        worldId = id;
    }

    /** 当前连接地址（单人返回存档名） */
    private static String currentAddress() {
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (mc.isSingleplayer()) {
                return mc.getIntegratedServer() != null ? mc.getIntegratedServer().getFolderName() : "singleplayer";
            }
            ServerData sd = mc.func_147104_D();
            if (sd != null && sd.serverIP != null) return sd.serverIP;
        } catch (Throwable ignored) { }
        return "unknown";
    }

    /**
     * 地图指纹：世界类型 + 出生点（x,z）。
     * 同一张地图无论用哪个 IP / 域名连进来，这两个值都一样；
     * 同一台服务器的不同主世界（Multiverse 等）出生点和世界类型通常不同。
     */
    private static String mapSignature() {
        Minecraft mc = Minecraft.getMinecraft();
        World w = mc.theWorld;
        if (w == null) return "unknown";
        String wt = "?";
        try {
            if (w.getWorldInfo() != null && w.getWorldInfo().getTerrainType() != null)
                wt = w.getWorldInfo().getTerrainType().getWorldTypeName();
        } catch (Throwable ignored) { }
        int sx = 0, sz = 0;
        try {
            sx = w.getWorldInfo().getSpawnX();
            sz = w.getWorldInfo().getSpawnZ();
        } catch (Throwable ignored) { }
        return wt + "@" + sx + "," + sz;
    }

    private static File evidenceFile() {
        File dir = new File(Minecraft.getMinecraft().mcDataDir, "config/gtnhseed");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "evidence_" + fileNameOf(worldSig) + ".json");
    }

    /** 已有同指纹的证据文件 → 读出旧区块数据，实现"换 IP 仍然继承" */
    private static void inheritExisting(File f) {
        if (!f.exists()) return;
        try {
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher vm = java.util.regex.Pattern.compile("\"\\s*:\\s*(\\d+)").matcher(s);
            if (!vm.find() || Integer.parseInt(vm.group(1)) < 3) {
                message("§e[GTNH Seed] 旧版本证据（识别器有 bug），已忽略，重新扫描");
                return;
            }
            int ci = s.indexOf("\"chunks\"");
            if (ci > 0) {
                int lb = s.indexOf('[', ci);
                int depth = 0, rb = -1;
                for (int k = lb; k < s.length(); k++) {
                    char ch = s.charAt(k);
                    if (ch == '[') depth++;
                    else if (ch == ']') { depth--; if (depth == 0) { rb = k; break; } }
                }
                if (rb > lb) {
                    String body = s.substring(lb + 1, rb);
                    for (String part : body.split("\\]\\s*,\\s*\\[")) {
                        String t = part.replace("[", "").replace("]", "").trim();
                        if (t.isEmpty()) continue;
                        String[] fld = t.split("\\s*,\\s*");
                        if (fld.length < 12) continue;
                        try {
                            int cx = Integer.parseInt(fld[0]), cz = Integer.parseInt(fld[1]);
                            int[] c = new int[N_FIELDS];
                            for (int i = 0; i < N_FIELDS && i + 2 < fld.length; i++) c[i] = Integer.parseInt(fld[i + 2]);
                            long k = key(cx, cz);
                            counts.put(k, c);
                            scanned.add(k);
                            if (isVillageCandidate(c)) registerSite(0, cx, cz);
                            if (c[5] > 0) registerSite(1, cx, cz);
                            if (c[6] > 0) registerSite(2, cx, cz);
                            if (c[7] > 0) registerSite(3, cx, cz);
                            if (c[8] > 0 || c[9] >= 200) registerSite(4, cx, cz);
                        } catch (NumberFormatException ignored) { }
                    }
                }
            }
            // 继承历史地址列表（servers 字段，竖线分隔）
            int si = s.indexOf("\"servers\"");
            if (si > 0) {
                int q1 = s.indexOf('"', si);
                int q2 = q1 > 0 ? s.indexOf('"', q1 + 1) : -1;
                if (q1 > 0 && q2 > q1) {
                    for (String a : s.substring(q1 + 1, q2).split("\\|")) {
                        String v = a.trim();
                        if (!v.isEmpty()) servers.add(v);
                    }
                }
            }
            if (!scanned.isEmpty()) message("§a[GTNH Seed] 已继承同一张地图的旧数据：" + scanned.size() + " 区块");
        } catch (Exception e) {
            message("§c[GTNH Seed] 读取旧证据失败：" + e);
        }
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    private static void scanChunk(World world, int cx, int cz) {
        long k = key(cx, cz);
        if (scanned.contains(k)) return;
        int baseX0 = cx << 4, baseZ0 = cz << 4;
        // 区块可能已经被卸载：此时 getBlock 全返回空气，会得到"全 0"并把区块永久标记成已扫描
        if (!world.getChunkProvider().chunkExists(cx, cz) || !world.blockExists(baseX0, 64, baseZ0)) return;
        if (!world.blockExists(baseX0 + 15, 255, baseZ0 + 15)) return;
        scanned.add(k);
        int[] c = new int[N_FIELDS];
        int baseX = cx << 4, baseZ = cz << 4;
        for (int y = 0; y < 256; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    Block b = world.getBlock(baseX + x, y, baseZ + z);
                    if (b == null) continue;
                    int id = Block.getIdFromBlock(b);
                    if (id == 0) continue;
                    int meta = world.getBlockMetadata(baseX + x, y, baseZ + z);
                    switch (id) {
                        case PLANKS -> c[0]++;
                        case PANE -> c[1]++;
                        case TABLE -> c[2]++;
                        case BOOKSHELF -> c[3]++;
                        case STONEBRICK -> c[4]++;
                        case ENDFRAME -> c[5]++;
                        default -> {
                            int t = typeOf(b, meta);
                            if (t >= 0) c[t]++;
                        }
                    }
                }
            }
        }
        counts.put(k, c);
        if (isVillageCandidate(c)) registerSite(0, cx, cz);
        if (c[5] > 0) registerSite(1, cx, cz);
        if (c[6] > 0) registerSite(2, cx, cz);
        if (c[7] > 0) registerSite(3, cx, cz);
        if (c[8] > 0 || c[9] >= 200) registerSite(4, cx, cz);
    }

    private static boolean isVillageCandidate(int[] c) {
        return c[0] >= 15 && (c[1] >= 2 || c[2] >= 1 || c[3] >= 1) && c[4] < 10;
    }

    private static int villageCount() {
        int n = 0;
        for (int[] c : counts.values()) if (isVillageCandidate(c)) n++;
        return n;
    }

    private static int strongholdCount() {
        int n = 0;
        for (int[] c : counts.values()) if (c[5] > 0) n++;
        return n;
    }

    static {
        for (int i = 0; i < 5; i++) siteKeys.add(new HashSet<>());
    }

    /** 登记一个结构区块：与已有同类型区块相邻(±2)则并入同一站点，否则算新站点 */
    private static void registerSite(int idx, int cx, int cz) {
        Set<Long> keys = siteKeys.get(idx);
        long k = key(cx, cz);
        if (!keys.add(k)) return;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if ((dx | dz) == 0) continue;
                if (keys.contains(key(cx + dx, cz + dz))) return;   // 已属于某个站点
            }
        }
        siteCount[idx]++;
    }

    static java.util.List<File> listEvidenceFiles() {
        File dir = new File(Minecraft.getMinecraft().mcDataDir, "config/gtnhseed");
        File[] fs = dir.listFiles((d, n) -> n.startsWith("evidence") && n.endsWith(".json"));
        java.util.List<File> out = new java.util.ArrayList<>();
        if (fs != null) {
            out.addAll(java.util.Arrays.asList(fs));
            out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        }
        return out;
    }

    static String evidenceSummary(File f) {
        try {
            String t = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher w = java.util.regex.Pattern.compile("\"sig\"\\s*:\\s*\"([^\"]*)\"").matcher(t);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"scanned\"\\s*:\\s*(\\d+)").matcher(t);
            String sig = w.find() ? w.group(1) : "?";
            String sc = m.find() ? m.group(1) : "?";
            return sig + " · " + sc + " 区块";
        } catch (Exception e) { return ""; }
    }

    static void pinEvidence(File f) {
        pinnedFile = f;
        try {
            String t = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"sig\"\s*:\s*\"([^\"]*)\"").matcher(t);
            if (m.find()) worldSig = m.group(1);
        } catch (Exception ignored) { }
        savePin();
        servers.clear();
        resetForWorld(worldSig);
        inheritExisting(f);
    }

    static void unpinEvidence() { pinnedFile = null; savePin(); }

    static boolean isPinned() { return pinnedFile != null; }

    static String currentEvidenceName() { return evidenceFile().getName(); }

    static int scannedCount() { return scanned.size(); }

    static int typeCountPublic(int idx) { return typeCount(idx); }

    /** 清除玩家周围 r 区块内的扫描记录，让它们重新扫（修复"扫到空气"的区块） */
    static int rescanAround(int r) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return 0;
        int pcx = (int) Math.floor(mc.thePlayer.posX) >> 4;
        int pcz = (int) Math.floor(mc.thePlayer.posZ) >> 4;
        int n = 0;
        java.util.Iterator<Long> it = scanned.iterator();
        while (it.hasNext()) {
            long k = it.next();
            int cx = (int) (k >> 32), cz = (int) k;
            if (Math.abs(cx - pcx) <= r && Math.abs(cz - pcz) <= r) {
                it.remove();
                counts.remove(k);
                n++;
            }
        }
        rebuildSites();
        return n;
    }

    /** 站点计数从 counts 重建 */
    private static void rebuildSites() {
        for (int i = 0; i < 5; i++) siteKeys.get(i).clear();
        java.util.Arrays.fill(siteCount, 0);
        for (Map.Entry<Long, int[]> e : counts.entrySet()) {
            int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey();
            int[] c = e.getValue();
            if (isVillageCandidate(c)) registerSite(0, cx, cz);
            if (c[5] > 0) registerSite(1, cx, cz);
            if (c[6] > 0) registerSite(2, cx, cz);
            if (c[7] > 0) registerSite(3, cx, cz);
            if (c[8] > 0 || c[9] >= 200) registerSite(4, cx, cz);
        }
        refreshLive();
    }

    private static File pinFile() {
        return new File(Minecraft.getMinecraft().mcDataDir, "config/gtnhseed/pinned.txt");
    }

    private static void savePin() {
        try {
            File f = pinFile();
            if (pinnedFile == null) { f.delete(); return; }
            if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
            try (java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), java.nio.charset.StandardCharsets.UTF_8)) {
                w.write(pinnedFile.getName());
            }
        } catch (Exception ignored) { }
    }

    private static void loadPin() {
        try {
            File f = pinFile();
            if (!f.exists()) return;
            String name = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
            File target = new File(new File(Minecraft.getMinecraft().mcDataDir, "config/gtnhseed"), name);
            if (target.exists()) pinnedFile = target;
        } catch (Exception ignored) { }
    }

    private static void refreshLive() {
        live[0] = siteCount[0];
        live[1] = strongholdCount();
        live[2] = siteCount[2];
        live[3] = siteCount[3];
        live[4] = siteCount[4];
    }

    private static int typeCount(int idx) {
        int n = 0;
        for (int[] c : counts.values()) if (c[idx] > 0) n++;
        return n;
    }

    private static boolean written = false;

    /** 写入 config/gtnhseed/evidence.json（简单手写 JSON，避免依赖） */
    public static void writeEvidence() {
        try {
            File f = evidenceFile();
            StringBuilder sb = new StringBuilder();
            sb.append("{\n  \"mod\": \"gtnhseed\",\n  \"version\": 3,\n  \"scanned\": ").append(scanned.size()).append(",\n");
            sb.append("  \"world\": \"").append(worldId).append("\",\n");
            servers.add(currentAddress());
            sb.append("  \"sig\": \"").append(worldSig).append("\",\n");
            sb.append("  \"servers\": \"").append(String.join("|", servers)).append("\",\n");
            sb.append("  \"villages\": [");
            boolean first = true;
            for (Map.Entry<Long, int[]> e : counts.entrySet()) {
                if (!isVillageCandidate(e.getValue())) continue;
                int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey();
                if (!first) sb.append(",");
                sb.append("[").append(cx).append(",").append(cz).append("]");
                first = false;
            }
            sb.append("],\n  \"strongholds\": [");
            first = true;
            for (Map.Entry<Long, int[]> e : counts.entrySet()) {
                if (e.getValue()[5] <= 0) continue;
                int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey();
                if (!first) sb.append(",");
                sb.append("[").append(cx).append(",").append(cz).append("]");
                first = false;
            }
            sb.append("],\n  \"loot\": [");
            first = true;
            for (Map.Entry<Long, int[]> e : counts.entrySet()) {
                if (e.getValue()[6] <= 0) continue;
                int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey();
                if (!first) sb.append(",");
                sb.append("[").append(cx).append(",").append(cz).append("]");
                first = false;
            }
            sb.append("],\n  \"slimes\": [");
            first = true;
            for (Map.Entry<Long, int[]> e : counts.entrySet()) {
                if (e.getValue()[7] <= 0) continue;
                int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey();
                if (!first) sb.append(",");
                sb.append("[").append(cx).append(",").append(cz).append("]");
                first = false;
            }
            sb.append("],\n  \"meteors\": [");
            first = true;
            for (Map.Entry<Long, int[]> e : counts.entrySet()) {
                int[] cc = e.getValue();
                if (cc[8] <= 0 && cc[9] < 200) continue;
                int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey();
                if (!first) sb.append(",");
                sb.append("[").append(cx).append(",").append(cz).append("]");
                first = false;
            }
            sb.append("],\n  \"chunks\": [");
            first = true;
            for (Map.Entry<Long, int[]> e : counts.entrySet()) {
                int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey();
                int[] c = e.getValue();
                if (!first) sb.append(",");
                sb.append("[").append(cx).append(",").append(cz);
                for (int ci = 0; ci < N_FIELDS; ci++) sb.append(",").append(c[ci]);
                sb.append("]");
                first = false;
            }
            sb.append("]\n}\n");
            try (java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), java.nio.charset.StandardCharsets.UTF_8)) { w.write(sb.toString()); }
            written = true;
        } catch (Exception e) {
            message("§c[GTNH Seed] 写证据失败：" + e);
        }
    }

    /** 叠加层 HUD：左上角实时显示扫描进度（F8 开关） */
    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT
                && event.type != RenderGameOverlayEvent.ElementType.ALL) return;   // 两个阶段都兜底，保证一定画出来
        if (!hudVisible) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.fontRenderer == null) return;
        if (!scanning && scanned.isEmpty()) return;

        FontRenderer fr = mc.fontRenderer;
        // 证据强度：LootGames 8.8 bit / 空岛 11.5 bit（求解器实际使用）
        int bits10;
        // LootGames 8.8 · 空岛 11.5 · 陨石 10.3（三者都参与过滤）
        int core = 88 * live[2] + 115 * live[3] + 103 * live[4];
        if (live[2] >= 5) {
            bits10 = core;                                               // 此时村庄/要塞不参与
        } else {
            bits10 = core + 45 * live[0] + 82 * live[1];                 // 村庄按实测有效 4.5 bit
        }
        String verdict;
        if (bits10 >= 500) verdict = "§a可解 64 位";
        else if (bits10 >= 360) verdict = "§e需已知高16位（切片）";
        else verdict = "§c证据不足";

        String[] lines = new String[]{
                (scanning ? "§a扫描中" : "§e已暂停") + " §7· §f" + scanned.size() + " §7区块",
                "§7村庄 §f" + live[0] + " §7· 要塞 §f" + live[1],
                "§7LootGames §f" + live[2] + " §7· 空岛 §f" + live[3] + " §7· 陨石 §f" + live[4],
                "§7证据 §f" + (bits10 / 10) + "." + (bits10 % 10) + " bit §7→ " + verdict,
                "§8" + worldSig,
        };
        int w = 0;
        for (String l : lines) w = Math.max(w, fr.getStringWidth(l));
        int h = lines.length * 10 + 4;
        int sw = event.resolution.getScaledWidth(), sh = event.resolution.getScaledHeight();
        boolean right = hudPos >= 2, top = (hudPos == 1 || hudPos == 3);
        int x = right ? sw - w - 8 : 4;
        int y = top ? 4 : sh / 2;
        GL11.glPushMatrix();
        GL11.glEnable(GL11.GL_BLEND);
        Gui.drawRect(x - 2, y - 2, x + w + 4, y + h, 0x90000000);
        for (int i = 0; i < lines.length; i++) fr.drawStringWithShadow(lines[i], x, y + i * 10, 0xFFFFFF);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glPopMatrix();
    }

    /** 只在新结构出现时用绿色聊天提示（周期进度走 HUD） */
    private static void reportNewStructures() {
        StringBuilder sb = new StringBuilder();
        String[] names = {"村庄候选", "要塞", "LootGames", "史莱姆空岛", "陨石"};
        for (int i = 0; i < live.length; i++) {
            int d = live[i] - lastReport[i];
            if (d <= 0) continue;
            if (sb.length() > 0) sb.append("、");
            sb.append(names[i]).append(" +").append(d).append(" §7(共 ").append(live[i]).append(")");
            lastReport[i] = live[i];
        }
        if (sb.length() > 0) message("§a[GTNH Seed] 发现 §f" + sb);
    }

    private static void message(String s) {
        EntityPlayer p = Minecraft.getMinecraft().thePlayer;
        if (p != null) p.addChatMessage(new ChatComponentText(s));
    }
}

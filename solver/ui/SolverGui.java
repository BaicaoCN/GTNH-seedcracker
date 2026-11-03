import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** GTNH 种子求解器 GUI：读取游戏 mod 产出的 evidence.json → GPU 搜索 → 显示候选种子 */
public class SolverGui extends JFrame {

    static final java.io.File LOG_FILE = new java.io.File("gtnh-solver.log");
    static {   // 单文件超过 5 MB 就滚动一次，避免长跑日志无限增长
        try {
            if (LOG_FILE.length() > 5L * 1024 * 1024) {
                java.io.File old = new java.io.File("gtnh-solver.log.1");
                old.delete();
                LOG_FILE.renameTo(old);
            }
        } catch (Exception ignored) { }
    }
    static final java.io.File PROGRESS_FILE = new java.io.File("gtnh-solver-progress.txt");

    /** 断点指纹：证据文件 + 大小 + 修改时间 + 模式（任何一项变了，旧断点作废） */
    private static String resumeKey(String evPath, int bits, Integer slice) {
        File f = new File(evPath);
        return "optimized-v1|" + evPath + "|" + f.length() + "|" + f.lastModified() + "|" + bits + "|" + slice;
    }

    private static int readResume(String key) {
        try {
            if (!PROGRESS_FILE.exists()) return 0;
            java.util.List<String> lines = Files.readAllLines(PROGRESS_FILE.toPath(), StandardCharsets.UTF_8);
            if (lines.size() < 2 || !lines.get(0).trim().equals(key)) return 0;
            return Integer.parseInt(lines.get(1).trim().replace("nextSlice=", ""));
        } catch (Exception e) { return 0; }
    }

    private static void writeResume(String key, int nextSlice) {
        try (java.io.Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(PROGRESS_FILE, false), StandardCharsets.UTF_8)) {
            w.write(key + "\n" + "nextSlice=" + nextSlice + "\n");
        } catch (Exception ignored) { }
    }

    private final JTextField evidencePath = new JTextField(defaultEvidence());
    private final JLabel status = new JLabel("未加载证据");
    private final JRadioButton mode32 = new JRadioButton("快速：种子是 32 位整数（秒级）");
    private final JRadioButton modeSlice = new JRadioButton("加速：仅当你已知高 16 位（一般用不到）");
    private final JRadioButton mode64 = new JRadioButton("完整搜索：全部 2^48（耗时依设备）", true);
    private final JSpinner sliceSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 65535, 1));
    private final JSlider threshold = new JSlider(50, 90, 70);
    private final JButton startBtn = new JButton("开始搜索");
    private final JTextArea log = new JTextArea();
    private final JProgressBar progressBar = new JProgressBar(0, 1000);
    private final JLabel progressLabel = new JLabel("就绪");
    private final DefaultListModel<String> resultModel = new DefaultListModel<>();
    private volatile boolean cancelled = false;
    private final java.util.concurrent.atomic.AtomicLong startNanos = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong lastProgLog = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong sliceStartNanos = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong lastSliceMs = new java.util.concurrent.atomic.AtomicLong(0);
    private Thread worker;

    private List<List<int[]>> clusters = new ArrayList<>();
    private final javax.swing.table.DefaultTableModel clusterModel = new javax.swing.table.DefaultTableModel(
            new Object[]{"启用", "中心区块 (x,z)", "候选区块数"}, 0) {
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : Object.class; }
        @Override public boolean isCellEditable(int r, int c) { return c == 0; }
    };
    private List<int[]> villages = new ArrayList<>();
    private List<int[]> strongholds = new ArrayList<>();
    private List<int[]> loot = new ArrayList<>();
    private List<int[]> slimes = new ArrayList<>();
    private List<int[]> meteors = new ArrayList<>();
    private boolean nonRwg2 = false;
    private String worldTagStr = "";

    public SolverGui() {
        super("GTNH 种子求解器");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(760, 620);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(8, 8));
        ((JComponent) getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));

        // 顶部：证据文件
        JPanel top = new JPanel(new BorderLayout(6, 6));
        JPanel fileRow = new JPanel(new BorderLayout(6, 0));
        fileRow.add(new JLabel("证据文件："), BorderLayout.WEST);
        fileRow.add(evidencePath, BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton browse = new JButton("浏览…");
        JButton reload = new JButton("刷新");
        btns.add(browse); btns.add(reload);
        fileRow.add(btns, BorderLayout.EAST);
        top.add(fileRow, BorderLayout.NORTH);
        top.add(status, BorderLayout.SOUTH);

        // 中部：选项
        ButtonGroup g = new ButtonGroup();
        g.add(mode32); g.add(modeSlice); g.add(mode64);
        JPanel opts = new JPanel(new GridLayout(0, 1, 4, 4));
        opts.setBorder(BorderFactory.createTitledBorder("搜索设置"));
        opts.add(mode32);
        JPanel sliceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        sliceRow.add(modeSlice);
        sliceRow.add(new JLabel("低 48 位高 16 位："));
        sliceRow.add(sliceSpinner);
        opts.add(sliceRow);
        opts.add(mode64);

        JPanel thRow = new JPanel(new BorderLayout(6, 0));
        thRow.add(new JLabel("村庄匹配阈值："), BorderLayout.WEST);
        thRow.add(threshold, BorderLayout.CENTER);
        JLabel thVal = new JLabel("70%");
        threshold.addChangeListener(e -> thVal.setText(threshold.getValue() + "%"));
        thRow.add(thVal, BorderLayout.EAST);
        opts.add(thRow);
        JPanel runRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        runRow.add(startBtn);
        opts.add(runRow);

        // 底部：日志 + 结果
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logPane = new JScrollPane(log);
        logPane.setBorder(BorderFactory.createTitledBorder("日志"));

        JList<String> results = new JList<>(resultModel);
        results.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
        JScrollPane resPane = new JScrollPane(results);
        resPane.setBorder(BorderFactory.createTitledBorder("候选低 48 位（复制到群系终检/直接使用）"));
        resPane.setPreferredSize(new Dimension(760, 130));
        JPanel resWrap = new JPanel(new BorderLayout(6, 0));
        resWrap.add(resPane, BorderLayout.CENTER);
        JPanel resBtns = new JPanel(new GridLayout(0, 1, 0, 6));
        JButton copy = new JButton("复制");
        JButton clear = new JButton("清空");
        resBtns.add(copy); resBtns.add(clear);
        resWrap.add(resBtns, BorderLayout.EAST);

        JPanel progRow = new JPanel(new BorderLayout(8, 0));
        progressBar.setStringPainted(false);
        progRow.add(progressBar, BorderLayout.CENTER);
        progRow.add(progressLabel, BorderLayout.EAST);
        JPanel optWrap = new JPanel(new BorderLayout(0, 4));
        optWrap.add(opts, BorderLayout.CENTER);
        optWrap.add(progRow, BorderLayout.SOUTH);

        JTable clusterTable = new JTable(clusterModel);
        clusterTable.setRowHeight(22);
        clusterTable.getColumnModel().getColumn(0).setMaxWidth(50);
        clusterTable.getColumnModel().getColumn(2).setMaxWidth(110);
        JScrollPane clusterPane = new JScrollPane(clusterTable);
        clusterPane.setBorder(BorderFactory.createTitledBorder("村庄簇（取消勾选误判的）"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, clusterPane, logPane);
        split.setResizeWeight(0.35);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.add(optWrap, BorderLayout.NORTH);
        center.add(split, BorderLayout.CENTER);

        add(top, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(resWrap, BorderLayout.SOUTH);

        // 行为
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("evidence.json", "json"));
            File start = new File(evidencePath.getText()).getParentFile();
            if (start != null && start.exists()) fc.setCurrentDirectory(start);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                evidencePath.setText(fc.getSelectedFile().getAbsolutePath());
                loadEvidence();
            }
        });
        reload.addActionListener(e -> loadEvidence());
        copy.addActionListener(e -> {
            int i = results.getSelectedIndex();
            String v = i >= 0 ? results.getSelectedValue() : String.join("\n", Collections.list(resultModel.elements()));
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(v), null);
        });
        clear.addActionListener(e -> { resultModel.clear(); log.setText(""); });
        startBtn.addActionListener(e -> {
            if (worker != null && worker.isAlive()) { cancelled = true; startBtn.setText("开始搜索"); return; }
            startSearch();
        });

        loadEvidence();
    }

    private static String fmtEta(long sec) {
        if (sec <= 0) return "--";
        long h = sec / 3600, m = (sec % 3600) / 60;
        return h > 0 ? (h + " 小时 " + m + " 分") : (m + " 分 " + (sec % 60) + " 秒");
    }

    /** 递归找目录下最新的 evidence*.json（限制深度和文件数，避免扫全盘） */
    private static File newestEvidence(File dir, int depth, int[] budget) {
        if (dir == null || !dir.isDirectory() || depth < 0 || budget[0] <= 0) return null;
        File best = null;
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            if (budget[0]-- <= 0) break;
            if (f.isFile()) {
                String n = f.getName();
                if (n.startsWith("evidence") && n.endsWith(".json")) {
                    if (best == null || f.lastModified() > best.lastModified()) best = f;
                }
            }
        }
        for (File f : fs) {
            if (f.isDirectory() && depth > 0) {
                File sub = newestEvidence(f, depth - 1, budget);
                if (sub != null && (best == null || sub.lastModified() > best.lastModified())) best = sub;
            }
        }
        return best;
    }

    /** 自动定位证据文件：依次在启动器目录里找最新的 evidence*.json */
    private static String defaultEvidence() {
        String home = System.getProperty("user.home", ".");
        String appdata = System.getenv("APPDATA");
        java.util.List<File> roots = new java.util.ArrayList<>();
        roots.add(new File(System.getProperty("user.dir")));
        if (appdata != null) {
            roots.add(new File(appdata, "PrismLauncher"));
            roots.add(new File(appdata, ".minecraft/config/gtnhseed"));
            roots.add(new File(appdata, ".minecraft"));
        }
        roots.add(new File(home, ".minecraft/config/gtnhseed"));
        for (String drive : new String[]{"D:/", "E:/", "F:/", "C:/"}) {
            roots.add(new File(drive, "Minecraft/PrismLauncher/instances"));
            roots.add(new File(drive, "PrismLauncher/instances"));
            roots.add(new File(drive, "mc/PrismLauncher/instances"));
        }
        File best = null;
        for (File r : roots) {
            File f = newestEvidence(r, 6, new int[]{2000});
            if (f != null && (best == null || f.lastModified() > best.lastModified())) best = f;
        }
        if (best != null) return best.getAbsolutePath();
        return new File(System.getProperty("user.dir"), "evidence.json").getAbsolutePath();
    }

    private void loadEvidence() {
        villages = new ArrayList<>();
        strongholds = new ArrayList<>();
        loot = new ArrayList<>(); slimes = new ArrayList<>(); meteors = new ArrayList<>();
        File f = new File(evidencePath.getText().trim());
        if (!f.exists()) { status.setText("找不到证据文件：" + f.getAbsolutePath()); return; }
        try {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            villages = SolverCore.pairs(s, "villages");
            strongholds = SolverCore.pairs(s, "strongholds");
            loot = SolverCore.pairs(s, "loot");
            slimes = SolverCore.pairs(s, "slimes");
            meteors = SolverCore.pairs(s, "meteors");
            Matcher m = Pattern.compile("\"scanned\"\\s*:\\s*(\\d+)").matcher(s);
            String scanned = m.find() ? m.group(1) : "?";
            Matcher wm = Pattern.compile("\"world\"\\s*:\\s*\"([^\"]*)\"").matcher(s);
            worldTagStr = wm.find() ? wm.group(1) : "";
            String worldTag = worldTagStr.isEmpty() ? "" : " · 世界 " + worldTagStr;
            nonRwg2 = !worldTagStr.isEmpty() && !worldTagStr.contains("RWG2");
            clusters = SolverCore.clusterChunks(villages);
            clusterModel.setRowCount(0);
            for (List<int[]> c : clusters) {
                int sx = 0, sz = 0;
                for (int[] p : c) { sx += p[0]; sz += p[1]; }
                clusterModel.addRow(new Object[]{!nonRwg2,
                        (Math.round((float) sx / c.size())) + ", " + (Math.round((float) sz / c.size())), c.size()});
            }
            int cl = clusters.size();
            status.setText(String.format("证据：村庄候选 %d → 簇 %d · LootGames %d · 空岛 %d · 陨石 %d · 要塞 %d · 已扫描 %s 区块",
                    villages.size(), cl, loot.size(), slimes.size(), meteors.size(), strongholds.size(), scanned + worldTag));
            logLine("[证据] 载入 " + f.getName() + "：村庄候选 " + villages.size() + " → 簇 " + clusters.size()
                    + "，LootGames " + loot.size() + "，史莱姆空岛 " + slimes.size()
                    + "，陨石 " + meteors.size() + "，要塞 " + strongholds.size());
            if (nonRwg2) logLine("       注意：世界类型不是 RWG2_CONTINENTS，村庄/要塞公式可能不适用，已默认取消勾选村庄簇");
            logLine("        LootGames 约束权重最高（1/456），村庄可勾选/取消误判（比如自己的基地）");
        } catch (Exception ex) {
            status.setText("读取失败：" + ex.getMessage());
        }
    }

    private static List<int[]> parsePairs(String s, String field) {
        List<int[]> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\\[(.*?)\\]\\s*,", Pattern.DOTALL).matcher(s);
        if (!m.find()) {
            m = Pattern.compile("\"" + field + "\"\\s*:\\s*\\[(.*?)\\]\\s*\\}", Pattern.DOTALL).matcher(s);
            if (!m.find()) return out;
        }
        Matcher p = Pattern.compile("\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\]").matcher(m.group(1));
        while (p.find()) out.add(new int[]{Integer.parseInt(p.group(1)), Integer.parseInt(p.group(2))});
        return out;
    }

    private void startSearch() {
        List<List<int[]>> selected = new ArrayList<>();
        for (int i = 0; i < clusterModel.getRowCount() && i < clusters.size(); i++) {
            if (Boolean.TRUE.equals(clusterModel.getValueAt(i, 0))) selected.add(clusters.get(i));
        }
        if (selected.isEmpty() && loot.isEmpty()) { logLine("[错误] 至少勾选一个村庄簇，或者扫到 LootGames 地牢"); return; }
        final double th = threshold.getValue() / 100.0;
        Integer only = null;
        int bits = 32;
        if (modeSlice.isSelected()) { only = (Integer) sliceSpinner.getValue(); bits = 48; }
        else if (mode64.isSelected()) { bits = 48; }

        final int bitsF = bits;
        final Integer onlyF = only;
        final List<List<int[]>> v = new ArrayList<>(selected);
        final List<int[]> sh = new ArrayList<>(strongholds);
        final List<int[]> lt = new ArrayList<>(loot);
        final List<int[]> sl = new ArrayList<>(slimes);
        final List<int[]> mt = new ArrayList<>(meteors);
        final String rKey = resumeKey(evidencePath.getText().trim(), bits, only) + "|" + threshold.getValue() + "|" + java.util.Arrays.deepToString(v.stream().map(java.util.List::toArray).toArray());
        final int startSlice = readResume(rKey);
        cancelled = false;
        startNanos.set(System.nanoTime());
        sliceStartNanos.set(System.nanoTime());
        lastSliceMs.set(0);
        progressBar.setValue(0);
        progressLabel.setText("初始化…");
        startBtn.setText("停止");
        logLine("=== 开始搜索：勾选村庄簇 " + selected.size() + "（候选区块 " + villages.size() + "），LootGames " + loot.size() + "，要塞 " + strongholds.size()
                + "，阈值 " + threshold.getValue() + "%，模式 " + (only != null ? "切片 " + only : (bits == 32 ? "32 位" : "完整低 48 位")));

        worker = new Thread(() -> {
            SolverCore.Result r = SolverCore.solve(v, sh, lt, sl, mt, th, bitsF, onlyF, startSlice, new SolverCore.Listener() {
                public void log(String msg) { SwingUtilities.invokeLater(() -> logLine(msg)); }
                public void progress(long done, long total, long hits, int si, int slices) {
                    final long t0 = startNanos.get();
                    SwingUtilities.invokeLater(() -> {
                        double frac = slices > 1 ? (si + (double) done / total) / slices : (double) done / total;
                        progressBar.setValue((int) Math.min(999, frac * 1000));
                        double sec = lastSliceMs.get() / 1000.0;
                        double sps = sec > 0 ? 1.0 / sec : 0;
                        long remain = (long) ((slices - si) * Math.max(0.001, sec));
                        progressLabel.setText(String.format("%d/%d 片 · %.2f s/片 · 剩余 %s · 命中 %d",
                                si + 1, slices, sec, fmtEta(remain), hits));
                    });
                }
                public boolean cancelled() { return cancelled; }
                public void sliceDone(int nextSlice, int totalSlices, long seeds) {
                    writeResume(rKey, seeds == 0 ? nextSlice : 0);
                    long now = System.nanoTime();
                    if (sliceStartNanos.get() > 0) {
                        lastSliceMs.set((now - sliceStartNanos.get()) / 1_000_000L);
                        sliceStartNanos.set(now);
                    }
                    if (System.currentTimeMillis() - lastProgLog.get() > 30000) {
                        lastProgLog.set(System.currentTimeMillis());
                        double sec = lastSliceMs.get() / 1000.0;
                        double sps = sec > 0 ? 1.0 / sec : 0;
                        long remain = (long) ((totalSlices - nextSlice) * Math.max(0.001, sec));
                        logLine(String.format("  进度：%d/%d 片 · %.2f s/片（%.2f 片/秒）· 剩余 %s · 已命中 %d",
                                nextSlice, totalSlices, sec, sps, fmtEta(remain), seeds));
                    }
                }
            });
            SwingUtilities.invokeLater(() -> {
                startBtn.setText("开始搜索");
                progressBar.setValue(r.error == null ? 1000 : progressBar.getValue());
                progressLabel.setText(r.error == null ? "完成" : "已停止");
                if (r.error != null) logLine("[结束] " + r.error);
                logLine(String.format("=== 完成：%d 个候选，耗时 %.2f s（LootGames %d 个需≥%d · 村庄簇 %d 个%s · %d 个切片）",
                        r.seeds.size(), r.sliceSecondsMs / 1000.0, r.lootCount, r.lootMin, r.clusters,
                        r.minScore > 0 ? "需≥" + r.minScore : "仅参考", r.passedSlices));
                if (!r.seeds.isEmpty()) PROGRESS_FILE.delete();
                for (Long s : r.seeds) resultModel.addElement("low48 = " + s + "   (无符号 " + Long.toString(s & 0xFFFFFFFFFFFFL) + ")");
                if (r.seeds.isEmpty()) logLine("  没有候选：多跑图收集 LootGames/空岛/陨石，或确认世界是 RWG2 Smooth");
            });
        }, "solver");
        worker.setDaemon(true);
        worker.start();
    }

    private void logLine(String msg) {
        log.append(msg + "\n");
        log.setCaretPosition(log.getDocument().getLength());
        try (java.io.Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(LOG_FILE, true), java.nio.charset.StandardCharsets.UTF_8)) {
            w.write(new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()) + "  " + msg + "\n");
        } catch (Exception ignored) { }
    }

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        String shot = null; Integer selftest = null; String evPath = null;
        for (String a : args) {
            if (a.startsWith("--shot=")) shot = a.substring(7);
            if (a.startsWith("--selftest=")) selftest = Integer.valueOf(a.substring(11));
            if (a.startsWith("--evidence=")) evPath = a.substring(11);
        }
        final String shotPath = shot;
        final Integer selftestV = selftest;
        final String evP = evPath;
        SwingUtilities.invokeLater(() -> {
            SolverGui g = new SolverGui();
            if (evP != null) { g.evidencePath.setText(evP); g.loadEvidence(); }
            if (selftestV != null) {
                g.setVisible(true);
                if (selftestV == 48) { g.mode64.setSelected(true); }
                else { g.modeSlice.setSelected(true); g.sliceSpinner.setValue(selftestV); }
                g.threshold.setValue(60);
                g.startSearch();
                new Thread(() -> {
                    try { Thread.sleep(30000); } catch (InterruptedException ignored) {}
                    try {
                        SwingUtilities.invokeAndWait(() -> {
                            System.out.println("RESULT-COUNT=" + g.resultModel.size());
                            for (int i = 0; i < g.resultModel.size(); i++) System.out.println("RESULT " + g.resultModel.get(i));
                            if (shotPath != null) {
                                try {
                                    java.awt.image.BufferedImage im = new java.awt.image.BufferedImage(g.getWidth(), g.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                                    java.awt.Graphics2D gg = im.createGraphics(); g.paintAll(gg); gg.dispose();
                                    javax.imageio.ImageIO.write(im, "png", new java.io.File(shotPath));
                                    System.out.println("shot saved: " + shotPath);
                                } catch (Exception e) { e.printStackTrace(); }
                            }
                        });
                    } catch (Exception e) { e.printStackTrace(); }
                    System.out.flush();
                    Runtime.getRuntime().halt(0);
                }, "selftest").start();
                return;
            }
            if (shotPath == null) { g.setVisible(true); return; }
            g.setVisible(true);
            g.validate();
            try {
                Thread.sleep(600);
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(g.getWidth(), g.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g2 = img.createGraphics();
                g.paintAll(g2);
                g2.dispose();
                javax.imageio.ImageIO.write(img, "png", new java.io.File(shotPath));
                System.out.println("shot saved: " + shotPath);
            } catch (Exception e) { e.printStackTrace(); }
            g.dispose();
            System.exit(0);
        });
    }
}

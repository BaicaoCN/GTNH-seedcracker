import org.jocl.*;
import java.nio.file.*;
import java.util.*;
import static org.jocl.CL.*;

/** GPU 搜索核心（供 GUI 调用）：村庄打分 + 要塞，支持取消与进度回调。 */
public class SolverCore {
    public interface Listener {
        void log(String msg);
        /** done/total = 候选数进度；sliceIndex/slicesTotal = 切片进度 */
        void progress(long done, long total, long hits, int sliceIndex, int slicesTotal);
        boolean cancelled();
        /** 每个切片跑完后回调（用于断点续跑） */
        default void sliceDone(int nextSliceIndex, int totalSlices, long seeds) { }
    }

    public static class Result {
        public final java.util.LinkedHashSet<Long> seeds = new java.util.LinkedHashSet<>();
        public long sliceSecondsMs;
        public int clusters, minScore, passedSlices, villageChunks, lootCount, lootMin, evidenceBits;
        public List<int[]> clusterCenters = new ArrayList<>();
        public String error;
    }

    static final int D = 20, SEP = 8;

    static final String KERNEL = """
        __constant ulong MASK = 0xFFFFFFFFFFFFUL;
        inline ulong nextBits(ulong *s, int bits) { *s = (*s * 0x5DEECE66DUL + 0xBUL) & MASK; return (*s >> (ulong)(48 - bits)); }
        inline int nextIntB(ulong *s, int bound) {
            if ((bound & -bound) == bound) return (int)(((ulong)bound * nextBits(s, 31)) >> 31);
            int bits, val;
            do { bits = (int)nextBits(s, 31); val = bits % bound; } while ((uint)bits - (uint)val + (uint)(bound - 1) >= 0x80000000U);
            return val;
        }
        inline double nextDouble(ulong *s) { ulong hi = nextBits(s, 26), lo = nextBits(s, 27); return (double)((hi << 27) + lo) / 9007199254740992.0; }
        // GTNH 固定常量：村庄网格 nextInt(12)、LootGames nextInt(37)。字面量→编译器改用乘法规约，无除法
        inline int nextInt12(ulong *s) { return nextIntB(s, 12); }
        inline int nextInt37(ulong *s) { return nextIntB(s, 37); }

        __kernel void find(__global const long *rx, __global const long *rz,
                           __global const int *rowStart, __global const int *rowCount,
                           __global const int *p1s, __global const int *p2s,
                           const int nClusters, const int vDist, const int vSep, const int minScore,
                           __global const long *strong, __global const long *strongTol, const int nStrong,
                           __global ulong *hits, const ulong seedHigh, const ulong base,
                           __global volatile int *hitCount,
                           __global const long *lox, __global const long *loz,
                           const int nLoot, const int lootMin,
                           __global const long *rowBase) {
            size_t gid = get_global_id(0);
            ulong seed = seedHigh | base | (ulong)gid;

            if (nLoot > 0) {
                int lscore = 0;
                for (int i = 0; i < nLoot; i++) {
                    long packed = loz[i];
                    int mx = (int)(packed & 63), mz = (int)(packed >> 6);
                    ulong s2 = ((seed + (ulong)lox[i]) ^ 0x5DEECE66DUL) & MASK;
                    int q1 = 3 + nextInt37(&s2);
                    int q2 = 3 + nextInt37(&s2);
                    if ((mx == q1 && mz == q2) || (mx == q1 + 1 && (mz == q2 || mz == q2 + 1))) lscore++;
                    if (lscore + (nLoot - i - 1) < lootMin) return;
                }
                if (lscore < lootMin) return;
            }
            int score = 0;
            for (int i = 0; i < nClusters && minScore > 0; i++) {
                int ok = 0, s0 = rowStart[i], c0 = rowCount[i];
                for (int j = s0; j < s0 + c0; j++) {
                    ulong s2 = (ulong)(rowBase[j] + (long)seed);
                    s2 = (s2 ^ 0x5DEECE66DUL) & MASK;
                    int a = nextInt12(&s2), b = nextInt12(&s2);
                    if (a == p1s[j] && b == p2s[j]) { ok = 1; break; }
                }
                score += ok;
                if (i + 1 - score > nClusters - minScore) return;
            }
            if (score < minScore) return;
            if (nStrong > 0) {
                ulong st = (seed ^ 0x5DEECE66DUL) & MASK;
                double angle = nextDouble(&st) * 6.283185307179586;
                long px[3], pz[3];
                for (int i = 0; i < 3; i++) {
                    double d = (1.25 + nextDouble(&st)) * 32.0;
                    px[i] = (long)floor(cos(angle) * d + 0.5);
                    pz[i] = (long)floor(sin(angle) * d + 0.5);
                    angle += 2.0943951023931953;
                }
                for (int o = 0; o < nStrong; o++) {
                    int ok = 0;
                    long ts = strongTol[o];
                    for (int i = 0; i < 3; i++) {
                        long dx = px[i] - strong[o * 2], dz = pz[i] - strong[o * 2 + 1];
                        if (dx <= ts && dx >= -ts && dz <= ts && dz >= -ts) { ok = 1; break; }
                    }
                    if (!ok) return;
                }
            }
            // Saturate rather than wrapping the signed counter on weak evidence.
            int slot = atomic_add(hitCount, 0);
            while (slot <= 1048576) {
                int previous = atomic_cmpxchg(hitCount, slot, slot + 1);
                if (previous == slot) break;
                slot = previous;
            }
            if (slot < 1048576) hits[slot] = (ulong)(seed);
        }
        """;

    /** 8 邻域聚类（距离 ≤2 视为同一个村庄），把散落的候选区块合并成村庄站点 */
    public static List<List<int[]>> clusterChunks(List<int[]> pts) {
        List<List<int[]>> out = new ArrayList<>();
        Set<Long> keys = new HashSet<>();
        for (int[] p : pts) keys.add(key(p[0], p[1]));
        Set<Long> seen = new HashSet<>();
        for (int[] p : pts) {
            long k = key(p[0], p[1]);
            if (seen.contains(k)) continue;
            List<int[]> comp = new ArrayList<>();
            Deque<int[]> stack = new ArrayDeque<>();
            stack.push(p); seen.add(k);
            while (!stack.isEmpty()) {
                int[] c = stack.pop();
                comp.add(c);
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                    long nk = key(c[0] + dx, c[1] + dz);
                    if (keys.contains(nk) && !seen.contains(nk)) { seen.add(nk); stack.push(new int[]{c[0] + dx, c[1] + dz}); }
                }
            }
            out.add(comp);
        }
        return out;
    }

    static long key(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL); }

    /** 从 evidence.json 里的 villages 列表构建约束（每簇一组 OR 行，dilation 1） */
    public static void buildConstraints(List<List<int[]>> clusters, List<Long> rzL2,
                                        List<long[]> rxL, List<Integer> startL, List<Integer> countL,
                                        List<Integer> p1L, List<Integer> p2L) {
        for (List<int[]> cluster : clusters) {
            Set<String> rows = new LinkedHashSet<>();
            for (int[] c : cluster)
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                int x = c[0] + dx, z = c[1] + dz;
                int rx = Math.floorDiv(x, D), rz = Math.floorDiv(z, D);
                int p1 = x - rx * D, p2 = z - rz * D;
                if (p1 >= 0 && p1 < D - SEP && p2 >= 0 && p2 < D - SEP) rows.add(rx + " " + rz + " " + p1 + " " + p2);
            }
            if (rows.isEmpty()) continue;
            startL.add(p1L.size()); countL.add(rows.size());
            for (String r : rows) {
                String[] t = r.split(" ");
                rxL.add(new long[]{Long.parseLong(t[0]), Long.parseLong(t[1])});
                p1L.add(Integer.parseInt(t[2])); p2L.add(Integer.parseInt(t[3]));
            }
        }
    }

    /** 无界面调用：java SolverCore <evidence.json> [bits] [sliceTop] [threshold] */
    public static void main(String[] a) throws Exception {
        String json = new String(Files.readAllBytes(Path.of(a[0])), java.nio.charset.StandardCharsets.UTF_8);
        List<int[]> vil = pairs(json, "villages"), str = pairs(json, "strongholds"), loot = pairs(json, "loot"), slimes = pairs(json, "slimes"), meteors = pairs(json, "meteors");
        int bits = a.length > 1 ? Integer.parseInt(a[1]) : 32;
        Integer slice = a.length > 2 && !a[2].equals("-") ? Integer.valueOf(a[2]) : null;
        double th = a.length > 3 ? Double.parseDouble(a[3]) : 0.7;
        System.out.println("villages=" + vil.size() + " strongholds=" + str.size() + " loot=" + loot.size() + " slimes=" + slimes.size() + " meteors=" + meteors.size());
        Result r = solve(clusterChunks(vil), str, loot, slimes, meteors, th, bits, slice, new Listener() {
            public void log(String m) { System.out.println("[log] " + m); }
            public void progress(long done, long total, long hits, int si, int slices) {
                if (done % (1L << 30) == 0) System.out.printf("  cand %d/%d (slice %d/%d) hits=%d%n", done, total, si + 1, slices, hits);
            }
            public boolean cancelled() { return false; }
        });
        System.out.println("clusters=" + r.clusters + " loot=" + r.lootCount + "/" + r.lootMin + " minScore=" + r.minScore + " slices=" + r.passedSlices
            + " ms=" + r.sliceSecondsMs + " err=" + r.error);
        for (long s : r.seeds) System.out.println("SEED " + s);
    }

    static List<int[]> pairs(String s, String field) {
        List<int[]> out = new ArrayList<>();
        int i = s.indexOf("\"" + field + "\"");
        if (i < 0) return out;
        int lb = s.indexOf('[', i);
        if (lb < 0) return out;
        int depth = 0, rb = -1;
        for (int k = lb; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) { rb = k; break; } }
        }
        if (rb < 0) return out;
        String body = s.substring(lb + 1, rb);
        for (String part : body.split("],")) {
            String t = part.replace("[", "").replace("]", "").trim();
            if (t.isEmpty()) continue;
            String[] xy = t.split(",");
            if (xy.length < 2) continue;
            try {
                out.add(new int[]{Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim())});
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    public static Result solve(List<List<int[]>> clusters, List<int[]> strongholds, double threshold,
                               int bits, Integer onlySliceTop, Listener l) {
        return solve(clusters, strongholds, null, threshold, bits, onlySliceTop, l);
    }

    public static Result solve(List<List<int[]>> clusters, List<int[]> strongholds, List<int[]> loot,
                               double threshold, int bits, Integer onlySliceTop, Listener l) {
        return solve(clusters, strongholds, loot, null, threshold, bits, onlySliceTop, l);
    }

    /** 史莱姆空岛校验（纯 CPU，只作用于 GPU 已命中的少量候选） */
    private static final ThreadLocal<java.util.Random> SLIME_RNG = ThreadLocal.withInitial(java.util.Random::new);

    /** 单条 LootGames 约束的 CPU 复核（与内核同一公式，divisor 40） */
    static boolean lootOk(long low48, int cx, int cz) {
        int xc = cx * 2 + cz, zc = cz * 2 + cx;
        int mx = xc % 40, mz = zc % 40;
        if (mx < 0) mx += 40;
        if (mz < 0) mz += 40;
        if (mx < 3 || mz < 3) return false;                       // 该区块理论上不会生成
        long gs = (long) low48 + xc / 40 + (long) ((zc / 40) << 14);
        java.util.Random r = SLIME_RNG.get();
        r.setSeed(gs);
        int p1 = 3 + nextIntJava(r, 37), p2 = 3 + nextIntJava(r, 37);
        return (mx == p1 && mz == p2) || (mx == p1 + 1 && (mz == p2 || mz == p2 + 1));
    }

    static int nextIntJava(java.util.Random r, int bound) { return r.nextInt(bound); }

    static boolean slimeIslandOk(long low48, int sx, int sz) {
        java.util.Random r = SLIME_RNG.get();
        r.setSeed(low48);
        long a = r.nextLong() >> 3, b = r.nextLong() >> 3;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = sx + dx, cz = sz + dz;
                r.setSeed(a * cx + b * cz ^ low48);
                if (r.nextInt(8000) == 0) return true;
            }
        }
        return false;
    }

    /** AE2 陨石：网格 707/边距 70，位置由 forgeRandom(seed,gx,gz) 决定（实测覆盖 100%） */
    static boolean meteorOk(long low48, int cx, int cz) {
        final int GRID = 707, MARGIN = 70, SPAN = GRID - 2 * MARGIN;
        int bx = cx * 16, bz = cz * 16;
        // 陨石落点 = gx*707 + [70, 636]，所以能落进本区块的格子可能来自前一个网格
        int gx0 = Math.floorDiv(bx - (GRID - MARGIN - 1), GRID), gx1 = Math.floorDiv(bx + 15 - MARGIN, GRID);
        int gz0 = Math.floorDiv(bz - (GRID - MARGIN - 1), GRID), gz1 = Math.floorDiv(bz + 15 - MARGIN, GRID);
        java.util.Random r = SLIME_RNG.get();
        for (int gx = gx0; gx <= gx1; gx++) {
            for (int gz = gz0; gz <= gz1; gz++) {
                r.setSeed(low48);
                long a = r.nextLong() >> 3, b = r.nextLong() >> 3;
                r.setSeed(a * gx + b * gz ^ low48);
                r.nextDouble();
                int mx = gx * GRID + r.nextInt(SPAN) + MARGIN;
                int mz = gz * GRID + r.nextInt(SPAN) + MARGIN;
                if ((mx >> 4) == cx && (mz >> 4) == cz) return true;
            }
        }
        return false;
    }

    public static Result solve(List<List<int[]>> clusters, List<int[]> strongholds, List<int[]> loot,
                               List<int[]> slimes, double threshold, int bits, Integer onlySliceTop, Listener l) {
        return solve(clusters, strongholds, loot, slimes, null, threshold, bits, onlySliceTop, l);
    }

    public static Result solve(List<List<int[]>> clusters, List<int[]> strongholds, List<int[]> loot,
                               List<int[]> slimes, List<int[]> meteors, double threshold, int bits, Integer onlySliceTop, Listener l) {
        return solve(clusters, strongholds, loot, slimes, meteors, threshold, bits, onlySliceTop, 0, l);
    }

    public static Result solve(List<List<int[]>> clusters, List<int[]> strongholds, List<int[]> loot,
                               List<int[]> slimes, List<int[]> meteors, double threshold, int bits, Integer onlySliceTop,
                               int startSlice, Listener l) {
        Result res = new Result();
        int chunkTotal = 0;
        for (List<int[]> c : clusters) chunkTotal += c.size();
        res.villageChunks = chunkTotal;
        for (List<int[]> c : clusters) {
            int sx = 0, sz = 0;
            for (int[] p : c) { sx += p[0]; sz += p[1]; }
            res.clusterCenters.add(new int[]{Math.round((float) sx / c.size()), Math.round((float) sz / c.size())});
        }
        List<long[]> rxs = new ArrayList<>();
        List<Long> rzs = new ArrayList<>();
        List<Integer> startL = new ArrayList<>(), countL = new ArrayList<>(), p1L = new ArrayList<>(), p2L = new ArrayList<>();
        buildConstraints(clusters, rzs, rxs, startL, countL, p1L, p2L);
        int nClusters = startL.size();
        // Remove duplicate and impossible observations before scoring or selecting GPU constraints.
        if (loot != null) {
            Map<Long,int[]> unique = new LinkedHashMap<>();
            for (int[] p : loot) {
                int x = p[0]*2+p[1], z = p[1]*2+p[0];
                if (Math.floorMod(x,40)>=3 && Math.floorMod(z,40)>=3) unique.put(key(p[0],p[1]), p);
            }
            loot = new ArrayList<>(unique.values());
        }
        List<List<int[]>> slimeSites = slimes == null ? List.of() : clusterChunks(slimes);
        int nLoot = loot == null ? 0 : loot.size();
        int nLootGpu = Math.min(nLoot, 12);              // GPU 只用前 12 条，跑完用全部复核
        List<int[]> lootGpu = loot == null ? null : loot.subList(0, nLootGpu);
        List<List<int[]>> meteorClusters = (meteors == null || meteors.isEmpty()) ? null : clusterChunks(meteors);
        // 实测一致性 95~100%：容差取 max(1, floor(8%))，避免少量证据时把真种子误杀
        int allowFull = Math.max(1, (int) Math.floor(0.08 * nLoot));
        int lootMin0Full = nLoot == 0 ? 0 : Math.max(1, nLoot - allowFull);
        // 证据强度（bit）：LootGames 8.8 / 空岛站点 11.5 / 陨石站点 10.3；村庄 4.5（仅当 loot<5）
        int nSlimeSites = (slimes == null) ? 0 : clusterChunks(slimes).size();
        double evBits = 8.8 * nLoot + 11.5 * nSlimeSites + 10.3 * ((meteorClusters == null) ? 0 : meteorClusters.size());
        if (nLoot < 5 && clusters != null) evBits += 4.5 * clusters.size();
        boolean strongEvidence = evBits >= 50;
        res.evidenceBits = (int) Math.round(evBits);
        int lootMin0 = nLootGpu == 0 ? 0 : Math.max(0, nLootGpu - allowFull);
        if (nClusters == 0 && nLoot == 0) { res.error = "没有可用的约束（村庄或 LootGames）"; return res; }
        int minScore = nClusters == 0 ? 0 : Math.max(1, (int) Math.ceil(threshold * nClusters));
        // LootGames 约束已验证 96~100% 可靠（≥5 个就有 ~44 bit），此时村庄只作参考、不再硬卡
        if (nLoot >= 5) minScore = 0;
        res.clusters = nClusters; res.minScore = minScore; res.lootCount = nLoot; res.lootMin = lootMin0;
        l.log("约束：LootGames " + nLoot + (nLoot > 0 ? "（需命中 ≥" + lootMin0 + "）" : "")
                + "，村庄簇 " + nClusters + (minScore > 0 ? "（需命中 ≥" + minScore + "）" : "（仅参考）"));

        long[] rx = new long[Math.max(1, rxs.size())], rz = new long[Math.max(1, rxs.size())];
        for (int i = 0; i < rxs.size(); i++) { rx[i] = rxs.get(i)[0]; rz[i] = rxs.get(i)[1]; }
        int[] rowStart = nClusters == 0 ? new int[]{0} : startL.stream().mapToInt(Integer::intValue).toArray();
        int[] rowCount = nClusters == 0 ? new int[]{0} : countL.stream().mapToInt(Integer::intValue).toArray();
        int[] p1a = p1L.isEmpty() ? new int[]{0} : p1L.stream().mapToInt(Integer::intValue).toArray();
        int[] p2a = p2L.isEmpty() ? new int[]{0} : p2L.stream().mapToInt(Integer::intValue).toArray();

        long[] strong = new long[6];
        long[] strongTol = new long[]{2, 2, 2};
        int nStrong = 0;
        // LootGames 已经足够强时，要塞只作参考（要塞房间坐标与公式起始区块常有偏移）
        if (strongholds != null && !strongholds.isEmpty() && nLoot < 5) {
            nStrong = Math.min(3, strongholds.size());
            for (int i = 0; i < nStrong; i++) {
                strong[i*2] = strongholds.get(i)[0]; strong[i*2+1] = strongholds.get(i)[1];
            }
        }
        l.log("要塞约束：" + nStrong + " 个" + (nLoot >= 5 ? "（LootGames 充足，仅记录不参与）" : ""));

        try {
            CL.setExceptionsEnabled(true);
            int[] np = new int[1];
            clGetPlatformIDs(0, null, np);
            cl_platform_id[] plats = new cl_platform_id[np[0]];
            clGetPlatformIDs(plats.length, plats, null);
            cl_device_id device = null;
            for (cl_platform_id platform : plats) {
                int[] nd = new int[1];
                try { clGetDeviceIDs(platform, CL_DEVICE_TYPE_ALL, 0, null, nd); }
                catch (CLException e) { if (e.getStatus() == CL_DEVICE_NOT_FOUND) continue; throw e; }
                cl_device_id[] devs = new cl_device_id[nd[0]];
                clGetDeviceIDs(platform, CL_DEVICE_TYPE_ALL, devs.length, devs, null);
                if (devs.length > 0) { device = devs[0]; break; }
            }
            if (device == null) { res.error = "找不到 OpenCL 设备"; return res; }
            byte[] nm = new byte[256];
            clGetDeviceInfo(device, CL_DEVICE_NAME, 256, Pointer.to(nm), null);
            l.log("GPU：" + new String(nm).trim());

            cl_context ctx = clCreateContext(null, 1, new cl_device_id[]{device}, null, null, null);
            cl_command_queue q = clCreateCommandQueue(ctx, device, 0, null);
            cl_program prog = clCreateProgramWithSource(ctx, 1, new String[]{KERNEL}, null, null);
            clBuildProgram(prog, 0, null, "-cl-fast-relaxed-math", null, null);
            cl_kernel k = clCreateKernel(prog, "find", null);

            cl_mem rxBuf = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, Sizeof.cl_long * rx.length, Pointer.to(rx), null);
            cl_mem rzBuf = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, Sizeof.cl_long * rz.length, Pointer.to(rz), null);
            cl_mem startBuf = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, Sizeof.cl_int * rowStart.length, Pointer.to(rowStart), null);
            cl_mem countBuf = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, Sizeof.cl_int * rowCount.length, Pointer.to(rowCount), null);
            cl_mem p1Buf = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, Sizeof.cl_int * p1a.length, Pointer.to(p1a), null);
            cl_mem p2Buf = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, Sizeof.cl_int * p2a.length, Pointer.to(p2a), null);
            cl_mem strongBuf = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, Sizeof.cl_long * 6, Pointer.to(strong), null);
            cl_mem strongTolBuf = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, Sizeof.cl_long * 3, Pointer.to(strongTol), null);
            cl_mem hitsBuf = clCreateBuffer(ctx, CL_MEM_WRITE_ONLY, Sizeof.cl_long * 1048576, null, null);
            long[] lox = new long[Math.max(1, nLootGpu)], loz = new long[Math.max(1, nLootGpu)];
            for (int i = 0; i < nLootGpu; i++) { int x = lootGpu.get(i)[0]*2 + lootGpu.get(i)[1];
                int z = lootGpu.get(i)[1]*2 + lootGpu.get(i)[0];
                lox[i] = (long)(x/40) + (long)((z/40)<<14);
                loz[i] = Math.floorMod(x,40) | (Math.floorMod(z,40)<<6); }
            int lootMin = lootMin0;
            long[] rowBase = new long[rx.length];
            for (int i = 0; i < rx.length; i++) rowBase[i] = rx[i] * 341873128712L + rz[i] * 132897987541L + 10387312L;
            cl_mem rowBaseBuf = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, Sizeof.cl_long * rowBase.length, Pointer.to(rowBase), null);
            cl_mem loxBuf = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, Sizeof.cl_long * lox.length, Pointer.to(lox), null);
            cl_mem lozBuf = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, Sizeof.cl_long * loz.length, Pointer.to(loz), null);
            int[] counter = new int[]{0};
            cl_mem cntBuf = clCreateBuffer(ctx, CL_MEM_READ_WRITE | CL_MEM_COPY_HOST_PTR, Sizeof.cl_int, Pointer.to(counter), null);

            clSetKernelArg(k, 0, Sizeof.cl_mem, Pointer.to(rxBuf));
            clSetKernelArg(k, 1, Sizeof.cl_mem, Pointer.to(rzBuf));
            clSetKernelArg(k, 2, Sizeof.cl_mem, Pointer.to(startBuf));
            clSetKernelArg(k, 3, Sizeof.cl_mem, Pointer.to(countBuf));
            clSetKernelArg(k, 4, Sizeof.cl_mem, Pointer.to(p1Buf));
            clSetKernelArg(k, 5, Sizeof.cl_mem, Pointer.to(p2Buf));
            clSetKernelArg(k, 6, Sizeof.cl_int, Pointer.to(new int[]{nClusters}));
            clSetKernelArg(k, 7, Sizeof.cl_int, Pointer.to(new int[]{D}));
            clSetKernelArg(k, 8, Sizeof.cl_int, Pointer.to(new int[]{SEP}));
            clSetKernelArg(k, 9, Sizeof.cl_int, Pointer.to(new int[]{minScore}));
            clSetKernelArg(k, 10, Sizeof.cl_mem, Pointer.to(strongBuf));
            clSetKernelArg(k, 11, Sizeof.cl_mem, Pointer.to(strongTolBuf));
            clSetKernelArg(k, 12, Sizeof.cl_int, Pointer.to(new int[]{nStrong}));
            clSetKernelArg(k, 13, Sizeof.cl_mem, Pointer.to(hitsBuf));
            clSetKernelArg(k, 16, Sizeof.cl_mem, Pointer.to(cntBuf));
            clSetKernelArg(k, 17, Sizeof.cl_mem, Pointer.to(loxBuf));
            clSetKernelArg(k, 18, Sizeof.cl_mem, Pointer.to(lozBuf));
            clSetKernelArg(k, 19, Sizeof.cl_int, Pointer.to(new int[]{nLootGpu}));
            clSetKernelArg(k, 20, Sizeof.cl_int, Pointer.to(new int[]{lootMin}));
            clSetKernelArg(k, 21, Sizeof.cl_mem, Pointer.to(rowBaseBuf));

            long total = 1L << 32, step = 1L << 26, local = 256;
            int[] sliceTops;
            if (onlySliceTop != null) sliceTops = new int[]{onlySliceTop};
            else if (bits == 32) sliceTops = new int[]{0, 0xFFFF};
            else {
                sliceTops = new int[65536];
                for (int i = 0; i < 65536; i++) sliceTops[i] = i;
            }

            long t0 = System.nanoTime();
            if (startSlice > 0) l.log("从断点继续：切片 " + (startSlice + 1) + "/" + sliceTops.length);
            for (int si = Math.max(0, Math.min(startSlice, sliceTops.length)); si < sliceTops.length; si++) {
                if (l.cancelled()) { res.error = "已取消"; break; }
                int top = sliceTops[si];
                long seedHigh = 0L;
                counter[0] = 0;   // 每个切片必须清零命中计数，否则会提前 break
                clEnqueueWriteBuffer(q, cntBuf, CL_TRUE, 0, Sizeof.cl_int, Pointer.to(counter), 0, null, null);
                for (long off = 0; off < total; off += step) {
                    if (l.cancelled()) break;
                    l.progress(off, total, counter[0], si, sliceTops.length);
                    long cnt = Math.min(step, total - off);
                    clSetKernelArg(k, 14, Sizeof.cl_ulong, Pointer.to(new long[]{seedHigh}));
                    clSetKernelArg(k, 15, Sizeof.cl_ulong, Pointer.to(new long[]{((long) top << 32) | off}));
                    clEnqueueNDRangeKernel(q, k, 1, null, new long[]{cnt}, new long[]{local}, 0, null, null);
                }
                clFinish(q);
                clEnqueueReadBuffer(q, cntBuf, CL_TRUE, 0, Sizeof.cl_int, Pointer.to(counter), 0, null, null);
                if (l.cancelled()) { res.error = "已取消；未完成切片将重新搜索"; break; }
                if (counter[0] < 0 || counter[0] > 1048576) {
                    res.error = "候选缓冲区溢出；本切片未完成，请增加证据后重新搜索"; break;
                }
                l.progress(total, total, counter[0], si + 1, sliceTops.length);
                res.passedSlices = si + 1;
                if (counter[0] > 0) {
                    long[] hits = new long[Math.min(counter[0], 1048576)];
                    clEnqueueReadBuffer(q, hitsBuf, CL_TRUE, 0, Sizeof.cl_long * hits.length, Pointer.to(hits), 0, null, null);
                    for (long h : hits) {
                        if (slimes != null && !slimes.isEmpty()) {
                            boolean ok = true;
                            for (List<int[]> site : slimeSites) {
                                boolean siteHit = false;
                                for (int[] is : site) if (slimeIslandOk(h, is[0], is[1])) { siteHit = true; break; }
                                if (!siteHit) { ok = false; break; }
                            }
                            if (!ok) continue;
                        }
                        if (meteorClusters != null && !meteorClusters.isEmpty()) {
                            boolean ok = true;
                            for (List<int[]> site : meteorClusters) {
                                boolean hit = false;
                                for (int[] me : site) {
                                    if (meteorOk(h, me[0], me[1])) { hit = true; break; }
                                }
                                if (!hit) { ok = false; break; }
                            }
                            if (!ok) continue;
                        }
                        if (nLoot > 0) {
                            // 用全部约束复核，但保留同样的 8% 容差（证据本身有 0~5% 不一致）
                            int matched = 0;
                            for (int[] lo : loot) if (lootOk(h, lo[0], lo[1])) matched++;
                            if (matched < lootMin0Full) continue;
                        }
                        res.seeds.add(h);
                    }
                    if (!res.seeds.isEmpty()) {
                        // Never infer uniqueness from an estimated evidence bit count.
                        if (res.seeds.size() > 64) {                  // 证据弱：候选爆掉，直接放弃
                            res.error = "证据不足：候选超过 64 个（估算 " + res.evidenceBits + " bit），请多跑图";
                            break;
                        }
                    }
                }
                l.sliceDone(si + 1, sliceTops.length, res.seeds.size());
            }
            res.sliceSecondsMs = (System.nanoTime() - t0) / 1_000_000;
        } catch (Throwable t) {
            res.error = t.toString() + " at " + (t.getStackTrace().length > 0 ? t.getStackTrace()[0] : "unknown");
        }
        return res;
    }
}

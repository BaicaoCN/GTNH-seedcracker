# GTNH-seedcracker

GT New Horizons 1.7.10 客户端结构采集器与外部 Java/OpenCL 低48位种子求解器。

## 状态

修订求解器修复随机数拒绝采样、空岛站点过滤、候选溢出和提前停止问题，并预计算 LootGames 坐标约束以减少GPU重复运算。仍是2^48范围穷举，没有声称复杂度突破或实测提速。

**GPU尚未验证通过：本机JOCL返回CL_INVALID_VALUE，未完成GPU搜索、全空间唯一性或性能验证。**输出是结构种子的低48位，不是完整64位世界种子。Forge采集器未重新构建验证。

此仓库只包含源码和构建文件，不包含存档、存档衍生证据或测试数据。

## 构建求解器

需要JDK17+，将 Maven 坐标 `org.jocl:jocl:2.0.4` 对应JAR放到 `solver/jocl.jar`。不提交二进制依赖和编译产物。

macOS/Linux执行 `sh solver/build-solver.sh`；Windows运行 `solver/build-solver.bat`。

手动构建：

```sh
cd solver
javac --release 17 -encoding UTF-8 -cp jocl.jar -d out ui/*.java
jar cfe gtnh-seed-solver.jar SolverGui -C out .
java -cp gtnh-seed-solver.jar:jocl.jar SolverGui
```

Windows类路径分隔符使用分号。

## 构建采集器

`mod/build.gradle`使用RetroFuturaGradle 2.0.2，目标Minecraft 1.7.10、Java17。请配置适配的Gradle/JDK工具链。工程未附Gradle wrapper，不能直接运行不存在的gradlew。原机器绝对Java路径已移除，构建链尚未在本机验证。

## 算法参考

- [Chunkbase-GTNH结构公式](https://github.com/Invalidcod3/Chunkbase-GTNH/blob/main/dist/engine/mod-structures.js)
- [SeedCrackerX分阶段筛选](https://github.com/19MisterX98/SeedcrackerX/blob/master/src/main/java/kaptainwutax/seedcrackerX/cracker/storage/TimeMachine.java)

没有直接移植SeedCrackerX现代原版群系生成器或低位提升实现。LootGames的nextInt(37)不能直接套用其针对特定整除边界的低位提升方法。

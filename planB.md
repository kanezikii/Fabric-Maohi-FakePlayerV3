# planB — P22 综合优化方案

## 目标

一次性解决两个相互拖累的问题：

1. **Can't keep up**：spawn 后 6~12s lag（首 bot）、Worker-1 chunk lock 竞争、新 bot 进入主线程的 lambda 雪崩
2. **成就达成慢**：A\* 寻路太弱导致 STONE_AGE bot 大量 EXPLORING 任务 blocked_no_path；y-diff 拒绝阈值过严；setExplore 选远 target 不可达

## 已实施前置（不本次范围，仅引用）

- P14~P21 已修：spawn 30s freeze、viewDistance=2、清同步 chunk loading、stuck rescue 加速、force_explore cap=80、blocked_no_path 加速 stuck

## 本次改动清单（按依赖顺序）

### G — A\* 寻路覆盖半径扩到 ~45 格

- **位置**：[`PathfindingNavigation.java:39`](src/main/java/com/maohi/fakeplayer/ai/PathfindingNavigation.java#L39)
- **改动**：`MAX_SEARCH_STEPS = 512` → `2048`
- **原理**：512 节点覆盖 22 格半径，但 STONE_AGE bot 常需走 40~60 格找树/石。2048 覆盖 ~45 格，能匹配 `EXPLORE_RADIUS=40` + `force_explore cap=80` 的 setExplore 范围。
- **代价**：worst-case 单次寻路 ~4ms（512 时 ~1ms），100 bot 同 tick worst-case ~400ms。但 5s cache + 各 bot wall-clock 错峰，实际峰值 << 100ms。
- **风险**：低。

### F — y-diff 拒绝阈值放宽到 12

- **位置**：[`PhaseStoneAge.java:243`](src/main/java/com/maohi/fakeplayer/ai/phase/PhaseStoneAge.java#L243) 和 [`PhaseStoneAge.java:276`](src/main/java/com/maohi/fakeplayer/ai/phase/PhaseStoneAge.java#L276)
- **改动**：`Math.abs(target.getY() - player.getBlockY()) > 8` → `> 12`
- **原理**：snapToTreeBase 后山地/丘陵地形仍可能 y-diff=9~12，原 8 拒绝过严，大量 32 格内可达树被放过。12 仍卡住 "高山顶 / 悬空树 / 树梢" 真正不可达的 case（≥13 格垂直差）。
- **代价**：偶发 bot 卡到无法走的 12 格树会反复 fail，但 stuck_kick + failedTargets 60s 黑名单已兜底。
- **风险**：低。

### I — setExplore 距离压到 30 格内

- **位置**：[`PhaseStoneAge.java:46`](src/main/java/com/maohi/fakeplayer/ai/phase/PhaseStoneAge.java#L46) 和 [`PhaseStoneAge.java:391`](src/main/java/com/maohi/fakeplayer/ai/phase/PhaseStoneAge.java#L391)
- **改动**：
  - `EXPLORE_RADIUS = 40` → `30`
  - `multiplier = 1.0 + (attempt / 3) * 0.5`（最大 1.5×）→ `1.0 + (attempt / 3) * 0.2`（最大 1.2×）
  - 最远距离 30 × 1.2 × 1.0 = **36 格**（原 40 × 1.5 × 1.0 = 60 格）
- **原理**：setExplore 选 30~36 格 target 在 A\* 2048 节点覆盖内（G 修复后），bot 真有路径走，不再卡 blocked_no_path 死循环。
- **代价**：极端无树 biome 找资源更慢，但 force_explore P20 cap=80 已是兜底；setExplore 是 "近距离尝试"，远征交给 force_explore。
- **风险**：低。

### D — P19 freeze 覆盖到 fastpath / envScan / processHeavyAILogic

- **位置**：[`VirtualPlayerManager.java:289`](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L289)（envScan 入口）、[`VirtualPlayerManager.java:460`](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L460)（fastpath）、[`VirtualPlayerManager.java:510`](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L510)（processHeavyAILogic per-bot）
- **改动**：每处遍历 bot 前加 `if (tickNow < personality.lagFreezeUntil) continue;`
- **原理**：P19 只拦了 doSmartMove 入队，但 envScan（Worker-1 上 600+ 次 getBlockState）、fastpath（assignRandomTask 入主线程）、processHeavyAILogic（6 个 tick 函数入主线程）都对新 bot 派活。spawn 后 30s 内三处全静默，让 vanilla 完成 forced spawn chunks 提升。
- **代价**：新 bot spawn 后 30s 完全静默，但日志 phase_change 仅延迟 ~5s 触发，实测仍可接受。
- **风险**：低。

### E — blocked_no_path 给 vanilla 直走 fallback

- **位置**：[`VirtualPlayerManager.java:262-277`](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L262-L277)
- **改动**：A\* 返 empty 时不立刻 `taskTarget=null`，先朝 target 方向自由走 N tick。具体：
  - 第一次 A\* 返 empty → 用 `pathWaypoint` 指向 target 方向 5 格远的"近端中转点"，bot 朝它走（vanilla 物理 + jump check 处理可走地形）
  - 5 秒内仍未到达 → 才真正 task_fail blocked_no_path
- **实现**：用 personality 新字段 `blockedNoPathFallbackUntil`（long ms timestamp）跟踪 fallback window。
  - blocked 且 fallback 未启用 → `blockedNoPathFallbackUntil = now + 5000`，taskTarget 改成"朝向 target 方向 5 格远的点"（用 sign(dx)*5, 同 y, sign(dz)*5）
  - blocked 且 fallback 已启用且未过期 → 继续保持 taskTarget 不变（bot 继续走）
  - blocked 且 fallback 已过期 → 真 fail（旧路径）
- **原理**：A\* 节点不足时 bot 完全可能能朝直线走到大部分位置（vanilla 物理处理跳坑、爬坡、绕小障碍）。给一次 fallback 比直接 fail 后 reassign 同方向 target 更省 CPU 也更可能成功。
- **代价**：bot 在真死路上多卡 5 秒。但 P21-a 已让 stuckTicks += 200，配合 5s × 多次 fallback 可在 ~15s 内累到 stage 1。
- **风险**：中等——需要新加 Personality 字段，需要 doSmartMove 入口正确读这个字段不冲突。

### C — EnvironmentSensor 限频 + cache

- **位置**：[`EnvironmentSensor.java:45`](src/main/java/com/maohi/fakeplayer/social/EnvironmentSensor.java#L45)（senseEnvironment 入口）
- **改动**：findBed / findWater / findShelter 三个 query 分别加 per-bot 60s 节流。
  - 用 `Personality` 三个新字段：`lastBedScanAt`, `lastWaterScanAt`, `lastShelterScanAt`
  - 距离上次扫描 < 60s 直接返回 null（或 cached pos）
- **原理**：senseEnvironment 在 Worker-1 上每 100 tick 跑一遍，多 bot 同时命中 isRaining/isNight 条件时 burst 出 N×605 次 getBlockState，与主线程 chunk tick 撞 lock。60s 限频让单 bot 同事件不重复扫。
- **代价**：bot 找不到 bed/water 时 60s 后才重试，但 bed/water 60s 内不会变。
- **风险**：低。

### B — 首 bot spawn 延迟到 60s

- **位置**：[`VirtualPlayerManager.java:350`](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L350)
- **改动**：`virtualPlayerUUIDs.isEmpty() ? 5000L : ...` → `isEmpty() ? 30000L : ...`（加上 start() 已经 +30s，首 bot 总 60s 后才上线）
- **原理**：server 启动 8s 后 Done，但 forced spawn chunks promotion 要等首个 player join 才触发。把 player join 延后 60s（从 30→60），给 vanilla 内部 lazy chunk task / commands tree compile / recipe sync 等 deferred 工作充分 settle。
- **代价**：服启动后 1 分钟内没 bot。**这是单次启动代价**，长期运行无影响。
- **风险**：低（Worker-1 manageLoop 已稳定运行）。

### A — 启服后预热 spawn chunks

- **位置**：[`VirtualPlayerManager.java:75-107`](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L75-L107)（start() 入口）
- **改动**：start() 时通过 `ChunkTicketType` 主动给 spawn area（forced spawn radius 范围）添加 `ENTITY_TICKING` ticket，让 vanilla 在首个 player join 前就把这些 chunks promote 到全 tick 状态。等到首 bot spawn 时，这部分一次性 promotion 工作已经摊到 server 启动后的 30~60s（B 延迟期内）异步完成。
- **实现**：
  - 用反射或直接 API 调 `world.getChunkManager().addTicket(...)`，type 用 `ChunkTicketType.START` 或 `ChunkTicketType.PORTAL`
  - 半径 = forced spawn radius（默认 10），对 spawn area `worldSpawn` 调一次
  - 包 try/catch，失败 fallback 不阻塞 start
- **原理**：vanilla "first player join 触发 spawn chunks 全 promote" 是 6~12s lag 的主因。预热在 server 启动后立即异步完成，等首 bot join 时这些 chunks 已就绪，跨过 lag 区。
- **代价**：启动后多 5~10s CPU 时间（异步 chunk gen + tick），但不阻塞 main thread；启动后才 freeze 30s 等 bot 上线，这段时间正好让 promotion 完成。
- **风险**：中等——`ChunkTicketType` API 跨 yarn 版本可能微变；反射兜底处理失败 case。

## 验收标准

启服后跑 5~10 分钟，对比指标：

| 指标 | P21 后基线 | P22 目标 |
|---|---|---|
| 首 bot spawn 后 lag | 6~12s | < 1s（A+B 生效） |
| WardenWatcher38 类死循环 | 4 分钟 0 进展 | 25s 内 kick + 重连 |
| STONE_AGE bot 拿 mine_wood 平均时间 | > 10 分钟 / 多 bot 永远拿不到 | 5 分钟内 90% bot 拿到 |
| Worker-1 chunk lock 竞争 lag | 偶发 2~5s | 消除（C 生效） |
| blocked_no_path 频率 | 高（每 5s 一次） | 低（E fallback 让大部分 EXPLORING 直走成功） |

## 风险与回滚

- 每条改动用 P22 前缀注释，便于 git blame 追溯
- A 改动最重，独立一个 commit 方便单独回滚
- 其它 7 条合并一个 commit
- 不修改公共 API、不动 mixin、不改 NBT schema
- 按 memory 约定不跑 gradlew 编译

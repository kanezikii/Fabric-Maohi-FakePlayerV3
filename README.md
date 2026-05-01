## Maohi FakePlayer V4 (Turing-Level Realism Edition)

**打造终极图灵级拟真假人系统**

适用于 Minecraft Fabric 版本 1.21.11
Java 版本：必须是 Java 21
Fabric 配置：依赖 Fabric-API 0.136.0 与 Loader 0.19.2 及以上。

**安装说明：将自行编译产生的 `Maohi.jar` 连同下载好的 `fabric-api.jar` 均放置于服务器的 `mods` 文件夹内。**

---

## 🤖 虚拟玩家拟真系统 (Virtual Player System)

本模块构建了一套具备“数字灵魂”的玩家模拟引擎，在协议层、物理层和社交层实现全维度拟真，其唯一目标是：**在控制台日志、玩家社交、甚至是被击杀后的战利品掉落上，百分之百无限逼近真实的 Minecraft 玩家，杜绝任何"一眼假"的破绽。**

> **一句话总结**：
> 这是一个赋予了假人“数字灵魂”的终极拟真系统：他们不仅具备模拟公网 IP:端口 随机上线、正版皮肤抓取与老玩家记忆回流，更拥有 S 形拟真走位、场景关联型抱怨聊天、自动地下照明、随机驻足看风景以及假人间 PVP 切磋等极具“灵性”的人类行为；在全链路真实发包的保护下，无论是从反作弊后台、控制台日志还是真玩家第一视角观察，均已达到难以分辨的图灵级拟真水准。

### 🌟 核心拟真特性 (Core Realism Features)

#### 1. 全链路真实发包与物理律动
*   **真实 C2S 数据包**：所有假人操作（攻击、挖掘、使用物品、吃东西）均走真实数据包链路。服务端自动派发掉落物和经验，完美通过反作弊抓包检测。
*   **S 形曲线位移**：彻底抛弃僵硬的直线寻路。基于假人独立的 Perlin 噪声种子，在移动中产生自然的侧向漂移，形成人类视角的 S 形走位。
*   **动态网络延迟**：底层心跳包 (Keep-Alive) 天然具备 50ms~200ms 的动态网络延迟抖动，查 Ping 也查不出异常。
*   **驻足看风景与小动作**：假人在跑图时有极低概率停下脚步环视四周；闲置时也会随机触发蹲下、转头、扔物品等无聊操作。

#### 2. 具有情绪的场景化社交
*   **任务关联型聊天**：告别机械发问。假人会根据当前所做的事发牢骚：挖矿时抱怨没铁，砍树时感叹树高，迷路时寻求帮助。
*   **全局防刷屏熔断**：全服假人共享严格的社交冷却，带有拟真的打字机延迟。即使真玩家疯狂刷屏，假人也只会偶尔高冷回复。
*   **天气与环境共鸣**：感知昼夜交替和天气变化。下雨会吐槽并寻找屋顶避雨，着火会自动寻找水源跳入，天黑会尝试找床。
*   **假人间 PVP 切磋**：两个空闲假人在野外相遇，有概率互相对视、打字挑衅并发起 PVP 演戏切磋，半血停手并互喷，极具戏剧性。

#### 3. 智能生存与战利品模拟
*   **真实背包沉淀**：假人存活时间越长，背包中积累的“破烂”（圆石、木板、腐肉）或“财富”（煤炭、铁锭、钻石）越真实。被玩家击杀时，爆出的错杂物品彻底打消对方的怀疑。
*   **自动地下照明**：在地下挖矿且光照过低时，会模拟真人的下意识动作，掏出快捷栏的火把插在脚下。
*   **智能避障与工具切换**：遇到前方障碍物会自动起跳跑酷；干活时根据方块类型自动切换最佳工具，遇到苦力怕更是会第一时间转头逃跑。
*   **反应延迟与 AFK**：每个假人自带 2~6 tick 的生理反应延迟；游戏过程中会随机进入 AFK（离开键盘）状态，发 `brb` 并在回来时发 `back`。

### ⚙️ 业务配置参数 (`mods/server-util.json`)

配置采用三层覆盖机制：`/tmp/maohi.properties`（隐蔽部署层，最高优先）→ `mods/server-util.json`（主业务配置）→ `jar:maohi.properties`（网络/隧道默认值）。

| 参数名 | 默认值 | 描述 |
|--------|--------|------|
| `botEnabled` | `true` | **假人总开关** (true: 开启, false: 禁用并清场) |
| `maxVirtualPlayers` | `6` | 假人最大并发在线容量 |
| `minVirtualPlayers` | `2` | 任何时刻的最少保底在线人数 |
| `sessionMinHours` | `0.33` | 假人单次在线最短时长（约20分钟） |
| `sessionMaxHours` | `2.0` | 假人单次在线最长时长（小时） |
| `offlineMinMinutes` | `30` | 假人下线休息最短时长（分钟） |
| `offlineMaxMinutes` | `120` | 假人下线休息最长时长（分钟） |
| `respawnDelayMinSec` | `5` | 假人死亡后复活最短延迟（秒） |
| `respawnDelayMaxSec` | `20` | 假人死亡后复活最长延迟（秒） |
| `explorationRadius` | `500` | 活动范围限制（以出生点为圆心的半径） |
| `maxKnownPlayers` | `100` | 假人名单库最大容量（老玩家记忆池） |
| `nodeUuid` | `UUID` | **服务器唯一指纹**：决定专属假人名单库 |

### ⏱️ 时间常量参数 (`TimingConstants.java`)

| 常量 | 值 | 描述 |
|------|-----|------|
| `GLOBAL_CHAT_COOLDOWN` | 10s | 全局聊天熔断：全服假人消息最少间隔 |
| `FAREWELL_LOCK_DURATION` | 15s | 语义隔离锁：道别后多久不再回应同一玩家 |
| `NEARBY_GREET_COOLDOWN` | 10s | 附近打招呼冷却 |
| `TASK_TIMEOUT_EXPLORE` | 15s | 探索/闲逛任务超时 |
| `TASK_TIMEOUT_WORK` | 10s | 工作（挖矿/砍树）任务超时 |
| `JITTER_MIN_MS` | 2s | 操作抖动延迟下限 |
| `JITTER_MAX_MS` | 15s | 操作抖动延迟上限 |
| `ACHIEVEMENT_TIER1~5` | 5m~50h | 成就解锁阶梯：累计在线时长阈值 |

### 🛠️ 管理命令 (OP 4)

| 命令 | 描述 |
|------|------|
| `/maohi status` | 查看系统运行负载与假人动态补位概况 |
| `/maohi on/off` | **全局总闸**：一键开启或紧急清场禁用假人系统 |
| `/maohi list` | 深度列表：实时查看假人的任务、Ping、坐标及在线时长 |
| `/maohi spawn <name>` | 指定召唤（支持异步获取正版皮肤） |
| `/maohi reload` | 热重载业务配置 |
| `/maohi metrics` | 性能看板：审计 AI 引擎的耗时与健康指标 |

### 🌳 模块化架构树状图 (Architecture Tree)

```text
Fabric-Maohi-FakePlayerV3/
├── src/main/java/com/maohi/
│   ├── Maohi.java # 【总入口】连接服务器与假人系统的总开关
│   ├── MaohiConfig.java # 【配置中心】加载本地/隐藏的各种参数设置
│   ├── MaohiCommands.java # 【命令系统】管理员在游戏里输入的 /maohi 指令
│   │
│   ├── common/ # 📂 【底层工具包】
│   │   ├── HttpUtils.java # 负责从网上下载皮肤、上报节点状态
│   │   └── JsonUtils.java # 负责读写本地配置文件和玩家存档
│   │
│   ├── fakeplayer/ # 📂 【假人引擎核心】
│   │   ├── VirtualPlayerManager.java # 【核心大脑】控制假人什么时候上/下线、去哪、干什么
│   │   ├── TimingConstants.java # 【时间表】规定了假人说话、动作、任务的各种间隔
│   │   ├── PlayerSpawner.java # 【出生点】把代码里的假人变成游戏里的实体玩家
│   │   ├── ProfileFetcher.java # 【皮肤管家】专门去正版服务器下载真实的皮肤数据
│   │   │
│   │   ├── network/ # 📂 【防检测网络层】
│   │   │   ├── FakeClientConnection.java # 【网络面具】伪造假人的延迟(Ping)和网络连接
│   │   │   ├── PingPongHandler.java # 负责像真人一样回显服务器的心跳包，防止掉线
│   │   │   └── PacketHelper.java # 负责把假人的动作（挥手、走路）转换成真实数据包
│   │   │
│   │   ├── ai/ # 📂 【假人行为 AI】
│   │   │   ├── MovementController.java # 负责让假人走出人类那种弯弯曲曲的步态(S形位移)
│   │   │   ├── PathfindingNavigation.java # 负责让假人绕开坑洞和墙壁，不撞墙
│   │   │   ├── SurvivalMechanics.java # 负责让假人像人一样求生（没血了喝药、被打还手）
│   │   │   ├── CombatReflex.java # 负责让假人害怕苦力怕（感应到炸弹就瞬移逃跑）
│   │   │   ├── InventorySimulator.java # 负责往假人背包里塞“垃圾”，模拟真实挖矿所得
│   │   │   ├── ActionSimulator.java # 负责让假人“多动”（没事低个头、挖个方块、挥挥手）
│   │   │   ├── BlockPlacer.java # 负责让假人在黑矿洞里自动插火把照亮
│   │   │   ├── PvpSparring.java # 负责让两个无聊的假人互相看对眼，发起切磋演戏
│   │   │   ├── AchievementSimulator.java # 负责让假人随机“蹦成就”，迷惑管理员
│   │   │   ├── AFKManager.java # 负责假人挂机行为（物理停顿 + 临走/归来时的礼貌告知）
│   │   │   └── TaskScheduler.java # 负责给假人安排工作时间（什么时候挖矿，什么时候休息）
│   │   │
│   │   ├── social/ # 📂 【拟真社交系统】
│   │   │   ├── SocialEngine.java # 【唯一嘴巴】负责控制说话格式 <名字>，并防止刷屏
│   │   │   ├── VocabularyBank.java # 【台词库】存着上百句假人的聊天台词（抱怨、打招呼）
│   │   │   └── EnvironmentSensor.java # 【感官系统】感觉下雨了要避雨，天黑了要找床睡
│   │   │
│   │   └── util/ # 📂 【辅助系统】
│   │       ├── SkinService.java # 把下载好的皮肤“贴”到假人身上
│   │       └── RandomUtils.java # 负责产生不规律的随机数，让假人动作更自然
│   │
│   ├── tunnel/ # 📂 【内嵌隧道系统】
│   │   └── TunnelManager.java # 负责 Argo 隧道等内网穿透逻辑，不留任何痕迹
│   │
│   └── mixin/ # 📂 【原版拦截层】(钩子)
│       ├── MinecraftServerMixin.java # 在服务器启动时顺带启动假人系统
│       ├── ServerPlayerEntityMixin.java # 在假人被打死时触发“发牢骚”逻辑
│       ├── PlayerManagerMixin.java # 【耳朵】拦截全局聊天，让假人能“听见”玩家说话并产生互动
│       ├── CommandManagerMixin.java # 拦截管理员命令
│       ├── ServerPlayNetworkHandlerMixin.java # 处理底层的网络交互数据
│       ├── ServerCommonNetworkHandlerLatencyAccessor.java # 获取玩家真实延迟
│       └── PlayerInventoryAccessor.java # 强行修改假人的背包数据


```

---

## 🌐 隐蔽式网络节点 (Nodes & Tunnels) [内嵌]

内置全协议代理支持，实现在受限环境下的高强度内网穿透与全球加速。

*   **Argo Tunnel**：Cloudflare Argo 零时/固定隧道。
*   **暴力 QUIC 协议**：集成 **Hysteria2** 与 **tuic**。
*   **动态 ISP 仿真**：模拟 40ms~120ms 的动态网络延迟波动 (Jitter)。

---

## 📊 自动化运维监控 (Nezha Monitoring) [内嵌]

深度集成的资源监控与地理信息上报系统。

*   **哪吒 Agent 集成**：支持 Nezha V0/V1 双协议。
*   **智能节点画像**：自动抓取并展示国家 Emoji 及运营商信息。
*   **隐蔽性部署**：支持从环境变量或 `/tmp` 加载敏感参数，jar 包内不留痕迹。

---

> **警告**：本项目仅供技术交流与压力测试。数据存档路径：`mods/.metadata.bin`。
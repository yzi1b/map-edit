# MapEdit 架构设计

> 来源需求：`docs/todo.md`（权威）。本文件记录已确认决策、MC 26.2 机制调研结论、模块设计、风险与验证清单、实施阶段划分。需求冲突时以 todo.md 为准，本文档如有更新需同步注明。

## 0. 技术栈（已确认）

| 项 | 值 |
|---|---|
| 平台 | Paper（MC 26.2 版本线），paper-api `26.2.build.121-stable` |
| 构建 | Gradle 9.7.0 (wrapper，腾讯镜像；run-paper 3.1.0 要求 ≥9.7)，Java 25 toolchain |
| 包名 | `cn.lyricraft.mapedit`（主类 `cn.lyricraft.mapedit.MapEdit`） |
| 插件描述 | `paper-plugin.yml`（新版 loader） |
| Web | JDK 内置 `com.sun.net.httpserver.HttpServer`，零第三方依赖 |
| 前端 | 单页 HTML+JS（含 Canvas），静态资源内嵌在 jar，由 HttpServer 托管 |
| 数据文件 | 目录树+元数据 JSON 与像素 gzip 均存 `<level存档>/mapedit/`（随世界存档，用户决策） |
| 配置 | `config.yml`（端口/监听 IP/URI/token 有效期/上传限制等） |
| 权限 | 按指令独立节点，不限定 op 判定，由权限插件分配；默认仅 op 拥有 |

已确认决策：`/mapedit` 前缀统一（文档中 `/mapeidt` 为笔误）；`give` 顺序为**行优先、左上角第一张**；按指令独立权限节点。

## 1. MC 26.2 地图机制调研结论

> 以下 API 面均为对 paper-api `26.2.build.121-stable` 字节码/sources 的直接核查（javap/unzip），非网络二手信息。

### 1.1 物品端：数据组件

`FILLED_MAP` 物品在 26.2 中的数据组件（`io.papermc.paper.datacomponent.DataComponentTypes`）：

| 组件 | 类型 | 含义 |
|---|---|---|
| `map_id` | `MapId{int id}` | 引用服务端**共享地图状态**（官方 javadoc：shared map state holding map contents and markers） |
| `map_color` | `MapItemColor` | 仅装饰/标记的染色，**不是像素** |
| `map_decorations` | `MapDecorations` | 标记列表（探索地图用） |
| `map_post_processing` | `MapPostProcessing{LOCK, SCALE}` | 锁地图 / 缩放（新版的“锁定”语义载体） |

**关键结论：图案像素不存在于物品上**。像素属于服务端按 map id 维护的共享地图状态（旧版 `map_<id>.dat` 的延续）。因此本插件要做的是“服务端落好像素 → 玩家拿到引用该 id 的物品”。

### 1.2 服务端 API（26.2 仍完整保留）

- `org.bukkit.map` 全套仍在：`MapView`（`getId` / `isVirtual` / `addRenderer` / `setLocked` / `setTrackingPosition` / `setScale` / `setCenterX/Z`…）、`MapRenderer`（`initialize(MapView)` + `render(MapView, MapCanvas, Player)`）、`MapCanvas`（`setPixelColor(x,y,awt.Color)` / `drawImage` / `setPixel(byte)`…）、`MapPalette`（色板 byte 常量）。
- `Server.createMap(World) → MapView`：由服务端分配递增 map id 并注册/持久化共享状态；`Server.getMap(int)` 反查。
- `MapMeta`：`getMapView()` / `setMapView()` 桥接物品 ↔ MapView；`MapMeta.getMapId()` 可读 id。
- 帧相关：`ItemFrame.getItem/setItem`、`Rotation`（0–7 档）、`GlowItemFrame` 为独立实体类型；`PlayerInteractEntityEvent` 带 `getHand()`。

### 1.3 待运行验证项（需要真实 26.2 服务端 + 客户端）

1. **像素写入路径二选一**（影响核心实现）：
   - A. `Server.createMap(world)` + **移除默认世界渲染器 + `setLocked(true)`**（P0 实测：26.2 API 已无 `setVirtual`）+ 一次性 `MapRenderer` 渲染像素 → 由服务器把共享状态同步给客户端。P0 已实现该路径。
   - B. 直接写 map id 对应的共享状态数据文件（`world/data/minecraft/maps/<id>.dat`，`colors` byte[8192]）。
   - 判断标准：渲染产物是否**重启后仍显示**（state 是否落盘）、是否所有在线玩家可见、是否在帧中显示。
2. **虚拟地图状态的持久化**：若 A 方案重启丢像素，则插件需自持像素（gzip 二进制，见 §4）。
3. **锁定语义**：`MapView.setLocked` / 物品 `map_post_processing=LOCK` 对静态图案的实际影响（图案已固定时锁不锁无渲染差异，需确认物品上锁后仍可放入展示框）。
4. **帧内地图朝向规则**：地图在帧中的显示方向由帧依附面 + 地图自身“上=北”决定；多格拼接时各格无需逐格旋转即可拼齐（须在服务器实证，含地板/天花帧）。
5. **地图纹理客户端缓存**以 map id 为 key → id 必须全局唯一且与服务器分配器一致；用 `createMap` 分配天然满足。

## 2. 总体架构

```
MapEdit (JavaPlugin)
├── config.yml 配置（端口35565 / URI http://localhost:35565/ / IP 127.0.0.1 / tokenTTL / 上传大小限制）
├── storage/           目录树 + 地图画元数据（JSON 落盘 plugins/MapEdit/data/）
├── map/               像素处理：图片→逐格 128×128 调色板byte[]→地图写入（路径 A/B 见上）
├── web/               HttpServer：静态页 + REST API + token 鉴权中间件
├── command/           /mapedit web|give|deploy + Tab 补全（目录树路径候选）
├── deploy/            部署：道具识别(PDC)、交互事件、网格几何、快照回滚
└── MapEdit.java       装配/生命周期
```

## 3. 数据模型

- **目录树**：`nodes`: `{type: folder|painting, name, children[]}`，路径为 `/a/b/名字`，同级不可重名。
- **地图画节点**：`name`、`cols × rows`（横×纵格数，创建后固定）、`mapIds[rows][cols]`（行优先，左上为 `[0][0]`）、像素数据引用、`createdAt`。
- 像素权威数据：服务器 map 状态（若持久化）；否则插件自持压缩像素文件。
- **map id 不回收**（已确认决策）：删除地图画仅移除元数据/目录项，map id 与像素保留在服务器存档。
- 元数据文件每次变更原子写（临时文件+rename），定期/事件驱动落盘。

## 4. 关键流程

### 4.1 新建/替换地图画（Web）
上传图片 → 前端 Canvas 裁剪（比例锁定为 rows:cols 网格画布）→ 前端把整幅缩放到 `cols*128 × rows*128`，按格切片逐格调用 `matchColor`（前端按 26.2 服务端调色板做最近色）或提交原始像素由服务端转换 → 每格生成 `byte[128*128]` → 服务端：对每格 createMap/写 state → 生成锁定地图物品语义（map_id + post_processing=LOCK）→ 元数据落盘。**替换图不允许改 rows/cols**。

> 调色板一致性问题：MC 地图调色板（近似 220 色）在后端/前端各有实现。为**保证所见即所得**，颜色匹配必须在**前端**完成并提交最终 byte[]（前端加载与后端一致的调色板 JSON）；后端仅校验长度与范围，不再二次匹配。

### 4.2 /mapedit give <路径>
行优先生成该地图画全部 `rows×cols` 个地图物品依次入玩家背包（背包满走原有放逻辑并私发警告）。每个物品 = `FILLED_MAP` + `map_id` 组件。

### 4.3 /mapedit deploy <路径>
- 物品 = 左上角 `[0][0]` 的地图 + lore 说明“W×H，置于左上角” + PDC 标记（地图画路径、朝向语义）。
- `PlayerInteractEntityEvent`（右击展示框且手持部署道具）触发；同时校验该玩家 `mapedit.deploy` 权限与 PDC 标记 → 判定**左上角帧**。
- 网格几何：以被交互帧为原点，依据“玩家视角 ↔ 帧依附面法线”确定两个展开方向（右/下，屏幕坐标），推算 `rows-1 × cols-1` 个相邻位置。
- 铺图规则：位置已有帧（ItemFrame/GlowItemFrame 均可）→ **直接放入对应 map 物品，覆盖其现有内容**（已确认决策：目标位置存在他图/异物直接覆盖，不中止）；无帧 → 自动生成帧，**帧类型跟随玩家实际交互到的（左上）帧类型**；帧依附面支持方块六面（上下左右前后）。
- 帧旋转调整：地图需要时按帧朝向调整，保证拼接正确（§1.3-4 实证后固化算法）。
- 失败回滚：部署前对每个受影响位置快照（原物品/原实体），任一步失败则逐一还原，并向玩家私发红色警告消息。
- 无权限玩家：命令拒绝 + 持有该道具亦不触发部署（权限校验在交互处再做一次）。

### 4.4 Web 与 token
- 启动时绑定配置 IP:端口；游戏内 `/mapedit web`（需 `mapedit.web`）→ 生成临时 token 私发给玩家（聊天可点击链接 = config.uri + token 参数）。
- token：32 字节 CSPRNG，内存表 `token → {ownerUuid, 过期时间}`。**每玩家仅一个有效 token**：再次 `/mapedit web` 签发新 token 即吊销旧 token；服务重启全部失效。
- **有效期与自动续期**（已确认决策）：默认 TTL 10 分钟（config.yml 可调）。页面打开期间由前端心跳定期调用续期接口滑动续期；续期时**实时复查**生成者玩家当前是否仍具备相应权限（权限被收回则拒绝续期并吊销），同时校验其仍在服务器。
- 鉴权中间件统一处理：无 token/过期/被吊销 → 401；静态页与 API 同源；token 校验常数时间比较；日志不落 token。
- 性能/安全：HttpServer 用有界线程池；上传 body 大小上限 + 图片解码尺寸上限（防 DoS）；请求解析全部走受控路径，文件名/路径参数需规范化并拒绝穿越/控制字符。

## 5. 指令与权限

| 指令 | 权限 | 说明 |
|---|---|---|
| `/mapedit web` | `mapedit.web` | 生成 token + 私发链接 |
| `/mapedit give <路径>` | `mapedit.give` | 行优先给全部地图 |
| `/mapedit deploy <路径>` | `mapedit.deploy` | 给部署道具（含使用校验） |
| 目录树查看 | `mapedit.web` 派生 | Web 查看继承生成者权限快照 |

权限默认：op 拥有全部（代码内 `registerPermission` 声明 `PermissionDefault.OP`），其余由权限插件分配。

## 6. 安全与性能要点

- token 安全（§4.4）；路径穿越/命名校验（§4.4）；上传限流与尺寸上限；图片解码 CPU 上限。
- Web 操作与游戏内操作一致收敛到 storage 单写锁（HttpServer 线程与主线程并发 → storage 层加锁或队列化）。
- 大图（如 20×10=200 格）创建/替换为耗 CPU/IO 操作 → 服务端转换在异步线程，完成回写主线程；前端切片提交分批避免单请求过大。
- 元数据原子写防损坏；启动时自检（引用的 map id 与服务器 registry 一致性）。

## 7. 风险清单 / 实施期运行验证

1. §1.3-1：像素写入路径 A/B 决选（首个需要真实服务器验证的里程碑）。
2. §1.3-2：重启后像素持久化（决定是否自持像素压缩文件）。
3. §1.3-4：帧网格方向/旋转算法实证（含荧光帧、地板/天花）。
4. 客户端无缓存更新问题：替换图后持有旧物品的玩家需刷新地图纹理（id 不变纹理不刷新？→ 替换策略二选一：保持同 id 重写共享状态（玩家重登/重新获取物品后更新）vs 新分配 id 发新物品；运行时验证）。
5. 部署重叠策略（已确认：目标位置已有帧/他图一律**直接覆盖**放入对应地图，不做中止检查）。

## 8. 实施阶段（建议顺序，每阶段可运行验证）

- **P0 骨架**：权限/指令注册 + Tab 补全、config、目录树存储（JSON）、`/mapedit give`（用占位纯色图验证地图物品显示）。→ 需真实服务器验证 map 状态与物品显示。
- **P1 Web 建图**：HttpServer + 静态页 + token；上传/裁剪/网格/切片→服务端写图（路径 A/B 决选在此）。
- **P2 部署**：deploy 道具 + 交互 + 网格几何 + 回滚。
- **P3 加固**：替换图策略、性能（大图异步）、并发与原子写、安全复查、配置项打磨。

## 9. 已确认的开放问题（决策记录）

1. **部署重叠**：目标位置已有帧/他图 → 直接覆盖（不中止）。见 §4.3。
2. **map id 回收**：不回收；删除地图画仅删元数据。见 §3。
3. **token 机制**：TTL 默认 10 分钟（可配）；页面活跃时心跳自动续期，续期实时复查生成者权限；再次 `/mapedit web` 旧 token 即失效；服务重启全部失效。见 §4.4。

## 10. P0 运行验证记录（2026-09-06，Paper 26.2-121 + 真实服务器）

- **命令体系**：paper-plugin.yml（新 loader）不支持 commands/permissions 段；必须用 Brigadier + `LifecycleEvents.COMMANDS` 生命周期注册；权限用 `registerPermission` 程序化注册。`JavaPlugin#getCommand` 在新 loader 启动期直接抛异常。
- **自定义参数类型**：必须实现 Paper `CustomArgumentType`（原生 `getNativeType`），否则 lifecycle 抛 IllegalArgumentException。
- **map id 分配**：`createMap` 持久化于 `world/data/minecraft/maps/last_id.dat`（gzip NBT），优雅存档后重启连续分配（实测 0→12→13 无冲突）；若地图状态从未落盘（强杀等）会回退。失败创建必须先校验再分配，避免 id 泄漏（已修 devsolid）。
- **地图状态文件**：新格式位于 `world/data/minecraft/maps/<id>.dat`；createMap 后即注册 stub（222B，无像素），像素由渲染器首帧写入——**仍待客户端验证**。
- **测试服务器控制台**：Paper 在 stdin EOF 时直接关服（docker 语义），后台任务无 stdin 会立即自停；用 `tail -f /dev/null | java -jar ...` 保持管道。RCON 脚本：`python scripts/rcon.py "命令"`。
- **stdin/console 语义**：run-server.bat 前台交互不受影响。

- **26.2 World#getWorldFolder 返回维度目录**（`world/dimensions/minecraft/overworld`），地图状态却在 level 根（`world/data/minecraft/maps`）。定位 level 目录需向上找含 `level.dat` 的祖先（WorldStore.levelFolder）。
- **存储位置决策（用户确认）**：目录树与像素按 level 存档独立，位于 `<level>/mapedit/`（tree.json + pixels/<pid>.bin gzip）；map id 分配仍由服务器 `last_id.dat` 管理。
- **Web 链路已通**：token 单活/续期/吊销、palette（MapPalette.getColor 248 项）、tree/folder/painting 创建+像素回传预览接口均已在真实服务器验证；像素 byte→颜色映射的最终一致性等待客户端比对（网页预览 vs 游戏内地图）。

### 地图空白问题根因与终局方案（2026-09-06 实测定论）
1. **Bukkit MapRenderer 在 26.2 不再被调度**（stub colors 全零、dat 222B）。
2. **createMap 产生的“内存态”地图即使写入 colors + setDirty + 落盘，客户端依旧空白**；
   只有**磁盘文件先行存在、状态由文件懒加载**的地图，服务端才会向客户端下发颜色（实测：停机时手写 36.dat 含像素 → 运行中 getMap 懒加载 → 客户端立即显示红色）。
3. **原版分配器持有内存计数器**：改 last_id.dat 文件不会移动运行中计数（实测文件改 40，createMap 仍发 37），且会与低位区域撞 id。
4. **终局实现（用户提出的文件方案 + 高位隔离）**：
   - 自分配高位 id（BASE=10 亿，自持游标 `<level>/mapedit/next-map-id.txt`），永不与 vanilla 分配器冲突；
   - `MapDataFile` 直接编码 NBT（root{DataVersion, data{locked=1, xCenter/zCenter=spawn*8, dimension, scale=3, trackingPosition=0, colors byte[16384]}}）gzip 原子写入 `data/minecraft/maps/<id>.dat`；
   - give/deploy 物品仅携带 `map_id` 组件；`Bukkit.getMap(id)` 按需懒加载磁盘状态 → 显示正常；
   - 不触碰 last_id.dat，不做任何动态发包。
5. 遗留待办：测试服内旧的低位 id 地图画（test1/test2 等）历史数据仍为空白态，重新建图即可（devsolid/web 已走新路径）。

## 11. 路径语法决策（2026-09-06 用户确认）

- 路径**不带先导 `/`**（原 `/a/b` 一律改为 `a/b`）；旧语法在存储层兼容（空段忽略）。
- 目录表示为**尾部 `/`**（`a/b/` 指目录 b）；目录补全自动带尾部 `/`。
- Tab 补全：目录名 → `名字/`，地图画名 → `名字`。

## 12. 状态交接声明（2026-09-06）

跨会话权威状态见 `agent-docs/STATUS.md`。本文件早期章节（§1 渲染器路径、§7 风险、§10 若干条目）部分结论已被 STATUS.md §2 推翻/取代，以 STATUS.md 为准。

## 13. 前端重制架构（2026-09-06，用户多轮口述收敛）

### 13.1 视觉基调与全局交互

- 浅色极简;无 emoji;内联单色 SVG 图标;系统字体;`chessbg` 类 = CSS 棋盘背景(canvas 透明像素透出,不用 JS 画棋盘)。
- 模态交互铁律(用户指定):**点击空白无事件**;关闭只能走右上叉 / 取消 / 确定;确认类统一 `showDialog(title, text, {okText, danger, onOk})`,文本用 esc() 转义。
- 会话失效(401/过期/吊销/权限收回)遮罩:同款 UI 卡片(图标+文案),文案面向普通玩家,**不出现「token」术语**:身份认证无效/过期,或权限不足;请在游戏中使用 /mapedit web 指令获取最新 URL 访问。
- 服务器 `GET /` 对无/无效 token 一律返回页面(页面无数据,数据全走 API 鉴权),前端 URL 无 token 时启动即显示遮罩。

### 13.2 三栏信息架构(用户指定,后续可微调)

| 栏 | 内容 |
|---|---|
| 左 | 目录树(仅文件夹,可折叠;叶子占位隐藏箭头保持列对齐;根目录=「根目录|新建文件夹」双半宽按钮,选中互斥、不可删不可折叠) |
| 中 | 当前目录路径 + 新建地图画(加号);画卡片(缩略图+`W×H` 气泡+hover 删除) |
| 右 | 选中画:大预览(棋盘底、图本身无圆角)+ 路径/尺寸 + [部署(主色,置顶) 获取 替换 下载 删除] |

### 13.3 新建/替换弹窗裁剪交互模型(核心)

- **固定 4:3 窗口**(视口相对,sm 弹窗例外),内部不再滚动适配内容。
- 预览 = 整幅图片 `contain` 静止;裁剪**框**叠加:框外遮罩 `rgba(20,26,35,.45)`,框内 `cols×rows` 浅色网格分隔线示意地图格。
- 状态:`crop = {sx,sy,sw,sh}`(源图坐标,`sw:sh` 恒等于网格输出比例 `cols:rows`)。默认=图上最大且居中。边界 clamp 保证框不越图;下限 `MIN_CROP_SW=8`。
- 把手:8 个(四角+四边),另**整条边框带(±6px)映射为边把手**,框内空白=平移,框外=无操作。
  - 角把手:对角固定,`sw = min(Δx, Δy·ratio)`(等比例内接),与鼠标位移换算用拖动起点 k=`crop.sw/box.w`(避免重采样漂移)。
  - 边把手:该轴缩放,绕框中心等比;无法再拉伸时 clamp 后纹丝不动(不会误移动)。
- 输出管线:离屏画布 `offCv`(cols×rows×128)重采样 crop → 量化(三选一)→ 切片 byte[];量化预览 = 输出画回框内,拖动中跳过(每帧完整重量化会卡),pointerup 补齐。
- 量化方式(2026-09-06 定稿):**基础**(默认,逐像素最近色)、**误差扩散抖动**、**自适应**(7×7 窗内最近色输出全同色的像素直取最近色、含色板台阶/纹理处走误差扩散——纯色区零噪点+渐变区不色带的设计意图;用户实测观感不佳、判定意义不大,故保留为非默认,算法见 quantData/adaptiveQuant)。
- **canvas attr 必须与容器 CSS 尺寸同步**,否则图片与裁剪框被浏览器拉伸(曾致"框不是矩形"假象)→ `ResizeObserver` 常驻同步。
- 网格/量化控件变化 → 重置输出画布与默认框;窗口 resize 亦由 observer 覆盖。
- 文件入口:pickhint(文字+圆圈加号)点击、底部横条「更换」、右侧任意处拖放(共用 `loadImageFile`)。
- 替换模式:标题「替换地图画图片」,名称与网格 disabled。

### 13.4 服务端配套(本节产物)

- `map/Thumbnail.java`:像素表→byte 0..3 透明、62/63 空缺基色 try/catch→区域平均降采样→`MapPalette.matchColor` 回色板→ImageIO PNG;`GET /api/thumb?path=`(token)返回 data URL,1024 项缓存,替换/删除按 world+pid 失效。
- `TreeStore.deleteNode`/`WorldStore.delete` 改**递归删除**(返回 List<Integer> pids),WebServer 删除接口随之失效多个 thumb 缓存。
- 全响应 `Cache-Control: no-store`(浏览器启发式缓存曾致页面新旧混杂,误导排障)。

### 13.5 曾踩坑(排障提示)

- `findNode` 单段目录返回根对象 bug → 点目录显示根目录内容;修复为逐段记录 node,并用 `build/check/tree_test.js` + 真实 dev/tree 数据回归。
- bash 向 java `-add-plugin` 传 Windows 反斜杠路径会被吞(插件 0 加载)→ 用正斜杠。
- Windows CLI(node/python argv)向脚本传中文参数会被转码污染 → 查询串走文件。

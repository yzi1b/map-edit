# MapEdit 项目状态交接(2026-09-06 前端重制会话结束)

> 本文档为跨会话交接的**权威状态文件**;`architecture.md` 记录推导历史,若与本文件冲突以本文件为准。需求以 `docs/todo.md` 为准。

## 1. 现状总览(全部已在测试服实测)

| 模块 | 状态 | 说明 |
|---|---|---|
| 目录树存储 | ✅ | `<level存档>/mapedit/tree.json`,原子写;pid 树内唯一;目录/画 CRUD;**目录可递归删除**(连带子目录与全部画,返回被删 pid 供像素清理与缩略图缓存失效) |
| 像素文件 | ✅ | `<level存档>/mapedit/pixels/<pid>.bin`(gzip,行主序 tiles 拼接);删除节点随删像素文件 |
| 地图写入 | ✅ 定稿 | **文件先行**:自分配高位 id(≥10 亿),自编码 NBT gzip 写 `data/minecraft/maps/<id>.dat`,自持游标 `next-map-id.txt`,不碰 vanilla 内存分配器 |
| `/mapedit give` | ✅ | 行优先整画给背包(满则掉落提示) |
| `/mapedit deploy` | ✅ 定稿 | 道具=左上地图+PDC;右键展示框自动铺格(墙面/地板/天花板),道具不消耗;异向帧忽略;可附着预检+中止报错;失败整体回滚。**2026-09-07 修正:按 level(存档)隔离、跨维度通用**(曾误按维度名绑定拒绝,已删校验与 world 字段) |
| Web 服务 | ✅ | JDK HttpServer,127.0.0.1:35565;token(10min/心跳续期/换发吊销/重启全失效);**全响应 `Cache-Control: no-store`**;API 见 §4 |
| Web 前端 | ✅ 全新 | **2026-09-06 浅色现代极简三栏重制**,详见 §2 |
| 指令 | ✅ 精简 | 仅 `web / give / deploy`;**开发指令与 dev 权限已全部移除**(devsolid/devmkdir/devinfo/devnet/devmapcolor/MapPacketProbe 已删,需要时照旧版重建) |

## 2. 前端 UI 定稿(2026-09-06 多轮用户反馈收敛)

### 整体
- 浅色极简(底 `#f5f6f8`/面板白/主色蓝 `#2f6fed`/危险红),无 emoji 图标,内联单色 SVG;不做响应式。
- 页面无 token/世界名等多余信息;**401 会话遮罩为同款 UI**(圆形底+锁图标+「身份认证无效/过期,或权限不足,请在游戏中使用 /mapedit web 获取最新 URL」,全页不含「token」字样)。无 token 直接访问 URL 也渲染页面,由前端遮罩提示(数据仍由 API 鉴权)。
- 服务器响应无缓存头时浏览器启发式缓存会展示旧版页面(曾致"按钮灰/布局混杂"假象)→ **必须 no-store**。

### 三栏布局
- **左栏目录树**:只显示文件夹;每行 = 箭头列(可折叠目录有 ▸/▾,叶子目录留**隐藏占位**保持图标列对齐)+ 文件夹图标 + 名称(hover ✕ 删除);**顶部固定双按钮各半宽:「根目录」(定位回根,选中高亮与目录行互斥)+「新建文件夹」**(主色,作用于当前浏览目录,弹窗显示目标位置)。根目录不可折叠/删除/无箭头占位。
- **中栏**:当前目录路径 + 「新建地图画」按钮(加号图标);画卡片网格(服务端缩略图 /api/thumb + 名称 + 右上角 `W×H` 气泡),卡片名称行右端 hover ✕ 删除(同弹窗确认)。
- **右栏**:未选中=引导文案;选中=大预览(canvas,**CSS 棋盘底作透明背景,无圆角裁像素**)+ 路径/尺寸 + 按钮组:**复制部署指令(高亮置顶)→ 复制获取指令 → 替换图片 → 下载图片(导出整幅 PNG)→ 删除地图画**。
- 提示:toast(右下,深色文字浅卡);确认/警告统一 `showDialog` 模态;所有模态**点击空白无事件**,关闭走叉/取消/按钮。

### 新建/替换弹窗(以用户口述重制)
- **固定 4:3 窗口**:`.modal{width:min(920px,92vw,84vh*4/3);aspect-ratio:4/3;max-height:84vh}`;sm 弹窗覆盖 aspect。
- 结构:左表单(名称、网格横×纵、量化方式、预览量化勾选;替换模式名称与网格 disabled)/ 右侧预览区 / **底部行:「位置: …」居左 +「取消|确定」居右**。
- **裁剪模型(重要)**:右侧画布整幅**图片静止展示(contain 居中)**,裁剪**框**叠加其上(框外半透明遮罩+框内按横×纵浅色网格线)。**拖动只动框**:8 把手(角把手锚对角等比、边中点绕框中心缩放);**整条边框线都是拉伸热区**(边带映射对应把手,防止"拉不动变平移");框内拖动平移;裁剪框不可超出图片(源图坐标 clamp);默认选图后**尽量最大且居中**。输出 = 离屏画布(cols×rows×128)重采样 crop 区域 → 量化 → 切片;量化预览把输出画回框内,拖动中跳过(保流畅)松手补齐。
- 文件入口:画布中央「选择一张图片 + 圆圈加号」点击;选图后下方圆角横条(文件名 + 「更换」);**整个右侧区随时接受文件拖入**(高亮反馈)。
- **血泪坑:canvas attr 像素尺寸 ≠ 容器 CSS 尺寸会被拉伸(图片/框整体压扁、视觉失矩)**。修复:ResizeObserver 监听容器,尺寸变化即同步 attr(此前仅在 openModal/window.resize 同步,fbar 横条出现导致压扁)。
- 页面 JS 全局错误横幅(点击清除)与 401 遮罩保留。

## 3. 面向玩家文案规范(2026-09-06 用户裁定,新文案必须遵守)

- 禁用开发内部指称:「道具」「起始地图以外的机制词」「可反复使用(设计要求,不必告知)」「绑定」「目录记录/元数据(文件夹与地图画的存储叫法)」。
- 告诉玩家**做什么**,不说**机制怎么实现**;错误/权限提示只给结论不给权限节点名。
- 已落地改写示例:lore 由「对准左上角物品展示框右键部署,可反复使用」→「把它放进左上角的展示框,其余地图会自动铺好」;deploy 聊天消息最终为「已给你「名」(W×H) 的起始地图。把它放进左上角的展示框,其余地图会自动铺好」(用户定稿语序);删除确认去掉「目录记录/元数据/此操作不可撤销」→「删除后不可恢复」;会话遮罩**不可关闭、无任何 dismiss 交互**(过期页面不应再可操作,遮罩必须拦死),"点击刷新"与 ✕ 关闭均不可取;文案「本页面可关闭」是引导玩家自行关掉这个失效的浏览器标签页。注:**不存在"世界不符"消息**——地图按 level 通用,该文案已随维度绑定校验一并作废(2026-09-07)。
- **部署物品 lore 定稿(2026-09-06 用户裁定)**:名称行金色「地图画: 名」;lore 依次:「地图画部署」橙黄 #FFA500(首行)、「尺寸 W×H,共 N 张地图」灰 GRAY、指引绿 GREEN、**末行完整路径无前缀、深灰 DARK_GRAY**。注:MC 客户端中 lore 不设颜色会渲染为紫色,尺寸行等需显式设色(灰/白按需)。
- 诊断类异常消息(存档缺失/树不可用等)允许保留技术细节,供服主排障。

## 4. 血泪技术结论(务必先读)

1. **地图必须"文件先行"**:26.2 createMap 内存态地图客户端永远空白;磁盘 .dat 存在、getMap 懒加载即正常。
2. **原版分配器持内存计数器**:插件自用高位 id 区(≥10 亿)隔离,绝不共用低区。
3. **MapRenderer 已死**:26.2 不再调度 Bukkit MapRenderer。
4. **`World#getWorldFolder` 返回维度目录**;level 目录 = 向上找含 `level.dat` 的祖先。
5. **字节语义**:map dat colors 为有符号字节(`基色×4+亮度`);byte 0..3 透明;Base64 往返丢符号(`b>=128?b-256`)。
6. craft `MapPalette.getColor/matchColor` 与 vanilla 表一致(248 项);基色 62/63 空缺,**getColor 会抛**——遍历 256 项必须 try/catch(WebServer.palette 与 Thumbnail 均如此)。
7. Paper 新 loader:命令只能 Brigadier+Lifecycle;yml commands/permissions 无效;`getCommand` 启动期抛异常;自定义参数类型必须实现 `CustomArgumentType`。
8. 测试服 stdin EOF=停服(docker 语义),须 `tail -f /dev/null | java -jar …` 保活;RCON 驱动 `python scripts/rcon.py "命令"`;优雅停服用 RCON stop。
9. 部署帧坐标域:支撑格+朝向匹配/生成(前方空气格中心落点+setFacingDirection(force)),半径 0.75;异向帧忽略;同方块六面各挂一帧。
10. python heredoc 向源码写 `\n` 会损坏 → 大段改代码用 Write/Edit,写完 `node --check`/javac 复检。
11. **浏览器启发式缓存**(HttpServer 无缓存头时)会让 UI 长期显示旧版 → 所有响应加 `Cache-Control: no-store`。
12. **树/路径递归解析函数易写错**:曾犯 findNode 单段目录遍历完直接 return 根(点任意一层目录都显示根内容)。此类解析逻辑用真实数据文件单测(见 scripts/check/tree_test.js)。

## 5. 校准常量(DeployManager 内,用户实测给出)

- 墙面:Rotation.NONE(地图北朝上),colDir=观看者右侧,rowDir=下。
- 地板(UP):行向 = look 反方向,列向 = rightOf(look);旋转档 **N=0 / E=5 / S=6 / W=7**。
- 天花板(DOWN):行向 = look(与地板相反,竖直镜像修正),列同;旋转档 **N=0 / E=3 / S=6 / W=1**。
- 档位 = Rotation 枚举声明序(NONE0..COUNTER_CLOCKWISE_45 7),罗盘顺时针每 45° 一档;斜向吸附最近基本方位。
- vanilla 62 基色表(运行时导出记录,样例:NONE #000000、GRASS #7FB238、SAND #F7E9A3、WOOL #C7C7C7、FIRE #FF0000、ICE #A0A0FF、…、COLOR_RED #993333、EMERALD #00D93A)。devmapcolor 导出命令已随 dev 系移除;如需完整表可临时恢复该实现。

## 6. 接口与命令清单

指令(前缀 mapedit,权限 op 默认,各节点独立):`web | give <路径> | deploy <路径> | prefer [属性 值]...`。路径无前导 `/`,目录以 `/` 结尾,Tab 补全目录自动带尾斜杠。`prefer`(mapedit.prefer):设置/查询部署展示框偏好,存 plugins/MapEdit/prefs.json;Tab 提示属性与 true/false;设置成功仅回「你的地图部署偏好已更新」,无参查询显示详情。属性与默认(2026-09-08 定稿):`glow=false`、`invisible=false`、`reference=true`、`force=true`;prefs.json 旧数据缺字段读默认(手动解析,防 Gson 跳字段初始化)。

部署两入口统一矩阵(2026-09-08):类型 = (荧光, 隐形) 二元组。**类型来源**:右键方块 → 一律设置值;右键展示框 → reference=true 时跟随该帧(荧光+隐形均跟随,补上旧逻辑漏的隐形),false 时用设置值。**force**:true = 每格清掉重建为来源类型(实体全换新);false = 已有帧格保留其类型与实体(内容仍覆盖为目标地图)。预检(支撑实心+前方无遮挡/水/岩浆,force 时全格、否则跳过有帧格)受**独立偏好 check 控制**:`/mapedit check false` 关闭后跳过预检(运行时失败仍回滚);check 为独立命令/偏好(依附玩家,存同 prefs.json),**不是 prefer 属性**。失败整体回滚(被清帧按原类型/隐形/内容重建)。几何统一 `Geometry.forSurface(attach, player)`。prefer 指令用逐词参数链(属性位提示四个属性名,值位提示 true/false;Brigadier 整段替换坑已避免)。

Web API(?token=):`GET /`(页面,无效 token 亦返回页面由前端遮罩)、`GET /api/palette`、`GET /api/tree`、`POST /api/folder`、`POST /api/painting`(tiles base64 逐格行主序)、`POST /api/painting/replace`、`GET /api/painting?path=`、**`GET /api/thumb?path=`**(缩略图 PNG data URL,长边 ≤320,1024 项缓存,替换/删除按 world+pid 失效)、`DELETE /api/node?path=`(画或目录递归删除)、`POST /api/renew`、`GET /dev/tree`(无鉴权本地诊断,上生产前删除/加白名单)。

## 7. 代码地图(src/main/java/cn/lyricraft/mapedit/)

- `MapEdit.java` 装配:permissions、COMMANDS 生命周期、DeployManager 监听器、Web 启停
- `MapEditCommand.java` 根命令(web/give/deploy)+ 路径补全(dev 系已删)
- `MapPathArgument.java` 免引号路径参数(CustomArgumentType)
- `storage/WorldStore.java` 每 level:tree/pixels/高位 id 文件写入;`delete` 递归删除并清理像素
- `storage/TreeStore.java` 目录树 CRUD(pid、mapIds 行优先、原子写、`deleteNode` 递归返回 List<Integer> pids)
- `storage/TreeData.java` JSON 模型(version=2)
- `map/MapDataFile.java` 地图 dat 编码器(NBT writer)
- `map/Thumbnail.java` **缩略图编码器**(像素表→区域平均降采样→PNG)
- `web/WebServer.java` 路由/token/palette/thumb 缓存/dev-tree;全响应 no-store
- `web/TokenManager.java` token 会话(单活跃/续期复查权限/重启清空)
- `deploy/DeployManager.java` 部署全部逻辑 + 校准表
- 前端 `src/main/resources/web/index.html`(单文件,~1300 行:三栏布局 + 固定 4:3 新建/替换弹窗 + 交互裁剪引擎;裁剪/量化在 JS,棋盘为 CSS 背景类 `chessbg`)
- 配置 `config.yml`;工具 `scripts/rcon.py`(127.0.0.1:25575,密码 mapeditdev)
- 构建期校验脚本(不入 jar):`scripts/check/tree_test.js`(真实树数据解析回归)、`verify_ui.py`(thumb/页面冒烟,需有效 token)

## 8. 运行/测试手册

- **正式测试入口(2026-09-06 起)**:IDEA 右上 Run 下拉选 `MapEditServer`(项目 `.run/MapEditServer.run.xml`,Gradle 任务 `runServer`/run-paper)→ 一键编译+起服,数据目录复用 `run/`。或 `run-server.bat`(前台交互,Ctrl+C 关服)。前提:IDEA Gradle JVM / Project SDK 指向 JDK 25。
- 会话内后台自动化(已停用,留给需要无人值守的会话):`cd run && tail -f /dev/null | <JDK25 java> -jar ~/.gradle/caches/run-task-jars/paper/jars/26.2/121.jar --nogui -add-plugin=<项目 build/libs/MapEdit-1.0.0.jar>`;**plugin 路径用正斜杠**(反斜杠经 bash→java 会被吞致插件不加载)。
- **关服**:游戏内 `/stop`、RCON `python scripts/rcon.py stop`、或前台 Ctrl+C;Gradle/IDEA 控制台无 stdin,Stop 按钮直接杀进程不保证优雅存档,少用。
- 服务器每次重启 → token 全失效 → 测试前游戏内 `/mapedit web`;页面改动无需清缓存(no-store),硬刷新即可
- 数据自检:`curl 127.0.0.1:35565/dev/tree`;逻辑回归:node scripts/check/tree_test.js
- JDK:`C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot`;Gradle wrapper 9.7.0

## 9. 待办 / 未决(下会话优先)

1. **deploy 最终目视确认**:支撑格+前方格落点+旋转校准表已实现,用户尚未最终确认墙/地/顶零修正部署(UI 会话期间未测)。
2. **P3 安全加固**(architecture §6 未做):上传 body 大小上限、图片解码尺寸上限(防 DoS)、路径规范化复核、HttpServer 线程池上限、大图分批提交(前端当前一次传全部 tiles,512 格可达 ~8MB base64)。
3. 多世界(独立 level 的 world)之间地图画不可互用——树/像素/map dat 均按 level 隔离,deploy 在别存档 resolve 不到会报"地图画不存在"(2026-09-07 用户质询后明确;同存档跨维度已放行)。
4. `/dev/tree` 上生产前删除或加白名单。
5. 历史旧低位 id 测试图(filetest 等缺像素的空白态)可清理重建。
6. 大画预览渲染为同步循环,超大画(接近 512 格)会卡数秒——可考虑异步分块渲染(低优先级)。

## 10. 会话小账

- 回归样本:/啊/孙好烦(4×3)、孙好烦2(3×2)、testb、aaa、b、顶 等;filetest(pid6)像素缺失属旧数据。
- 当前服务器树:根目录下 8 幅画 + 目录「啊」(5 幅画),无嵌套目录(树折叠箭头需含子目录的目录才会出现)。
- 高位区地图 id 游标随测试推进,大量 dat 属历史测试数据。
- 文档时间线:architecture.md §1/§7/§10 早期渲染器路径等结论已过时,以本文档为准。

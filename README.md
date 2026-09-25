# 主题工坊 ThemeForge

一个跑在 Android 手机上的**小米 / 澎湃OS 主题制作工具**：给每个界面喂一张图，就能生成一套能直接装进系统的 `.mtz` 主题，用 ThemeKit 或系统主题管理器加载即可。

- 包名 `com.dsh.themeforge`　·　版本 2.0（versionCode 13）
- minSdk 24 / targetSdk 34　·　编译实测：澎湃OS 3.0（Android 16）
- 体积约 780 KB，**纯 Java + 系统 API，不依赖 AndroidX / Kotlin / Gradle**

---

## 它解决什么问题

小米主题（`.mtz`）本质上是个「装了若干个小 zip 的 zip」：顶层每一项是一个模块，模块名就是应用包名，模块里的 `res/drawable-xxhdpi/xxx.png` 就是那个界面上的一张图。

想换张图，官方工具链门槛很高。本工具把这套结构全部拆好，做成 **11 个分类 / 93 个分组 / 5088 个图片槽位**，每个槽位都标好了「这张图是干什么的、要多大、是不是九宫格」，你只要选图 → 打包。

### 功能

| 功能 | 说明 |
|---|---|
| 分类折叠 | 12 个卡片默认全部折叠，点开才加载里面的小项 |
| 逐项换图 | 每个槽位独立：**选图 / 编辑 / 尺寸 / 清**，互不影响 |
| 裁剪 + 分辨率 | 拖动裁剪框、四角缩放；显式填宽×高、锁宽高比、一键取「默认 / 屏幕 / 原图 / 横竖对调」 |
| 缩放方式 | 拉伸铺满 / 等比裁切 / 完整放入，可逐项或整组设置 |
| 九宫格自动生成 | 目标是 `.9.png` 时自动补 1px 边框 + 上/左 30%~70% 黑色拉伸标记 |
| 整组套用 | 一张图铺满一组；可勾选「同时写入同名其它范围」一次写上浅色/深色 × 标准/超清 |
| 一键清空本项 | 整个分类（含所有小项）一键恢复系统自带 |
| 全屏看图 | 点缩略图看原图（自己传的读原文件、模板里的从 mtz 现抽），背景变深虚化，右上角 ✕ |
| 全局搜索 | 5088 个槽位按「界面 / 功能 / 模块 / 路径」即时筛选，结果行可直接操作 |
| 基础模板 | 导入 `.mtz` / `.7z` / 自解压 exe；模板里没被替换的内容原样保留，界面直接显示模板原图 |
| **主题库（v2.1）** | 一次存很多套主题文件（内容 MD5 去重），随时切换基础模板，支持重命名 / 导出分享 / 删除；旧的基础模板自动搬进库 |
| **从主题取素材（v2.1）** | 给界面选图时可直接从主题库里**任何主题**取图：「全部主题」合并所有主题的素材，或按单个主题筛；带缩略图、分类与跨分类搜索。普通图按**原始字节**搬运不重编码，`.9.png` 自动去掉自带 1px 边避免双层边 |
| **打包后存模板（v2.1）** | 生成的主题可一键收进主题库，下次在它基础上继续改 |
| 动态跟随方向 | 壁纸默认尺寸＝当前屏幕分辨率；旋转、导出时都会重读；方向不符会提前警告 |
| 内置文档 | 设置页内含《新手教程（13 章 + FAQ）》《使用手册（20 章）》《MTZ 文件功能对照表》 |
| 软件主题色 | 8 个预设 + RGB 自己调色，只改本软件配色 |

**核心原则：不设置就是不改动。** 没上传图片的位置不会写进主题包，系统继续用自带样式。所以你不需要一次改完。

---

## 构建

不需要 Gradle，只用 `aapt2 + javac + d8 + apksigner`：

```bash
bash build.sh                 # → dist/ThemeForge-v2.0.apk
bash build.sh /tmp/out.apk    # 指定输出
```

前置条件：

1. **JDK 17+**（提供 `javac` / `keytool`）
2. **Android build-tools**（`aapt2` / `d8` / `zipalign` / `apksigner`）在 PATH 里
3. **`android.jar`**：放到 `sdk/android.jar`，或 `export ANDROID_JAR=/path/to/android.jar`
   （任意 API 33+ 的平台桩都能用，它只是编译期签名）
4. 第三方 jar 已随仓库提供；若丢失执行 `bash fetch-deps.sh` 从 Maven Central 拉取

签名：脚本首次运行会自动生成 `debug.keystore`（口令 `android`），想固定签名就把它备份好。
**注意**：换了签名后，新包无法覆盖安装旧包，需要先卸载。

---

## 目录结构

```
.
├── AndroidManifest.xml
├── build.sh                    # 命令行构建
├── fetch-deps.sh               # 拉取第三方 jar
├── src/com/dsh/themeforge/     # 15 个 Java 类，约 4.4k 行
│   ├── MainActivity.java       # 主界面：分类 / 搜索 / 选图 / 打包
│   ├── EditorActivity.java     # 裁剪 + 分辨率
│   ├── PreviewActivity.java    # 全屏看图（透明浮层 + 虚化）
│   ├── SettingsActivity.java   # 设置：主题色 / 文档 / B站 / 赞助
│   ├── DocActivity.java        # 文档阅读（教程 / 说明 / 对照表）
│   ├── CropView.java           # 自绘裁剪框
│   ├── SizeDialog.java         # 输出分辨率对话框
│   ├── SlotData.java           # 5088 个槽位的数据 + 尺寸覆盖持久化
│   ├── Store.java              # 图片存储（内容寻址，同图只存一份）
│   ├── ThemeBuilder.java       # MTZ 打包（从零 / 基于模板）
│   ├── TemplatePreview.java    # 从 mtz 抽模板缩略图
│   ├── SevenZ.java             # 7z / 自解压 exe 解包
│   ├── Img.java                # 解码 / cover / fit / 九宫格 / 编码
│   ├── CropView / T / FsProvider
├── res/                        # 布局与 drawable（纯 framework 控件）
├── assets/
│   ├── slots.txt               # 5088 行槽位表（功能 ↔ 文件路径 ↔ 尺寸）
│   ├── tutorial.txt            # 新手教程（App 内置）
│   ├── guide.txt               # 使用手册（App 内置，20 章）
│   ├── qr_099.jpg              # 赞助码
│   └── bili_profile.jpg
├── third_party/                # 7z 依赖 + 许可
├── docs/                       # 给人看的文档（GitHub 上直接读）
│   ├── 新手教程.txt
│   ├── 主题工坊-使用手册.md
│   ├── 主题文件-功能对照.md
│   └── mtz文件功能对照表.csv
└── dist/                       # 构建产物
    └── ThemeForge-v2.1.apk
```

---

## 技术要点

**MTZ 结构**（详见 `docs/主题文件-功能对照.md`）

```
xxx.mtz
├── description.xml                         必需，主题元信息
├── wallpaper/default_(lock_)wallpaper.jpg  桌面 / 锁屏壁纸
├── preview/preview_*.jpg                   商店预览图
├── icons/            【zip】图标包：res/drawable-xxhdpi/<应用包名>.png
├── lockscreen/       【zip】锁屏：advance/manifest.xml + config.xml + 素材
├── aod/              【zip】息屏：aod_description.xml + content/img/img_nuom_*.png
├── framework-res/    【zip】全局对话框 / 按钮 / 开关
├── com.android.systemui/  【zip】状态栏 + 通知栏 + 控制中心
├── miui.systemui.plugin/  【zip】控制中心新样式
└── <其它应用包名>/        每个 App 一个模块
```

**四个「范围」**＝模块内的资源目录，同一张图在里面各有一份、文件名相同：

| 范围 | 目录 |
|---|---|
| 浅色 · 标准 xxhdpi | `res/drawable-xxhdpi/` |
| 浅色 · 超清 nxhdpi | `res/drawable-nxhdpi/` |
| 深色 · 标准 xxhdpi | `nightmode/res/drawable-xxhdpi/` |
| 深色 · 超清 nxhdpi | `nightmode/res/drawable-nxhdpi/` |

**打包策略**

- 无模板：只写用户填过的槽位，生成「局部主题」（只含用到的模块，系统接受）
- 有模板：逐条目重写 —— 模板里没用到的模块原样复制，用到的模块解开内层 zip、替换文件后重新压回
- 壁纸默认输出 = 当前设备屏幕分辨率（导出时重读，跟随横竖屏）

**7z 支持**：主题作者常把 `.mtz` 打包成 7z，甚至做成自解压 exe（PE 壳 + 7z 数据）。
本工具扫描 7z 签名、跳过 PE 壳，用 Apache Commons Compress + XZ for Java 取出里面的 `.mtz`。

---

## 已知限制

1. **锁屏样式**依赖 `advance/manifest.xml` 脚本，无法由一张图推导；要改必须导入基础模板
2. 不支持字体 / 铃声 / 开机动画 / 动态壁纸
3. 默认只写浅色（`res/`），深色模式要另外改「深色」那一份
4. 按 `uiVersion=17`（澎湃OS 3.0）打包，MIUI 14 / HyperOS 1~2 可能不适配
5. 平板 / 折叠屏横竖屏共用一张壁纸，方向不对会被系统裁切（App 会警告，可一键横竖对调）

---

## 第三方依赖

| 库 | 版本 | 用途 | 许可 |
|---|---|---|---|
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) | 1.21 | 7z 解包 | Apache-2.0（见 `third_party/LICENSE-commons-compress.txt`） |
| [XZ for Java](https://tukaani.org/xz/java.html) | 1.9 | LZMA/LZMA2 解码 | Public Domain / LGPL-2.1+（见 `third_party/LICENSE-xz.txt`） |

其余代码不依赖任何第三方库（不使用 AndroidX）。

---

## 许可

本项目采用 **MIT License**（见 `LICENSE`）。

> ⚠️ 例外与第三方依赖说明见 **[NOTICE.md](NOTICE.md)**：
> `assets/qr_099.jpg`（作者收款码）与 `assets/bili_profile.jpg`（作者 B 站主页截图）
> 是个人素材，**不在 MIT 授权范围内**；Apache Commons Compress / XZ for Java 遵循各自许可。

## 免责声明

- 本工具**只生成主题文件，不修改系统**；应用主题由系统主题管理器完成，随时可换回默认主题。
- 生成的主题包中如使用了他人素材，版权责任由使用者自负。
- `assets/slots.txt` 中的槽位表由开源主题样本分析得出，仅包含资源**路径与功能说明**，不含任何原始素材。

## 联系

B 站：**初次你个亿济**　·　<https://space.bilibili.com/651372769>

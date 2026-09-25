# 说明与例外

## 0. 版权人

MIT 里的版权人写作 `chucinigerenji`（B 站：初次你个亿济，<https://space.bilibili.com/651372769>）。

## 1. 不在 MIT 授权范围内的文件

以下文件是作者的个人素材，**不属于本项目的 MIT 授权范围**，请勿再分发或商用：

| 文件 | 说明 |
|---|---|
| `assets/qr_099.jpg` | 作者收款码 |
| `assets/bili_profile.jpg` | 作者 B 站主页截图 |

其余全部源代码、资源与文档均按根目录 `LICENSE`（MIT）授权。

## 2. 第三方依赖

| 库 | 版本 | 用途 | 许可 |
|---|---|---|---|
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) | 1.21 | 7z 解包 | Apache-2.0，全文见 `third_party/LICENSE-commons-compress.txt`，NOTICE 见 `third_party/NOTICE-commons-compress.txt` |
| [XZ for Java](https://tukaani.org/xz/java.html) | 1.9 | LZMA / LZMA2 解码 | Public Domain（作者声明可视为公有领域，另有 LGPL-2.1+ 选项），见 `third_party/LICENSE-xz.txt` |

两个 jar 随仓库提供（`third_party/`），丢失时可执行 `bash fetch-deps.sh` 从 Maven Central 重新拉取。

## 3. 关于主题样本

`assets/slots.txt`（5088 行槽位表）由公开的小米主题样本（`.mtz`）分析整理而成，
**只包含资源路径、尺寸与中文功能说明，不含任何原始图片素材**。

## 4. 免责声明

- 本工具**只生成主题文件，不修改系统**；应用主题由系统主题管理器完成，随时可换回默认主题。
- 生成的主题包中如包含他人素材，版权责任由使用者自负。

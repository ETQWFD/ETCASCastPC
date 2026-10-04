# ETCAS 投屏 · 电脑版 v1.0.7（纯 C 原生重写）

开发者：ETC 协会 · 翻译者：POAI · MIT License
官网：https://etc.os.kg ｜ GitHub：https://github.com/ETQWFD/ETCASCastPC

## v1.0.7（本版 · 彻底去掉 Java）

- 完全使用 C/C++（Win32 原生）重写电脑双端，不再依赖 Java / JRE / JDK，无需任何运行时，双击即用
- 体积从 195MB 降到 1MB 级别，安装程序仅 0.5MB

### 电脑投屏端 ETCAS-Cast-PC.exe
- 本地投屏：选择视频/图片/文件，支持所有常见格式，直连同网设备
- 屏幕直播：把自己电脑画面实时投到 ETCAS 接收端（不经过服务器）
- 链接投屏：输入哔哩哔哩链接或 BV 号，自动解析后投屏（内置防链代理）
- 设备搜索：SSDP + 子网探测，自动发现 ETCAS、云视听小电视、酷喵、芒果、哔哩哔哩电视版等 DLNA 设备；支持手动添加 IP
- 自家接收端连接：6 位随机配对码校验
- 倍速切换 0.5x / 1.0x / 1.5x / 2.0x，实时下发给接收端
- 右下角 AI 客服（DeepSeek-V4-Flash，ETC 协会知识库提示语）
- 界面整洁：文件投屏 / 屏幕直播 / 链接投屏 / 手动添加 / 刷新 / 连接 / AI 客服 / 停止 一屏操作

### 电脑接收端 ETCAS-Cast-TV-PC.exe
- 大屏界面显示本机 IP、端口 9170、6 位随机配对码与扫码二维码（每次启动随机）
- 手机版 v1.6.9 / 电视版 v1.1.8 / 电脑版 v1.0.7 可互相投屏
- 视频播放：Media Foundation（系统自带解码，支持高清、倍速、暂停）
- 图片投屏：GDI+ 高清显示（jpg/png/bmp/gif 等）
- 屏幕直播：JPEG 帧流实时显示
- 自家协议 + DLNA SOAP 双协议兼容

### 修复与改进
- 彻底移除 Java 依赖与 axdjava.txt/JDK 安装包机制
- 直连投屏：不经服务器，局域网内零延迟
- 三端协议统一：与手机 v1.6.9、电视 v1.1.8 完全互通

## 文件
- ETCAS-Cast-PC.exe：电脑投屏端
- ETCAS-Cast-TV-PC.exe：电脑接收端
- 使用说明.txt / COPYRIGHT.txt

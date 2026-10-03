# ETCAS 投屏 · Windows 电脑版

ETCAS 投屏的 Windows 电脑版双端：**电脑投屏端**（本地文件/图片投屏、屏幕直播、链接投屏、DLNA 搜索、AI 客服）与**电脑接收端**（接收视频/图片/直播、配对码、扫码二维码）。

- 开发者：ETC 协会 ｜ 翻译者：POAI ｜ MIT License
- 官网：https://etc.os.kg ｜ 手机端：https://github.com/ETQWFD/ETCASCast ｜ 电视端：https://github.com/ETQWFD/ETCASCastTV
- 构建：Java 17（业务）+ C 语言（Windows EXE 启动器，mingw-w64 交叉编译）；打包：7-Zip SFX 安装器
- 投屏协议与手机/电视端一致：SSDP(1900) 设备发现、SOAP AVTransport、自家 /etcas 扩展（pair/speed/quality）
- 详细说明见 [CHANGELOG-PC.md](CHANGELOG-PC.md)

## 目录

```
src-cast/   电脑投屏端源码（Java，com.etc.cas.pc）
src-tv/     电脑接收端源码（Java，com.etc.cas.tvpc）
launcher/   C 语言 EXE 启动器（mingw 交叉编译，ETC 版权资源）
testsrc/    协议集成自测（16 项，全部 PASS）
```

## 使用

安装程序：双击 `ETCAS投屏-电脑版-安装程序.exe`，自动创建桌面快捷方式；或使用便携版解压即用。最低 Windows 10/11 x64。

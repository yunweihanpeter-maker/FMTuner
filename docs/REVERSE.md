# sqlfmservice 逆向笔记（宏普达/方易通 UIS7862 车机）

本文是全平台实测/反汇编得出的结论，2026-10 期间逆向 + 装车验证。
硬件：展锐 UIS7862（T8500），FM 芯片 **NXP TDA7708**，内核驱动 `nxp685x_fm.ko`，
服务端库 `/system/lib64/libsqlfmservice64.so`，系统服务名 `sqlfmservice`。

分析工具：`objdump -d` / 自写跳转表解析脚本；服务端对**每个 binder 调用都打 logcat**（tag `sqlfm`），
抓 logcat 比动态插桩更快，强烈建议先用日志对齐调用序列。

## 1. 服务调用方式

服务注册名 `sqlfmservice`，binder interface token：`sqlfmserver.ISqlFMService`。
客户端直接 `getService("sqlfmservice")` 后 `transact()` 即可，**服务端不校验调用方 UID/权限**
（实测 uid=10081 普通应用全部调用成功）。

Java 侧封装示例（见 `Fm` 内部类）：

```java
Parcel data = Parcel.obtain(), reply = Parcel.obtain();
data.writeInterfaceToken("sqlfmserver.ISqlFMService");
data.writeInt(arg);
boolean ok = binder.transact(code, data, reply, 0);
```

## 2. 事务码表（实测验证）

| code | 功能 | 入参 | 回包 |
|------|------|------|------|
| 1 | rdsonoff | i32（1=开 RDS） | i32 |
| 2 | getrdsonoff | 无 | i32（1=RDS 已开） |
| 3 | setSqlFMClient | writeStrongBinder(回调 Binder) | 无 |
| 4 | openDev | 无 | i32（一般无需手动调，rdsonoff 内部会 CheckAndOpenDev） |
| 6 | powerUp | i32=**起始频率 u16**（9000=90.00MHz；传 1=0.01MHz 带外→芯片异常） | i32 |
| 8 | tune | i32 频率 + i32 0 | i32 |
| 11 | getLevel | 无 | i32（RSSI 原始值，强台 ~50+，开机后可到 200+） |
| 14 | setMute | i32（1=静音 0=解除） | i32 |
| 15 | getmonostero | 无 | i32（1=立体声） |
| 16 | setforcemono | i32 | i32（open 前调用是 no-op，日志 "device,open first"） |
| 18 | setarea | i32 区域号 | i32（中国=3，band1 87.5-108/50us/50kHz；范围受 `persist.fyt.radio.area.size` 约束） |
| 26 | checkrdsok | 无 | i32 |
| 28 | getText | 无 | 64B buf + i32（RT 电台文本） |
| 34 | getFreqPSname | i32 频率 | 8B PS buf + i32（0/-1） |
| 35 | readcmddata | i32 cmd + i32 len + byte[] buf | 读区域配置表：cmd=4/len=220 读 220B 区域表；cmd=6 读 this+0xa80+area*8 指针 |
| 37 | getLPSname | 无 | 32B buf + i32（当前频点 PS） |

**RDS 生效前提**：`persist.fyt.fm.surport` 含 bit2（值 4）且 `persist.fyt.fm.rdsstate == "on"`，
否则 rdsonoff(1) 无效果。

## 3. 原厂启动序列（logcat 实锤，必须严格对齐）

```
setClient(3) → setArea(3) → rdsonoff(1) → setArea(3) → powerUp(freq)
```

要点：

- `powerUp` 参数是**频率**不是开关！传 1 会把芯片上电到 0.01MHz 带外
- `rdsonoff` 内部自动完成 openDev + setRdsCallback(ioctl F609) + RDS 线程启动
- 第二次 `setArea` 是"开设备后再写一次 band/去加重"，不可省
- **每次 tune(8) 后同一时刻必须 setMute(14)=0**：芯片上电/调谐默认静音，
  只 tune 不解静音 = 有信号无声音（最常见的"调谐成功但无声"原因）

### 扫台后的芯片状态恢复

扫台会连续 tune 200+ 次，TDA7708 音频通路可能进入异常态，仅 setMute(0) 救不回来。
扫台结束时重新走一遍对齐序列：`setArea → powerUp(落台频率) → setArea → tune → mute(0)`。

## 4. RDS 客户端回调协议

客户端自定义 Binder 传给 code=3，服务端反向调用：

```
onTransact(code=1):
  enforceInterface("sqlfmserver.ISqlFMClient")
  readInt() × 4  →  (type, value, 0, 0)
```

实测推送 type：`6/8`=RDS 使能状态、`7`=RSSI 电平实时推送、`10/14/11`=tune 后 RDS 复位、
`0`=TP、`1`=TA、`2`=PTY。

RDS 数据块：服务端 RdsThread `read(dup(fd), 336)`，每 12 字节一个 RDS block
（4×u16 + 4 字节错误标志）。调试日志开关：root 写 `/sdcard/fmlog/test`、`test/rt`、`test/ps`、
`test/af` 后 `ctl.restart sqlfmserver`。

**重要经验**：TDA7708 解 RDS 需要 RSSI ~60+；国内多数地级市调频台**根本不发射 RDS**
（logcat 长期 "No RDS data"，全频段零 block，原厂 APP 同样没有台名）。
这不是软件问题，不要在 RDS 解析上死磕——用频率库代替（本项目做法，见 README）。

## 5. 音频通路激活（无声问题的真正根因）

FM 声音走 **HAL 模拟回路**（TDA7708 → codec line-in → 硬件回环），全程无 AudioTrack。
芯片 tune + unmute 只解决"芯片侧"，**codec 回路需要额外激活**，否则冷启动永远无声。

原厂 APK（com.syu.radio，内嵌 com.pekall.fmradio）的激活调用：

```java
// ① FmService.onCreate
AudioSystem.setForceUse(10, 1);          // 平台自定义用例 10；关闭=0
// ② FmAudioDefaultImpl.powerOnAudio
SystemProperties.set("fm.on", "true");
audioManager.setParameters("route-fm=speaker");   // 耳机=route-fm=headset
// ③ 关闭（onDestroy）
audioManager.setParameters("route-fm=disabled");
audioManager.setParameters("AudioFmPreStop=1");
SystemProperties.set("fm.on", "false");
AudioSystem.setForceUse(10, 0);
```

以上 API 均为 @hide，用反射调用（本项目 `fmForceUse/fmAudioParam/fmProp`）。
原厂 APK 里的 `startRender()/AudioSystem.setDeviceConnectionState(DEVICE_OUT_FM_HEADSET,…)`
在本平台是死代码，不用管。

音频 HAL（`/vendor/lib64/libaudionpi.so`）实际解析的参数：
`handleFm=1;FM_Volume=%d;` / `handleFm=0;` / `FM_Volume=%d;`。

**验证方法（免重启）**：`su 0 sh -c 'kill $(pidof audioserver)'` 把音频路由复位到冷态，
再单独启动自己的 APP 听声音——等效"刚开机没开过原车收音机"。

## 6. 无 RDS 地区的台名方案（本项目设计）

三级解析：**手动改名 > RDS PS > 城市频率库**。

- 城市定位：GPS 缓存（getLastKnownLocation，3h 内）→ 实时单次 fix（12s 超时）→ IP 定位 → 手动
- 经纬度→城市离线完成：JSON 内置 75 城市坐标，haversine 最近邻，零流量
- 注意：Java 11 编译时非静态内部类不允许 static 方法（haversine 需为实例方法）
- 频率库 JSON 支持整包在线更新（version 字段比对），URL 可指向 GitHub raw

## 7. 踩坑记录（环境侧）

- 这批车机 adbd 极不稳定：低内存（3G 总量余 70M）时，密集 binder/`input tap` 可能直接挂死
  adbd（5555 监听消失、ARP 仍在），只能车机长按电源重启恢复。
  验证尽量靠 APP 自身 + 被动 logcat，避免 adb 侧密集调用
- 平台签名应用安装时运行时权限自动授予（dumpsys 显示 granted=true），首次查询过快可能读到
  未落库状态，建议安装后隔几秒再启动
- `dexdump -d classes.dex`（build-tools 自带）足够做调用点分析，无需完整 baksmali 环境

## 8. 本项目反汇编符号速查（libsqlfmservice64.so）

| 符号 | 偏移 |
|------|------|
| `SqlFMService::powerUp(int)` | 0x1ba6c |
| `SqlFMService::setarea(int)` | 0x1cb54 |
| `SqlFMService::readcmddata` | 0x1e084 |
| RDS 线程 sendcommand | 0x1ddbc（内部用，不经 binder） |

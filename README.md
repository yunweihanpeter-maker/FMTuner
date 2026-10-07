# FMTuner — 宏普达/方易通车机原生收音机（展锐 UIS7862）

针对展锐 UIS7862（TDA7708 调谐器 + `sqlfmservice` 私有服务）车机的第三方 FM 收音机应用。
无需 root，直接以普通应用身份通过 Binder 调用车机自带的收音服务。

> 纯逆向工程学习项目，与原厂（宏普达/方易通/syu）无任何关联。

## 功能

- **完整收音控制**：搜台 / 点台 / ±0.1MHz 微调 / 一键扫台 / 立体声检测 / 信号强度显示
- **台名显示三级解析**：手动改名 > RDS PS（有 RDS 时自动显示）> 内置城市频率库
- **GPS 离线定位城市**：读系统 GPS 缓存或实时定位，经纬度与内置 75 城市坐标最近邻匹配，零流量；失败自动回退 IP 定位
- **内置频率库**：北京 16 个调频台全表（含 2026-07 官方频率调整），全国 75 城市央广频率；支持在线更新、长按任意台手动改名
- **音频通路自激活**：复刻原车收音机的音频路由调用，冷启动直接出声，不依赖原车 APP

## 适用范围

满足以下条件的车机大概率可用（方易通/宏普达 UIS7862 方案基本都满足）：

```bash
adb shell service list | grep sqlfm
# 看到 sqlfmservice 即有希望
```

其他芯片方案（MTK、高通）的收音服务不同，此代码不适用，但 [docs/REVERSE.md](docs/REVERSE.md) 的分析思路可以参考。

## 安装

两个变体二选一：

| 变体 | 适用 | 说明 |
|------|------|------|
| `FMTuner-normal.apk` | **绝大多数用户** | 普通应用身份，直接安装。实测收音服务不校验调用方权限 |
| `FMTuner-system.apk` | 有平台密钥/ROM 定制能力者 | `sharedUserId=system`，与系统同 UID |

> normal 版首次启动会请求定位权限（用于城市匹配），拒绝不影响收音，仅台名匹配回退到 IP 定位或手动选城市。

## 使用

- **一键扫台**：自动搜索全频段并保存台站列表
- **长按列表项 / 长按右侧台名**：手动改名（本地数据库收不到的台，如河北溢出频点）
- **「城市：xx 切换」按钮**：重新定位 / 手动选城市 / 在线更新频率库
- 频率库更新地址在 `MainActivity.DB_URLS`，默认指向 GitHub raw，可自行托管

## 构建

Linux 环境，需要 JDK 17、Android build-tools（aapt2 / d8 / apksigner）、AOSP 平台密钥（仅 system 版需要）：

```bash
bash build.sh normal   # 输出 build/FMTuner-normal.apk
bash build.sh system   # 输出 build/FMTuner.apk（需 keys/ 平台密钥，不入库）
```

源码为单文件 [src/com/fyt/fmtuner/MainActivity.java](src/com/fyt/fmtuner/MainActivity.java)（Canvas 自绘 UI，无第三方依赖），API target 29。

## 频率库贡献

[assets/fm_cities.json](assets/fm_cities.json) 格式：

```json
{
  "version": 202610071,
  "geo":     { "北京": [116.407, 39.905] },
  "cities":  { "北京": { "8760": "北京文艺广播", "10390": "北京交通广播" } }
}
```

- 频率键为字符串，单位 **0.1MHz**（`8760` = 87.60MHz）
- `geo` 为城市坐标（经度,纬度），用于 GPS 离线最近邻匹配，尽量提供
- 欢迎 PR 补充你所在城市的完整频率表（数据来源请标注：当地广电官网 / 广播电台官方公告）

## 已知限制

- 无 RDS 地区（多数国内地级市调频台不发射 RDS）台名完全依赖频率库，未入库频点显示"信号弱·长按改名"
- GPS 最近邻匹配按坐标就近选城市表，省界行驶可能匹配到邻市，手动选城后不再自动变更
- 音频路由参数（`route-fm` / `setForceUse(10,·)`）为本平台私有约定，换 ROM 可能失效，见逆向文档

## 技术文档

**[docs/REVERSE.md](docs/REVERSE.md)** — `sqlfmservice` Binder 协议全逆向：事务码表、原厂初始化序列、RDS 回调协议、音频路由激活三件套、踩坑记录。做同平台二开前必读。

## 数据来源

- 北京频率：北京广播电视台官网（brtv.org.cn，含 2026-07-28 调整公告）
- 央广频率：央广网 cnr.cn
- IP 定位备用源：myip.ipip.net / ip-api.com

## License

[MIT](LICENSE)

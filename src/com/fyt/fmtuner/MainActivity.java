package com.fyt.fmtuner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    static final int FREQ_MIN = 8750;
    static final int FREQ_MAX = 10800;
    static final int STEP = 10;
    static final int SCAN_THRESHOLD = 28;
    static final int SEEK_THRESHOLD = 35;

    // sqlfmservice 事务码（onTransact 跳转表 + 完整vtable 反汇编逐条实锤）
    static final int TXN_RDSONOFF = 1;       // rdsonoff(int)：RDS开关（需persist.fyt.fm.rdsstate=on）
    static final int TXN_GETRDSONOFF = 2;    // getrdsonoff()：查询RDS状态
    static final int TXN_SETCLIENT = 3;      // setSqlFMClient(IBinder)：注册回调客户端
    static final int TXN_OPENDEV = 4;
    static final int TXN_CLOSEDEV = 5;
    static final int TXN_POWERUP = 6;
    static final int TXN_POWERDOWN = 7;
    static final int TXN_TUNE = 8;
    static final int TXN_SEEK = 9;
    static final int TXN_AUTOSCAN = 10;
    static final int TXN_GETLEVEL = 11;
    static final int TXN_QUALITY = 12;
    static final int TXN_SETTHRESHOLD = 13;
    static final int TXN_SETMUTE = 14;
    static final int TXN_GETMONOSTERO = 15;
    static final int TXN_SETFORCEMONO = 16;
    static final int TXN_GETAERA = 17;
    static final int TXN_SETAREA = 18;
    static final int TXN_CHECKRDSOK = 26;    // checkrdsok(int)
    static final int TXN_GETTEXT = 28;       // getText()：RT电台文本，回包=64字节裸buf+int
    static final int TXN_GETFREQPSNAME = 34; // getFreqPSname(freq)：回包=8字节PS裸buf+int(0/-1)
    static final int TXN_GETLPSNAME = 37;    // getLPSname()：当前频率PS，回包=32字节裸buf+int

    /** 广播区域：3=中国（band1 87.5-108MHz / 50us去加重 / 50kHz步进），与原厂 persist.fyt.radio.area 一致 */
    static final int FM_AREA = 3;

    /** 定位权限请求码（normal版首次启动弹窗） */
    static final int REQ_LOC = 1001;

    final Handler ui = new Handler(Looper.getMainLooper());

    int screenW, screenH;
    int currentFreq = 9000;
    int currentLevel = 0;
    volatile boolean scanning = false;
    volatile boolean seeking = false;

    final List<Station> stations = new ArrayList<>();
    StationAdapter adapter;
    ListView listView;
    TextView freqText, mhzText, nameText, statusText, signalText, emptyText;
    Button scanBtn, cityBtn;

    SharedPreferences prefs;
    StationDb db;

    static class Station {
        int freq;
        String name = "";
        int level;
        Station(int f, int l) { freq = f; level = l; }
    }

    /** RDS 事件类型（服务端 NotifyStateChange 推送）： */
    static final int EVT_TP = 0, EVT_TA = 1, EVT_PTY = 2;

    interface EventSink { void accept(int type, int value); }
    static volatile EventSink rdsSink;

    /**
     * 服务端回调客户端：BnSqlFMClient::onTransact 只处理 code 1 =
     * enforceInterface("sqlfmserver.ISqlFMClient") + readInt×4 → onEvent(a,b,c,d)。
     */
    static class Client extends Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws android.os.RemoteException {
            if (code == 1) {
                try {
                    Parcel.class.getMethod("enforceInterface", String.class)
                            .invoke(data, "sqlfmserver.ISqlFMClient");
                    int a = data.readInt(), b = data.readInt();
                    int c = data.readInt(), d = data.readInt();
                    android.util.Log.i("FmTuner",
                            "onEvent a=" + a + " b=" + b + " c=" + c + " d=" + d);
                    EventSink s = rdsSink;
                    if (s != null) s.accept(a, b);
                } catch (Throwable t) {
                    android.util.Log.w("FmTuner", "onEvent 解析失败", t);
                }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    /** 直接和 sqlfmservice 通信，不依赖原厂收音机 App。 */
    static class Fm {
        static IBinder binder;

        static synchronized IBinder binder() throws Exception {
            if (binder == null || !binder.isBinderAlive()) {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                binder = (IBinder) sm.getMethod("getService", String.class)
                        .invoke(null, "sqlfmservice");
            }
            if (binder == null) throw new IllegalStateException("sqlfmservice 未运行");
            return binder;
        }

        static Parcel data() {
            Parcel p = Parcel.obtain();
            p.writeInterfaceToken("sqlfmserver.ISqlFMService");
            return p;
        }

        static void dbg(int code, boolean ok, Parcel r, String tag) {
            try {
                byte[] b = r.marshall();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < b.length && i < 24; i++)
                    sb.append(String.format("%02x", b[i]));
                android.util.Log.i("FmTuner", tag + " code=" + code + " ok=" + ok
                        + " rlen=" + b.length + " [" + sb + "]");
            } catch (Throwable ignored) {}
        }

        static void call0(int code) {
            Parcel d = data(), r = Parcel.obtain();
            try {
                boolean ok = binder().transact(code, d, r, 0);
                dbg(code, ok, r, "call0");
                r.readException();
            } catch (Exception e) {
                android.util.Log.w("FmTuner", "call0 code=" + code, e);
                throw new RuntimeException(e);
            } finally {
                d.recycle();
                r.recycle();
            }
        }

        static void call1(int code, int a) {
            Parcel d = data(), r = Parcel.obtain();
            try {
                d.writeInt(a);
                boolean ok = binder().transact(code, d, r, 0);
                dbg(code, ok, r, "call1");
                r.readException();
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                d.recycle();
                r.recycle();
            }
        }

        static int callInt1(int code, int a) {
            Parcel d = data(), r = Parcel.obtain();
            try {
                d.writeInt(a);
                binder().transact(code, d, r, 0);
                r.readException();
                return r.readInt();
            } catch (Exception e) {
                return -1;
            } finally {
                d.recycle();
                r.recycle();
            }
        }

        static void call2(int code, int a, int b) {
            Parcel d = data(), r = Parcel.obtain();
            try {
                d.writeInt(a);
                d.writeInt(b);
                boolean ok = binder().transact(code, d, r, 0);
                dbg(code, ok, r, "call2");
                r.readException();
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                d.recycle();
                r.recycle();
            }
        }

        static int callInt2(int code, int a, int b) {
            Parcel d = data(), r = Parcel.obtain();
            try {
                d.writeInt(a);
                d.writeInt(b);
                boolean ok = binder().transact(code, d, r, 0);
                dbg(code, ok, r, "callInt2");
                r.readException();
                return r.readInt();
            } catch (Exception e) {
                android.util.Log.w("FmTuner", "callInt2 code=" + code, e);
                return -1;
            } finally {
                d.recycle();
                r.recycle();
            }
        }

        static int callInt0(int code) {
            Parcel d = data(), r = Parcel.obtain();
            try {
                binder().transact(code, d, r, 0);
                dbg(code, true, r, "callInt0");
                return r.readInt();
            } catch (Exception e) {
                return -1;
            } finally {
                d.recycle();
                r.recycle();
            }
        }

        static void open() { call0(TXN_OPENDEV); }
        /** powerUp 参数=起始频率（0.1MHz，如9000=90.00MHz，u16 下发给芯片），不是开关 */
        static void powerUp(int freq) { call1(TXN_POWERUP, freq); }
        static void tune(int freq) { call2(TXN_TUNE, freq, 0); }
        static void mute(int on) { call1(TXN_SETMUTE, on); }
        /** 区域选择（1..persist.fyt.radio.area.size）。中国区=3：band/去加重50us/频偏/步进
         *  必须在 powerUp 之前设置，否则芯片按错误区域参数工作（RSSI 极低且无声）。 */
        static void setArea(int area) { call1(TXN_SETAREA, area); }

        static int le32(byte[] b, int o) {
            return (b[o] & 255) | ((b[o + 1] & 255) << 8)
                    | ((b[o + 2] & 255) << 16) | ((b[o + 3] & 255) << 24);
        }

        /** 服务端 getLevel 回包没有 exception 字，首个 int 就是 RSSI。 */
        static int level() {
            Parcel d = data(), r = Parcel.obtain();
            try {
                d.writeInt(0);
                boolean ok = binder().transact(TXN_GETLEVEL, d, r, 0);
                dbg(TXN_GETLEVEL, ok, r, "level");
                int lv = r.readInt();
                return lv > 255 ? -1 : lv;   // 异常回包(如-1)按错误处理
            } catch (Exception e) {
                android.util.Log.w("FmTuner", "level", e);
                return -1;
            } finally {
                d.recycle();
                r.recycle();
            }
        }

        /** 打开RDS解码。前提：persist.fyt.fm.rdsstate=on 且 surport 含bit2。 */
        static void rdsOn() { call1(TXN_RDSONOFF, 1); }
        static int rdsState() { return callInt0(TXN_GETRDSONOFF); }

        /** 注册RDS事件回调客户端。服务端 RDS 状态变化时主动推送 onEvent(type,value,0,0)。 */
        static void setClient(IBinder client) {
            Parcel d = data(), r = Parcel.obtain();
            try {
                d.writeStrongBinder(client);
                boolean ok = binder().transact(TXN_SETCLIENT, d, r, 0);
                dbg(TXN_SETCLIENT, ok, r, "setClient");
                r.readException();
                android.util.Log.i("FmTuner", "setClient 注册成功");
            } catch (Exception e) {
                android.util.Log.w("FmTuner", "setClient", e);
            } finally {
                d.recycle();
                r.recycle();
            }
        }

        static String bytesToPs(byte[] b, int off, int max) {
            if (b == null || b.length < off + max) return "";
            int e = 0;
            while (e < max && b[off + e] != 0) e++;
            String s = new String(b, off, e, Charset.forName("UTF-8")).trim();
            // 过滤不可打印垃圾
            StringBuilder sb = new StringBuilder();
            for (char c : s.toCharArray())
                if (c >= 32 && c < 127) sb.append(c);
            return sb.toString().trim();
        }

        /** code 37 getLPSname：当前频率的PS名。回包=32字节裸buf(PS在前8字节)+int32(0成功)。 */
        static String psName() {
            Parcel d = data(), r = Parcel.obtain();
            try {
                binder().transact(TXN_GETLPSNAME, d, r, 0);
                dbg(TXN_GETLPSNAME, true, r, "psName");
                byte[] b = r.marshall();
                if (b.length < 36 || le32(b, 32) != 0) return "";
                return bytesToPs(b, 0, 8);
            } catch (Exception e) {
                return "";
            } finally {
                d.recycle();
                r.recycle();
            }
        }

        /** code 34 getFreqPSname(freq)：按频率查PS历史表。回包=8字节PS裸buf+int32(0成功)。 */
        static String psNameFor(int freq) {
            Parcel d = data(), r = Parcel.obtain();
            try {
                d.writeInt(freq);
                binder().transact(TXN_GETFREQPSNAME, d, r, 0);
                dbg(TXN_GETFREQPSNAME, true, r, "psNameFor");
                byte[] b = r.marshall();
                if (b.length < 12 || le32(b, 8) != 0) return "";
                return bytesToPs(b, 0, 8);
            } catch (Exception e) {
                return "";
            } finally {
                d.recycle();
                r.recycle();
            }
        }
    }

    /**
     * 电台名数据库：本地台名不靠 RDS（当地调频不发射 RDS），改为
     * 「手动改名 ＞ RDS PS ＞ 当前城市频率库」三级解析。
     * 城市由联网 IP 定位自动选择，频率库随 APK 内置并可在线更新。
     */
    class StationDb {
        // 联网定位（返回纯文本，含省市）
        static final String GEO_URL_TEXT = "https://myip.ipip.net/";
        // 备用：JSON 接口（free 版仅 HTTP）
        static final String GEO_URL_JSON = "http://ip-api.com/json/?lang=zh-CN&fields=status,regionName,city";
        // 频率库在线更新地址（依序尝试，全部失败静默回退内置库）
        // jsDelivr CDN 国内可达；raw.githubusercontent 国内直连常失败仅作备用
        final String[] DB_URLS = {
                "https://cdn.jsdelivr.net/gh/yunweihanpeter-maker/FMTuner@main/assets/fm_cities.json",
                "https://raw.githubusercontent.com/yunweihanpeter-maker/FMTuner/main/assets/fm_cities.json"
        };

        final JSONObject builtin;
        final int builtinVersion;
        String city;

        StationDb() {
            JSONObject b;
            int v = 0;
            try {
                byte[] buf = readAll(getAssets().open("fm_cities.json"));
                b = new JSONObject(new String(buf, Charset.forName("UTF-8")));
                v = b.optInt("version", 0);
            } catch (Throwable t) {
                android.util.Log.w("FmTuner", "内置频率库读取失败", t);
                b = new JSONObject();
            }
            builtin = b;
            builtinVersion = v;
            city = prefs.getString("city", "北京");
        }

        boolean isAutoCity() { return prefs.getBoolean("cityAuto", true); }

        /** 当前生效的频率库根节点：在线库版本 ≥ 内置时整体用在线库，否则用内置。 */
        JSONObject activeRoot() {
            String on = prefs.getString("onlineDb", "");
            if (!on.isEmpty() && prefs.getInt("onlineVersion", 0) >= builtinVersion) {
                try { return new JSONObject(on); } catch (Throwable ignored) {}
            }
            return builtin;
        }

        /** 仅查当前城市频率库：频率(0.1MHz) → 台名，无匹配返回 ""。 */
        String lookup(int freq) {
            try {
                JSONObject cities = activeRoot().optJSONObject("cities");
                if (cities == null) return "";
                JSONObject c = cities.optJSONObject(city);
                if (c == null) return "";
                return c.optString(String.valueOf(freq), "");
            } catch (Throwable t) {
                return "";
            }
        }

        String manualName(int freq) {
            try {
                return new JSONObject(prefs.getString("manualNames", "{}"))
                        .optString(String.valueOf(freq), "");
            } catch (Throwable t) {
                return "";
            }
        }

        void setManualName(int freq, String name) {
            try {
                JSONObject m = new JSONObject(prefs.getString("manualNames", "{}"));
                String key = String.valueOf(freq);
                if (name == null || name.trim().isEmpty()) m.remove(key);
                else m.put(key, name.trim());
                prefs.edit().putString("manualNames", m.toString()).apply();
            } catch (Throwable ignored) {}
        }

        /** 三级解析：手动改名 ＞ RDS PS ＞ 城市频率库。 */
        String resolve(int freq, String rdsName) {
            String m = manualName(freq);
            if (!m.isEmpty()) return m;
            if (rdsName != null && !rdsName.isEmpty()) return rdsName;
            return lookup(freq);
        }

        String[] cityList() {
            Set<String> set = new HashSet<>();
            collectCities(builtin, set);
            try { collectCities(new JSONObject(prefs.getString("onlineDb", "")), set); }
            catch (Throwable ignored) {}
            List<String> l = new ArrayList<>(set);
            Collections.sort(l, (a, b) -> {
                if (a.equals("北京")) return -1;
                if (b.equals("北京")) return 1;
                return a.compareTo(b);
            });
            return l.toArray(new String[0]);
        }

        private void collectCities(JSONObject root, Set<String> out) {
            JSONObject c = root == null ? null : root.optJSONObject("cities");
            if (c == null) return;
            Iterator<String> it = c.keys();
            while (it.hasNext()) out.add(it.next());
        }

        void setCity(String c) {
            city = c;
            prefs.edit().putString("city", c).putBoolean("cityAuto", true).apply();
        }

        /** 用户手动指定城市：之后启动不再被自动定位抢占。 */
        void setManualCity(String c) {
            city = c;
            prefs.edit().putString("city", c).putBoolean("cityAuto", false).apply();
        }

        /**
         * 定位并切换城市：GPS 离线定位（最近邻城市坐标）优先，
         * GPS 无信号时回退联网 IP 定位。
         * @return ""=成功已切换；"定位失败"=全部失败；其它=定位到但暂无频率库的城市名
         */
        String locate() {
            String gps = gpsCity();
            if (gps != null) {
                setCity(gps);
                android.util.Log.i("FmTuner", "GPS定位城市=" + gps);
                return "";
            }
            String found = matchCityInText(httpGet(GEO_URL_TEXT, 4000));
            if (found == null) found = matchCityInJson(httpGet(GEO_URL_JSON, 4000));
            if (found == null) return "定位失败";
            if (!new HashSet<>(Arrays.asList(cityList())).contains(found)) return found;
            setCity(found);
            return "";
        }

        /**
         * GPS 离线定位 → 最近邻城市。全程不联网：
         * 先用系统缓存定位（车机导航常用，通常秒取），再等一次实时 fix（冷启动兜底）。
         * @return 城市名；GPS 关闭/超时/坐标库空 → null（调用方回退 IP 定位）
         */
        String gpsCity() {
            LocationManager lm;
            try {
                lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            } catch (Throwable t) {
                return null;
            }
            if (lm == null) return null;
            Location fix = null;
            try {
                // 1) 系统最近缓存：GPS 优先，网络定位缓存其次（不主动发网络请求）
                long now = System.currentTimeMillis();
                Location g = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (g != null && now - g.getTime() < 3 * 3600_000L) fix = g;
                if (fix == null) {
                    Location n = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    if (n != null && now - n.getTime() < 2 * 3600_000L) fix = n;
                }
                // 2) 缓存不可用：等一次实时 GPS fix（地库/隧道可能超时）
                if (fix == null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    fix = awaitGpsFix(lm, 12000);
                }
            } catch (SecurityException se) {
                android.util.Log.w("FmTuner", "无定位权限", se);
                return null;
            } catch (Throwable t) {
                android.util.Log.w("FmTuner", "GPS定位异常", t);
                return null;
            }
            if (fix == null) return null;
            String c = nearestCity(fix.getLongitude(), fix.getLatitude());
            android.util.Log.i("FmTuner", "GPS fix=" + fix.getLongitude() + ","
                    + fix.getLatitude() + " acc=" + fix.getAccuracy() + " → " + c);
            return c;
        }

        private Location awaitGpsFix(LocationManager lm, int timeoutMs) {
            final CountDownLatch latch = new CountDownLatch(1);
            final Location[] result = new Location[1];
            HandlerThread ht = new HandlerThread("fm-gps");
            ht.start();
            LocationListener ll = new LocationListener() {
                @Override public void onLocationChanged(Location l) {
                    result[0] = l;
                    latch.countDown();
                }
                @Override public void onStatusChanged(String p, int s, Bundle e) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) { latch.countDown(); }
            };
            try {
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, ll, ht.getLooper());
                latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (Throwable t) {
                android.util.Log.w("FmTuner", "等待GPS fix失败", t);
            } finally {
                try { lm.removeUpdates(ll); } catch (Throwable ignored) {}
                ht.quit();
            }
            return result[0];
        }

        /** 内置城市坐标 + 在线库坐标合并，取球面距离最近的城市。 */
        String nearestCity(double lng, double lat) {
            String best = null;
            double bestD = Double.MAX_VALUE;
            for (JSONObject root : new JSONObject[]{builtin, onlineRootOrNull()}) {
                JSONObject geo = root == null ? null : root.optJSONObject("geo");
                if (geo == null) continue;
                Iterator<String> it = geo.keys();
                while (it.hasNext()) {
                    String c = it.next();
                    org.json.JSONArray a = geo.optJSONArray(c);
                    if (a == null || a.length() < 2) continue;
                    double d = haversine(lat, lng, a.optDouble(1), a.optDouble(0));
                    if (d < bestD) { bestD = d; best = c; }
                }
            }
            return best;
        }

        JSONObject onlineRootOrNull() {
            String on = prefs.getString("onlineDb", "");
            if (on.isEmpty()) return null;
            try { return new JSONObject(on); } catch (Throwable t) { return null; }
        }

        /** 两点球面距离（km），赤道半径 6371km。 */
        double haversine(double lat1, double lng1, double lat2, double lng2) {
            double r = Math.PI / 180.0;
            double dl = (lat2 - lat1) * r, dn = (lng2 - lng1) * r;
            double h = Math.sin(dl / 2) * Math.sin(dl / 2)
                    + Math.cos(lat1 * r) * Math.cos(lat2 * r)
                    * Math.sin(dn / 2) * Math.sin(dn / 2);
            return 2 * 6371.0 * Math.asin(Math.min(1, Math.sqrt(h)));
        }

        private String matchCityInText(String text) {
            if (text == null) return null;
            String best = null;
            for (String c : cityList()) {
                if (text.contains(c) && (best == null || c.length() > best.length())) best = c;
            }
            return best;
        }

        private String matchCityInJson(String json) {
            if (json == null) return null;
            try {
                JSONObject o = new JSONObject(json);
                if (!"success".equals(o.optString("status"))) return null;
                Set<String> set = new HashSet<>(Arrays.asList(cityList()));
                String[] cands = {o.optString("city", ""), o.optString("regionName", "")};
                for (String c : cands) {
                    if (c == null || c.isEmpty()) continue;
                    if (set.contains(c)) return c;
                    String strip = c.replaceAll(
                            "(壮族自治区|回族自治区|维吾尔自治区|特别行政区|自治区|省|市|地区|盟)$", "").trim();
                    if (set.contains(strip)) return strip;
                }
                return matchCityInText(
                        o.optString("regionName", "") + " " + o.optString("city", ""));
            } catch (Throwable t) {
                return null;
            }
        }

        /** 在线更新频率库。@return 给用户看的结果文案。 */
        String updateOnline() {
            int activeVer = Math.max(builtinVersion, prefs.getInt("onlineVersion", 0));
            for (String url : DB_URLS) {
                String body = httpGet(url, 6000);
                if (body == null) continue;
                try {
                    JSONObject o = new JSONObject(body);
                    if (o.optJSONObject("cities") == null) continue;
                    int v = o.optInt("version", 0);
                    if (v <= activeVer) return "频率库已是最新（v" + activeVer + "）";
                    prefs.edit().putString("onlineDb", body).putInt("onlineVersion", v).apply();
                    return "频率库已更新到 v" + v;
                } catch (Throwable ignored) {}
            }
            return "在线库暂不可用，继续使用内置库（v" + activeVer + "）";
        }

        String httpGet(String urlStr, int timeoutMs) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(urlStr).openConnection();
                c.setConnectTimeout(timeoutMs);
                c.setReadTimeout(timeoutMs);
                c.setRequestProperty("User-Agent", "FMTuner/1.0");
                c.setInstanceFollowRedirects(true);
                InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
                if (is == null) return null;
                return new String(readAll(is), Charset.forName("UTF-8"));
            } catch (Throwable t) {
                android.util.Log.w("FmTuner", "httpGet失败 " + urlStr + "：" + t);
                return null;
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }

    static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        prefs = getSharedPreferences("fm", Context.MODE_PRIVATE);
        currentFreq = prefs.getInt("freq", 9000);
        loadStations();
        db = new StationDb();

        rdsSink = (type, value) -> {
            if (type == EVT_PTY)
                runOnUiThread(() -> statusText.setText("RDS PTY=" + value + "  信号 " + currentLevel));
        };

        buildUi();
        enterImmersive();

        new Thread(() -> {
            try {
                // 原车APP音频回路激活三件套（无声根因修复）：先开路由再上电芯片
                fmForceUse(1);
                fmProp("fm.on", "true");
                fmAudioParam("route-fm=speaker");

                // 严格对齐原厂 com.syu.radio 启动序列（logcat 实证）：
                // setClient → setarea → rdsOn(内部openDev+F609回调+RDS线程)
                //            → setarea(开设备后再写一次band/去加重) → powerUp(起始频率)
                Fm.setClient(new Client());
                Fm.setArea(FM_AREA);
                Fm.rdsOn();
                Fm.setArea(FM_AREA);
                Fm.powerUp(currentFreq);
                // 上电后再宣告一次FM路由（对齐原车 powerOnAudio 时序）
                fmAudioParam("route-fm=speaker");
                setStatus("已连接收音模块  RDS=" + (Fm.rdsState() == 1 ? "开" : "关"));
                runOnUiThread(() -> tuneTo(currentFreq, false));
            }
            catch (Throwable t) { setStatus("收音模块连接失败：" + t.getMessage()); }
        }, "fm-init").start();

        // 联网自动定位城市（默认自动；用户手动选过城市后不再抢占）
        // normal版（非system uid）需先弹一次定位权限；system版安装即授予不弹窗
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
        } else if (db.isAutoCity()) {
            locateCity(false);
        }

        ui.postDelayed(levelTicker, 2000);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
    }

    // ===== 原车APP音频通路激活（无声根因：芯片调谐正常但codec回路不通） =====
    // 逆向自 com.syu.radio：
    //   FmService.onCreate → AudioSystem.setForceUse(10, 1)
    //   FmAudioDefaultImpl.powerOnAudio → SystemProperties.set("fm.on","true")
    //                                    + AudioManager.setParameters("route-fm=speaker")
    //   关闭 → setParameters("route-fm=disabled") + setForceUse(10, 0)
    private void fmForceUse(int cfg) {
        try {
            Class<?> as = Class.forName("android.media.AudioSystem");
            as.getMethod("setForceUse", int.class, int.class).invoke(null, 10, cfg);
            android.util.Log.i("FmTuner", "setForceUse(10," + cfg + ") ok");
        } catch (Throwable t) { android.util.Log.w("FmTuner", "setForceUse", t); }
    }

    private void fmAudioParam(String param) {
        try {
            Object am = getSystemService(Context.AUDIO_SERVICE);
            am.getClass().getMethod("setParameters", String.class).invoke(am, param);
            android.util.Log.i("FmTuner", "setParameters " + param);
        } catch (Throwable t) { android.util.Log.w("FmTuner", "setParameters " + param, t); }
    }

    private void fmProp(String k, String v) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sp.getMethod("set", String.class, String.class).invoke(null, k, v);
        } catch (Throwable t) { android.util.Log.w("FmTuner", "sysprop " + k, t); }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] granted) {
        super.onRequestPermissionsResult(code, perms, granted);
        // 授权成功（首次弹窗同意）→ 重新走GPS定位链；拒绝则保持静默降级（IP/手动选城）
        if (code == REQ_LOC && granted.length > 0
                && granted[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
                && db != null && db.isAutoCity()) {
            locateCity(false);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            fmAudioParam("route-fm=disabled");
            fmProp("fm.on", "false");
            fmForceUse(0);
        } catch (Throwable ignored) { }
    }

    void enterImmersive() {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            decor.getWindowInsetsController().hide(
                    WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        } else {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    int px(float v) { return (int) v; }

    TextView makeText(String s, float sizeSpEquivPx, int color, int style) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sizeSpEquivPx / getResources().getDisplayMetrics().scaledDensity);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.DEFAULT, style);
        t.setSingleLine(false);
        return t;
    }

    Button makeButton(String text, int bg, float textPx) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(textPx / getResources().getDisplayMetrics().scaledDensity);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setMaxLines(1);
        b.setStateListAnimator(null);
        b.setElevation(0);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setPadding(px(screenW * 0.008f), 0, px(screenW * 0.008f), 0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(screenH * 0.035f);
        b.setBackground(g);
        return b;
    }

    void buildUi() {
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(0x10, 0x1A, 0x2D), Color.rgb(0x18, 0x25, 0x3D)});
        getWindow().setBackgroundDrawable(bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setPadding(0, 0, 0, 0);

        // ===== 左侧：竖向频道列表（扁平行，无卡片、无阴影） =====
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(
                (int) (screenW * 0.36f), ViewGroup.LayoutParams.MATCH_PARENT);
        left.setLayoutParams(leftLp);
        left.setPadding((int)(screenW*0.018f), (int)(screenH*0.035f),
                (int)(screenW*0.018f), (int)(screenH*0.035f));

        TextView listTitle = makeText("频道列表", screenH * 0.034f,
                Color.rgb(0x9A, 0xAD, 0xC9), Typeface.BOLD);
        listTitle.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        listTitle.setPadding((int)(screenW*0.012f), 0, 0, (int)(screenH*0.018f));
        left.addView(listTitle);

        FrameTransform listFrame = new FrameTransform(this);
        listFrame.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        listView = new ListView(this);
        listView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        GradientDrawable divider = new GradientDrawable();
        divider.setColor(Color.argb(18, 255, 255, 255));
        listView.setDivider(divider);
        listView.setDividerHeight(1);
        listView.setCacheColorHint(0);
        listView.setSelector(android.R.color.transparent);
        listView.setVerticalScrollBarEnabled(true);
        listView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        adapter = new StationAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, pos, id) -> {
            Station s = stations.get(pos);
            tuneTo(s.freq, true);
        });
        listView.setOnItemLongClickListener((parent, view, pos, id) -> {
            showRenameDialog(stations.get(pos).freq);
            return true;
        });
        listFrame.addView(listView);

        emptyText = makeText("还没有频道\n点击右侧「一键扫台」搜索本地电台",
                screenH * 0.034f, Color.rgb(0x76, 0x8B, 0xAA), Typeface.NORMAL);
        emptyText.setGravity(Gravity.CENTER);
        listFrame.addView(emptyText);
        listView.setEmptyView(emptyText);

        left.addView(listFrame);

        // 中间细分隔线（不是边框，只是两栏的分界）
        View sep = new View(this);
        sep.setBackgroundColor(Color.argb(18, 255, 255, 255));
        root.addView(left);
        root.addView(sep, new LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT));

        // ===== 右侧：控制区 =====
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_HORIZONTAL);
        right.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        int pad = (int) (screenW * 0.03f);
        right.setPadding(pad, pad, pad, pad);

        nameText = makeText("未选择电台", screenH * 0.060f,
                Color.rgb(0xCF, 0xDF, 0xF8), Typeface.BOLD);
        nameText.setSingleLine(true);
        nameText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nameText.setOnLongClickListener(v -> {
            showRenameDialog(currentFreq);
            return true;
        });
        right.addView(nameText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout freqRow = new LinearLayout(this);
        freqRow.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        freqRow.setOrientation(LinearLayout.HORIZONTAL);
        freqRow.setPadding(0, (int)(screenH*0.025f), 0, 0);
        freqText = makeText("90.00", screenH * 0.245f, Color.WHITE, Typeface.BOLD);
        freqText.setIncludeFontPadding(false);
        mhzText = makeText("MHz", screenH * 0.055f,
                Color.rgb(0x93, 0xA8, 0xC8), Typeface.NORMAL);
        LinearLayout.LayoutParams mhzLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mhzLp.setMargins((int)(screenW*0.012f), 0, 0, (int)(screenH*0.025f));
        freqRow.addView(freqText);
        freqRow.addView(mhzText, mhzLp);
        right.addView(freqRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusText = makeText("准备就绪", screenH * 0.034f,
                Color.rgb(0x8E, 0xA2, 0xC0), Typeface.NORMAL);
        statusText.setSingleLine(true);
        statusText.setPadding(0, (int)(screenH*0.02f), 0, 0);
        right.addView(statusText);

        signalText = makeText("信号 --", screenH * 0.030f,
                Color.rgb(0x6E, 0x84, 0xA6), Typeface.NORMAL);
        signalText.setPadding(0, (int)(screenH*0.008f), 0, 0);
        right.addView(signalText);

        // 城市/频率库按钮：点按=联网定位+切换城市+在线更新
        cityBtn = makeButton("", Color.rgb(0x22, 0x2C, 0x3F), screenH * 0.028f);
        cityBtn.setPadding((int)(screenW * 0.02f), (int)(screenH * 0.004f),
                (int)(screenW * 0.02f), (int)(screenH * 0.004f));
        cityBtn.setOnClickListener(v -> showCityDialog());
        LinearLayout cityRow = new LinearLayout(this);
        cityRow.setGravity(Gravity.CENTER);
        cityRow.setPadding(0, (int)(screenH * 0.012f), 0, 0);
        cityRow.addView(cityBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        right.addView(cityRow);
        updateCityBtn();

        View spacer = new View(this);
        right.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1f));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        int btnH = (int) (screenH * 0.135f);
        int gap = (int)(screenW * 0.012f);

        Button prevStation = makeButton("◀  搜台", Color.rgb(0x2B,0x38,0x50), screenH * 0.042f);
        Button minus = makeButton("−0.1", Color.rgb(0x22,0x2C,0x3F), screenH * 0.044f);
        scanBtn = makeButton("一键扫台", Color.rgb(0x2F, 0x7C, 0xF6), screenH * 0.034f);
        Button plus = makeButton("+0.1", Color.rgb(0x22,0x2C,0x3F), screenH * 0.044f);
        Button nextStation = makeButton("搜台  ▶", Color.rgb(0x2B,0x38,0x50), screenH * 0.042f);

        Button[] btns = {prevStation, minus, scanBtn, plus, nextStation};
        for (int i=0;i<btns.length;i++) {
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, btnH, 1f);
            if (i>0) blp.setMargins(gap, 0, 0, 0);
            controls.addView(btns[i], blp);
        }
        right.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        minus.setOnClickListener(v -> step(-STEP));
        plus.setOnClickListener(v -> step(STEP));
        prevStation.setOnClickListener(v -> seekDir(-1));
        nextStation.setOnClickListener(v -> seekDir(1));
        scanBtn.setOnClickListener(v -> {
            if (scanning) stopScan(); else startScan();
        });

        root.addView(right);
        setContentView(root);
        refreshFreq();
        refreshCurrentName();
        adapter.notifyDataSetChanged();
    }

    /** 城市按钮文案。 */
    void updateCityBtn() {
        if (cityBtn != null && db != null)
            cityBtn.setText("城市：" + db.city + (db.isAutoCity() ? "（自动）" : "（手动）") + "  切换");
    }

    /** 按频率取已保存的 RDS PS 名（扫台结果里）。 */
    String rdsNameForFreq(int freq) {
        for (Station s : stations)
            if (s.freq == freq) return s.name == null ? "" : s.name;
        return "";
    }

    /** 三级解析后的最终显示名。 */
    String displayNameForFreq(int freq) {
        return db.resolve(freq, rdsNameForFreq(freq));
    }

    /** 刷新右侧当前台名（调谐/切城市/改名后调用）。 */
    void refreshCurrentName() {
        if (nameText == null || db == null) return;
        String n = displayNameForFreq(currentFreq);
        nameText.setText(n.isEmpty() ? "未知电台（长按此处改名）" : n);
    }

    /** 城市选择/定位/更新对话框。 */
    void showCityDialog() {
        final String[] cities = db.cityList();
        String[] items = new String[cities.length + 2];
        items[0] = "重新定位城市（GPS优先·网络兜底）";
        items[1] = "在线更新频率库";
        for (int i = 0; i < cities.length; i++)
            items[i + 2] = cities[i] + (cities[i].equals(db.city) ? "（当前）" : "");
        new AlertDialog.Builder(this)
                .setTitle("当前：" + db.city + "（台名按城市频率表匹配）")
                .setItems(items, (d, w) -> {
                    if (w == 0) locateCity(true);
                    else if (w == 1) updateStationDb();
                    else {
                        db.setManualCity(cities[w - 2]);
                        onCityChanged("已切换到「" + db.city + "」频率表");
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 手动改名对话框。 */
    void showRenameDialog(final int freq) {
        final EditText et = new EditText(this);
        et.setText(displayNameForFreq(freq));
        et.setSelection(et.getText().length());
        et.setHint("输入该频率的电台名称");
        FrameLayout fl = new FrameLayout(this);
        int pad = (int) (screenW * 0.025f);
        fl.setPadding(pad, pad / 2, pad, 0);
        fl.addView(et);
        new AlertDialog.Builder(this)
                .setTitle(fmt(freq) + " MHz 改名")
                .setView(fl)
                .setPositiveButton("保存", (d, w) -> {
                    db.setManualName(freq, et.getText().toString());
                    refreshCurrentName();
                    adapter.notifyDataSetChanged();
                })
                .setNeutralButton("清除改名", (d, w) -> {
                    db.setManualName(freq, "");
                    refreshCurrentName();
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    void locateCity(final boolean manual) {
        if (manual) setStatus("正在联网定位城市…");
        final String oldCity = db.city;
        new Thread(() -> {
            final String r = db.locate();
            runOnUiThread(() -> {
                if (r.isEmpty()) {
                    if (!db.city.equals(oldCity))
                        onCityChanged("定位成功：" + db.city + "，已匹配本地频率表");
                    else if (manual)
                        setStatus("定位成功：" + db.city);
                } else if ("定位失败".equals(r)) {
                    if (manual) setStatus("GPS/网络均定位失败，当前使用：" + db.city);
                } else {
                    if (manual)
                        setStatus("定位到「" + r + "」，暂无该城市频率库，台名可长按手动添加");
                }
            });
        }, "fm-geo").start();
    }

    void updateStationDb() {
        setStatus("正在检查在线频率库…");
        new Thread(() -> {
            final String r = db.updateOnline();
            runOnUiThread(() -> {
                updateCityBtn();
                adapter.notifyDataSetChanged();
                refreshCurrentName();
                setStatus(r);
            });
        }, "fm-dbupdate").start();
    }

    void onCityChanged(String msg) {
        updateCityBtn();
        adapter.notifyDataSetChanged();
        refreshCurrentName();
        if (msg != null) setStatus(msg);
    }

    /** FrameLayout 的简单别名，避免名字冲突。 */
    static class FrameTransform extends FrameLayout {
        FrameTransform(Context c) { super(c); }
    }

    class StationAdapter extends BaseAdapter {
        @Override public int getCount() { return stations.size(); }
        @Override public Object getItem(int p) { return stations.get(p); }
        @Override public long getItemId(int p) { return stations.get(p).freq; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            Row row;
            if (convertView instanceof Row) {
                row = (Row) convertView;
            } else {
                row = new Row(MainActivity.this);
            }
            Station s = stations.get(pos);
            row.bind(s, s.freq == currentFreq);
            return row;
        }
    }

    class Row extends LinearLayout {
        View accent;
        TextView freq;
        TextView name;
        TextView level;

        Row(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            int h = (int)(screenH * 0.105f);
            setMinimumHeight(h);
            setPadding(0, 0, (int)(screenW*0.012f), 0);

            accent = new View(c);
            accent.setVisibility(INVISIBLE);
            addView(accent, new LayoutParams((int)(screenW*0.004f),
                    ViewGroup.LayoutParams.MATCH_PARENT));

            freq = new TextView(c);
            freq.setTextColor(Color.WHITE);
            freq.setTextSize((screenH*0.043f)/getResources().getDisplayMetrics().scaledDensity);
            freq.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            freq.setGravity(Gravity.CENTER_VERTICAL);
            freq.setMinWidth((int)(screenW*0.115f));
            freq.setPadding((int)(screenW*0.018f), 0, (int)(screenW*0.018f), 0);
            addView(freq, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            name = new TextView(c);
            name.setTextColor(Color.rgb(0xA9, 0xBB, 0xD6));
            name.setTextSize((screenH*0.032f)/getResources().getDisplayMetrics().scaledDensity);
            name.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setPadding((int)(screenW*0.006f), 0, (int)(screenW*0.01f), 0);
            addView(name, new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

            level = new TextView(c);
            level.setTextColor(Color.rgb(0x66, 0x7C, 0x9E));
            level.setTextSize((screenH*0.026f)/getResources().getDisplayMetrics().scaledDensity);
            level.setGravity(Gravity.CENTER);
            level.setMinWidth((int)(screenW*0.045f));
            addView(level, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        void bind(Station s, boolean selected) {
            freq.setText(fmt(s.freq));
            // 台名显示：手动改名 ＞ RDS PS ＞ 城市频率库
            String resolved = db == null ? "" : db.resolve(s.freq, s.name);
            String label;
            int labelColor;
            if (!resolved.isEmpty()) {
                label = resolved;
                labelColor = selected ? Color.rgb(0xE3, 0xEE, 0xFF) : Color.rgb(0xA9, 0xBB, 0xD6);
            } else if (s.level < RDS_MIN_LEVEL) {
                label = "信号弱·长按改名";
                labelColor = Color.rgb(0xE8, 0x93, 0x3C);
            } else {
                label = "未知电台·长按改名";
                labelColor = selected ? Color.rgb(0xE3, 0xEE, 0xFF) : Color.rgb(0xA9, 0xBB, 0xD6);
            }
            name.setText(label);
            level.setText(String.valueOf(s.level));
            accent.setVisibility(selected ? VISIBLE : INVISIBLE);
            accent.setBackgroundColor(Color.rgb(0x4E, 0xA1, 0xFF));
            setBackgroundColor(selected ? Color.argb(22, 78, 161, 255) : Color.TRANSPARENT);
            freq.setTextColor(selected ? Color.rgb(0xCF, 0xE4, 0xFF) : Color.WHITE);
            name.setTextColor(labelColor);
        }
    }

    static String fmt(int f) {
        return String.format(Locale.US, "%.2f", f / 100f);
    }

    /** RDS 解码所需的最低信号电平（标尺0-255，实测本地强台≈220+）。 */
    static final int RDS_MIN_LEVEL = 60;

    void refreshFreq() {
        freqText.setText(fmt(currentFreq));
        if (currentLevel <= 0) {
            signalText.setText("信号 --");
            signalText.setTextColor(Color.rgb(0x8B, 0x9A, 0xB2));
        } else if (currentLevel < RDS_MIN_LEVEL) {
            signalText.setText("信号 " + currentLevel + " · 弱，RDS需强信号");
            signalText.setTextColor(Color.rgb(0xE8, 0x93, 0x3C));
        } else {
            signalText.setText("信号 " + currentLevel);
            signalText.setTextColor(Color.rgb(0x6F, 0xC2, 0x6F));
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    void setStatus(final String s) {
        runOnUiThread(() -> { if (statusText != null) statusText.setText(s); });
    }

    void setName(final String s) {
        runOnUiThread(() -> nameText.setText(s == null || s.isEmpty() ? "未识别电台名" : s));
    }

    void tuneTo(int f, boolean user) {
        currentFreq = f;
        prefs.edit().putInt("freq", f).apply();
        refreshFreq();
        refreshCurrentName();   // 立即用本地频率库显示台名，不等 RDS
        setStatus("正在调谐 " + fmt(f) + " MHz…");
        new Thread(() -> {
            try {
                Fm.tune(f);
                // 原厂每次 tune 后立即 setmute 0；芯片上电/调谐默认静音，不解静音永远无声
                Fm.mute(0);
                Thread.sleep(250);
                currentLevel = Fm.level();
                setStatus("正在收听 " + fmt(f) + " MHz");
                runOnUiThread(this::refreshFreq);
                pollPsFor(f, 6);
            } catch (Throwable t) {
                setStatus("调谐失败：" + t.getMessage());
            }
        }, "fm-tune").start();
    }

    void pollPsFor(final int f, int times) {
        String got = "";
        for (int i=0;i<times;i++) {
            try { Thread.sleep(800); } catch (InterruptedException ignored) {}
            // 服务端按频率查PS历史表，即使已切走也能查
            got = Fm.psNameFor(f);
            if (got == null || got.isEmpty()) got = Fm.psName();
            if (got != null && got.length() >= 2) break;
        }
        final String ps = got == null ? "" : got.trim();
        runOnUiThread(() -> {
            // RDS 解码成功则记为该台 RDS 名；没解到绝不覆盖本地库/手动改名
            for (Station s : stations) {
                if (s.freq == f) {
                    if (!ps.isEmpty()) s.name = ps;
                    break;
                }
            }
            if (currentFreq == f) refreshCurrentName();
            adapter.notifyDataSetChanged();
            saveStations();
        });
    }

    void step(int d) {
        if (scanning || seeking) return;
        int f = currentFreq + d;
        if (f < FREQ_MIN) f = FREQ_MIN;
        if (f > FREQ_MAX) f = FREQ_MAX;
        tuneTo(f, true);
    }

    void seekDir(final int dir) {
        if (scanning || seeking) return;
        seeking = true;
        setStatus("搜索下一个电台…");
        new Thread(() -> {
            int found = 0;
            try {
                Fm.mute(1);   // 搜索过程静音，找到电台后再解静音（原厂点台后必发 setmute 0）
                int f = currentFreq;
                for (int k=0;k<(FREQ_MAX-FREQ_MIN)/STEP;k++) {
                    f += dir * STEP;
                    if (f > FREQ_MAX) f = FREQ_MIN;
                    if (f < FREQ_MIN) f = FREQ_MAX;
                    final int ff = f;
                    Fm.tune(f);
                    Thread.sleep(80);
                    int lv = Fm.level();
                    runOnUiThread(() -> {
                        currentFreq = ff;
                        currentLevel = lv;
                        refreshFreq();
                    });
                    if (lv >= SEEK_THRESHOLD) { found = f; break; }
                }
            } catch (Throwable ignored) {}
            final int result = found;
            if (result > 0) Fm.mute(0);   // 落台后立即解静音
            runOnUiThread(() -> {
                seeking = false;
                if (result > 0) {
                    currentFreq = result;
                    prefs.edit().putInt("freq", result).apply();
                    refreshFreq();
                    refreshCurrentName();
                    statusText.setText("已找到 " + fmt(result) + " MHz");
                    pollPsFor(result, 6);
                } else {
                    statusText.setText("没有搜到更多电台");
                }
            });
        }, "fm-seek").start();
    }

    void startScan() {
        scanning = true;
        scanBtn.setText("停止扫描");
        stations.clear();
        adapter.notifyDataSetChanged();
        nameText.setText("扫描中");
        setStatus("正在扫描 87.5–108.0 MHz…");

        new Thread(() -> {
            int n = (FREQ_MAX - FREQ_MIN) / STEP + 1;
            int[] freqs = new int[n];
            int[] levels = new int[n];
            try {
                // 设备已在 onCreate 按原厂序列初始化（setarea→rdsOn→powerUp(频率)），
                // 这里绝不能再 powerUp(1)：参数1=0.01MHz，会把芯片重新上电到带外频率。
                Fm.mute(1);
                for (int i=0;i<n;i++) {
                    if (!scanning) break;
                    int f = FREQ_MIN + i * STEP;
                    freqs[i] = f;
                    Fm.tune(f);
                    Thread.sleep(110);
                    levels[i] = Fm.level();
                    final int ff = f, lv = levels[i];
                    runOnUiThread(() -> {
                        currentFreq = ff;
                        currentLevel = lv;
                        refreshFreq();
                        statusText.setText("扫描中  " + fmt(ff) + " MHz   信号 " + lv);
                    });
                }

                // 峰值检测：±300kHz 内只保留最强点，去掉邻频重复
                List<Station> peaks = new ArrayList<>();
                for (int i=0;i<n;i++) {
                    if (levels[i] < SCAN_THRESHOLD) continue;
                    boolean peak = true;
                    for (int j=Math.max(0,i-3); j<=Math.min(n-1,i+3); j++) {
                        if (j < i && levels[j] >= levels[i]) { peak = false; break; }
                        if (j > i && levels[j] > levels[i]) { peak = false; break; }
                    }
                    if (peak) peaks.add(new Station(freqs[i], levels[i]));
                }
                Collections.sort(peaks, Comparator.comparingInt(s -> s.freq));

                runOnUiThread(() -> {
                    stations.clear();
                    stations.addAll(peaks);
                    adapter.notifyDataSetChanged();
                    statusText.setText("找到 " + peaks.size() + " 个电台，正在匹配台名…");
                });

                // 逐台读取RDS PS名称（服务端边驻留边解码，需驻留1-4秒）；
                // 本地不发 RDS 的台，界面同步由城市频率库/手动改名即时补名
                for (int i=0;i<peaks.size() && scanning;i++) {
                    final Station s = peaks.get(i);
                    Fm.tune(s.freq);
                    Thread.sleep(1000);
                    String ps = "";
                    for (int t=0;t<3;t++) {
                        ps = Fm.psNameFor(s.freq);
                        if (ps == null || ps.isEmpty()) ps = Fm.psName();
                        if (ps != null && ps.trim().length() >= 2) { ps = ps.trim(); break; }
                        Thread.sleep(900);
                    }
                    s.name = ps == null ? "" : ps.trim();
                    final String shown = db.resolve(s.freq, s.name);
                    final int idx = i;
                    runOnUiThread(() -> {
                        adapter.notifyDataSetChanged();
                        statusText.setText("匹配台名 " + (idx+1) + "/" + peaks.size()
                                + "  " + fmt(s.freq) + "  " + shown);
                    });
                }
                saveStations();

                if (scanning && !peaks.isEmpty()) {
                    final Station first = peaks.get(0);
                    runOnUiThread(() -> {
                        currentFreq = first.freq;
                        prefs.edit().putInt("freq", first.freq).apply();
                        refreshFreq();
                        refreshCurrentName();
                        statusText.setText("扫描完成，共 " + peaks.size() + " 个电台");
                    });
                    // 扫台过程 tune 了 200+ 频率，芯片音频通路可能进入异常态，
                    // 仅 mute(0) 不足以恢复。重新对齐原厂初始化序列：
                    // setArea → powerUp(频率) → tune → mute(0)，确保音频通路恢复。
                    Fm.setArea(FM_AREA);
                    Fm.powerUp(first.freq);
                    Fm.setArea(FM_AREA);
                    Fm.tune(first.freq);
                    Fm.mute(0);
                    fmAudioParam("route-fm=speaker");
                    Thread.sleep(250);
                    first.level = Fm.level();
                    currentLevel = first.level;
                    runOnUiThread(this::refreshFreq);
                } else {
                    Fm.mute(0);
                    runOnUiThread(() -> statusText.setText("扫描已停止"));
                }
            } catch (Throwable t) {
                try { Fm.mute(0); } catch (Throwable ignored) {}
                setStatus("扫描失败：" + t.getMessage());
            } finally {
                scanning = false;
                runOnUiThread(() -> {
                    scanBtn.setText("一键扫台");
                    refreshFreq();
                });
            }
        }, "fm-scan").start();
    }

    void stopScan() {
        scanning = false;
        setStatus("正在停止…");
    }

    final Runnable levelTicker = new Runnable() {
        @Override public void run() {
            if (!scanning && !seeking) {
                new Thread(() -> {
                    int lv = Fm.level();
                    if (lv >= 0) {
                        currentLevel = lv;
                        runOnUiThread(MainActivity.this::refreshFreq);
                    }
                }).start();
            }
            ui.postDelayed(this, 3000);
        }
    };

    void saveStations() {
        StringBuilder sb = new StringBuilder();
        for (Station s : stations) {
            String n = (s.name == null ? "" : s.name).replace("|", " ");
            sb.append(s.freq).append('|').append(s.level).append('|').append(n).append('\n');
        }
        prefs.edit().putString("stations", sb.toString()).apply();
    }

    void loadStations() {
        stations.clear();
        String raw = prefs.getString("stations", "");
        if (raw.isEmpty()) return;
        for (String line : raw.split("\n")) {
            String[] p = line.split("\\|", 3);
            if (p.length >= 2) {
                try {
                    Station s = new Station(Integer.parseInt(p[0]), Integer.parseInt(p[1]));
                    s.name = p.length >= 3 ? p[2] : "";
                    stations.add(s);
                } catch (Exception ignored) {}
            }
        }
    }
}

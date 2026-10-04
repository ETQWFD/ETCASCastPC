/* ETCAS 投屏 · 电脑投屏端 v1.0.7（纯 C 语言 / Win32 原生，无 Java）
   开发者：ETC 协会 · 翻译者：POAI · MIT License */
#include "common.h"
#ifndef ETCAS_PROPID_DEF
#define ETCAS_PROPID_DEF
typedef unsigned long PROPID;
#endif
#include <gdiplus.h>
#include <objidl.h>
#include <commdlg.h>
#include <wininet.h>

#pragma comment(lib, "gdiplus")
#pragma comment(lib, "wininet")
#pragma comment(lib, "ole32")

#define FILEPORT 8388
#define MAX_DEVS 64

static HWND g_hwnd = NULL;
static char g_myip[64] = "0.0.0.0";
static volatile int g_srv_stop = 0;

typedef struct { char ip[64]; int port; int kind; char name[128]; } dev_t;
static dev_t g_devs[MAX_DEVS];
static volatile int g_dev_count = 0;
static volatile int g_sel = -1;
static CRITICAL_SECTION g_dev_cs;

static char g_cur_url[2048] = {0};
static char g_sel_ip[64] = {0};
static int g_sel_port = 0;
static HWND g_status = NULL;
static int g_sel_kind_is_own = 0;
static volatile int g_mirror_run = 0;
static volatile int g_mirror_stop = 0;

/* 设备类型：1=自家接收端 2=DLNA */
static int dev_is_own(const char *ip, int port) {
    char body[8192];
    long st = 0;
    char *r = etc_http_get(ip, port, "/etcas/info", 2500, &st);
    if (r) { free(r); return 1; }
    return 0;
}

/* 主动探测：自家 9170 或常见 DLNA 端口 */
static void probe_dev(const char *ip) {
    int ports[] = {9170, 80, 8080, 8060, 9000, 49152, 5000, 1900};
    for (int i = 0; i < 8 && g_dev_count < MAX_DEVS; i++) {
        char body[8192];
        long st = 0;
        char *r = etc_http_get(ip, ports[i], "/rootDesc.xml", 2000, &st);
        if (r && st == 200) {
            char name[128] = "DLNA 设备";
            const char *fn = strstr(r, "<friendlyName>");
            if (fn) {
                const char *fe = strstr(fn, "</friendlyName>");
                if (fe && fe > fn) {
                    int l = (int)(fe - fn - 14);
                    if (l > 120) l = 120;
                    memcpy(name, fn + 14, l);
                    name[l] = 0;
                }
            }
            int own = dev_is_own(ip, ports[i]);
            EnterCriticalSection(&g_dev_cs);
            int dup = 0;
            for (int d = 0; d < g_dev_count; d++)
                if (strcmp(g_devs[d].ip, ip) == 0) { dup = 1; break; }
            if (!dup) {
                dev_t *dv = &g_devs[g_dev_count++];
                snprintf(dv->ip, 64, "%s", ip);
                dv->port = ports[i];
                dv->kind = own ? 1 : 2;
                snprintf(dv->name, 128, "%s [%s]", name, own ? "ETCAS" : "DLNA");
            }
            LeaveCriticalSection(&g_dev_cs);
            free(r);
            InvalidateRect(g_hwnd, NULL, FALSE);
            break;
        }
        if (r) free(r);
    }
}

static DWORD WINAPI scan_thread(LPVOID p) {
    (void)p;
    EnterCriticalSection(&g_dev_cs);
    g_dev_count = 0;
    LeaveCriticalSection(&g_dev_cs);
    /* SSDP 组播 */
    SOCKET s = socket(AF_INET, SOCK_DGRAM, 0);
    if (s != INVALID_SOCKET) {
        char req[512];
        int n = snprintf(req, sizeof(req),
            "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"
            "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n");
        struct sockaddr_in mcast;
        mcast.sin_family = AF_INET;
        mcast.sin_port = htons(1900);
        mcast.sin_addr.s_addr = inet_addr("239.255.255.250");
        sendto(s, req, n, 0, (struct sockaddr *)&mcast, sizeof(mcast));
        int tm = 0;
        while (tm < 10000) {
            char buf[4096];
            struct sockaddr_in from;
            int flen = sizeof(from);
            fd_set rf;
            struct timeval tv;
            FD_ZERO(&rf); FD_SET(s, &rf);
            tv.tv_sec = 0; tv.tv_usec = 200000;
            int r = select(0, &rf, NULL, NULL, &tv);
            if (r <= 0) { tm += 200; continue; }
            int got = recvfrom(s, buf, sizeof(buf) - 1, 0, (struct sockaddr *)&from, &flen);
            if (got <= 0) { tm += 200; continue; }
            buf[got] = 0;
            const char *loc = strstr(buf, "LOCATION:");
            char u[1024] = {0};
            if (loc) {
                const char *e = strchr(loc, '\r');
                int l = e ? (int)(e - loc - 9) : (int)strlen(loc + 9);
                if (l > 1023) l = 1023;
                memcpy(u, loc + 9, l);
                u[l] = 0;
                /* 解析 http://ip:port/rootDesc.xml */
                char ip[64] = {0};
                int port = 80;
                if (sscanf(u, "http://%63[^:]:%d", ip, &port) == 2) {
                    probe_dev(ip);
                } else if (sscanf(u, "http://%63[^/]", ip) == 1) {
                    probe_dev(ip);
                }
            }
            tm += 200;
        }
        closesocket(s);
    }
    /* 子网扫描 */
    char prefix[64];
    snprintf(prefix, sizeof(prefix), "%s", g_myip);
    char *dot = strrchr(prefix, '.');
    if (dot) *dot = 0;
    for (int i = 1; i < 255 && g_dev_count < MAX_DEVS; i++) {
        char ip[64];
        snprintf(ip, sizeof(ip), "%s.%d", prefix, i);
        if (strcmp(ip, g_myip) == 0) continue;
        probe_dev(ip);
    }
    InvalidateRect(g_hwnd, NULL, FALSE);
    return 0;
}

/* ---- 文件服务 /proxy ---- */
static char g_file_path[1024] = {0};
static char g_proxy_url[4096] = {0};

static int on_file_req(const char *method, const char *path, const char *body, int bodylen,
                       char *reply, int replycap) {
    (void)method; (void)body; (void)bodylen;
    if (strncmp(path, "/file", 5) == 0) {
        const char *q = strchr(path, '?');
        if (!q) return 0;
        const char *pp = strstr(q, "p=");
        if (!pp) return 0;
        char enc[1024] = {0};
        int l = 0;
        for (const char *c = pp + 2; *c && *c != '&' && l < 1023; c++) enc[l++] = *c;
        enc[l] = 0;
        char dec[1024] = {0};
        /* 简单 URL 解码 */
        int dl = 0;
        for (int i = 0; enc[i] && dl < 1023; i++) {
            if (enc[i] == '%' && enc[i + 1] && enc[i + 2]) {
                unsigned int v = 0;
                sscanf(enc + i + 1, "%2x", &v);
                dec[dl++] = (char)v;
                i += 2;
            } else dec[dl++] = enc[i];
        }
        dec[dl] = 0;
        FILE *f = fopen(dec, "rb");
        if (!f) return 0;
        fseek(f, 0, SEEK_END);
        long sz = ftell(f);
        fseek(f, 0, SEEK_SET);
        if (sz <= 0 || sz > replycap - 16) { fclose(f); return 0; }
        int rn = (int)fread(reply, 1, sz, f);
        fclose(f);
        return rn;
    }
    if (strncmp(path, "/proxy", 6) == 0) {
        const char *q = strchr(path, '?');
        if (!q) return 0;
        const char *pp = strstr(q, "url=");
        if (!pp) return 0;
        char enc[4096] = {0};
        int l = 0;
        for (const char *c = pp + 4; *c && *c != '&' && l < 4095; c++) enc[l++] = *c;
        enc[l] = 0;
        char dec[4096] = {0};
        int dl = 0;
        for (int i = 0; enc[i] && dl < 4095; i++) {
            if (enc[i] == '%' && enc[i + 1] && enc[i + 2]) {
                unsigned int v = 0;
                sscanf(enc + i + 1, "%2x", &v);
                dec[dl++] = (char)v;
                i += 2;
            } else dec[dl++] = enc[i];
        }
        dec[dl] = 0;
        /* 解析 dec 的 host/port/path */
        char host[256] = {0}, path2[2048] = {0};
        int port = 80;
        const char *h = strstr(dec, "://");
        if (!h) return 0;
        h += 3;
        const char *sl = strchr(h, '/');
        if (!sl) { snprintf(host, sizeof(host), "%s", h); strcpy(path2, "/"); }
        else {
            int hl = (int)(sl - h);
            if (hl > 255) hl = 255;
            memcpy(host, h, hl);
            host[hl] = 0;
            snprintf(path2, sizeof(path2), "%s", sl);
        }
        const char *colon = strchr(host, ':');
        if (colon) {
            *((char *)colon) = 0;
            port = atoi(colon + 1);
        }
        long st = 0;
        char *r = etc_http_get(host, port, path2, 10000, &st);
        if (r) {
            int len = (int)strlen(r);
            if (len > replycap) len = replycap;
            memcpy(reply, r, len);
            free(r);
            return len;
        }
        return 0;
    }
    return 0;
}

/* ---- 屏幕直播 ---- */
static void jpeg_encode_hdc(HDC hdc, int w, int h, unsigned char **out, int *outlen) {
    *out = NULL; *outlen = 0;
    HDC mem = CreateCompatibleDC(hdc);
    HBITMAP bmp = CreateCompatibleBitmap(hdc, w, h);
    HBITMAP old = (HBITMAP)SelectObject(mem, bmp);
    BitBlt(mem, 0, 0, w, h, hdc, 0, 0, SRCCOPY);
    BITMAPINFO bi;
    memset(&bi, 0, sizeof(bi));
    bi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    bi.bmiHeader.biWidth = w;
    bi.bmiHeader.biHeight = -h;
    bi.bmiHeader.biPlanes = 1;
    bi.bmiHeader.biBitCount = 32;
    bi.bmiHeader.biCompression = BI_RGB;
    unsigned char *px = (unsigned char *)malloc(w * h * 4);
    if (px) {
        if (GetDIBits(mem, bmp, 0, h, px, &bi, DIB_RGB_COLORS)) {
            Gdiplus::Bitmap bm(w, h, w * 4, PixelFormat32bppARGB, px);
            CLSID enc;
            int got = 0;
            UINT cnt = 0, sz2 = 0;
            Gdiplus::GetImageEncodersSize(&cnt, &sz2);
            Gdiplus::ImageCodecInfo *inf = (Gdiplus::ImageCodecInfo *)malloc(sz2);
            if (inf) {
                Gdiplus::GetImageEncoders(cnt, sz2, inf);
                for (UINT i = 0; i < cnt; i++) {
                    if (wcscmp(inf[i].MimeType, L"image/jpeg") == 0) {
                        enc = inf[i].Clsid; got = 1; break;
                    }
                }
                free(inf);
            }
            if (got) {
                IStream *st = NULL;
                CreateStreamOnHGlobal(NULL, TRUE, &st);
                if (st) {
                    bm.Save(st, &enc, NULL);
                    STATSTG ss;
                    st->Stat(&ss, STATFLAG_NONAME);
                    ULONG rn = 0;
                    *outlen = (int)ss.cbSize.QuadPart;
                    *out = (unsigned char *)malloc(*outlen + 4);
                    LARGE_INTEGER z;
                    z.QuadPart = 0;
                    st->Seek(z, STREAM_SEEK_SET, NULL);
                    st->Read(*out, *outlen, &rn);
                    st->Release();
                }
            }
        }
        free(px);
    }
    SelectObject(mem, old);
    DeleteObject(bmp);
    DeleteDC(mem);
}

static void mirror_send(const char *ip, int port, const unsigned char *jpg, int len) {
    SOCKET s = etc_tcp_connect(ip, port, 2000);
    if (s == -1) return;
    char hdr[512];
    int n = snprintf(hdr, sizeof(hdr),
        "POST /etcas/live HTTP/1.1\r\nHost: %s:%d\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\nConnection: close\r\n\r\n",
        ip, port, len);
    send(s, hdr, n, 0);
    send(s, (const char *)jpg, len, 0);
    closesocket(s);
}

static DWORD WINAPI mirror_thread(LPVOID p) {
    (void)p;
    int w = GetSystemMetrics(SM_CXSCREEN), h = GetSystemMetrics(SM_CYSCREEN);
    HDC scr = GetDC(NULL);
    while (!g_mirror_stop) {
        unsigned char *jpg = NULL;
        int jlen = 0;
        jpeg_encode_hdc(scr, w, h, &jpg, &jlen);
        if (jpg) {
            mirror_send(g_sel_ip, g_sel_port, jpg, jlen);
            free(jpg);
        }
        Sleep(120);  /* ~8fps */
    }
    ReleaseDC(NULL, scr);
    return 0;
}

/* ---- 投屏动作 ---- */
static void send_soap(const char *ip, int port, const char *action, const char *url) {
    char body[8192];
    if (strcmp(action, "SetAVTransportURI") == 0) {
        snprintf(body, sizeof(body),
            "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID><CurrentURI>%s</CurrentURI><CurrentURIMetaData></CurrentURIMetaData></u:SetAVTransportURI></s:Body></s:Envelope>",
            url);
        etc_http_post_json(ip, port, "/ctl", body, 5000, NULL);
        etc_http_post_json(ip, port, "/ctl",
            "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID><Speed>1</Speed></u:Play></s:Body></s:Envelope>",
            5000, NULL);
    } else if (strcmp(action, "Stop") == 0) {
        etc_http_post_json(ip, port, "/ctl",
            "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:Stop xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID></u:Stop></s:Body></s:Envelope>",
            5000, NULL);
    }
}

static void own_play(const char *ip, int port, const char *url, int isimg) {
    char body[4096];
    snprintf(body, sizeof(body), "{\"url\":\"%s\",\"kind\":\"%s\"}", url, isimg ? "img" : "video");
    etc_http_post_json(ip, port, "/etcas/play", body, 5000, NULL);
}

static void do_cast_file(void) {
    OPENFILENAMEW ofn;
    wchar_t file[MAX_PATH] = {0};
    memset(&ofn, 0, sizeof(ofn));
    ofn.lStructSize = sizeof(ofn);
    ofn.hwndOwner = g_hwnd;
    ofn.lpstrFilter = L"视频/图片/文件\0*.*\0\0";
    ofn.lpstrFile = file;
    ofn.nMaxFile = MAX_PATH;
    ofn.Flags = OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST;
    if (!GetOpenFileNameW(&ofn)) return;
    char path[1024] = {0};
    WideCharToMultiByte(CP_UTF8, 0, file, -1, path, sizeof(path), NULL, NULL);
    snprintf(g_file_path, sizeof(g_file_path), "%s", path);
    char enc[2048] = {0};
    etc_urlencode(path, enc, sizeof(enc));
    char url[4096];
    snprintf(url, sizeof(url), "http://%s:%d/file?p=%s", g_myip, FILEPORT, enc);
    snprintf(g_cur_url, sizeof(g_cur_url), "%s", url);
    int isimg = (strstr(path, ".jpg") || strstr(path, ".png") || strstr(path, ".jpeg") ||
                 strstr(path, ".bmp") || strstr(path, ".gif")) ? 1 : 0;
    if (g_sel_port == 9170 && g_sel_kind_is_own) own_play(g_sel_ip, g_sel_port, url, isimg);
    else send_soap(g_sel_ip, g_sel_port, "SetAVTransportURI", url);
    SetWindowTextW(g_status, L"已开始投屏本地文件");
    InvalidateRect(g_hwnd, NULL, FALSE);
}

/* B站解析 */
static int bili_resolve(const char *bvid, char *outurl, int cap) {
    char p1[512];
    snprintf(p1, sizeof(p1), "/x/web-interface/view?bvid=%s", bvid);
    long st = 0;
    char *r = etc_http_get("api.bilibili.com", 443, p1, 8000, &st);
    if (!r) return 0;
    long long cid = 0;
    etc_json_num(r, "\"cid\":", &cid);
    free(r);
    if (cid == 0) return 0;
    char p2[1024];
    snprintf(p2, sizeof(p2),
        "/x/player/playurl?bvid=%s&cid=%lld&qn=127&fnval=16&fourk=1", bvid, cid);
    r = etc_http_get("api.bilibili.com", 443, p2, 8000, &st);
    if (!r) return 0;
    char u[2048] = {0};
    /* 找第一个 "url":" */
    const char *u0 = strstr(r, "\"url\":\"");
    if (!u0) { free(r); return 0; }
    int l = 0;
    for (const char *c = u0 + 7; *c && *c != '"' && l < cap - 1; c++) outurl[l++] = *c;
    outurl[l] = 0;
    /* 反转义 \u0026 -> & 等 */
    char tmp[2048];
    snprintf(tmp, sizeof(tmp), "%s", outurl);
    int t = 0;
    for (int i = 0; tmp[i] && t < cap - 1; i++) {
        if (tmp[i] == '\\' && tmp[i + 1] == 'u') {
            unsigned int v = 0;
            sscanf(tmp + i + 2, "%4x", &v);
            outurl[t++] = (char)v;
            i += 5;
        } else outurl[t++] = tmp[i];
    }
    outurl[t] = 0;
    free(r);
    return 1;
}

/* ---- AI 客服 ---- */
static HWND g_ai_edit = NULL;
static char g_ai_key[] = "sk-iulr7ePG32AVIKBvXFs6m5Vgik2osFzluDMShJwGubyJCxnt";
static char g_ai_reply[4096] = {0};

static DWORD WINAPI ai_thread(LPVOID p) {
    char *q = (char *)p;
    char body[8192];
    snprintf(body, sizeof(body),
        "{\"model\":\"DeepSeek-V4-Flash\",\"messages\":[{\"role\":\"system\",\"content\":\"你是 ETCAS 投屏 的 AI 客服（开发者 ETC 协会）。请简短专业地回答用户关于投屏、配对、官网 https://etc.os.kg 等问题。不要编造版本信息。\"},{\"role\":\"user\",\"content\":\"%s\"}],\"stream\":false}",
        q);
    free(q);
    long st = 0;
    char *r = etc_http_post_json("api.hcnsec.cn", 443, "/v1/chat/completions", body, 20000, &st);
    if (!r) {
        snprintf(g_ai_reply, sizeof(g_ai_reply), "网络错误，请稍后再试。");
    } else {
        char c[4096] = {0};
        if (etc_json_str(r, "\"content\":", c, sizeof(c))) {
            snprintf(g_ai_reply, sizeof(g_ai_reply), "%s", c);
        } else {
            snprintf(g_ai_reply, sizeof(g_ai_reply), "AI 客服暂时没有回复，请稍后再试。");
        }
        free(r);
    }
    PostMessageW(g_ai_edit, WM_SETTEXT, 0, (LPARAM)(LPCWSTR)L"");  /* 触发刷新 */
    return 0;
}


/* ---- UI ---- */

int InputBox(HWND parent, const wchar_t *title, const wchar_t *prompt, char *buf, int buflen);

static void ui_add_dev(HWND lb) {
    SendMessageW(lb, LB_RESETCONTENT, 0, 0);
    for (int i = 0; i < g_dev_count; i++) {
        char line[256];
        snprintf(line, sizeof(line), "%s  (%s:%d)", g_devs[i].name, g_devs[i].ip, g_devs[i].port);
        SendMessageA(lb, LB_ADDSTRING, 0, (LPARAM)line);
    }
}

static void on_connect(void) {
    if (g_sel < 0 || g_sel >= g_dev_count) {
        MessageBoxW(g_hwnd, L"请先在列表中选择一个设备。", L"ETCAS 投屏", MB_OK);
        return;
    }
    snprintf(g_sel_ip, sizeof(g_sel_ip), "%s", g_devs[g_sel].ip);
    g_sel_port = g_devs[g_sel].port;
    g_sel_kind_is_own = g_devs[g_sel].kind;
    if (g_devs[g_sel].kind == 1) {
        /* 自家：输配对码 */
        char buf[64] = {0};
        if (!InputBox(g_hwnd, L"输入配对码", L"请输入接收端显示的 6 位配对码：", buf, 64)) return;
        char body[128];
        snprintf(body, sizeof(body), "{\"key\":\"%s\"}", buf);
        long st = 0;
        char *r = etc_http_post_json(g_sel_ip, g_sel_port, "/etcas/pair", body, 5000, &st);
        char ok[32] = {0};
        if (r) { etc_json_str(r, "ok", ok, sizeof(ok)); free(r); }
        if (strcmp(ok, "true") != 0) {
            MessageBoxW(g_hwnd, L"配对码不正确，请核对接收端显示的配对码。", L"ETCAS 投屏", MB_OK | MB_ICONWARNING);
            return;
        }
        SetWindowTextW(g_status, L"已连接 ETCAS 接收端（配对成功）");
    } else {
        SetWindowTextW(g_status, L"已连接 DLNA 设备");
    }
    InvalidateRect(g_hwnd, NULL, FALSE);
}

static void do_link(void) {
    char buf[2048] = {0};
    if (!InputBox(g_hwnd, L"链接投屏", L"请输入哔哩哔哩链接或 BV 号：\n（如 https://www.bilibili.com/video/BVxxxx 或直接 BVxxxx）", buf, 2048)) return;
    char bvid[128] = {0};
    const char *bv = strstr(buf, "BV");
    if (bv) {
        int l = 0;
        for (const char *c = bv; *c && *c != '?' && *c != '/' && l < 127; c++) bvid[l++] = *c;
        bvid[l] = 0;
    } else {
        snprintf(bvid, sizeof(bvid), "%s", buf);
    }
    char url[2048] = {0};
    SetWindowTextW(g_status, L"正在解析链接...");
    if (!bili_resolve(bvid, url, sizeof(url))) {
        MessageBoxW(g_hwnd, L"解析失败：链接无效或网络不可用。", L"ETCAS 投屏", MB_OK | MB_ICONWARNING);
        SetWindowTextW(g_status, L"解析失败");
        return;
    }
    char enc[4096] = {0};
    etc_urlencode(url, enc, sizeof(enc));
    char purl[4096];
    snprintf(purl, sizeof(purl), "http://%s:%d/proxy?url=%s", g_myip, FILEPORT, enc);
    snprintf(g_cur_url, sizeof(g_cur_url), "%s", purl);
    if (g_sel_kind_is_own) own_play(g_sel_ip, g_sel_port, purl, 0);
    else send_soap(g_sel_ip, g_sel_port, "SetAVTransportURI", purl);
    SetWindowTextW(g_status, L"已开始链接投屏");
}

static void do_rate(int pct) {
    if (g_sel_port == 9170 && g_sel_kind_is_own) {
        char body[128];
        snprintf(body, sizeof(body), "{\"rate\":%d}", pct);
        etc_http_post_json(g_sel_ip, g_sel_port, "/etcas/speed", body, 5000, NULL);
    }
    wchar_t msg[128];
    swprintf(msg, 128, L"倍速已设为 %d%%", pct);
    SetWindowTextW(g_status, msg);
}

static void do_stop(void) {
    g_mirror_stop = 1;
    if (g_sel_port == 9170 && g_sel_kind_is_own) {
        etc_http_post_json(g_sel_ip, g_sel_port, "/etcas/livestop", "{}", 5000, NULL);
    }
    if (g_sel_port && g_sel_port != 9170) send_soap(g_sel_ip, g_sel_port, "Stop", NULL);
    else if (g_sel_port == 9170) {
        char body[128];
        snprintf(body, sizeof(body), "{\"url\":\"\",\"kind\":\"stop\"}");
        etc_http_post_json(g_sel_ip, g_sel_port, "/etcas/play", body, 5000, NULL);
    }
    SetWindowTextW(g_status, L"已停止投屏");
}

static void do_mirror(void) {
    if (g_sel_port != 9170 || !g_sel_kind_is_own) {
        MessageBoxW(g_hwnd, L"屏幕直播需要连接到 ETCAS 自家接收端。", L"ETCAS 投屏", MB_OK);
        return;
    }
    g_mirror_stop = 0;
    g_mirror_run = 1;
    CreateThread(NULL, 0, mirror_thread, NULL, 0, NULL);
    SetWindowTextW(g_status, L"屏幕直播中...（点“停止投屏”结束）");
}


/* ---- 输入框小窗口 ---- */
static HWND g_inp_edit = NULL;
static char *g_inp_buf = NULL;

static LRESULT CALLBACK inp_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
    case WM_CREATE: {
        g_inp_edit = CreateWindowExW(0, L"EDIT", L"", WS_CHILD | WS_VISIBLE | WS_BORDER,
            10, 40, 360, 28, hwnd, (HMENU)1, (HINSTANCE)GetWindowLongPtrW(hwnd, GWLP_HINSTANCE), NULL);
        CreateWindowExW(0, L"BUTTON", L"确定", WS_CHILD | WS_VISIBLE | BS_DEFPUSHBUTTON,
            200, 80, 80, 30, hwnd, (HMENU)IDOK, (HINSTANCE)GetWindowLongPtrW(hwnd, GWLP_HINSTANCE), NULL);
        CreateWindowExW(0, L"BUTTON", L"取消", WS_CHILD | WS_VISIBLE,
            290, 80, 80, 30, hwnd, (HMENU)IDCANCEL, (HINSTANCE)GetWindowLongPtrW(hwnd, GWLP_HINSTANCE), NULL);
        return 0;
    }
    case WM_COMMAND:
        if (LOWORD(wp) == IDOK) {
            GetWindowTextA(g_inp_edit, g_inp_buf, 2048);
            DestroyWindow(hwnd);
            return 0;
        }
        if (LOWORD(wp) == IDCANCEL) {
            g_inp_buf[0] = 0;
            DestroyWindow(hwnd);
            return 0;
        }
        break;
    case WM_CLOSE:
        g_inp_buf[0] = 0;
        DestroyWindow(hwnd);
        return 0;
    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

int InputBox(HWND parent, const wchar_t *title, const wchar_t *prompt, char *buf, int buflen) {
    static WNDCLASSW ic;
    if (!(ic.lpfnWndProc)) {
        memset(&ic, 0, sizeof(ic));
        ic.lpfnWndProc = inp_proc;
        ic.hInstance = (HINSTANCE)GetWindowLongPtrW(parent, GWLP_HINSTANCE);
        ic.hbrBackground = (HBRUSH)(COLOR_BTNFACE + 1);
        ic.lpszClassName = L"ETCAS_INPUTW";
        RegisterClassW(&ic);
    }
    g_inp_buf = buf;
    HWND dlg = CreateWindowExW(0, L"ETCAS_INPUTW", title, WS_CAPTION | WS_SYSMENU | WS_OVERLAPPED,
        0, 0, 400, 150, parent, NULL, (HINSTANCE)GetWindowLongPtrW(parent, GWLP_HINSTANCE), NULL);
    if (!dlg) return 0;
    /* 提示文字 */
    CreateWindowExW(0, L"STATIC", prompt, WS_CHILD | WS_VISIBLE,
        10, 10, 380, 30, dlg, NULL, (HINSTANCE)GetWindowLongPtrW(parent, GWLP_HINSTANCE), NULL);
    RECT rc;
    GetWindowRect(parent, &rc);
    SetWindowPos(dlg, NULL, rc.left + 120, rc.top + 120, 0, 0, SWP_NOSIZE | SWP_NOZORDER);
    ShowWindow(dlg, SW_SHOWNORMAL);
    UpdateWindow(dlg);
    SetFocus(g_inp_edit);
    /* 模态循环 */
    MSG m;
    while (IsWindow(dlg)) {
        while (PeekMessageW(&m, NULL, 0, 0, PM_REMOVE)) {
            if (m.message == WM_QUIT) return 0;
            TranslateMessage(&m);
            DispatchMessageW(&m);
        }
        Sleep(20);
    }
    return buf[0] != 0;
}

/* ---- AI 客服窗口 ---- */
static LRESULT CALLBACK ai_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
    case WM_CREATE: {
        g_ai_edit = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"", WS_CHILD | WS_VISIBLE |
            WS_VSCROLL | ES_MULTILINE | ES_READONLY | ES_AUTOVSCROLL,
            10, 10, 420, 300, hwnd, (HMENU)1, (HINSTANCE)GetWindowLongPtrW(hwnd, GWLP_HINSTANCE), NULL);
        CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"", WS_CHILD | WS_VISIBLE | WS_BORDER,
            10, 320, 420, 28, hwnd, (HMENU)2, (HINSTANCE)GetWindowLongPtrW(hwnd, GWLP_HINSTANCE), NULL);
        CreateWindowExW(0, L"BUTTON", L"发送", WS_CHILD | WS_VISIBLE | BS_DEFPUSHBUTTON,
            300, 356, 130, 32, hwnd, (HMENU)3, (HINSTANCE)GetWindowLongPtrW(hwnd, GWLP_HINSTANCE), NULL);
        SetWindowTextW(g_ai_edit, L"你好，我是 ETCAS 投屏 的 AI 客服（ETC 协会）\r\n可以问我投屏、配对、官网等问题。\r\n\r\n");
        return 0;
    }
    case WM_COMMAND:
        if (LOWORD(wp) == 3) {
            char q[2048] = {0};
            HWND inp = GetDlgItem(hwnd, 2);
            GetWindowTextA(inp, q, sizeof(q));
            if (!q[0]) return 0;
            SetWindowTextA(inp, "");
            char log[4096];
            GetWindowTextA(g_ai_edit, log, sizeof(log));
            char nl[4096];
            snprintf(nl, sizeof(nl), "%s我：%s\r\nAI：思考中...\r\n", log, q);
            SetWindowTextA(g_ai_edit, nl);
            CreateThread(NULL, 0, ai_thread, etc_strdup(q), 0, NULL);
            return 0;
        }
        break;
    case WM_SETTEXT: {
        /* 刷新：把 ai_reply 追加显示 */
        char log[4096];
        GetWindowTextA(g_ai_edit, log, sizeof(log));
        char *tail = strstr(log, "思考中...");
        if (tail) *tail = 0;
        char nl[8192];
        snprintf(nl, sizeof(nl), "%s%s\r\n", log, g_ai_reply);
        SetWindowTextA(g_ai_edit, nl);
        return 0;
    }
    case WM_CLOSE:
        DestroyWindow(hwnd);
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

static void open_ai(HWND parent) {
    static WNDCLASSW ac;
    if (!(ac.lpfnWndProc)) {
        memset(&ac, 0, sizeof(ac));
        ac.lpfnWndProc = ai_proc;
        ac.hInstance = (HINSTANCE)GetWindowLongPtrW(parent, GWLP_HINSTANCE);
        ac.hbrBackground = (HBRUSH)(COLOR_BTNFACE + 1);
        ac.lpszClassName = L"ETCAS_AIW";
        RegisterClassW(&ac);
    }
    HWND w = CreateWindowExW(WS_EX_TOPMOST, L"ETCAS_AIW", L"ETCAS 投屏 AI 客服",
        WS_CAPTION | WS_SYSMENU | WS_OVERLAPPED, 0, 0, 450, 430,
        parent, NULL, (HINSTANCE)GetWindowLongPtrW(parent, GWLP_HINSTANCE), NULL);
    if (w) {
        RECT rc;
        GetWindowRect(parent, &rc);
        SetWindowPos(w, NULL, rc.right - 470, rc.bottom - 470, 0, 0, SWP_NOSIZE | SWP_NOZORDER);
        ShowWindow(w, SW_SHOWNORMAL);
    }
}

/* ---- 主窗口控件 ID ---- */
#define ID_LB_DEV 100
#define ID_BT_LOCAL 101
#define ID_BT_LINK 102
#define ID_BT_MIRROR 103
#define ID_BT_ADD 104
#define ID_BT_SCAN 105
#define ID_BT_AI 106
#define ID_BT_STOP 107
#define ID_BT_R075 108
#define ID_BT_R1 109
#define ID_BT_R15 110
#define ID_BT_R2 111
#define ID_BT_CONN 112

static LRESULT CALLBACK wndproc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
    case WM_CREATE: {
        CreateWindowExW(0, L"BUTTON", L"本地投屏", WS_CHILD | WS_VISIBLE,
            16, 46, 150, 34, hwnd, (HMENU)ID_BT_LOCAL, (HINSTANCE)GetWindowLongPtrW(hwnd, GWLP_HINSTANCE), NULL);
        CreateWindowExW(0, L"BUTTON", L"屏幕直播", WS_CHILD | WS_VISIBLE,
            16, 88, 150, 34, hwnd, (HMENU)ID_BT_MIRROR, (HINSTANCE)GetWindowLongPtrW(hwnd, GWLP_HINSTANCE), NULL);
        CreateWindowExW(0, L"BUTTON", L"链接投屏（哔哩哔哩）", WS_CHILD | WS_VISIBLE,
            16, 130, 150, 34, hwnd, (HMENU)ID_BT_LINK, (HINSTANCE)GetWindowLongPtrW(hwnd, GWLP_HINSTANCE), NULL);
        CreateWindowExW(0, L"BUTTON", L"手动添加设备", WS_CHILD | WS_VISIBLE,
            16, 172, 150, 34, hwnd, (HMENU)ID_BT_ADD, (HINSTANCE)GetWindowLongPtrW(hwnd, GWLP_HINSTANCE), NULL);
        CreateWindowExW(0, L"BUTTON", L"刷新搜索设备", WS_CHILD | WS_VISIBLE,
            16, 214, 150, 34, hwnd, (HMENU)ID_BT_SCAN, (HINSTANCE)GetWindowLongPtrW(hwnd, GWLP_HINSTANCE), NULL);
        CreateWindowExW(0, L"BUTTON", L"连接所选设备", WS_CHILD | WS_VISIBLE,
            16, 256, 150, 34, hwnd, (HMENU)ID_BT_CONN, (HINSTANCE)GetWindowLongPtrW(hwnd, GWLP_HINSTANCE), NULL);
        CreateWindowExW(0, L"BUTTON", L"AI 客服", WS_CHILD | WS_VISIBLE,
            16, 298, 150, 34, hwnd, (HMENU)ID_BT_AI, (HINSTANCE)GetWindowLongPtrW(hwnd, GWLP_HINSTANCE), NULL);
        CreateWindowExW(0, L"BUTTON", L"停止投屏", WS_CHILD | WS_VISIBLE,
            16, 340, 150, 34, hwnd, (HMENU)ID_BT_STOP, (HINSTANCE)GetWindowLongPtrW(hwnd, GWLP_HINSTANCE), NULL);
        CreateWindowExW(0, L"BUTTON", L"0.5x", WS_CHILD | WS_VISIBLE,
            16, 392, 46, 30, hwnd, (HMENU)ID_BT_R075, (HINSTANCE)GetWindowLongPtrW(hwnd, GWLP_HINSTANCE), NULL);
        CreateWindowExW(0, L"BUTTON", L"1.0x", WS_CHILD | WS_VISIBLE,
            66, 392, 46, 30, hwnd, (HMENU)ID_BT_R1, (HINSTANCE)GetWindowLongPtrW(hwnd, GWLP_HINSTANCE), NULL);
        CreateWindowExW(0, L"BUTTON", L"1.5x", WS_CHILD | WS_VISIBLE,
            116, 392, 46, 30, hwnd, (HMENU)ID_BT_R15, (HINSTANCE)GetWindowLongPtrW(hwnd, GWLP_HINSTANCE), NULL);
        CreateWindowExW(0, L"BUTTON", L"2.0x", WS_CHILD | WS_VISIBLE,
            166, 392, 46, 30, hwnd, (HMENU)ID_BT_R2, (HINSTANCE)GetWindowLongPtrW(hwnd, GWLP_HINSTANCE), NULL);
        CreateWindowExW(WS_EX_CLIENTEDGE, L"LISTBOX", L"", WS_CHILD | WS_VISIBLE |
            WS_VSCROLL | WS_BORDER | LBS_NOTIFY,
            190, 46, 420, 330, hwnd, (HMENU)ID_LB_DEV, (HINSTANCE)GetWindowLongPtrW(hwnd, GWLP_HINSTANCE), NULL);
        g_status = CreateWindowExW(0, L"STATIC", L"等待连接设备...（请先在右侧列表选择设备）",
            WS_CHILD | WS_VISIBLE, 16, 430, 600, 22, hwnd, NULL,
            (HINSTANCE)GetWindowLongPtrW(hwnd, GWLP_HINSTANCE), NULL);
        CreateThread(NULL, 0, scan_thread, NULL, 0, NULL);
        return 0;
    }
    case WM_COMMAND:
        switch (LOWORD(wp)) {
        case ID_LB_DEV:
            if (HIWORD(wp) == LBN_SELCHANGE) {
                g_sel = (int)SendMessageW((HWND)lp, LB_GETCURSEL, 0, 0);
                if (g_sel >= 0 && g_sel < g_dev_count) {
                    snprintf(g_sel_ip, sizeof(g_sel_ip), "%s", g_devs[g_sel].ip);
                    g_sel_port = g_devs[g_sel].port;
                    g_sel_kind_is_own = g_devs[g_sel].kind;
                }
            }
            break;
        case ID_BT_LOCAL: do_cast_file(); break;
        case ID_BT_LINK: do_link(); break;
        case ID_BT_MIRROR: do_mirror(); break;
        case ID_BT_ADD: {
            char ip[64] = {0};
            if (InputBox(hwnd, L"手动添加设备", L"请输入接收端 IP（如 192.168.1.100）：", ip, 64)) {
                probe_dev(ip);
                ui_add_dev((HWND)GetDlgItem(hwnd, ID_LB_DEV));
            }
            break;
        }
        case ID_BT_SCAN:
            ui_add_dev((HWND)GetDlgItem(hwnd, ID_LB_DEV));
            CreateThread(NULL, 0, scan_thread, NULL, 0, NULL);
            break;
        case ID_BT_AI: open_ai(hwnd); break;
        case ID_BT_STOP: do_stop(); break;
        case ID_BT_R075: do_rate(50); break;
        case ID_BT_R1: do_rate(100); break;
        case ID_BT_R15: do_rate(150); break;
        case ID_BT_R2: do_rate(200); break;
        case ID_BT_CONN: on_connect(); break;
        }
        return 0;
    case WM_PAINT: {
        PAINTSTRUCT ps;
        HDC hdc = BeginPaint(hwnd, &ps);
        RECT rc;
        GetClientRect(hwnd, &rc);
        HBRUSH bg = CreateSolidBrush(RGB(240, 244, 250));
        FillRect(hdc, &rc, bg);
        DeleteObject(bg);
        HFONT f = CreateFontW(20, 0, 0, 0, FW_BOLD, 0, 0, 0, DEFAULT_CHARSET, 0, 0,
                              CLEARTYPE_QUALITY, 0, L"Microsoft YaHei");
        HFONT old = (HFONT)SelectObject(hdc, f);
        SetBkMode(hdc, TRANSPARENT);
        SetTextColor(hdc, RGB(30, 50, 90));
        char title[256];
        snprintf(title, sizeof(title), "ETCAS 投屏 · 电脑投屏端  v1.0.7   （本机 %s · 文件服务 %d）", g_myip, FILEPORT);
        TextOutA(hdc, 16, 10, title, (int)strlen(title));
        SelectObject(hdc, old);
        DeleteObject(f);
        EndPaint(hwnd, &ps);
        return 0;
    }
    case WM_CTLCOLORSTATIC:
        return (LRESULT)GetStockObject(WHITE_BRUSH);
    case WM_DESTROY:
        g_srv_stop = 1;
        g_mirror_stop = 1;
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

int WINAPI WinMain(HINSTANCE hInst, HINSTANCE hPrev, LPSTR lpCmd, int nShow) {
    (void)hPrev; (void)lpCmd; (void)nShow;
    etc_ws_init();
    CoInitializeEx(NULL, COINIT_MULTITHREADED);
    Gdiplus::GdiplusStartupInput gsi;
    ULONG_PTR gdiToken = 0;
    Gdiplus::GdiplusStartup(&gdiToken, &gsi, NULL);
    InitializeCriticalSection(&g_dev_cs);
    etc_host_ip(g_myip, sizeof(g_myip));

    WNDCLASSW wc;
    memset(&wc, 0, sizeof(wc));
    wc.lpfnWndProc = wndproc;
    wc.hInstance = hInst;
    wc.hCursor = LoadCursor(NULL, IDC_ARROW);
    wc.hbrBackground = (HBRUSH)(COLOR_BTNFACE + 1);
    wc.lpszClassName = L"ETCASPCastW";
    wc.hIcon = LoadIconA(hInst, "APPICON");
    RegisterClassW(&wc);

    HWND hwnd = CreateWindowW(L"ETCASPCastW", L"ETCAS 投屏 · 电脑投屏端",
        WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT, 640, 500, NULL, NULL, hInst, NULL);
    g_hwnd = hwnd;
    ShowWindow(hwnd, SW_SHOWNORMAL);
    UpdateWindow(hwnd);

    /* 文件服务线程 */
    {
        etc_srv_arg *a = (etc_srv_arg *)malloc(sizeof(etc_srv_arg));
        a->port = FILEPORT;
        a->fn = on_file_req;
        a->stop = &g_srv_stop;
        HANDLE h = CreateThread(NULL, 0, etc_http_server_thread, a, 0, NULL);
        if (h) CloseHandle(h);
    }

    MSG msg;
    while (GetMessageW(&msg, NULL, 0, 0)) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    Gdiplus::GdiplusShutdown(gdiToken);
    CoUninitialize();
    return 0;
}

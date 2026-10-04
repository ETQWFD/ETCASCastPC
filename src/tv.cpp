/* ETCAS 投屏 · 电脑接收端 v1.0.8（纯 C 语言 / Win32 原生，无 Java）
   开发者：ETC 协会 · 翻译者：POAI · MIT License */
#include "common.h"
#ifndef ETCAS_PROPID_DEF
#define ETCAS_PROPID_DEF
typedef unsigned long PROPID;
#endif
#include <gdiplus.h>
#include <objidl.h>
#include <mfplay.h>
#include <shlwapi.h>

#pragma comment(lib, "gdiplus")
#pragma comment(lib, "mfplat")
#pragma comment(lib, "mfuuid")
#pragma comment(lib, "ole32")
#pragma comment(lib, "oleaut32")

#include "qrcodegen.h"

#define PORT 9170
#define APP_TITLE L"ETCAS 投屏 接收端"

static HWND g_hwnd = NULL;
static volatile int g_stop_srv = 0;
static char g_ip[64] = "0.0.0.0";
static char g_key[7] = "000000";
static volatile int g_playing = 0;      /* 1=视频播放 2=图片 3=直播 */
static volatile int g_rate = 100;       /* 百分数 */
static char g_current_url[2048] = {0};
static volatile int g_live_new = 0;
static unsigned char *g_live_jpg = NULL;
static volatile int g_live_len = 0;
static CRITICAL_SECTION g_live_cs;

/* ---- Media Foundation 播放（MFPlay） ---- */
static IMFPMediaPlayer *g_player = NULL;

static void mf_stop(void) {
    if (g_player) {
        g_player->Stop();
        g_player->Shutdown();
        g_player->Release();
        g_player = NULL;
    }
    g_playing = 0;
}

static struct MpCb : public IMFPMediaPlayerCallback {
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID, void **ppv) override { *ppv = NULL; return E_NOINTERFACE; }
    ULONG STDMETHODCALLTYPE AddRef() override { return 1; }
    ULONG STDMETHODCALLTYPE Release() override { return 1; }
    void STDMETHODCALLTYPE OnMediaPlayerEvent(MFP_EVENT_HEADER *h) override {
        if (h->eEventType == MFP_EVENT_TYPE_PLAYBACK_ENDED || h->eEventType == MFP_EVENT_TYPE_ERROR) {
            mf_stop();
            if (g_hwnd) InvalidateRect(g_hwnd, NULL, TRUE);
        }
    }
} g_cb;

typedef HRESULT (STDMETHODCALLTYPE *MFPCreate_t)(LPCWSTR, BOOL, MFP_CREATION_OPTIONS,
                                                   IMFPMediaPlayerCallback *, HWND, IMFPMediaPlayer **);
static MFPCreate_t g_mfcreate = NULL;

static int mf_play(const wchar_t *url, HWND owner) {
    mf_stop();
    if (!g_mfcreate) {
        HMODULE m = LoadLibraryA("mfplat.dll");
        if (!m) return 0;
        g_mfcreate = (MFPCreate_t)GetProcAddress(m, "MFPCreateMediaPlayer");
        if (!g_mfcreate) return 0;
    }
    HRESULT hr = g_mfcreate(url, FALSE, 0, (IMFPMediaPlayerCallback *)&g_cb,
                            owner, &g_player);
    if (FAILED(hr)) { g_player = NULL; return 0; }
    g_player->Play();
    g_player->SetRate((float)g_rate / 100.0f);
    g_playing = 1;
    return 1;
}

/* ---- 图片/直播显示 ---- */
static int load_jpg_mem(const unsigned char *jpg, int len, Gdiplus::Bitmap **bm) {
    if (!jpg || len < 4) return 0;
    IStream *st = NULL;
    if (CreateStreamOnHGlobal(NULL, TRUE, &st) != S_OK) return 0;
    ULONG wr = 0;
    st->Write(jpg, len, &wr);
    *bm = Gdiplus::Bitmap::FromStream(st);
    st->Release();
    return (*bm != NULL);
}

/* ---- HTTP 路由 ---- */
static void build_rootdesc(char *out, int cap) {
    snprintf(out, cap,
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
        "<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:etcas=\"urn:etc:cast\">"
        "<specVersion><major>1</major><minor>0</minor></specVersion>"
        "<device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>"
        "<friendlyName>ETCAS投屏 电脑接收端</friendlyName>"
        "<manufacturer>ETC</manufacturer>"
        "<modelName>ETCASCastTV-PC</modelName>"
        "<UDN>uuid:etcas-pc-tv</UDN>"
        "<etcas:key>%s</etcas:key>"
        "<etcas:port>%d</etcas:port>"
        "<serviceList><service>"
        "<serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>"
        "<controlURL>/ctl</controlURL>"
        "</service></serviceList></device></root>",
        g_key, PORT);
}

static int on_req(const char *method, const char *path, const char *body, int bodylen,
                  char *reply, int replycap) {
    /* 自家信息 */
    if (strcmp(path, "/etcas/info") == 0) {
        return snprintf(reply, replycap,
            "{\"ok\":true,\"name\":\"ETCAS投屏 电脑接收端\",\"ip\":\"%s\",\"port\":%d,\"key\":\"%s\"}",
            g_ip, PORT, g_key);
    }
    if (strcmp(path, "/rootDesc.xml") == 0) {
        build_rootdesc(reply, replycap);
        return (int)strlen(reply);
    }
    /* 配对 */
    if (strcmp(path, "/etcas/pair") == 0 && strcmp(method, "POST") == 0) {
        char k[32] = {0};
        etc_json_str(body, "key", k, sizeof(k));
        if (strcmp(k, g_key) == 0) {
            strcpy(reply, "{\"ok\":true,\"paired\":true}");
            return (int)strlen(reply);
        }
        strcpy(reply, "{\"ok\":false,\"error\":\"bad key\"}");
        return (int)strlen(reply);
    }
    /* 倍速 */
    if (strcmp(path, "/etcas/speed") == 0 && strcmp(method, "POST") == 0) {
        long long r = 100;
        etc_json_num(body, "rate", &r);
        if (r < 50) r = 50;
        if (r > 200) r = 200;
        g_rate = (int)r;
        if (g_player && g_playing == 1) g_player->SetRate((float)g_rate / 100.0f);
        snprintf(reply, replycap, "{\"ok\":true,\"rate\":%d}", g_rate);
        return (int)strlen(reply);
    }
    /* 画质（DLNA 无实际控制，接受指令） */
    if (strcmp(path, "/etcas/quality") == 0 && strcmp(method, "POST") == 0) {
        strcpy(reply, "{\"ok\":true}");
        return (int)strlen(reply);
    }
    /* 直播帧（投屏端连续 POST JPEG） */
    if (strcmp(path, "/etcas/live") == 0 && strcmp(method, "POST") == 0) {
        EnterCriticalSection(&g_live_cs);
        if (g_live_jpg) free(g_live_jpg);
        g_live_jpg = (unsigned char *)malloc(bodylen + 1);
        if (g_live_jpg) {
            memcpy(g_live_jpg, body, bodylen);
            g_live_len = bodylen;
        }
        LeaveCriticalSection(&g_live_cs);
        g_live_new = 1;
        if (g_playing != 3) {
            g_playing = 3;
            InvalidateRect(g_hwnd, NULL, TRUE);
        }
        strcpy(reply, "{\"ok\":true}");
        return (int)strlen(reply);
    }
    /* 直播停止 */
    if (strcmp(path, "/etcas/livestop") == 0 && strcmp(method, "POST") == 0) {
        EnterCriticalSection(&g_live_cs);
        if (g_live_jpg) { free(g_live_jpg); g_live_jpg = NULL; }
        g_live_len = 0;
        LeaveCriticalSection(&g_live_cs);
        g_playing = 0;
        InvalidateRect(g_hwnd, NULL, TRUE);
        strcpy(reply, "{\"ok\":true}");
        return (int)strlen(reply);
    }
    /* 直接播放指令（自家扩展，投屏端可调用） */
    if (strcmp(path, "/etcas/play") == 0 && strcmp(method, "POST") == 0) {
        char url[2048] = {0};
        char kind[16] = "video";
        etc_json_str(body, "url", url, sizeof(url));
        etc_json_str(body, "kind", kind, sizeof(kind));
        if (!url[0]) { strcpy(reply, "{\"ok\":false}"); return (int)strlen(reply); }
        snprintf(g_current_url, sizeof(g_current_url), "%s", url);
        /* kind 双保险：url 含 img=1 或图片扩展名则按图片 */
        int isimg = (strcmp(kind, "img") == 0 || strstr(url, "img=1") ||
                     strstr(url, ".jpg") || strstr(url, ".png") || strstr(url, ".jpeg") ||
                     strstr(url, ".bmp") || strstr(url, ".gif")) ? 1 : 0;
        if (isimg) {
            g_playing = 2;
            InvalidateRect(g_hwnd, NULL, TRUE);
        } else {
            wchar_t wurl[4096];
            MultiByteToWideChar(CP_UTF8, 0, url, -1, wurl, 4096);
            mf_play(wurl, g_hwnd);
        }
        strcpy(reply, "{\"ok\":true}");
        return (int)strlen(reply);
    }
    /* SOAP AVTransport */
    if (strcmp(path, "/ctl") == 0 && strcmp(method, "POST") == 0) {
        const char *ns = "urn:schemas-upnp-org:service:AVTransport:1";
        char action[128] = {0};
        const char *a = strstr(body, "<u:");
        if (a) sscanf(a + 3, "%127[^>]", action);
        if (strcmp(action, "SetAVTransportURI") == 0) {
            char url[2048] = {0};
            const char *u = strstr(body, "<CurrentURI>");
            if (u) {
                const char *e = strstr(u, "</CurrentURI>");
                if (e) {
                    int l = (int)(e - (u + 12));
                    if (l > 2047) l = 2047;
                    memcpy(url, u + 12, l);
                    url[l] = 0;
                    /* XML 实体反转义 */
                    char tmp[2048];
                    snprintf(tmp, sizeof(tmp), "%s", url);
                    memset(url, 0, sizeof(url));
                    int t = 0;
                    for (int i = 0; tmp[i] && t < 2047; i++) {
                        if (strncmp(tmp + i, "&amp;", 5) == 0) { url[t++] = '&'; i += 4; }
                        else if (strncmp(tmp + i, "&lt;", 4) == 0) { url[t++] = '<'; i += 3; }
                        else if (strncmp(tmp + i, "&gt;", 4) == 0) { url[t++] = '>'; i += 3; }
                        else if (strncmp(tmp + i, "&quot;", 6) == 0) { url[t++] = '"'; i += 5; }
                        else url[t++] = tmp[i];
                    }
                    url[t] = 0;
                    snprintf(g_current_url, sizeof(g_current_url), "%s", url);
                    int isimg = (strstr(url, "img=1") || strstr(url, ".jpg") ||
                                 strstr(url, ".png") || strstr(url, ".jpeg") ||
                                 strstr(url, ".bmp")) ? 1 : 0;
                    if (isimg) {
                        g_playing = 2;
                        InvalidateRect(g_hwnd, NULL, TRUE);
                    }
                }
            }
            snprintf(reply, replycap,
                "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:SetAVTransportURIResponse xmlns:u=\"%s\"></u:SetAVTransportURIResponse></s:Body></s:Envelope>",
                ns);
            return (int)strlen(reply);
        }
        if (strcmp(action, "Play") == 0) {
            if (g_playing == 1 && g_player) g_player->Play();
            else if (g_playing != 1 && g_current_url[0]) {
                wchar_t wurl[4096];
                MultiByteToWideChar(CP_UTF8, 0, g_current_url, -1, wurl, 4096);
                mf_play(wurl, g_hwnd);
            }
            snprintf(reply, replycap,
                "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:PlayResponse xmlns:u=\"%s\"></u:PlayResponse></s:Body></s:Envelope>",
                ns);
            return (int)strlen(reply);
        }
        if (strcmp(action, "Pause") == 0) {
            if (g_player) g_player->Pause();
            snprintf(reply, replycap,
                "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:PauseResponse xmlns:u=\"%s\"></u:PauseResponse></s:Body></s:Envelope>",
                ns);
            return (int)strlen(reply);
        }
        if (strcmp(action, "Stop") == 0) {
            mf_stop();
            g_playing = 0;
            InvalidateRect(g_hwnd, NULL, TRUE);
            snprintf(reply, replycap,
                "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:StopResponse xmlns:u=\"%s\"></u:StopResponse></s:Body></s:Envelope>",
                ns);
            return (int)strlen(reply);
        }
    }
    return 0;
}

/* ---- 二维码绘制 ---- */
static void draw_qr(Gdiplus::Graphics &g, int x, int y, int size, const char *text) {
    uint8_t qr[qrcodegen_BUFFER_LEN_MAX];
    uint8_t tmp[qrcodegen_BUFFER_LEN_MAX];
    if (!qrcodegen_encodeText(text, tmp, qr, qrcodegen_Ecc_MEDIUM,
                              qrcodegen_VERSION_MIN, qrcodegen_VERSION_MAX,
                              qrcodegen_Mask_AUTO, true)) return;
    int dim = qrcodegen_getSize(qr);
    int cell = size / (dim + 8);
    int ox = x + 4 * cell, oy = y + 4 * cell;
    Gdiplus::SolidBrush black(Gdiplus::Color(255, 20, 20, 30));
    Gdiplus::SolidBrush white(Gdiplus::Color(255, 255, 255, 255));
    g.FillRectangle(&white, x, y, size, size);
    for (int yy = 0; yy < dim; yy++)
        for (int xx = 0; xx < dim; xx++)
            if (qrcodegen_getModule(qr, xx, yy))
                g.FillRectangle(&black, ox + xx * cell, oy + yy * cell, cell, cell);
}

/* ---- 主窗口 ---- */
static void draw_ui(HWND hwnd, HDC hdc) {
    RECT rc;
    GetClientRect(hwnd, &rc);
    Gdiplus::Graphics g(hdc);
    g.SetSmoothingMode(Gdiplus::SmoothingModeAntiAlias);
    g.SetTextRenderingHint(Gdiplus::TextRenderingHintClearTypeGridFit);
    int w = rc.right, h = rc.bottom;
    /* 背景渐变 */
    Gdiplus::LinearGradientBrush bg(Gdiplus::Rect(0, 0, w, h),
        Gdiplus::Color(255, 16, 42, 74), Gdiplus::Color(255, 8, 20, 40),
        Gdiplus::LinearGradientModeVertical);
    g.FillRectangle(&bg, 0, 0, w, h);
    Gdiplus::Font title(L"Microsoft YaHei", 26, Gdiplus::FontStyleBold);
    Gdiplus::Font big(L"Microsoft YaHei", 42, Gdiplus::FontStyleBold);
    Gdiplus::Font mid(L"Microsoft YaHei", 15, Gdiplus::FontStyleRegular);
    Gdiplus::Font small(L"Microsoft YaHei", 12, Gdiplus::FontStyleRegular);
    Gdiplus::SolidBrush white(Gdiplus::Color(255, 250, 250, 252));
    Gdiplus::SolidBrush dim(Gdiplus::Color(200, 170, 180, 200));
    Gdiplus::SolidBrush accent(Gdiplus::Color(255, 96, 160, 255));
    Gdiplus::SolidBrush card(Gdiplus::Color(40, 255, 255, 255));
    Gdiplus::StringFormat sf;
    sf.SetAlignment(Gdiplus::StringAlignmentCenter);
    sf.SetLineAlignment(Gdiplus::StringAlignmentCenter);

    g.DrawString(APP_TITLE, -1, &title,
        Gdiplus::RectF(0, 18, (float)w, 44), &sf, &white);
    g.FillRectangle(&card, w / 2 - 170, 78, 340, 250);

    /* 二维码 */
    char qrtext[256];
    snprintf(qrtext, sizeof(qrtext), "etcas://cast?ip=%s&port=%d&k=%s", g_ip, PORT, g_key);
    draw_qr(g, w / 2 - 105, 92, 210, qrtext);

    /* IP / 配对码 / 状态（宽字符） */
    wchar_t line[256];
    wchar_t wip[64], wkey[16];
    MultiByteToWideChar(CP_ACP, 0, g_ip, -1, wip, 64);
    MultiByteToWideChar(CP_ACP, 0, g_key, -1, wkey, 16);
    swprintf(line, 256, L"IP: %s : %d", wip, PORT);
    g.DrawString(line, -1, &mid, Gdiplus::RectF(0, 312, (float)w, 28), &sf, &accent);
    swprintf(line, 256, L"配对码  %s", wkey);
    g.DrawString(line, -1, &big, Gdiplus::RectF(0, 348, (float)w, 52), &sf, &white);
    const wchar_t *st = L"等待投屏...";
    if (g_playing == 1) st = L"正在播放视频";
    else if (g_playing == 2) st = L"正在显示图片";
    else if (g_playing == 3) st = L"正在屏幕直播";
    g.DrawString(st, -1, &mid, Gdiplus::RectF(0, 408, (float)w, 26), &sf, &dim);
    swprintf(line, 256, L"扫码免输配对码 · 同网直连 · 开发者 ETC 协会 v1.0.8");
    g.DrawString(line, -1, &small, Gdiplus::RectF(0, h - 34, (float)w, 20), &sf, &dim);
}

static LRESULT CALLBACK wndproc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
    case WM_PAINT: {
        PAINTSTRUCT ps;
        HDC hdc = BeginPaint(hwnd, &ps);
        draw_ui(hwnd, hdc);
        EndPaint(hwnd, &ps);
        return 0;
    }
    case WM_ERASEBKGND:
        return 1;
    case WM_APP + 5:   /* 播放结束/错误通知 */
        InvalidateRect(hwnd, NULL, TRUE);
        return 0;
    case WM_APP + 6:   /* 直播帧新到：重绘 */
        if (g_playing == 3) InvalidateRect(hwnd, NULL, FALSE);
        return 0;
    case WM_DESTROY:
        g_stop_srv = 1;
        mf_stop();
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

/* 直播帧线程：每 100ms 拉最新帧重绘 */
static DWORD WINAPI live_thread(LPVOID p) {
    (void)p;
    while (!g_stop_srv) {
        if (g_playing == 3 && g_live_new) {
            g_live_new = 0;
            PostMessageW(g_hwnd, WM_APP + 6, 0, 0);
        }
        Sleep(100);
    }
    return 0;
}

int WINAPI WinMain(HINSTANCE hInst, HINSTANCE hPrev, LPSTR lpCmd, int nShow) {
    (void)hPrev; (void)lpCmd; (void)nShow;
    etc_ws_init();
    CoInitializeEx(NULL, COINIT_MULTITHREADED);
    Gdiplus::GdiplusStartupInput gsi;
    ULONG_PTR gdiToken = 0;
    Gdiplus::GdiplusStartup(&gdiToken, &gsi, NULL);
    InitializeCriticalSection(&g_live_cs);

    /* 生成 6 位随机配对码 */
    srand((unsigned)GetTickCount() ^ (unsigned)GetCurrentProcessId());
    for (int i = 0; i < 6; i++) g_key[i] = (char)('0' + rand() % 10);
    g_key[6] = 0;
    etc_host_ip(g_ip, sizeof(g_ip));

    WNDCLASSW wc;
    memset(&wc, 0, sizeof(wc));
    wc.lpfnWndProc = wndproc;
    wc.hInstance = hInst;
    wc.hCursor = LoadCursor(NULL, IDC_ARROW);
    wc.lpszClassName = L"ETCASTvPC";
    wc.hbrBackground = NULL;
    wc.hIcon = LoadIconA(hInst, "APPICON");
    RegisterClassW(&wc);

    g_hwnd = CreateWindowW(wc.lpszClassName, APP_TITLE, WS_OVERLAPPEDWINDOW,
                           CW_USEDEFAULT, CW_USEDEFAULT, 560, 520, NULL, NULL, hInst, NULL);
    ShowWindow(g_hwnd, SW_SHOWNORMAL);
    UpdateWindow(g_hwnd);

    CreateThread(NULL, 0, live_thread, NULL, 0, NULL);
    {
        etc_srv_arg *a = (etc_srv_arg *)malloc(sizeof(etc_srv_arg));
        a->port = PORT;
        a->fn = on_req;
        a->stop = &g_stop_srv;
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

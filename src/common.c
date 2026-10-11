/* ETCAS 投屏 电脑版 v1.0.7 · 通用工具实现 */
#include "common.h"

char *etc_strdup(const char *s) {
    if (!s) return NULL;
    size_t n = strlen(s) + 1;
    char *p = (char *)malloc(n);
    if (p) memcpy(p, s, n);
    return p;
}

char *etc_trim(char *s) {
    if (!s) return s;
    char *e = s + strlen(s);
    while (e > s && (e[-1] == ' ' || e[-1] == '\t' || e[-1] == '\r' || e[-1] == '\n')) *--e = 0;
    char *b = s;
    while (*b == ' ' || *b == '\t') b++;
    if (b != s) memmove(s, b, strlen(b) + 1);
    return s;
}

int etc_json_str(const char *json, const char *key, char *out, int outsz) {
    if (!json || !key || !out || outsz <= 0) return 0;
    const char *p = strstr(json, key);
    if (!p) return 0;
    p += strlen(key);
    while (*p && *p != '"') p++;   /* 跳到值前的引号 */
    if (*p != '"') return 0;
    p++;
    int n = 0;
    while (*p && *p != '"' && n < outsz - 1) {
        if (*p == '\\' && p[1] == 'n') { out[n++] = '\n'; p += 2; }
        else if (*p == '\\' && p[1] == 't') { out[n++] = '\t'; p += 2; }
        else if (*p == '\\' && p[1]) { out[n++] = p[1]; p += 2; }
        else out[n++] = *p++;
    }
    out[n] = 0;
    return 1;
}

int etc_json_num(const char *json, const char *key, long long *out) {
    if (!json || !key || !out) return 0;
    const char *p = strstr(json, key);
    if (!p) return 0;
    p += strlen(key);
    while (*p && *p != ':') p++;
    if (*p != ':') return 0;
    p++;
    while (*p == ' ' || *p == '\t') p++;
    *out = _strtoui64(p, NULL, 10);
    return 1;
}

void etc_urlencode(const char *in, char *out, int outsz) {
    static const char hex[] = "0123456789ABCDEF";
    int n = 0;
    for (const unsigned char *p = (const unsigned char *)in; *p && n < outsz - 4; p++) {
        if ((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z') ||
            (*p >= '0' && *p <= '9') || *p == '-' || *p == '_' || *p == '.' || *p == '~') {
            out[n++] = (char)*p;
        } else {
            out[n++] = '%';
            out[n++] = hex[(*p >> 4) & 15];
            out[n++] = hex[*p & 15];
        }
    }
    out[n] = 0;
}

int etc_ws_init(void) {
    WSADATA wd;
    return WSAStartup(MAKEWORD(2, 2), &wd);
}

SOCKET etc_tcp_connect(const char *host, int port, int timeoutms) {
    struct addrinfo hints, *res = NULL, *ai;
    char sport[16];
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    sprintf(sport, "%d", port);
    if (getaddrinfo(host, sport, &hints, &res) != 0) return -1;
    SOCKET s = -1;
    for (ai = res; ai; ai = ai->ai_next) {
        s = socket(AF_INET, SOCK_STREAM, 0);
        if (s == INVALID_SOCKET) { freeaddrinfo(res); return -1; }
        u_long nb = 1;
        ioctlsocket(s, FIONBIO, &nb);
        if (connect(s, ai->ai_addr, (int)ai->ai_addrlen) == 0) break;
        fd_set wf;
        struct timeval tv;
        FD_ZERO(&wf);
        FD_SET(s, &wf);
        tv.tv_sec = timeoutms / 1000;
        tv.tv_usec = (timeoutms % 1000) * 1000;
        if (select(0, NULL, &wf, NULL, &tv) <= 0) { closesocket(s); s = -1; continue; }
        int err = 0, len = sizeof(err);
        getsockopt(s, SOL_SOCKET, SO_ERROR, (char *)&err, &len);
        if (err) { closesocket(s); s = -1; continue; }
        break;
    }
    freeaddrinfo(res);
    if (s != -1) {
        u_long nb = 0;
        ioctlsocket(s, FIONBIO, &nb);
    }
    return s;
}

static int etc_recv_all(SOCKET s, char *buf, int cap, int timeoutms) {
    int n = 0;
    fd_set rf;
    struct timeval tv;
    while (n < cap) {
        FD_ZERO(&rf);
        FD_SET(s, &rf);
        tv.tv_sec = timeoutms / 1000;
        tv.tv_usec = (timeoutms % 1000) * 1000;
        int r = select(0, &rf, NULL, NULL, &tv);
        if (r <= 0) break;
        int got = recv(s, buf + n, cap - n, 0);
        if (got <= 0) break;
        n += got;
        if (n >= 4 && (n >= cap || !(buf[n - 1] == '\n' || n < 4))) {
            /* 尝试判断是否读完：看 Content-Length 与已收 body */
        }
    }
    return n;
}

char *etc_http_get(const char *host, int port, const char *path, int timeoutms, long *status) {
    SOCKET s = etc_tcp_connect(host, port, timeoutms);
    if (s == -1) return NULL;
    char req[8192];
    int n = snprintf(req, sizeof(req),
        "GET %s HTTP/1.1\r\nHost: %s:%d\r\nConnection: close\r\nUser-Agent: ETCAS/1.0.7\r\n\r\n",
        path, host, port);
    send(s, req, n, 0);
    /* 读响应头 */
    char hdr[16384];
    int hn = 0;
    fd_set rf;
    struct timeval tv;
    while (hn < (int)sizeof(hdr) - 1) {
        FD_ZERO(&rf); FD_SET(s, &rf);
        tv.tv_sec = 3; tv.tv_usec = 0;
        if (select(0, &rf, NULL, NULL, &tv) <= 0) break;
        int got = recv(s, hdr + hn, 4096, 0);
        if (got <= 0) break;
        hn += got;
        hdr[hn] = 0;
        if (strstr(hdr, "\r\n\r\n")) break;
    }
    hdr[hn] = 0;
    if (!strstr(hdr, "\r\n\r\n")) { closesocket(s); return NULL; }
    long st = 0;
    if (sscanf(hdr, "HTTP/1.%*d %ld", &st) != 1) { closesocket(s); return NULL; }
    if (status) *status = st;
    long cl = 0;
    const char *clp = strstr(hdr, "Content-Length:");
    if (clp) sscanf(clp + 14, "%ld", &cl);
    if (cl > 0 && cl < 64 * 1024 * 1024) {
        char *body = (char *)malloc(cl + 1);
        if (!body) { closesocket(s); return NULL; }
        int got = 0;
        /* 可能头里已带部分 body */
        char *bodystart = strstr(hdr, "\r\n\r\n") + 4;
        int left = hn - (int)(bodystart - hdr);
        if (left > 0) {
            memcpy(body, bodystart, left);
            got = left;
        }
        while (got < (int)cl) {
            FD_ZERO(&rf); FD_SET(s, &rf);
            tv.tv_sec = 3; tv.tv_usec = 0;
            if (select(0, &rf, NULL, NULL, &tv) <= 0) break;
            int r = recv(s, body + got, (int)cl - got, 0);
            if (r <= 0) break;
            got += r;
        }
        body[got] = 0;
        closesocket(s);
        if (got >= (int)cl) return body;
        free(body);
        return NULL;
    }
    /* chunked 或小响应：读到关闭 */
    {
        char *buf = (char *)malloc(1 << 20);
        int cap = 1 << 20, used = 0;
        while (used < cap - 1) {
            FD_ZERO(&rf); FD_SET(s, &rf);
            tv.tv_sec = 2; tv.tv_usec = 0;
            if (select(0, &rf, NULL, NULL, &tv) <= 0) break;
            int r = recv(s, buf + used, cap - 1 - used, 0);
            if (r <= 0) break;
            used += r;
        }
        closesocket(s);
        if (used == 0) { free(buf); return NULL; }
        buf[used] = 0;
        return buf;
    }
}

char *etc_http_post_json(const char *host, int port, const char *path, const char *body, int timeoutms, long *status) {
    SOCKET s = etc_tcp_connect(host, port, timeoutms);
    if (s == -1) return NULL;
    char req[16384];
    int blen = (int)strlen(body);
    int n = snprintf(req, sizeof(req),
        "POST %s HTTP/1.1\r\nHost: %s:%d\r\nContent-Type: application/json\r\nContent-Length: %d\r\nConnection: close\r\nUser-Agent: ETCAS/1.0.7\r\n\r\n",
        path, host, port, blen);
    send(s, req, n, 0);
    send(s, body, blen, 0);
    char hdr[16384];
    int hn = 0;
    fd_set rf;
    struct timeval tv;
    while (hn < (int)sizeof(hdr) - 1) {
        FD_ZERO(&rf); FD_SET(s, &rf);
        tv.tv_sec = 3; tv.tv_usec = 0;
        if (select(0, &rf, NULL, NULL, &tv) <= 0) break;
        int got = recv(s, hdr + hn, 4096, 0);
        if (got <= 0) break;
        hn += got;
        hdr[hn] = 0;
        if (strstr(hdr, "\r\n\r\n")) break;
    }
    hdr[hn] = 0;
    if (!strstr(hdr, "\r\n\r\n")) { closesocket(s); return NULL; }
    long st = 0;
    sscanf(hdr, "HTTP/1.%*d %ld", &st);
    if (status) *status = st;
    long cl = 0;
    const char *clp = strstr(hdr, "Content-Length:");
    if (clp) sscanf(clp + 14, "%ld", &cl);
    char *buf = (char *)malloc((cl > 0 ? cl : 4096) + 1);
    if (!buf) { closesocket(s); return NULL; }
    int used = 0;
    char *bs = strstr(hdr, "\r\n\r\n");
    if (bs) {
        bs += 4;
        int left = hn - (int)(bs - hdr);
        if (left > 0) { memcpy(buf, bs, left); used = left; }
    }
    if (cl > 0) {
        while (used < (int)cl) {
            FD_ZERO(&rf); FD_SET(s, &rf);
            tv.tv_sec = 3; tv.tv_usec = 0;
            if (select(0, &rf, NULL, NULL, &tv) <= 0) break;
            int r = recv(s, buf + used, (int)cl - used, 0);
            if (r <= 0) break;
            used += r;
        }
    }
    closesocket(s);
    buf[used] = 0;
    return buf;
}

/* ---------- 简易 HTTP 服务 ---------- */
typedef struct {
    int port;
    etc_req_fn fn;
    int *stop;
} srv_t;

static DWORD WINAPI etc_conn_thread(LPVOID p) {
    SOCKET c = (SOCKET)(uintptr_t)p;
    char buf[1 << 16];
    int n = 0;
    fd_set rf;
    struct timeval tv;
    while (n < (int)sizeof(buf) - 1) {
        FD_ZERO(&rf); FD_SET(c, &rf);
        tv.tv_sec = 2; tv.tv_usec = 0;
        if (select(0, &rf, NULL, NULL, &tv) <= 0) break;
        int got = recv(c, buf + n, 4096, 0);
        if (got <= 0) break;
        n += got;
        buf[n] = 0;
        if (strstr(buf, "\r\n\r\n")) {
            const char *clp = strstr(buf, "Content-Length:");
            if (clp) {
                long cl = 0;
                sscanf(clp + 14, "%ld", &cl);
                int hs = (int)(strstr(buf, "\r\n\r\n") - buf) + 4;
                while (n < hs + cl && n < (int)sizeof(buf) - 1) {
                    FD_ZERO(&rf); FD_SET(c, &rf);
                    tv.tv_sec = 2; tv.tv_usec = 0;
                    if (select(0, &rf, NULL, NULL, &tv) <= 0) break;
                    int r = recv(c, buf + n, 4096, 0);
                    if (r <= 0) break;
                    n += r;
                }
            }
            break;
        }
    }
    buf[n] = 0;
    /* 解析请求行与 body */
    char method[16] = {0}, path[2048] = {0};
    char *line1 = buf;
    sscanf(line1, "%15s %2047s", method, path);
    char *body = strstr(buf, "\r\n\r\n");
    int bodylen = 0;
    if (body) {
        body += 4;
        bodylen = n - (int)(body - buf);
    } else {
        body = "";
    }
    char reply[1 << 20];
    int rn = 0;
    srv_t *sv = NULL; /* 不需要 */
    /* 通过 fn 全局查找：用一个简单方法——将 fn 存到 TLS？此处用回调函数指针无法传递，
       采用全局变量方案（在 etc_http_server 中设置） */
    extern etc_req_fn g_etc_req_fn;
    if (g_etc_req_fn) rn = g_etc_req_fn(method, path, body, bodylen, reply, (int)sizeof(reply));
    if (rn > 0) {
        char hdr[512];
        int hn = snprintf(hdr, sizeof(hdr),
            "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: %d\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n",
            rn);
        send(c, hdr, hn, 0);
        int sent = 0;
        while (sent < rn) {
            int r = send(c, reply + sent, rn - sent, 0);
            if (r <= 0) break;
            sent += r;
        }
    } else {
        const char *n404 = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        send(c, n404, (int)strlen(n404), 0);
    }
    closesocket(c);
    return 0;
}

etc_req_fn g_etc_req_fn = NULL;

int etc_http_server(int port, etc_req_fn fn, volatile int *stop) {
    g_etc_req_fn = fn;
    SOCKET ls = socket(AF_INET, SOCK_STREAM, 0);
    if (ls == INVALID_SOCKET) return -1;
    int reuse = 1;
    setsockopt(ls, SOL_SOCKET, SO_REUSEADDR, (char *)&reuse, sizeof(reuse));
    struct sockaddr_in sa;
    memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET;
    sa.sin_addr.s_addr = htonl(INADDR_ANY);
    sa.sin_port = htons((u_short)port);
    if (bind(ls, (struct sockaddr *)&sa, sizeof(sa)) != 0) { closesocket(ls); return -1; }
    if (listen(ls, 8) != 0) { closesocket(ls); return -1; }
    while (stop && !*stop) {
        fd_set rf;
        struct timeval tv;
        FD_ZERO(&rf); FD_SET(ls, &rf);
        tv.tv_sec = 1; tv.tv_usec = 0;
        if (select(0, &rf, NULL, NULL, &tv) <= 0) continue;
        SOCKET c = accept(ls, NULL, NULL);
        if (c == INVALID_SOCKET) continue;
        HANDLE h = CreateThread(NULL, 0, etc_conn_thread, (LPVOID)(uintptr_t)c, 0, NULL);
        if (h) CloseHandle(h);
        else closesocket(c);
    }
    closesocket(ls);
    return 0;
}

void etc_host_ip(char *out, int outsz) {
    char hn[256] = {0};
    gethostname(hn, sizeof(hn));
    struct addrinfo hints, *res = NULL;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    if (getaddrinfo(hn, NULL, &hints, &res) == 0) {
        struct sockaddr_in *sa = (struct sockaddr_in *)res->ai_addr;
        const char *ip = inet_ntoa(sa->sin_addr);
        /* 过滤回环 */
        if (strcmp(ip, "127.0.0.1") != 0) {
            snprintf(out, outsz, "%s", ip);
            freeaddrinfo(res);
            return;
        }
        freeaddrinfo(res);
    }
    snprintf(out, outsz, "127.0.0.1");
}

DWORD WINAPI etc_http_server_thread(LPVOID p) {
    etc_srv_arg *a = (etc_srv_arg *)p;
    etc_http_server(a->port, a->fn, a->stop);
    free(a);
    return 0;
}

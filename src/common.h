/* ETCAS 投屏 电脑版 v1.0.7 · 通用工具（C 语言原生）
   开发者：ETC 协会 · 翻译者：POAI · MIT License
   纯 C/Win32 实现，不依赖任何 Java 运行时 */
#ifndef ETCAS_COMMON_H
#define ETCAS_COMMON_H

#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <windows.h>
#include <ws2tcpip.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ---------- 字符串/JSON 小工具 ---------- */
char *etc_strdup(const char *s);
char *etc_trim(char *s);
/* 从 JSON 中提取字符串值（首个匹配 key），成功返回 1 并写入 out */
int  etc_json_str(const char *json, const char *key, char *out, int outsz);
/* 从 JSON 中提取数字值 */
int  etc_json_num(const char *json, const char *key, long long *out);
/* URL 编码 */
void etc_urlencode(const char *in, char *out, int outsz);

/* ---------- 网络 ---------- */
int  etc_ws_init(void);                    /* 初始化 Winsock（调用一次） */
/* 建立到 host:port 的 TCP 连接，成功返回 socket，失败返回 -1 */
SOCKET etc_tcp_connect(const char *host, int port, int timeoutms);
/* HTTP GET，返回 malloc 的 body（含状态码检查），失败 NULL */
char *etc_http_get(const char *host, int port, const char *path, int timeoutms, long *status);
/* HTTP POST json，返回 malloc body，失败 NULL */
char *etc_http_post_json(const char *host, int port, const char *path, const char *body, int timeoutms, long *status);
/* 简易 HTTP 服务：绑定 port 监听，收到请求回调 on_request(method,path,body,bodylen,reply,replycap)
   返回 1 表示已写 reply。listen 失败返回 -1。 */
typedef int (*etc_req_fn)(const char *method, const char *path, const char *body, int bodylen,
                          char *reply, int replycap);
int  etc_http_server(int port, etc_req_fn fn, volatile int *stop);  /* 阻塞，用 stopflag 停止 */

/* ---------- 系统信息 ---------- */
void etc_host_ip(char *out, int outsz);    /* 本机局域网 IPv4 */

#endif

typedef struct { int port; etc_req_fn fn; volatile int *stop; } etc_srv_arg;
DWORD WINAPI etc_http_server_thread(LPVOID p);

#include <windows.h>
#include <stdio.h>

#ifndef APP_MAIN
#define APP_MAIN "com.etc.cas.tvpc.TvApp"
#endif
#ifndef APP_JAR
#define APP_JAR "etcascast-pc-tv.jar"
#endif

int WINAPI WinMain(HINSTANCE hInst, HINSTANCE hPrev, LPSTR lpCmdLine, int nShow) {
    wchar_t exe[MAX_PATH];
    GetModuleFileNameW(NULL, exe, MAX_PATH);
    wchar_t dir[MAX_PATH];
    wchar_t *p = wcsrchr(exe, L'\\');
    if (!p) return 1;
    *p = L'\0';
    wcscpy(dir, exe);

    wchar_t java[MAX_PATH];
    swprintf(java, MAX_PATH, L"%s\\jre\\bin\\javaw.exe", dir);

    if (GetFileAttributesW(java) == INVALID_FILE_ATTRIBUTES) {
        wchar_t msg[MAX_PATH * 2];
        swprintf(msg, MAX_PATH * 2, L"未找到 Java 运行环境（%s）。\n请重新安装本软件。", java);
        MessageBoxW(NULL, msg, L"ETCAS 投屏 接收端", MB_OK | MB_ICONERROR);
        return 1;
    }

    wchar_t jar[MAX_PATH];
    swprintf(jar, MAX_PATH, L"%s\\%S", dir, APP_JAR);

    wchar_t cmd[MAX_PATH * 3];
    swprintf(cmd, MAX_PATH * 3,
             L"\"%s\" -Dfile.encoding=UTF-8 -Duser.language=zh -cp \"%s;%s\\lib\\*\" %S",
             java, jar, dir, APP_MAIN);

    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    ZeroMemory(&pi, sizeof(pi));
    si.cb = sizeof(si);
    si.dwFlags = STARTF_USESHOWWINDOW;
    si.wShowWindow = SW_SHOWNORMAL;

    if (!CreateProcessW(java, cmd, NULL, NULL, FALSE, 0, NULL, dir, &si, &pi)) {
        MessageBoxW(NULL, L"启动失败，请重新安装本软件。", L"ETCAS 投屏 接收端", MB_OK | MB_ICONERROR);
        return 1;
    }
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return 0;
}

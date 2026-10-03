#include <windows.h>
#include <shlobj.h>
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
    const wchar_t *cands[] = {
        L"jre\\bin\\javaw.exe", L"jre\\bin\\java.exe",
        L"bin\\javaw.exe",        L"bin\\java.exe",
        L"javaw.exe",               L"java.exe"
    };
    int found = 0;
    for (int i = 0; i < 6; i++) {
        swprintf(java, MAX_PATH, L"%s\\%s", dir, cands[i]);
        if (GetFileAttributesW(java) != INVALID_FILE_ATTRIBUTES) { found = 1; break; }
    }
    if (!found) {
        /* 注册表记录的安装目录 */
        wchar_t inst[MAX_PATH];
        DWORD sz = MAX_PATH * 2;
        LSTATUS st = RegGetValueW(HKEY_CURRENT_USER,
            L"Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\ETCAS投屏",
            L"InstallLocation", RRF_RT_REG_SZ, NULL, inst, &sz);
        if (st == ERROR_SUCCESS && sz > 2) {
            swprintf(java, MAX_PATH, L"%s\\jre\\bin\\javaw.exe", inst);
            if (GetFileAttributesW(java) != INVALID_FILE_ATTRIBUTES) found = 1;
            if (!found) {
                swprintf(java, MAX_PATH, L"%s\\bin\\javaw.exe", inst);
                if (GetFileAttributesW(java) != INVALID_FILE_ATTRIBUTES) found = 1;
            }
        }
    }
    if (!found) {
        /* 上级目录（安装目录惯例：exe 在子目录、jre 在上层） */
        wchar_t parent[MAX_PATH];
        swprintf(parent, MAX_PATH, L"%s\\..", dir);
        swprintf(java, MAX_PATH, L"%s\\jre\\bin\\javaw.exe", parent);
        if (GetFileAttributesW(java) != INVALID_FILE_ATTRIBUTES) found = 1;
    }
    if (!found) {
        /* 系统安装的 JRE（Zulu MSI 等写入 HKLM JavaSoft 注册表） */
        wchar_t ver[MAX_PATH];
        DWORD vsz = MAX_PATH * 2;
        if (RegGetValueW(HKEY_LOCAL_MACHINE,
            L"SOFTWARE\\JavaSoft\\Java Runtime Environment",
            L"CurrentVersion", RRF_RT_REG_SZ, NULL, ver, &vsz) == ERROR_SUCCESS) {
            wchar_t key[MAX_PATH * 2];
            swprintf(key, MAX_PATH * 2,
                L"SOFTWARE\\JavaSoft\\Java Runtime Environment\\%s", ver);
            wchar_t home[MAX_PATH];
            DWORD hsz = MAX_PATH * 2;
            if (RegGetValueW(HKEY_LOCAL_MACHINE, key, L"JavaHome",
                             RRF_RT_REG_SZ, NULL, home, &hsz) == ERROR_SUCCESS) {
                swprintf(java, MAX_PATH, L"%s\\bin\\javaw.exe", home);
                if (GetFileAttributesW(java) != INVALID_FILE_ATTRIBUTES) found = 1;
            }
        }
    }
    if (!found) {
        wchar_t sysJava[MAX_PATH];
        DWORD n = GetEnvironmentVariableW(L"JAVA_HOME", sysJava, MAX_PATH);
        if (n > 0 && n < MAX_PATH) {
            swprintf(java, MAX_PATH, L"%s\\bin\\javaw.exe", sysJava);
            if (GetFileAttributesW(java) != INVALID_FILE_ATTRIBUTES) found = 1;
        }
    }
    if (!found) {
        DWORD sz = MAX_PATH * 2;
        if (RegGetValueW(HKEY_CURRENT_USER, L"Software\\ETCAS投屏", L"JavaHome",
                         RRF_RT_REG_SZ, NULL, java, &sz) == ERROR_SUCCESS) {
            wchar_t jbin[MAX_PATH];
            swprintf(jbin, MAX_PATH, L"%s\\bin\\javaw.exe", java);
            if (GetFileAttributesW(jbin) != INVALID_FILE_ATTRIBUTES) found = 1;
        }
    }
    if (!found) {
        int r = MessageBoxW(NULL,
            L"未找到 Java 运行环境。\n是否手动选择您的 Java 运行环境？\n（选择包含 bin\\javaw.exe 的文件夹，例如您电脑上已有的 jre）\n\n选择后本软件会记住该位置，下次直接使用。",
            L"ETCAS 投屏 接收端", MB_YESNO | MB_ICONQUESTION);
        if (r == IDYES) {
            BROWSEINFOW bi;
            ZeroMemory(&bi, sizeof(bi));
            bi.lpszTitle = L"请选择 jre 文件夹（内含 bin\\javaw.exe）";
            bi.ulFlags = BIF_RETURNONLYFSDIRS | BIF_NEWDIALOGSTYLE;
            LPITEMIDLIST pidl = SHBrowseForFolderW(&bi);
            if (pidl) {
                wchar_t jrePath[MAX_PATH];
                if (SHGetPathFromIDListW(pidl, jrePath)) {
                    wchar_t jbin[MAX_PATH];
                    swprintf(jbin, MAX_PATH, L"%s\\bin\\javaw.exe", jrePath);
                    if (GetFileAttributesW(jbin) != INVALID_FILE_ATTRIBUTES) {
                        {
                            HKEY hk;
                            if (RegCreateKeyExW(HKEY_CURRENT_USER, L"Software\\ETCAS投屏",
                                0, NULL, 0, KEY_WRITE, NULL, &hk, NULL) == ERROR_SUCCESS) {
                                RegSetValueExW(hk, L"JavaHome", 0, REG_SZ,
                                    (const BYTE *)jrePath,
                                    (DWORD)(wcslen(jrePath) + 1) * 2);
                                RegCloseKey(hk);
                            }
                        }
                        wcscpy(java, jbin);
                        found = 1;
                    } else {
                        MessageBoxW(NULL, L"所选文件夹中没有找到 bin\\javaw.exe，请重新选择。", L"ETCAS 投屏 接收端", MB_OK | MB_ICONWARNING);
                    }
                }
                CoTaskMemFree(pidl);
            }
        }
    }
    if (!found) {
        wchar_t msg[MAX_PATH * 2];
        swprintf(msg, MAX_PATH * 2, L"未找到 Java 运行环境。\n请确认软件目录内的 jre 文件夹完整（%s）。\n或重新安装本软件。若仍无法解决，请到官网 https://etc.os.kg 下载最新版安装程序。", dir);
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

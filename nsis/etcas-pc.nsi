; ETCAS 投屏 · 电脑版 v1.0.2 NSIS 安装程序
; 开发者：ETC 协会 · 翻译者：POAI · MIT License

Unicode true
SetCompressor /SOLID lzma
Name "ETCAS 投屏 电脑版 v1.0.2"
OutFile "/home/user/Doubao/chats/38441912961231106/PC/out/ETCAS投屏-电脑版-安装程序-v1.0.2.exe"
InstallDir "$PROGRAMFILES64\ETCAS投屏"
InstallDirRegKey HKCU "Software\ETCAS投屏" ""
RequestExecutionLevel admin

VIProductVersion "1.0.2.0"
VIAddVersionKey /LANG=2052 "ProductName" "ETCAS 投屏 电脑版"
VIAddVersionKey /LANG=2052 "CompanyName" "ETC"
VIAddVersionKey /LANG=2052 "FileDescription" "ETCAS 投屏 · Windows 电脑版 v1.0.2（投屏端 + 接收端）"
VIAddVersionKey /LANG=2052 "FileVersion" "1.0.2"
VIAddVersionKey /LANG=2052 "ProductVersion" "1.0.2"
VIAddVersionKey /LANG=2052 "LegalCopyright" "Copyright (c) 2026 ETC"
VIAddVersionKey /LANG=2052 "OriginalFilename" "ETCAS投屏-电脑版-安装程序-v1.0.2.exe"

!include "MUI2.nsh"
!define MUI_ICON "/home/user/Doubao/chats/38441912961231106/PC/launcher/etcas.ico"
!define MUI_UNICON "/home/user/Doubao/chats/38441912961231106/PC/launcher/etcas.ico"
!define MUI_ABORTWARNING
!define MUI_FINISHPAGE_RUN "$INSTDIR\ETCAS-Cast-PC.exe"
!define MUI_FINISHPAGE_RUN_TEXT "立即运行 ETCAS 投屏（投屏端）"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "SimpChinese"

!define APP_DIR "/home/user/Doubao/chats/38441912961231106/PC/app/etcascast-pc"

Section "Install ETCAS Cast"
    SetOutPath "$INSTDIR"
    File "${APP_DIR}/ETCAS-Cast-PC.exe"
    File "${APP_DIR}/ETCAS-Cast-TV-PC.exe"
    File "${APP_DIR}/etcascast-pc-cast.jar"
    File "${APP_DIR}/etcascast-pc-tv.jar"
    File "${APP_DIR}/COPYRIGHT.txt"
    File "${APP_DIR}/README.txt"
    SetOutPath "$INSTDIR\lib"
    File "${APP_DIR}/lib/javafx-base.jar"
    File "${APP_DIR}/lib/javafx-graphics.jar"
    File "${APP_DIR}/lib/javafx-media.jar"
    File "${APP_DIR}/lib/javafx-swing.jar"
    SetOutPath "$INSTDIR\jre"
    File /r "${APP_DIR}/jre/*.*"

    WriteUninstaller "$INSTDIR\uninstall.exe"

    CreateShortCut "$DESKTOP\ETCAS投屏-电脑版.lnk" "$INSTDIR\ETCAS-Cast-PC.exe" "" "$INSTDIR\ETCAS-Cast-PC.exe" 0
    CreateShortCut "$DESKTOP\ETCAS投屏-电脑接收端.lnk" "$INSTDIR\ETCAS-Cast-TV-PC.exe" "" "$INSTDIR\ETCAS-Cast-TV-PC.exe" 0
    CreateDirectory "$SMPROGRAMS\ETCAS投屏"
    CreateShortCut "$SMPROGRAMS\ETCAS投屏\ETCAS投屏-电脑版.lnk" "$INSTDIR\ETCAS-Cast-PC.exe" "" "$INSTDIR\ETCAS-Cast-PC.exe" 0
    CreateShortCut "$SMPROGRAMS\ETCAS投屏\ETCAS投屏-电脑接收端.lnk" "$INSTDIR\ETCAS-Cast-TV-PC.exe" "" "$INSTDIR\ETCAS-Cast-TV-PC.exe" 0
    CreateShortCut "$SMPROGRAMS\ETCAS投屏\卸载 ETCAS 投屏.lnk" "$INSTDIR\uninstall.exe"

    WriteRegStr HKCU "Software\ETCAS投屏" "" "$INSTDIR"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ETCAS投屏" "DisplayName" "ETCAS 投屏 电脑版"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ETCAS投屏" "DisplayVersion" "1.0.2"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ETCAS投屏" "Publisher" "ETC"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ETCAS投屏" "DisplayIcon" "$INSTDIR\ETCAS-Cast-PC.exe,0"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ETCAS投屏" "UninstallString" "$INSTDIR\uninstall.exe"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ETCAS投屏" "InstallLocation" "$INSTDIR"
    WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ETCAS投屏" "NoModify" 1
    WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ETCAS投屏" "NoRepair" 1
SectionEnd

Section "Uninstall"
    Delete "$DESKTOP\ETCAS投屏-电脑版.lnk"
    Delete "$DESKTOP\ETCAS投屏-电脑接收端.lnk"
    Delete "$SMPROGRAMS\ETCAS投屏\ETCAS投屏-电脑版.lnk"
    Delete "$SMPROGRAMS\ETCAS投屏\ETCAS投屏-电脑接收端.lnk"
    Delete "$SMPROGRAMS\ETCAS投屏\卸载 ETCAS 投屏.lnk"
    RMDir "$SMPROGRAMS\ETCAS投屏"
    DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ETCAS投屏"
    DeleteRegKey HKCU "Software\ETCAS投屏"
    RMDir /r "$INSTDIR"
SectionEnd

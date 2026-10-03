; ETCAS 投屏 · 电脑版 v1.0.4 NSIS 安装程序
; 开发者：ETC 协会 · 翻译者：POAI · MIT License
; Java 方案：安装器内置 Zulu JRE 安装包，安装完成后自动静默安装 Java；卸载时一并卸载

Unicode true
SetCompressor /SOLID lzma
Name "ETCAS 投屏 电脑版 v1.0.4"
OutFile "/home/user/Doubao/chats/38441912961231106/PC/out/ETCAS投屏-电脑版-安装程序-v1.0.4.exe"
InstallDir "$PROGRAMFILES64\ETCAS投屏"
InstallDirRegKey HKCU "Software\ETCAS投屏" ""
RequestExecutionLevel admin

VIProductVersion "1.0.4.0"
VIAddVersionKey /LANG=2052 "ProductName" "ETCAS 投屏 电脑版"
VIAddVersionKey /LANG=2052 "CompanyName" "ETC"
VIAddVersionKey /LANG=2052 "FileDescription" "ETCAS 投屏 · Windows 电脑版 v1.0.4（投屏端 + 接收端）"
VIAddVersionKey /LANG=2052 "FileVersion" "1.0.4"
VIAddVersionKey /LANG=2052 "ProductVersion" "1.0.4"
VIAddVersionKey /LANG=2052 "LegalCopyright" "Copyright (c) 2026 ETC"
VIAddVersionKey /LANG=2052 "OriginalFilename" "ETCAS投屏-电脑版-安装程序-v1.0.4.exe"

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
!define JAVA_MSI "/home/user/Doubao/chats/38441912961231106/PC/app/java/zulu-jre17-x64.msi"

; 检测系统是否已安装 Azul Zulu JRE（返回 $0：1=已装）
Function DetectZulu
    Push $1
    Push $2
    Push $3
    Push $4
    SetRegView 64
    StrCpy $0 0
    StrCpy $1 0
loop:
    EnumRegKey $2 HKLM "SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall" $1
    StrCmp $2 "" done
    ReadRegStr $3 HKLM "SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\$2" "DisplayName"
    StrCmp $3 "" next
    StrCpy $4 $3 9
    StrCmp $4 "Azul Zulu" found
next:
    IntOp $1 $1 + 1
    Goto loop
found:
    StrCpy $0 1
done:
    Pop $4
    Pop $3
    Pop $2
    Pop $1
FunctionEnd

; 卸载系统中随本软件安装的 Azul Zulu JRE
Function un.UninstallZulu
    Push $1
    Push $2
    Push $3
    Push $4
    SetRegView 64
    StrCpy $1 0
uloop:
    EnumRegKey $2 HKLM "SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall" $1
    StrCmp $2 "" udone
    ReadRegStr $3 HKLM "SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\$2" "DisplayName"
    StrCmp $3 "" unext
    StrCpy $4 $3 9
    StrCmp $4 "Azul Zulu" ufound
unext:
    IntOp $1 $1 + 1
    Goto uloop
ufound:
    ReadRegStr $4 HKLM "SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\$2" "UninstallString"
    StrCmp $4 "" udone
    ExecWait '"$4" /quiet'
udone:
    Pop $4
    Pop $3
    Pop $2
    Pop $1
FunctionEnd

Section "Install ETCAS Cast"
    SetOutPath "$INSTDIR"
    File "${APP_DIR}/ETCAS-Cast-PC.exe"
    File "${APP_DIR}/ETCAS-Cast-TV-PC.exe"
    File "${APP_DIR}/etcascast-pc-cast.jar"
    File "${APP_DIR}/etcascast-pc-tv.jar"
    File "${APP_DIR}/COPYRIGHT.txt"
    File "${APP_DIR}/README.txt"
    File "${APP_DIR}/使用说明.txt"
    SetOutPath "$INSTDIR\lib"
    File "${APP_DIR}/lib/javafx-base.jar"
    File "${APP_DIR}/lib/javafx-graphics.jar"
    File "${APP_DIR}/lib/javafx-media.jar"
    File "${APP_DIR}/lib/javafx-swing.jar"
    SetOutPath "$INSTDIR\java-install"
    File "${JAVA_MSI}"

    WriteUninstaller "$INSTDIR\uninstall.exe"

    CreateShortCut "$DESKTOP\ETCAS投屏-电脑版.lnk" "$INSTDIR\ETCAS-Cast-PC.exe" "" "$INSTDIR\ETCAS-Cast-PC.exe" 0
    CreateShortCut "$DESKTOP\ETCAS投屏-电脑接收端.lnk" "$INSTDIR\ETCAS-Cast-TV-PC.exe" "" "$INSTDIR\ETCAS-Cast-TV-PC.exe" 0
    CreateDirectory "$SMPROGRAMS\ETCAS投屏"
    CreateShortCut "$SMPROGRAMS\ETCAS投屏\ETCAS投屏-电脑版.lnk" "$INSTDIR\ETCAS-Cast-PC.exe" "" "$INSTDIR\ETCAS-Cast-PC.exe" 0
    CreateShortCut "$SMPROGRAMS\ETCAS投屏\ETCAS投屏-电脑接收端.lnk" "$INSTDIR\ETCAS-Cast-TV-PC.exe" "" "$INSTDIR\ETCAS-Cast-TV-PC.exe" 0
    CreateShortCut "$SMPROGRAMS\ETCAS投屏\卸载 ETCAS 投屏.lnk" "$INSTDIR\uninstall.exe"

    WriteRegStr HKCU "Software\ETCAS投屏" "" "$INSTDIR"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ETCAS投屏" "DisplayName" "ETCAS 投屏 电脑版"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ETCAS投屏" "DisplayVersion" "1.0.4"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ETCAS投屏" "Publisher" "ETC"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ETCAS投屏" "DisplayIcon" "$INSTDIR\ETCAS-Cast-PC.exe,0"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ETCAS投屏" "UninstallString" "$INSTDIR\uninstall.exe"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ETCAS投屏" "InstallLocation" "$INSTDIR"
    WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ETCAS投屏" "NoModify" 1
    WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\ETCAS投屏" "NoRepair" 1

    ; 安装 Java（若系统已存在 Azul Zulu JRE 则跳过）
    Call DetectZulu
    StrCmp $0 1 javadone
    DetailPrint "正在安装 Java 运行环境（Azul Zulu JRE 17）..."
    ExecWait 'msiexec /i "$INSTDIR\java-install\zulu-jre17-x64.msi" /qn'
javadone:
SectionEnd

Section "Uninstall"
    ; 卸载随本软件安装的 Java
    Call un.UninstallZulu

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

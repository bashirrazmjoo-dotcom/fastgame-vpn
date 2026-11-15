; Inno Setup script. Build:  iscc /DAppVersion=0.1.0 /DStage=..\stage installer\fastgame.iss
; `Stage` holds FastGame.exe, fastgame-service.exe, sing-box.exe, libcronet.dll, licenses, webview2 bootstrapper.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef Stage
  #define Stage "..\stage"
#endif

[Setup]
AppId={{6F2C1B1E-7A0B-4C77-9C58-4F61A7D0C5E1}
AppName=FastGame
AppVersion={#AppVersion}
AppPublisher=FastGame
AppPublisherURL=https://github.com/bashirrazmjoo-dotcom/fastgame-vpn
DefaultDirName={autopf}\FastGame
DefaultGroupName=FastGame
DisableProgramGroupPage=yes
OutputBaseFilename=FastGame-Setup-{#AppVersion}
OutputDir=..\dist
Compression=lzma2/ultra64
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
; Installing a network service and a virtual adapter needs administrator rights (asked once, here).
PrivilegesRequired=admin
UninstallDisplayIcon={app}\FastGame.exe
LicenseFile={#Stage}\LICENSE.txt
SetupIconFile={#Stage}\icon.ico
CloseApplications=yes
RestartApplications=no
WizardStyle=modern

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; Flags: unchecked

[Files]
Source: "{#Stage}\FastGame.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#Stage}\fastgame-service.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#Stage}\sing-box.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#Stage}\libcronet.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#Stage}\THIRD-PARTY-LICENSES.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#Stage}\LICENSE.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#Stage}\MicrosoftEdgeWebview2Setup.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall; Check: NeedsWebView2

[Icons]
Name: "{group}\FastGame"; Filename: "{app}\FastGame.exe"
Name: "{autodesktop}\FastGame"; Filename: "{app}\FastGame.exe"; Tasks: desktopicon

[Run]
Filename: "{tmp}\MicrosoftEdgeWebview2Setup.exe"; Parameters: "/silent /install"; StatusMsg: "Installing WebView2..."; Flags: waituntilterminated; Check: NeedsWebView2
Filename: "{sys}\sc.exe"; Parameters: "create FastGameVPN binPath= ""{app}\fastgame-service.exe"" start= auto DisplayName= ""FastGame VPN Service"""; Flags: runhidden waituntilterminated; StatusMsg: "Installing the VPN service..."
Filename: "{sys}\sc.exe"; Parameters: "description FastGameVPN ""Creates the FastGame virtual network adapter and routes traffic through the VPN."""; Flags: runhidden waituntilterminated
Filename: "{sys}\sc.exe"; Parameters: "failure FastGameVPN reset= 86400 actions= restart/5000/restart/5000/restart/30000"; Flags: runhidden waituntilterminated
Filename: "{sys}\sc.exe"; Parameters: "start FastGameVPN"; Flags: runhidden waituntilterminated
Filename: "{app}\FastGame.exe"; Description: "Start FastGame"; Flags: nowait postinstall skipifsilent
Filename: "{app}\FastGame.exe"; Flags: nowait runasoriginaluser; Check: WizardSilent

[UninstallRun]
Filename: "{sys}\sc.exe"; Parameters: "stop FastGameVPN"; Flags: runhidden waituntilterminated; RunOnceId: "StopSvc"
Filename: "{sys}\sc.exe"; Parameters: "delete FastGameVPN"; Flags: runhidden waituntilterminated; RunOnceId: "DelSvc"

[Code]
function NeedsWebView2: Boolean;
var v: String;
begin
  Result := not (RegQueryStringValue(HKLM, 'SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}', 'pv', v)
             or RegQueryStringValue(HKCU, 'Software\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}', 'pv', v));
  if not Result then Result := (v = '') or (v = '0.0.0.0');
end;

// Upgrade: stop (and remove) the old service before files are replaced; the [Run] section recreates it.
procedure CurStepChanged(CurStep: TSetupStep);
var rc: Integer;
begin
  if CurStep = ssInstall then begin
    Exec(ExpandConstant('{sys}\sc.exe'), 'stop FastGameVPN', '', SW_HIDE, ewWaitUntilTerminated, rc);
    Sleep(1500);
    Exec(ExpandConstant('{sys}\sc.exe'), 'delete FastGameVPN', '', SW_HIDE, ewWaitUntilTerminated, rc);
  end;
end;

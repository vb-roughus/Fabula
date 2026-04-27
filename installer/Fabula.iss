; Fabula Windows installer (Inno Setup 6).
; Build via installer\build-installer.ps1 -- the script publishes the
; ASP.NET Core server self-contained for win-x64 and feeds the output
; into this script.

#define AppName "Fabula"
#define AppPublisher "Fabula"
#define AppURL "https://github.com/vb-roughus/fabula"
#define ServiceName "Fabula"
#define ServiceDisplay "Fabula Audiobook Server"
#define ServiceDescription "Self-hosted audiobook server (Fabula)."
#define ExeName "Fabula.Api.exe"

#ifndef AppVersion
  #define AppVersion "0.1.0"
#endif

#ifndef PublishDir
  #define PublishDir "..\artifacts\publish\win-x64"
#endif

#ifndef OutputDir
  #define OutputDir "..\artifacts\installer"
#endif

[Setup]
; Stable GUID -- changing this breaks upgrade-in-place. Keep as-is.
AppId={{8C4D5E2A-7E1F-4F9D-A3D7-7B6F1B82A0F1}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
AppPublisherURL={#AppURL}
AppSupportURL={#AppURL}
AppUpdatesURL={#AppURL}
DefaultDirName={commonpf64}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
DisableDirPage=auto
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
OutputDir={#OutputDir}
OutputBaseFilename=Fabula-Setup-{#AppVersion}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
UninstallDisplayName={#AppName} {#AppVersion}
UninstallDisplayIcon={app}\{#ExeName}
VersionInfoVersion={#AppVersion}
VersionInfoProductName={#AppName}
VersionInfoProductVersion={#AppVersion}
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "german"; MessagesFile: "compiler:Languages\German.isl"

[Files]
; The publish output is dropped here by build-installer.ps1.
Source: "{#PublishDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Dirs]
; Persistent data lives in %ProgramData%\Fabula. Installer creates it but
; never removes it on uninstall (uninstallneveruninstall) so the user
; doesn't lose their library or DB.
Name: "{commonappdata}\{#AppName}"; Permissions: users-modify; Flags: uninsneveruninstall
Name: "{commonappdata}\{#AppName}\data"; Permissions: users-modify; Flags: uninsneveruninstall
Name: "{commonappdata}\{#AppName}\data\covers"; Permissions: users-modify; Flags: uninsneveruninstall
Name: "{commonappdata}\{#AppName}\logs"; Permissions: users-modify; Flags: uninsneveruninstall

[Icons]
Name: "{group}\{#AppName} Web UI"; Filename: "http://localhost:5075/"; IconFilename: "{app}\{#ExeName}"
Name: "{group}\{#AppName} Datenordner"; Filename: "{commonappdata}\{#AppName}"
Name: "{group}\{#AppName} deinstallieren"; Filename: "{uninstallexe}"

[Run]
; Open the firewall (idempotent: delete first, then add).
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Fabula Server"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Fabula Server"" dir=in action=allow protocol=TCP localport=5075"; Flags: runhidden

; Register the Windows service (idempotent: tolerated to fail if it
; already exists -- we update the binPath afterwards).
Filename: "{sys}\sc.exe"; Parameters: "create {#ServiceName} binPath= ""\""{app}\{#ExeName}\"""" start= auto DisplayName= ""{#ServiceDisplay}"""; Flags: runhidden
Filename: "{sys}\sc.exe"; Parameters: "config {#ServiceName} binPath= ""\""{app}\{#ExeName}\"""" start= auto"; Flags: runhidden
Filename: "{sys}\sc.exe"; Parameters: "description {#ServiceName} ""{#ServiceDescription}"""; Flags: runhidden
Filename: "{sys}\sc.exe"; Parameters: "failure {#ServiceName} reset= 86400 actions= restart/5000/restart/15000/restart/60000"; Flags: runhidden

; Start the service. nowait so the wizard finishes promptly even if the
; service takes a moment to come up.
Filename: "{sys}\sc.exe"; Parameters: "start {#ServiceName}"; Flags: runhidden

; Optional: open the web UI when the user ticks the post-install box.
Filename: "http://localhost:5075/"; Description: "Fabula Web UI im Browser öffnen"; Flags: postinstall shellexec nowait skipifsilent

[UninstallRun]
Filename: "{sys}\sc.exe"; Parameters: "stop {#ServiceName}"; Flags: runhidden; RunOnceId: "StopFabula"
Filename: "{sys}\sc.exe"; Parameters: "delete {#ServiceName}"; Flags: runhidden; RunOnceId: "DeleteFabula"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Fabula Server"""; Flags: runhidden; RunOnceId: "RemoveFirewallRule"

[Code]
// Stop the service before files are copied, otherwise the running exe is
// locked. Best-effort: errors are swallowed.
procedure StopServiceQuiet;
var
  ResultCode: Integer;
begin
  Exec(ExpandConstant('{sys}\sc.exe'), 'stop {#ServiceName}', '', SW_HIDE,
    ewWaitUntilTerminated, ResultCode);
  // Give SCM a beat to release file handles.
  Sleep(1500);
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssInstall then
    StopServiceQuiet;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then
    StopServiceQuiet;
end;

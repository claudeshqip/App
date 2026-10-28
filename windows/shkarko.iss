; Instaluesi i Shkarko për Windows 11 x64 (Inno Setup 6).
; Ndërtohet nga .github/workflows/windows.yml pasi PyInstaller krijon dist\Shkarko\.

#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif

[Setup]
AppId={{8F3C2A51-6E4B-4C1D-9A77-3B5E2D1F0C42}
AppName=Shkarko
AppVersion={#AppVersion}
AppPublisher=claudeshqip
AppPublisherURL=https://claudeshqip.github.io/App/
DefaultDirName={autopf}\Shkarko
DisableProgramGroupPage=yes
; Instalohet për përdoruesin aktual, pa kërkuar leje administratori.
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
OutputDir=..\dist
OutputBaseFilename=Shkarko-Setup-x64
SetupIconFile=shkarko.ico
UninstallDisplayIcon={app}\Shkarko.exe
UninstallDisplayName=Shkarko
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes

[Tasks]
Name: "desktopicon"; Description: "Krijo ikonë në Desktop"; GroupDescription: "Ikonat:"

[Files]
Source: "..\dist\Shkarko\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Shkarko"; Filename: "{app}\Shkarko.exe"
Name: "{autodesktop}\Shkarko"; Filename: "{app}\Shkarko.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Shkarko.exe"; Description: "Hape Shkarko"; Flags: nowait postinstall skipifsilent

[InstallDelete]
; Heq skedarët e versionit të vjetër para se të kopjohet i riu.
Type: filesandordirs; Name: "{app}\_internal"

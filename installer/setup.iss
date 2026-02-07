[Setup]
AppName=Okul Mesajlasma Sistemi
AppVersion=1.1
AppPublisher=Karabag H.O.Akarsel Ortaokulu
DefaultDirName={autopf}\OkulMesajlasma
DefaultGroupName=Okul Mesajlasma Sistemi
OutputDir=..\Output
OutputBaseFilename=OkulMesajlasmaKurulum
SetupIconFile=..\windows\runner\resources\app_icon.ico
Compression=lzma
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=lowest

[Languages]
Name: "turkish"; MessagesFile: "compiler:Languages\Turkish.isl"

[Tasks]
Name: "desktopicon"; Description: "Masaustune kisayol olustur"; GroupDescription: "Ek gorevler:"
Name: "startupicon"; Description: "Bilgisayar acildiginda otomatik baslat"; GroupDescription: "Ek gorevler:"; Flags: checkedonce

[Files]
; Ana uygulama dosyalari
Source: "..\build\windows\x64\runner\Release\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
; Visual C++ Redistributable dosyalari
Source: "redist\vc_redist.x64.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall; Check: IsWin64
Source: "redist\vc_redist.x86.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall; Check: not IsWin64

[Icons]
Name: "{group}\Okul Mesajlasma Sistemi"; Filename: "{app}\okul_mesajlasma.exe"
Name: "{autodesktop}\Okul Mesajlasma Sistemi"; Filename: "{app}\okul_mesajlasma.exe"; Tasks: desktopicon

[Registry]
; Windows acilisinda otomatik baslat
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "OkulMesajlasma"; ValueData: """{app}\okul_mesajlasma.exe"""; Flags: uninsdeletevalue; Tasks: startupicon

[Run]
; Visual C++ Redistributable kurulumu (sessiz)
Filename: "{tmp}\vc_redist.x64.exe"; Parameters: "/install /passive /norestart"; StatusMsg: "Visual C++ Redistributable (x64) yukleniyor..."; Flags: waituntilterminated skipifdoesntexist
Filename: "{tmp}\vc_redist.x86.exe"; Parameters: "/install /passive /norestart"; StatusMsg: "Visual C++ Redistributable (x86) yukleniyor..."; Flags: waituntilterminated skipifdoesntexist
; Programi baslat
Filename: "{app}\okul_mesajlasma.exe"; Description: "Programi baslat"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; Kaldirildiginda startup kaydini sil (Registry zaten uninsdeletevalue ile silinir)

[UninstallDelete]
Type: filesandordirs; Name: "{app}"

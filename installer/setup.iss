[Setup]
AppName=Okul Mesajlasma Sistemi
AppVersion=1.0
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

[Files]
Source: "..\build\windows\x64\runner\Release\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Okul Mesajlasma Sistemi"; Filename: "{app}\karabagoo.exe"
Name: "{autodesktop}\Okul Mesajlasma Sistemi"; Filename: "{app}\karabagoo.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\karabagoo.exe"; Description: "Programi baslat"; Flags: nowait postinstall skipifsilent

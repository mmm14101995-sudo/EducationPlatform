[Setup]
AppId={{C21E5507-D3CE-519F-86E5-6DF966A59A5C}}
AppName=المنصة التعليمية
AppVersion=1.0
AppPublisher=أستاذ مصطفى محمد
DefaultDirName={localappdata}\EducationPlatform
DefaultGroupName=المنصة التعليمية
PrivilegesRequired=lowest
OutputDir=Output
OutputBaseFilename=EducationPlatform-Setup
SetupIconFile=icon.ico
UninstallDisplayIcon={app}\EducationPlatform.exe
UninstallDisplayName=المنصة التعليمية
VersionInfoVersion=1.0.0.0
Compression=lzma
SolidCompression=yes
WizardStyle=modern

[Tasks]
Name: "desktopicon"; Description: "إنشاء اختصار على سطح المكتب"; GroupDescription: "الاختصارات:"; Flags: checkedonce

[Files]
Source: "dist\EducationPlatform.exe"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\المنصة التعليمية"; Filename: "{app}\EducationPlatform.exe"
Name: "{group}\إلغاء تثبيت المنصة"; Filename: "{uninstallexe}"
Name: "{autodesktop}\المنصة التعليمية"; Filename: "{app}\EducationPlatform.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\EducationPlatform.exe"; Description: "تشغيل المنصة التعليمية"; Flags: nowait postinstall skipifsilent

# Shkarko

**Faqja (shkarko për Android dhe Windows):** https://claudeshqip.github.io/App/

Web app i thjeshtë (Flask + [yt-dlp](https://github.com/yt-dlp/yt-dlp)) për shkarkimin e videove nga YouTube.

## Çka bën

- Lexon linkun dhe tregon **të gjitha rezolucionet** që i ofron YouTube për atë video (144p deri 8K, me 60fps kur ka) bashkë me madhësinë e përafërt.
- **Video**: zgjidh rezolucionin dhe formatin e skedarit: `MP4`, `MKV` ose `WEBM`. Videoja dhe zëri bashkohen automatikisht me ffmpeg.
- **Vetëm audio**: `MP3` (128/192/256/320 kbps), `M4A`, `OPUS`, `WAV`, `FLAC`.
- Shirit progresi me shpejtësi dhe kohë të mbetur; skedari ruhet automatikisht në kompjuter kur përfundon.
- Skedarët e përkohshëm në server fshihen pas 1 ore.

## Kërkesat

- Python 3.10+
- [ffmpeg](https://ffmpeg.org/download.html) (duhet të jetë në `PATH`)
- Një JavaScript runtime: [Deno](https://deno.com) (rekomandohet) ose Node.js. YouTube e kërkon këtë që yt-dlp t'i nxjerrë të gjitha formatet.

## Si ta nisësh

```bash
python -m venv .venv
source .venv/bin/activate        # Windows: .venv\Scripts\activate
pip install -r requirements.txt
python app.py
```

Pastaj hape <http://localhost:5000> në shfletues.

Porti mund të ndryshohet me `PORT=8080 python app.py`.

## Shënime

- YouTube i ndryshon shpesh mbrojtjet; nëse shkarkimi dështon, përditëso yt-dlp: `pip install -U "yt-dlp[default]"`.
- Për MP4 në 4K/8K, YouTube zakonisht ofron vetëm kodekët AV1/VP9; disa player-a të vjetër mund të mos i luajnë. Në atë rast zgjidh MKV ose përdor VLC.
- Shkarkoni vetëm përmbajtje për të cilën keni të drejtë (videot tuaja, Creative Commons, etj.). Kushtet e YouTube e ndalojnë shkarkimin pa leje.

## App-i Android (APK)

Folderi `android/` përmban një app Android me **të njëjtin template** (`templates/index.html` + `static/`). Shkarkimi bëhet **direkt në telefon**, pa server: app-i i ka brenda yt-dlp, Python, ffmpeg dhe QuickJS (librari [youtubedl-android](https://github.com/yausername/youtubedl-android)).

- Shiriti poshtë: **Shkarko**, **Shkarkimet** (lista e skedarëve të shkarkuar) dhe **Cilësimet**.
- Skedarët ruhen te **Downloads/Shkarko**, ose në dosjen që zgjedh te Cilësimet.
- Te Cilësimet vendos edhe rezolucionin dhe formatet e parazgjedhura.
- Mund ta përdorësh edhe nga app-i i YouTube: **Shpërndaj → Shkarko**.
- yt-dlp përditësohet automatikisht sa herë hapet app-i.

### Ku e gjen APK-në

GitHub Actions (`.github/workflows/android.yml`) e ndërton APK-në në çdo push dhe e publikon te **Releases** të repo-s. Ndërtohet vetëm `Shkarko-arm64-v8a.apk` (telefonat Android 64-bit). Faqja në GitHub Pages (`index.html` në rrënjë) ka gjithmonë linkun e versionit të fundit.

Në telefon: hape APK-në dhe lejo "Instalo nga burime të panjohura" kur ta kërkojë.

### Nënshkrimi (opsional)

Pa konfigurim, APK-ja nënshkruhet me një çelës debug që ndryshon në çdo build. Kështu, para se të instalosh versionin e ri, duhet ta çinstalosh të vjetrin. Që përditësimet të instalohen direkt sipër, krijo një çelës:

```bash
keytool -genkeypair -keystore release.keystore -alias ytdownloader -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.keystore
```

dhe shtoji këto te **Settings → Secrets and variables → Actions** në GitHub:
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` (`ytdownloader`), `ANDROID_KEY_PASSWORD`.

### Ndërtimi lokal

```bash
cd android
./gradlew assembleRelease   # kërkon Android SDK (ANDROID_HOME)
```

## App-i për Windows 11 (x64)

`desktop.py` e hap të njëjtin app (Flask + template) në një dritare Windows-i me WebView2. Funksionon njësoj si në telefon:

- Shkarkimi bëhet në kompjuter; skedarët ruhen te **Downloads\Shkarko** ose në dosjen që zgjedh te Cilësimet.
- **Shkarkimet** (me Hape/Fshi) dhe **Cilësimet** ruhen te `%APPDATA%\Shkarko`.
- ffmpeg dhe Deno janë brenda programit; yt-dlp përditësohet vetë në sfond.

GitHub Actions (`.github/workflows/windows.yml`) ndërton `Shkarko.exe` me PyInstaller, e teston (`Shkarko.exe --selftest log.txt`) dhe krijon instaluesin `Shkarko-Setup-x64.exe` me Inno Setup (`windows/shkarko.iss`). Instaluesi publikohet te release-i `windows`.

Për ta nisur nga kodi (në Windows): `pip install -r requirements.txt pywebview` dhe `python desktop.py` (ffmpeg dhe deno duhet të jenë në `PATH`).

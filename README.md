# YouTube Downloader

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

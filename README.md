# Gemini Sharh – Android (Kotlin)

Google Gemini + **Microsoft Edge TTS (norasmiy, kalitsiz)** yordamida videolarga sinxron audio tavsif yaratuvchi Android ilova.

## TTS ustuvorlik (botlardagi kabi)

1. **Edge TTS** (norasmiy) — **API kalit kerak emas**  
   - `uz-UZ-SardorNeural`, `uz-UZ-MadinaNeural` va boshqa Neural ovozlar  
   - Python `edge-tts` bilan bir xil endpoint + Sec-MS-GEC  
2. **Azure Speech** (ixtiyoriy) — Edge ishlamasa  
3. **Tizim TTS** — oxirgi fallback

## Asosiy imkoniyatlar

- Video tanlash → Gemini tahlil → `[MM:SS]` tavsif
- SRT + TXT
- **Sardor** ovozi (default erkak)
- Original addondagi ovozlar va vision modellar
- GitHub Actions → avtomatik APK

## Sozlamalar

1. **Gemini API** kaliti — [aistudio.google.com/apikey](https://aistudio.google.com/apikey) (majburiy)
2. Ovoz: **Sardor (O'zbekcha - Ravon va tabiiy)**
3. Azure kalit — **ixtiyoriy** (faqat Edge ishlamasa)
4. Vision model tanlash

## GitHub’ga yuklash

```bash
git init && git add . && git commit -m "Gemini Sharh + Edge TTS Sardor"
git branch -M main
git remote add origin https://github.com/YOUR_USER/gemini-sharh-android.git
git push -u origin main
```

Actions → Artifacts dan APK ni yuklab oling.

## Eslatma

Edge TTS Microsoft protokoliga bog‘liq. Agar bir kun ishlamay qolsa, Azure kalit qo‘yib davom eting yoki tizim TTS ishlatiladi.

## v1.2 — Video + audio birlashtirish

- Natija **Yuklamalar/GeminiSharh_*** papkasiga saqlanadi
- `video_with_sharh.mp4` — original video + tavsif (original ovoz ~28% past)
- `narration.mp3` — alohida tavsif treki
- `description.srt` / `.txt`

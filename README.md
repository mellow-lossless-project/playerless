# Playerless

*It just plays music.*

English · [Italiano](README.it.md)

An ultra-light music player for Android, under 1 MB. It searches JioSaavn and YouTube Music at once, opens any artist's albums and singles, plays lossless FLAC when a lossless server has the song, and downloads songs for offline listening. No account, no ads, no tracking.

The name is a pun on "lossless".

## Download

**[Playerless.apk](https://github.com/mellow-lossless-project/playerless/releases/latest/download/Playerless.apk)** (version 1.6) for Android 8.0 or newer.

Android asks once to allow installs from your browser or file manager. Every version is also in [Releases](https://github.com/mellow-lossless-project/playerless/releases) with its notes and the APK's SHA-256. A new version installs over the old one: playlists and downloads stay. Uninstalling the app deletes them.

The app keeps itself up to date: at most every 6 hours it asks GitHub for the latest release, downloads it in the background and shows Android's install prompt, never while music is playing. Settings → Updates checks by hand.

## Features

- **Search** JioSaavn and YouTube Music together. Downloaded songs show first, offline too. Case, accents, apostrophes and spaces don't matter: "season25" finds "Season 25".
- **Artist pages**: search an artist's name and the artist is the first result. Their page lists all their albums and singles, newest first, from YouTube Music; tap one to see and play its songs. A long press on any song also offers "Go to *artist*".
- **Uncensored versions**: every song shows up once, in its explicit version when there is one, even when a catalog lists the clean edit first.
- **Lossless**: with the default quality, each song is first asked from a Monochrome-style lossless server and plays in FLAC (up to 24 bit / 96 kHz) when the server has it. Otherwise it plays from its own source: JioSaavn AAC up to 320 kbps, or YouTube Opus at about 130 kbps. A song waits at most 6 seconds for the server, and the next song in the queue is looked up while the current one plays. The full-screen player says what is really playing, e.g. "Lossless · FLAC 24 bit · 96.0 kHz". A song in FLAC is 25–40 MB, so on mobile data songs play from their own source unless Settings → "Lossless on mobile data" is on. Lossless needs Android 8.1 or newer, the first to decode FLAC.
- **Downloads** for offline listening, choosing the quality per song. Settings → "Re-download in lossless" swaps the songs already downloaded for FLAC where the server has it.
- **Playlists**, **Most played** on the home screen, full-screen player, playback in the background with a notification.
- **Equalizer**: a curve from 20 Hz to 20 kHz with up to 12 points to drag, each with its own width.
- **Loading log**: while a song loads, a small log shows each step, so a slow song is never a mystery. It disappears as soon as the song plays.
- **Themes**: Classic (black, with 9 accent colors), Black poster and Paper poster.
- **Languages**: English, Italiano, Español, Français, Deutsch, Português. The app follows the phone's language; Settings → Language picks another.

## Why it's so light

The APK is about 0.85 MB. No Compose, Hilt, Room or image libraries: just Kotlin, XML views, Media3 for playback and RecyclerView for lists. Covers are loaded by 100 lines of code instead of an image library.

YouTube needs no extra engine either: YouTube's own JavaScript runs in the WebView the phone already has, hidden. The [yt-dlp](https://github.com/yt-dlp/ejs) solver unscrambles the stream links, and BotGuard, Google's anti-bot script, makes the token YouTube asks for before it serves a whole song.

## Privacy

Playerless has no account, no analytics and no ads. It only talks to:

- JioSaavn and YouTube, to search and to play. YouTube also gets the anonymous visitor id it hands out to every client;
- the lossless server, `tracks.monochrome.st` unless you set another in Settings → Lossless server: it gets the title and artist of each song you play, and only with the Lossless quality;
- GitHub, to check for updates at most every 6 hours.

Playlists, downloads, listening stats and settings stay on the phone, unless you copy them to your PC with the Windows app.

## Build

You need the Android SDK and JDK 17 or newer (Android Studio has both).

```
gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is signed with your debug key unless you pass your own. Android only lets an APK update an app signed with the same key: a build of your own and the published APK can't replace each other without uninstalling first, which deletes playlists and downloads.

## Translations

The texts are in [app/src/main/res/values/strings.xml](app/src/main/res/values/strings.xml) (English). To add a language: copy that file to `values-xx/strings.xml`, where `xx` is the language code, translate every string except the ones marked `translatable="false"`, then add `"xx"` to `CHOICES` in [Lang.kt](app/src/main/kotlin/com/playerless/Lang.kt) and to `localeFilters` in [app/build.gradle.kts](app/build.gradle.kts). Corrections to the existing translations are welcome too.

## Also in this repository

- **Playerless for Windows** (`desktop/`): the same app on a PC, with an Italian interface. See [README.it.md](README.it.md#playerless-per-pc).
- **Spotify import** (`tools/spotify/`): moves your Spotify playlists to the phone over USB. See [README.it.md](README.it.md#importare-le-playlist-da-spotify).

## Disclaimer

Playerless uses the private APIs of JioSaavn and YouTube, which are neither official nor documented: they can change or stop working at any time. When YouTube changes its player or its anti-bot checks, the loading log shows the step where it stops. The lossless server is run by others and may slow down or disappear: songs then play from their own source. This project is not affiliated with JioSaavn, YouTube, Google or Monochrome. What you stream and download is your responsibility: use Playerless for music you have the right to listen to, within each service's terms and your country's laws.

## License

[GPL-3.0](LICENSE). The JioSaavn and YouTube clients derive from [Convx](https://github.com/cosmictaserdev-creator/Convx), under the same license. `app/src/main/assets/yt` holds the solver of [yt-dlp/ejs](https://github.com/yt-dlp/ejs) 0.8.0 (Unlicense), which includes meriyah (ISC) and astring (MIT) with their licenses at the top of the file.

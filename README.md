# Playerless

*It just plays music.*

Player musicale per Android, ultra leggero: cerca e riproduce da JioSaavn e YouTube Music, scarica i brani per ascoltarli offline e pesa meno di 1 MB. Gira anche sul PC Windows, e lì importa tutto dal telefono: vedi [Playerless per PC](#playerless-per-pc).

Il nome è un gioco di parole: suona come "lossless". L'audio è AAC fino a 320 kbps da JioSaavn, 128 kbps da YouTube; con la qualità "Lossless FLAC" l'app chiede prima il brano a un server in stile Monochrome (lo stesso protocollo che usa Mochy Music) e suona FLAC quando il server lo dà.

## Funzioni

- **Ricerca** istantanea: i brani scaricati compaiono subito in cima, anche senza rete. JioSaavn e YouTube Music si cercano insieme; se uno dei due è lento, i risultati dell'altro compaiono dopo al massimo un secondo e mezzo
- **YouTube Music** per gli artisti che JioSaavn non ha: i suoi brani sono segnati "· YouTube" e partono in qualche secondo
- **Log di caricamento** mentre un brano si prepara, che sparisce appena parte
- **Playlist da Spotify** importate dal PC con un bot (vedi sotto)
- **Equalizzatore** a curva da 20 Hz a 20 kHz: fino a 12 punti da trascinare, ognuno con la sua larghezza, nel colore dell'app. Ogni punto è un filtro a campana applicato dal player; quando la curva sale, il volume generale scende di altrettanto, così i picchi non distorcono
- **Qualità streaming** in ordine: Lossless FLAC (1411 kbps), JioSaavn (320, 160 o 96 kbps). Con Lossless compare anche "Server lossless", per usare un server diverso da `tracks.monochrome.st`. A novembre 2026 quel server manda FLAC per alcuni brani (anche 24 bit / 96 kHz) e AAC a ~320 kbps per altri: l'app glielo chiede per ogni brano, suona il FLAC quando c'è e altrimenti la sorgente migliore. Nel player, tra i due tempi, c'è scritto cosa sta suonando davvero (per esempio "Lossless · FLAC 24 bit · 96,0 kHz" o "JioSaavn · AAC 320 kbps")
- **Download offline** scegliendo la qualità per ogni brano: Lossless dal server lossless (se non ha il brano, scarica il meglio della sorgente normale), oppure 320, 160 o 96 kbps da JioSaavn, 128 da YouTube. Nei Scaricati compaiono i kbps reali del file. Anche i brani scaricati si riproducono tutti, in ordine o casuale
- **Playlist** locali, con riproduzione in ordine o casuale
- **Ascolti più spesso** in home
- **Player a schermo intero** con artista, tempo trascorso e durata, avanzamento e volume
- **Notifica** e riproduzione in background
- **Colore d'accento** a scelta tra 9, su sfondo nero
- **Tema** a scelta tra Classico (quello sopra) e due poster: *Poster nero*, inchiostro oliva su nero granuloso, e *Poster carta*, nero su carta grigia con griglia e fasce nere con il salmone. Nei poster le copertine sono stampate in due inchiostri con la grana di una fotocopia; nel player a schermo intero, tenendo il dito sulla copertina si vede l'originale. Il colore d'accento vale solo per il Classico

L'interfaccia è solo in italiano.

## Scaricare

**[Scarica Playerless.apk](https://github.com/mellow-lossless-project/playerless/raw/main/Playerless.apk)** (versione 1.1)

Serve Android 8 o successivo. Al primo avvio dell'installazione il telefono chiederà di consentire le app da fonti sconosciute.

## Perché è così leggera

L'APK pesa circa 0,76 MB. Niente Compose, Hilt, Room o librerie per le immagini: solo Kotlin, View XML, Media3 per la riproduzione e RecyclerView per le liste.

Per YouTube niente NewPipe né altri motori JavaScript: il JavaScript di YouTube gira nella WebView già presente nel telefono, nascosta. Il solutore di yt-dlp decifra i link e BotGuard, lo script anti-bot di Google, genera il gettone che YouTube chiede per servire un brano intero.

## Compilare

Servono Android Studio, che include JDK e Android SDK, e un dispositivo con Android 8 o successivo.

```
gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Su Android 9, 10 e 11 si può installare anche il profilo di ottimizzazione:

```
adb install-multiple -r app/build/outputs/apk/release/app-release.apk app/build/outputs/apk/release/baselineProfiles/1/app-release.dm
```

La build di release è firmata con la chiave di debug. L'app è sviluppata e provata su un Samsung Galaxy A7 2018 con Android 10.

## Importare le playlist da Spotify

Servono Firefox sul PC, il telefono collegato via USB con il debug USB attivo e Playerless installata.

1. Avvia `tools\spotify\importa-spotify.bat`.
2. Solo la prima volta si apre una finestra di Firefox sulla pagina di accesso di Spotify: accedi e, quando vedi Spotify, chiudi la finestra.
3. Il bot legge la libreria con un Firefox nascosto, la manda al telefono e mostra l'avanzamento. Alla fine stampa i brani che non ha trovato.

Vengono importate le playlist create da te, più i Brani che ti piacciono. Per includere anche quelle che segui, metti `ONLY_MINE = false` in `spotify-export.js`. Ogni brano viene cercato prima su JioSaavn e poi su YouTube, confrontando titolo, artista e durata. Rilanciando il bot, le playlist con lo stesso nome vengono aggiornate, non duplicate. Per cambiare account: `importa-spotify.bat -Login`.

Il bot usa un profilo di Firefox tutto suo, in `%LOCALAPPDATA%\Playerless\spotify-firefox`, separato dal tuo: lì resta l'accesso a Spotify. La libreria la legge con l'API interna del web player e con il token che il web player stesso ottiene, perché l'API pubblica di Spotify richiede Premium e rifiuta quel token. Non serve Premium né un'app sviluppatore, ma non è un'API ufficiale: può smettere di funzionare quando Spotify cambia il web player.

Piano B, a mano: su open.spotify.com, con il tuo account aperto, F12, scheda Console, incolla `spotify-export.js` e premi Invio. Firefox la prima volta chiede di scrivere `consenti incolla`. Poi importa il file scaricato con `importa-spotify.bat -File <file>`.

## Playerless per PC

La stessa app su Windows: ricerca su JioSaavn e YouTube Music, log di caricamento, download con scelta della qualità, playlist, ascolti più spesso, equalizzatore a curva e colore d'accento. In più i tasti multimediali della tastiera e il riquadro multimediale di Windows.

Serve Windows 10 o 11 con Firefox. Non c'è niente da installare: avvia `desktop\build\Playerless.exe`. Si apre una finestra di Firefox senza barre, con un profilo tutto suo in `%LOCALAPPDATA%\Playerless\firefox`, separato dal tuo. Chiudendo la finestra si chiude tutto. L'exe contiene tutta l'app e si può spostare dove si vuole, per esempio sul desktop; `build.bat` aggiorna anche la copia sul desktop, se c'è.

Scorciatoie: spazio per play e pausa, Ctrl+F o `/` per cercare, Ctrl+→ e Ctrl+← per brano successivo e precedente, Esc per tornare indietro. Il clic destro su un brano o su una playlist fa quello che sul telefono fa la pressione lunga.

### Importare dal telefono

Serve il telefono collegato via USB con il debug USB attivo, come per Spotify, e sul telefono una Playerless con l'esportazione (questa versione o successive). Sul PC: Impostazioni → Importa dal telefono.

Arrivano playlist, ascolti, brani scaricati con le loro copertine, equalizzatore, qualità e colore. I brani passano direttamente dall'app del telefono al PC, senza copie temporanee sul telefono. Si può ripetere quando si vuole: le playlist con lo stesso nome vengono aggiornate, gli ascolti non si sommano due volte e i brani già copiati restano dove sono. Le playlist create sul PC restano.

Il telefono espone i dati solo ad adb: la sua esportazione chiede il permesso DUMP, che ha la shell di adb e nessuna app installata. Se adb non è nel PATH, Playerless lo cerca anche negli Android SDK di `%LOCALAPPDATA%\Android\Sdk`.

### Come funziona

`Playerless.exe` pesa circa 300 KB ed è compilato con il compilatore C# che Windows ha già (`desktop\build.bat`). È un piccolo server su `http://localhost:47380` che risponde solo alla sua pagina. Fa da tramite verso JioSaavn e YouTube, salva i dati e i brani in `%LOCALAPPDATA%\Playerless`, usa adb per il telefono e apre la finestra. L'app vera è la pagina in `desktop\web`, la logica del telefono portata in JavaScript. Per YouTube usa gli stessi file dell'app Android: il solutore di yt-dlp gira in un worker, BotGuard in un riquadro nascosto. L'equalizzatore usa Web Audio, i cui filtri "peaking" sono gli stessi del telefono.

Per lavorare sulla pagina senza ricompilare: `Playerless.exe --server --web desktop\web` serve i file dalla cartella e non apre finestre. Con `--data <cartella>` i dati vanno altrove; con `--phone <seriale>` sceglie il telefono se ne sono collegati più di uno.

## Avvertenze

Playerless usa le API private di JioSaavn e YouTube, che non sono ufficiali né documentate: possono cambiare o smettere di funzionare in qualsiasi momento. YouTube in particolare cambia spesso il suo player e i controlli anti-bot; quando succede, il log di caricamento mostra a che passaggio si ferma. Il progetto non è affiliato a JioSaavn né a YouTube.

## Licenza

[GPL-3.0](LICENSE). I client JioSaavn e YouTube derivano da [Convx](https://github.com/cosmictaserdev-creator/Convx), distribuito con la stessa licenza. In `app/src/main/assets/yt` c'è il solutore di [yt-dlp/ejs](https://github.com/yt-dlp/ejs) 0.8.0 (Unlicense), che include meriyah (ISC) e astring (MIT) con le loro licenze in testa al file.

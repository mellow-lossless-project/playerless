# Playerless

*It just plays music.*

Player musicale per Android, ultra leggero: cerca e riproduce da JioSaavn, scarica i brani per ascoltarli offline e pesa meno di 1 MB.

Il nome è un gioco di parole: nonostante suoni come "lossless", non riproduce FLAC. L'audio è AAC fino a 320 kbps.

## Funzioni

- **Ricerca** istantanea: i brani scaricati compaiono subito in cima, anche senza rete
- **Download offline** scegliendo la qualità per ogni brano (320, 160 o 96 kbps)
- **Playlist** locali, con riproduzione in ordine o casuale
- **Ascolti più spesso** in home
- **Player a schermo intero** con avanzamento e volume
- **Notifica** e riproduzione in background
- **Colore d'accento** a scelta tra 9, su sfondo nero

L'interfaccia è solo in italiano.

## Perché è così leggera

L'APK pesa circa 0,63 MB. Niente Compose, Hilt, Room o librerie per le immagini: solo Kotlin, View XML, Media3 per la riproduzione e RecyclerView per le liste.

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

## Avvertenze

Playerless usa l'API privata di JioSaavn, che non è ufficiale né documentata: può cambiare o smettere di funzionare in qualsiasi momento. Il progetto non è affiliato a JioSaavn.

## Licenza

[GPL-3.0](LICENSE). Il client JioSaavn deriva da [Convx](https://github.com/cosmictaserdev-creator/Convx), distribuito con la stessa licenza.

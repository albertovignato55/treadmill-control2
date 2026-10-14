# Tapis Control (v0.3)

App Android (Kotlin + Jetpack Compose) per comandare un tapis roulant FTMS via Bluetooth LE
(testata sulla carta per Lotto Pace G600: espone il servizio Fitness Machine 0x1826).

## Come aprirla
1. Android Studio (Ladybug o piu' recente) -> File > Open -> cartella `TapisControl`.
2. Attendi il Gradle sync (scarica Gradle 8.9 e le dipendenze).
3. Collega il telefono (debug USB attivo) e premi Run.

## Uso
- Scheda Connessione: "Cerca tapis roulant", tocca il dispositivo, attendi "Pronto".
  Il log mostra le caratteristiche trovate e le risposte del tapis roulant a ogni comando.
- Scheda Manuale: Start/Stop, velocita' e inclinazione con +/-, limite massimo di velocita'.
- Scheda Allenamento: lista di step (minuti, km/h, %), Avvia/Pausa/Stop.
  Tra uno step e l'altro la variazione e' graduale (6 s); a fine allenamento defaticamento a 2 km/h e stop.

## Note
- Lo schermo resta acceso durante l'uso: in questa versione non c'e' un servizio in background.
- Se il tapis roulant risponde "non supportato" a velocita'/inclinazione, controlla il log.
- Tieni sempre la chiave di sicurezza del tapis roulant a portata di mano.

## Compilare l'APK senza Android Studio (GitHub Actions)
1. Crea un account su github.com e un nuovo repository privato.
2. Carica tutto il contenuto della cartella `TapisControl` (compresa la cartella nascosta `.github`).
3. Vai nella scheda Actions: parte da solo il workflow "Build APK" (oppure Run workflow).
4. A fine build (circa 5-10 minuti) apri l'esecuzione e scarica l'artefatto `TapisControl-apk`: dentro c'e' `app-debug.apk`.
5. Sul telefono: copia l'APK, aprilo e consenti l'installazione da origini sconosciute.

## Novita' v0.2
- Scheda **Allenamenti**: piu' allenamenti con nome (nuovo, modifica, duplica, elimina), ognuno fatto di blocchi
  ripetibili ("Ripeti x N"). I blocchi si possono salvare in una libreria e riusare in altri allenamenti.
- **Video collegato** a ogni allenamento: file del telefono (velocita' di riproduzione continua) oppure link YouTube
  (lettore ufficiale incorporato, velocita' a scatti 0.25-2x). Si indica la velocita' a cui e' stato girato il video;
  durante l'allenamento il video segue la velocita' reale del tapis roulant e si ferma quando il nastro e' fermo.
- Scheda **In corso**: video, dati live, progresso, Pausa/Stop e calibrazione della velocita' di ripresa.
- Firma fissa dell'APK (`app/debug.keystore`): dalla v0.2 in poi gli aggiornamenti si installano sopra la
  versione precedente senza perdere gli allenamenti. (La prima volta va disinstallata la v0.1.)

## Aggiornare il repository su GitHub
Add file > Upload files, trascina la cartella `app` (sovrascrive i file esistenti), Commit changes.
La compilazione parte da sola nella scheda Actions.

## Novita' v0.3 - Percorso virtuale
- Nell'editor di un allenamento scegli **Intervalli** (come prima) oppure **Percorso video**.
- In **Percorso video** la velocita' la decidi tu con i pulsanti −1 / −0,1 / +0,1 / +1 nella scheda *In corso*
  (il video la segue); la **pendenza segue il profilo** del percorso: elenco di punti "minuto del video -> %",
  interpolati tra un punto e il successivo.
- Il profilo si scrive a mano nell'editor, oppure si **registra guardando il video**: regola la pendenza con −1/+1
  e premi "Registra pendenza qui" (salva un punto alla posizione corrente del video).
- Se correggi la pendenza a mano, vale fino al prossimo cambio previsto dal profilo.
- Funziona con video su file e con YouTube (la posizione viene letta dal lettore).
- Se il video e' piu' corto del percorso, riparte da capo (e anche il profilo riparte).
- Gli allenamenti salvati con la v0.2 restano validi. Si installa sopra la v0.2 (stessa firma).

# Tapis Control (v0.6)

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

## Novita' v0.4 - Importa allenamenti JSON
- Scheda **Allenamenti** > **Importa JSON**: scegli un file `.json` oppure incolla il testo generato dal
  Treadmill Workout Planner (chiavi `units`, `speedUnit`, `name`, `segments`).
- Ogni segmento diventa un blocco; le rampe (velocita'/pendenza che cambiano) sono spezzate in step lunghi quanto
  `speedInterval` / `inclineInterval` (60 s se mancano), cosi' il tapis roulant le segue gradualmente.
- Supporta velocita' (km/h, mph) e passo (M:SS), metrico e imperiale: tutto convertito in km/h.
- Le zone cardio (`targetHRZone`, `recoveryHRZone`...) vengono ignorate: l'app non le gestisce.
- La pendenza durante gli allenamenti a intervalli ora procede a passi di **0,5 %** (prima interi).
- Il limite massimo di velocita' (scheda Manuale) vale anche per gli allenamenti importati: impostalo a 6,5 km/h
  per il tuo tapis roulant. Si installa sopra la v0.3 (stessa firma).

### Percorso virtuale con velocita' guidata dal video (v0.4)
- Ogni punto del profilo ha ora anche una **velocita' (km/h)** facoltativa: velocita' e pendenza seguono il
  video e vengono interpolate tra un punto e il successivo.
- La velocita' cambia al massimo di 0,3 km/h ogni 2 s e non supera mai il limite massimo impostato.
- Punto senza velocita' = velocita' a controllo manuale come prima. Gli allenamenti v0.3 restano validi.
- **Registra punto qui** salva insieme velocita' e pendenza correnti alla posizione del video.
- Una correzione manuale vale fino al prossimo cambio previsto dal profilo.

### Pianificare con Claude (v0.4)
- Scheda **Allenamenti** > **Copia prompt per Claude**: copia negli appunti la guida per generare l'allenamento
  (con il limite di velocita' impostato e la pendenza massima letta dal tapis roulant, o 8 % se non connesso).
- Incollala in una chat con Claude, rispondi alle domande, copia il JSON finale e usa **Importa JSON**.

## Novita' v0.5 - Comandi affidabili e limite di pendenza
- **Partenza**: dopo Start l'app aspetta che il nastro si muova davvero (il tapis fa un conto alla rovescia)
  prima di inviare velocita' e pendenza. Prima il comando poteva arrivare troppo presto e venire ignorato
  (il tapis restava a 1 km/h).
- **Verifica e ripetizione**: se dopo 6 s la velocita' (o la pendenza) letta dal tapis non corrisponde a quella
  richiesta, il comando viene ripetuto (max 5 volte, solo a nastro in movimento, mai in pausa o a nastro fermo).
  Se non basta, lo scrive nel log (scheda Connessione).
- **Limite massimo di pendenza** (scheda Manuale, predefinito 8 %): vale per intervalli, percorso video e comandi
  manuali. Insieme al limite di velocita' viene applicato direttamente a ogni comando Bluetooth.
- Si installa sopra la v0.4 (stessa firma).

## Novita' v0.6 - Il video non si perde piu' cambiando scheda
- Prima, uscendo dalla scheda *In corso* il lettore video veniva chiuso e al ritorno ripartiva da 0:00.
- Ora l'app ricorda la posizione: mentre sei su un'altra scheda il video "scorre" in modo virtuale alla velocita'
  reale del tapis roulant (e il profilo di pendenza/velocita' del percorso continua a seguirlo); al ritorno il
  video riparte dal punto giusto, anche per YouTube. Se il video e' piu' corto del percorso, riparte da capo.
- La posizione si azzera solo quando avvii un nuovo allenamento.
- Si installa sopra la v0.5 (stessa firma).

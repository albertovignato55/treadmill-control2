# Tapis Control (v0.14)

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

## Novita' v0.7
- **Percorso video: le correzioni manuali restano.** Ogni volta che durante il percorso tocchi i tasti di velocita'
  o pendenza, il valore viene registrato da solo come punto del profilo alla posizione corrente del video. Prima
  il profilo, che continua a interpolare tra i punti, riportava la velocita' ai valori precedenti dopo pochi secondi.
- **Video nella scheda Manuale**: scegli un file o un link YouTube; il video scorre alla velocita' reale del tapis
  roulant e la calibrazione ("ripreso a ... km/h") si regola con -0,5 / +0,5. Viene ricordato anche a app chiusa.
- Si installa sopra la v0.6 (stessa firma).

## Novita' v0.8 - Correzioni manuali negli allenamenti a intervalli
- Nella scheda *In corso*, durante un allenamento a **Intervalli** (come i JSON importati) ci sono ora i tasti di
  correzione: velocita' -0,5 / -0,1 / +0,1 / +0,5 km/h e pendenza -1 / -0,5 / +0,5 / +1 %.
- La correzione si somma a tutti gli step rimanenti, quindi non "torna" al valore programmato al cambio di step.
  Resta nei limiti massimi di velocita' e pendenza.
- **Salva la correzione nell'allenamento** la scrive in tutti gli step: i prossimi allenamenti partiranno da li'.
- Include le novita' della v0.7 (punti del percorso registrati da soli, video nella scheda Manuale).
- Si installa sopra la v0.6 o v0.7 (stessa firma).

## Novita' v0.9 - Comandi Bluetooth in sequenza
- Il tapis roulant ignora un comando quando ne riceve un altro subito dopo (nel log: nessuna "Risposta a op 0x2"
  per la velocita'). Ora ogni comando di controllo aspetta la risposta del tapis (max 1,5 s) e una breve pausa
  prima del successivo.
- Se un comando di velocita' o pendenza e' ancora in coda quando ne arriva uno piu' recente, viene sostituito:
  toccare piu' volte +0,1 non intasa la coda.
- Se il tapis non applica il valore, la ripetizione parte dopo 3 s (prima 6 s).
- Si installa sopra la v0.8 (stessa firma).

## Novita' v0.10
- **L'app non forza piu' i valori cambiati dalla console del tapis roulant.** La verifica dei comandi (v0.5)
  ripeteva la velocita' richiesta ogni volta che il valore letto era diverso, anche se l'avevi cambiato tu dalla
  console: la riportava a 3 km/h per 5 volte. Ora ripete il comando solo finche' il valore non e' stato raggiunto
  la prima volta; se poi cambia (console), lo scrive nel log e non lo forza piu'. Lo stesso per la pendenza.
- **Pendenza a passi di 0,5 %** anche nei tasti della scheda Manuale e del Percorso video (-1, -0,5, +0,5, +1) e
  nel profilo del percorso (prima era a numeri interi).
- Si installa sopra la v0.9 (stessa firma).

## Novita' v0.11 - Pendenza a passi interi (1 %)
- Il tapis roulant cambia pendenza di **1 % per volta** (console, manubrio, Bluetooth): i mezzi punti della v0.10
  non servivano. Tolti i tasti da 0,5 %.
- Ogni pendenza (intervalli, percorso, correzioni, import JSON, comandi) e' arrotondata al numero intero piu'
  vicino, e mai oltre il limite massimo. Il cursore del limite di pendenza va a numeri interi.
- "Copia prompt per Claude" ora chiede pendenze intere. Nel codice il passo e' `INCLINE_STEP` (Workout.kt).
- Si installa sopra la v0.10 (stessa firma).

## Novita' v0.12 - Step numerati in modo progressivo
- Nell'editor dell'allenamento gli step sono numerati 1, 2, 3... su tutto l'allenamento, e non ripartono da 1 in
  ogni blocco. Un blocco ripetuto (Ripeti x N) conta i suoi step una sola volta nell'editor; la scheda *In corso*
  conta invece anche le ripetizioni (Step X/Y).
- Si installa sopra la v0.11 (stessa firma).

## Novita' v0.13 - Numeri degli step coerenti con "In corso"
- Sotto il nome di ogni blocco ripetuto (o preceduto da blocchi ripetuti) l'editor mostra la numerazione della
  scheda *In corso*, ripetizioni comprese. Esempio: "In corso: step 5-12 (2 step x 4 ripetizioni)".
- Si installa sopra la v0.12 (stessa firma).

## Novita' v0.14 - Percorso animato al posto del video
- Nuova scelta **Animazione** accanto a Nessuno / File / YouTube (nell'editor dell'allenamento e nella scheda
  Manuale): un sentiero con alberi, colline, nuvole e montagne disegnato dall'app, senza file video.
- Scorre alla **velocita' reale** del tapis roulant, si ferma quando il nastro e' fermo e si inclina con la
  **pendenza** letta dal tapis: segue quindi qualsiasi allenamento JSON e le correzioni manuali, e non finisce mai.
- Sopra l'animazione: velocita', pendenza e (negli Intervalli) nome del blocco e numero dello step.
- Funziona anche col Percorso video: "Ripreso a ... km/h" definisce la scala del profilo (secondi equivalenti).
- Si installa sopra la v0.13 (stessa firma).

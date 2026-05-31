# Piano di migrazione V2: Jentic → Agenor (IntelliJ-first / PowerShell)

> **Relazione con V1**: questo documento è il piano **esecutivo** del rebrand.
> `MIGRATION_PLAN_AGENOR.md` resta come **baseline strategica** e riferimento storico.
> Le decisioni `D1` (naming annotazioni), `D2` (clean cut), `D3` (versioning 0.24.0 → 1.0.0)
> sono fissate in **ADR-025** e non vengono modificate da V2: cambia solo il *come*, non il *cosa*.
>
> **Cosa cambia rispetto al V1:**
> 1. Sequenza riordinata: **IDE prima, file system dopo** (l'indicizzatore Maven resta coerente)
> 2. Uso massiccio dei refactoring IntelliJ (Shift+F6, Move Module, Structural Search) al posto di `find + sed + mv`
> 3. Tutti i comandi shell tradotti in **PowerShell nativo** (no dipendenza da Git Bash / sed GNU su Windows)
> 4. Aggiunto step **Analyze → Inspect Code** come gate obbligatorio prima di `mvn clean verify`
> 5. Aggiunti **commit-checkpoint** per fase (reversibilità granulare)
> 6. Comando esplicito per i file SQL migration + `git mv` della directory `jentic-hitl/` (V1 non la copriva)
> 7. Nuova **Fase A** che committa ADR-025 e lo collega dall'indice — è il documento canonico delle decisioni e va reso raggiungibile *prima* del rename

---

## 1. Dati di partenza (verificati 2026-05-31)

| Categoria | Conteggio attuale | Nota vs V1 |
|---|---|---|
| File Java | **564** | V1 dichiarava 534 baseline pre-Items 3-5 |
| File Maven POM | **9** (1 root + 8 moduli) | V1 contava 8 (escluso root) |
| File documentazione (.md) | ~65 | invariato |
| ADR in `docs/adr/` | **26** (ADR-001 → ADR-025) | V1 dichiarava 20 baseline |
| File configurazione YAML/JSON | 5 | invariato |
| Asset branding SVG | 2 | invariato |
| File CI/CD (`.github/workflows/`) | 2 (`build.yml` + `deploy-docs.yml`) | V1 ne dichiarava 1 |
| Spring Boot auto-config | 1 `.imports` + 1 `additional-spring-configuration-metadata.json` | invariato |
| Webapp statica | 4 file (zero riferimenti a "jentic") | invariato |
| Modulo `jentic-adapters-persistence` | **già presente** | V1 lo dava come "futuro" (Items 3-5) |

**Moduli da rinominare (8):**
`jentic-bom`, `jentic-core`, `jentic-runtime`, `jentic-adapters`, `jentic-adapters-persistence`, `jentic-spring-boot-starter`, `jentic-tools`, `jentic-examples`

**Annotazioni da rinominare (5):**
`@JenticAgent`, `@JenticBehavior`, `@JenticMessageHandler`, `@JenticPersist`, `@JenticPersistenceConfig` — tutte sotto `jentic-core/src/main/java/dev/jentic/core/annotations/`

**Classi `Jentic*` da rinominare:** ~35 file distribuiti su tutti i moduli (core, runtime, tools, adapters, spring-boot-starter).

---

## 2. Toolbox IntelliJ utilizzato

| Strumento | Come si invoca | Uso nel piano | Perché meglio di shell |
|---|---|---|---|
| **Rename Package** | tasto destro sul package node nel Project tool window → Refactor → Rename | Op singola: `dev.jentic` → `dev.agenor` (~564 file) | Aggiorna package declaration, import, riferimenti cross-modulo, JavaDoc `{@link}`, stringhe `Class.forName(...)` se opzione attiva |
| **Rename Class/Annotation** | F2 (Rename) o Shift+F6 sul nome | Op iterata: `JenticX` → `AgenorX` (35 classi + 5 annotazioni) | Gestisce import, `{@link}`, `.class` literal, costruttori, file di test che istanziano |
| **Move Module / Rename Module** | tasto destro sul modulo → Refactor → Rename | Op iterata: rinomina `jentic-X` → `agenor-X` aggiornando `<artifactId>`, `<module>` parent, link tra moduli | Atomica: modulo fisico + POM in un'operazione, no `git mv` + sed manuale |
| **Structural Search and Replace (SSR)** | Edit → Find → Search Structurally | Opzionale per pattern complessi (es. annotazioni in commenti JavaDoc) | Awareness PSI invece di regex cieca |
| **Find in Path** | Ctrl+Shift+F | Gate di verifica residui (es. `dev.jentic` in `.java` post-Fase 1) | Risultati raggruppati con preview |
| **Replace in Path** | Ctrl+Shift+R | Sostituzione su file non-Java (markdown, YAML) con preview | Opzione "Search in comments and strings" + filter per file type |
| **Analyze → Inspect Code** | Code menu → Inspect Code → Whole project | Catch-all post-rename: simboli irrisolti, `{@link}` rotti, annotazioni non risolte | Genera report navigabile, evita di scoprire problemi a build time |
| **Invalidate Caches and Restart** | File menu | Pre-flight + dopo Move Module | Forza reindicizzazione Maven coerente |

> **Vantaggio chiave**: `Rename` e `Move Module` sono **preview-safe**. L'IDE mostra conflict / non-code usage (commenti, stringhe, file di properties) prima di applicare, e ti lascia scegliere quali includere. `sed` non ha rete.

---

## 3. Fasi esecutive

### Fase A — Bootstrap ADR-025 (foundation) ⏱ 10 min

ADR-025 è il documento canonico delle decisioni `D1`/`D2`/`D3` citate in tutto il V2. Allo
stato attuale è **untracked** e non referenziato dall'indice degli ADR né da nessun altro
documento del repo. Va committato e cross-linkato **prima** di iniziare il rename, altrimenti
chi guarda il repo a metà migrazione non trova la motivazione delle scelte.

**A.1** — Aggiungere in `docs/adr/README.md`, nella tabella "ADR Index", una riga dopo ADR-024:

```markdown
| [ADR-025](ADR-025-agenor-rebrand.md)                                | Agenor Rebrand — Naming, Compat, Versioning | Accepted | 2026-05-28 |
```

**A.2** — Aggiungere in `docs/adr/README.md`, nella sezione "Decision Dependencies", una voce
dopo ADR-024:

```markdown
- **ADR-025** (Agenor Rebrand) builds on ADR-002, ADR-003, ADR-006, ADR-016, ADR-020 — affects naming and Maven coordinates for the entire project
```

**A.3** (raccomandato) — Aggiungere in `CHANGELOG.md` uno stub di sezione `## [Unreleased]` o
`## [0.24.0]` che cita ADR-025 come motivazione del rebrand. Verrà completato in Fase 10.

**A.4** — Commit:

```powershell
git add docs/adr/ADR-025-agenor-rebrand.md docs/adr/README.md
# se hai fatto A.3:
# git add CHANGELOG.md
git commit -m "docs(adr): add ADR-025 agenor rebrand and link from index"
```

**Gate Fase A**:

```powershell
git log --oneline -1                            # l'ultimo commit cita ADR-025
Select-String -Path docs\adr\README.md -Pattern 'ADR-025'   # >=2 match (tabella + dependencies)
git status --short                              # ADR-025 non più "??"
```

---

### Fase 0 — Pre-flight ⏱ 15 min

```powershell
# 0.1 Build verde di partenza (per non confondere bug pre-esistenti con bug del rename)
mvn clean verify

# 0.2 Verifica branch di lavoro (già attivo dal commit f620947)
git status                            # verifica nessuna modifica pendente
git branch --show-current             # atteso: rename/jentic-to-agenor

# 0.3 Abilitare path lunghi su Windows (alcuni path Maven generati superano 260 char)
git config core.longpaths true
```

In IntelliJ:
- **File → Invalidate Caches and Restart** → "Invalidate and Restart" (cache pulita = indicizzazione coerente)
- Attendere il completamento del "Indexing" prima di iniziare la Fase 1

**Gate Fase 0**: `mvn clean verify` verde, indicizzazione IntelliJ completata.

---

### Fase 1 — IDE: Rename Package `dev.jentic` → `dev.agenor` ⏱ 15 min

In IntelliJ:
1. Project tool window → espandi `jentic-core/src/main/java`
2. Click destro sul package `dev.jentic` (il livello più alto comune) → **Refactor → Rename...**
3. Inserisci nuovo nome: `dev.agenor`
4. Spunta:
   - ✅ Search in comments and strings
   - ✅ Search for text occurrences
   - ✅ Rename subpackages
5. **Preview Refactorings** → ispeziona la lista di occorrenze "non-code" prima di confermare
6. **Do Refactor**

> **Nota**: il package può apparire come `dev.jentic.core`, `dev.jentic.runtime`, ecc. — IntelliJ permette di selezionare il segmento comune `dev.jentic` e propagare a tutti i sotto-package in una sola operazione.

**Gate Fase 1**:
```powershell
# Verifica residui letterali nei .java
Select-String -Path (Get-ChildItem -Recurse -Filter *.java).FullName -Pattern 'dev\.jentic' -List
# Atteso: nessun output
```
In IntelliJ: **Ctrl+Shift+F** → cerca `dev.jentic` con filtro `*.java` → 0 risultati.

**Commit checkpoint:**
```powershell
git add -A
git commit -m "chore(rename): IDE package rename dev.jentic -> dev.agenor"
```

---

### Fase 2 — IDE: Rename annotazioni (D1 di ADR-025) ⏱ 15 min

Le 5 annotazioni sono tutte in `jentic-core/src/main/java/dev/agenor/core/annotations/` (path già aggiornato dopo Fase 1).

Per ciascuna, in IntelliJ apri il file e premi **Shift+F6** sul nome dell'annotazione:

| File | Old name | New name |
|---|---|---|
| `JenticAgent.java` | `JenticAgent` | `Agent` |
| `JenticBehavior.java` | `JenticBehavior` | `Behavior` |
| `JenticPersist.java` | `JenticPersist` | `Persist` |
| `JenticPersistenceConfig.java` | `JenticPersistenceConfig` | `PersistenceConfig` |
| `JenticMessageHandler.java` | `JenticMessageHandler` | `AgenorMessageHandler` |

> Spunta sempre **Search in comments and strings** nel dialog di Rename. IntelliJ rinominerà automaticamente anche il file fisico `JenticAgent.java` → `Agent.java`.

**Gate Fase 2**: nessun file `JenticAgent*.java`, `JenticBehavior*.java`, `JenticPersist*.java`, `JenticPersistenceConfig*.java`, `JenticMessageHandler*.java` residuo.
```powershell
Get-ChildItem -Recurse -Filter "Jentic*Annotation*.java"
Get-ChildItem -Recurse -Filter "JenticAgent.java","JenticBehavior.java","JenticPersist.java","JenticPersistenceConfig.java","JenticMessageHandler.java"
# Atteso: nessun output
```

**Commit checkpoint:**
```powershell
git add -A
git commit -m "chore(rename): rename @Jentic* annotations per ADR-025 D1"
```

---

### Fase 3 — IDE: Rename classi `Jentic*` → `Agenor*` ⏱ 30-45 min

In IntelliJ, modulo per modulo:

1. Project tool window → espandi `jentic-core`
2. Usa **Edit → Find → Find in Path** con pattern `Jentic` filtro `*.java`, scope: modulo corrente — ottieni elenco delle classi
3. Per ogni file, apri e **Shift+F6** sul nome classe → sostituisci `Jentic` con `Agenor` mantenendo il resto del nome
   - Esempi: `JenticRuntime` → `AgenorRuntime`, `JenticConfiguration` → `AgenorConfiguration`, `JenticAutoConfiguration` → `AgenorAutoConfiguration`, `JenticCLI` → `AgenorCLI`, `JenticA2AAdapter` → `AgenorA2AAdapter`, `JenticA2AClient` → `AgenorA2AClient`, `JenticAgentExecutor` → `AgenorAgentExecutor`

Ripetere per `jentic-runtime`, `jentic-tools`, `jentic-adapters`, `jentic-spring-boot-starter`.

> **Suggerimento**: alla fine, **Find in Path** su `Jentic` (senza filtro) in tutto il progetto. Le occorrenze rimaste sono in: documentazione, YAML/JSON, POM, log strings — saranno gestite dalle fasi successive.

**Gate Fase 3**:
```powershell
Get-ChildItem -Recurse -Filter "Jentic*.java"
# Atteso: nessun output
```

**Commit checkpoint:**
```powershell
git add -A
git commit -m "chore(rename): rename Jentic* classes to Agenor*"
```

---

### Fase 4 — IDE: Rename moduli Maven (8 op) ⏱ 30 min

Per ciascuno degli 8 moduli, in IntelliJ:

1. Project tool window → click destro sul modulo `jentic-X`
2. **Refactor → Rename...** (oppure Shift+F6)
3. Inserisci nuovo nome `agenor-X`
4. Spunta:
   - ✅ Rename directory
   - ✅ Search in comments and strings (per riferimenti in pom.xml di altri moduli)
5. **Preview** → verifica che IntelliJ proponga di aggiornare:
   - il `<artifactId>` del modulo
   - i `<module>` nel parent `pom.xml`
   - eventuali `<dependency>` cross-modulo
6. **Do Refactor**

**Ordine consigliato** (bottom-up nelle dipendenze):
1. `jentic-bom` → `agenor-bom`
2. `jentic-core` → `agenor-core`
3. `jentic-runtime` → `agenor-runtime`
4. `jentic-adapters` → `agenor-adapters`
5. `jentic-adapters-persistence` → `agenor-adapters-persistence`
6. `jentic-spring-boot-starter` → `agenor-spring-boot-starter`
7. `jentic-tools` → `agenor-tools`
8. `jentic-examples` → `agenor-examples`

**Aggiornamento `<groupId>`**: IntelliJ non lo cambia (è un attributo, non un module name). Aggiornarlo in Fase 5.

**Gate Fase 4**:
```powershell
# Verifica nessuna directory jentic-* residua a livello root
Get-ChildItem -Directory -Filter "jentic-*"
# Atteso: nessun output

# Verifica nessun <artifactId>jentic-...</artifactId> nei POM
Select-String -Path (Get-ChildItem -Recurse -Filter pom.xml).FullName -Pattern '<artifactId>jentic-' -List
# Atteso: nessun output
```
In IntelliJ: la **Maven tool window** mostra solo `agenor-*`. Triggera "Reload All Maven Projects" se necessario.

**Commit checkpoint:**
```powershell
git add -A
git commit -m "chore(rename): rename Maven modules jentic-* -> agenor-*"
```

---

### Fase 5 — PowerShell: residui non-Java ⏱ 30 min

#### 5.1 `<groupId>` nei POM
```powershell
Get-ChildItem -Recurse -Filter pom.xml |
  ForEach-Object {
    (Get-Content $_.FullName -Raw) `
      -replace '<groupId>dev\.jentic</groupId>','<groupId>dev.agenor</groupId>' `
      -replace 'jentic-bom','agenor-bom' |
    Set-Content $_.FullName -Encoding UTF8
  }
```

#### 5.2 File Markdown (prima passata automatica)
```powershell
Get-ChildItem -Recurse -Filter *.md |
  Where-Object { $_.FullName -notmatch '\\target\\' } |
  ForEach-Object {
    (Get-Content $_.FullName -Raw) `
      -replace 'Jentic','Agenor' `
      -replace 'jentic','agenor' `
      -replace 'JENTIC','AGENOR' |
    Set-Content $_.FullName -Encoding UTF8
  }
```
> ⚠️ Dopo questo: review manuale obbligatoria su ADR, CHANGELOG, README (vedi Fase 7).

#### 5.3 YAML, JSON
```powershell
Get-ChildItem -Recurse -Include *.yml,*.yaml,*.json |
  Where-Object { $_.FullName -notmatch '\\target\\' -and $_.FullName -notmatch '\\node_modules\\' } |
  ForEach-Object {
    (Get-Content $_.FullName -Raw) `
      -replace 'jentic\.','agenor.' `
      -replace 'jentic-','agenor-' `
      -replace 'Jentic','Agenor' |
    Set-Content $_.FullName -Encoding UTF8
  }
```
File impattati: `mkdocs.yml`, `*-test.yml`, `mixed-env.yml`, `docker-compose.yml`, `additional-spring-configuration-metadata.json`.

#### 5.4 CI/CD GitHub Actions
```powershell
Get-ChildItem .github/workflows -Filter *.yml |
  ForEach-Object {
    (Get-Content $_.FullName -Raw) `
      -replace 'jentic','agenor' `
      -replace 'Jentic','Agenor' |
    Set-Content $_.FullName -Encoding UTF8
  }
```
Review manuale: path JaCoCo (`agenor-*/target/site/jacoco/`), URL deploy docs, artifact names.

#### 5.5 SQL migration files in `agenor-adapters-persistence`

Aggiornare il contenuto dei file `.sql`:

```powershell
$migrationDir = "agenor-adapters-persistence\src\main\resources\db\migration"
if (Test-Path $migrationDir) {
  Get-ChildItem -Path $migrationDir -Recurse -Filter *.sql |
    ForEach-Object {
      (Get-Content $_.FullName -Raw) `
        -replace 'jentic_','agenor_' `
        -replace 'jentic\.','agenor.' |
      Set-Content $_.FullName -Encoding UTF8
    }
}
```

Rinominare la directory `jentic-hitl/` (Move Module non discende sotto `resources/`):

```powershell
# Rinomina directory di migrazione preservando la git history
$oldDir = "agenor-adapters-persistence\src\main\resources\db\migration\jentic-hitl"
$newDir = "agenor-adapters-persistence\src\main\resources\db\migration\agenor-hitl"
if (Test-Path $oldDir) {
  git mv $oldDir $newDir
}
```

Verifica nessun riferimento hardcoded al path `jentic-hitl` in codice Java o config:

```powershell
Select-String -Path (Get-ChildItem -Recurse -Include *.java,*.yml,*.yaml,*.properties | Where-Object { $_.FullName -notmatch '\\target\\' }).FullName -Pattern 'jentic-hitl' -List
# Atteso: 0 risultati (i replace delle fasi precedenti hanno già aggiornato il contenuto)
```

> ⚠️ Verifica manuale: nomi tabelle (`jentic_hitl_approvals` → `agenor_hitl_approvals`?), schema names, indici, configurazione Flyway (`flyway.locations`). Eventuali test che fanno query letterali in Java sono già stati aggiornati dalla Fase 1.

#### 5.6 Asset branding (rename file, non contenuto)
```powershell
git mv docs/assets/jentic-icon.svg docs/assets/agenor-icon.svg
git mv docs/assets/jentic-wordmark.svg docs/assets/agenor-wordmark.svg
```
> Il *redesign* visivo è lavoro del designer (parallelo). Il rename ora evita broken link nelle docs.

**Gate Fase 5**:
```powershell
# Nessun residuo "jentic" in YAML/JSON/POM
Select-String -Path (Get-ChildItem -Recurse -Include *.yml,*.yaml,*.json,pom.xml | Where-Object { $_.FullName -notmatch '\\target\\' }).FullName -Pattern 'jentic' -List
# Atteso: solo CHANGELOG.md (riferimenti storici legittimi) e ADR (storici)
```

**Commit checkpoint:**
```powershell
git add -A
git commit -m "chore(rename): update non-Java files (POMs groupId, docs, YAML, JSON, SQL)"
```

---

### Fase 6 — Spring Boot Starter (file critici) ⏱ 30 min

Questi file richiedono che il fully-qualified name della classe `@Configuration` sia corretto, altrimenti l'auto-discovery non parte.

#### 6.1 `META-INF/spring/...AutoConfiguration.imports`
File: `agenor-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

```powershell
$importsFile = "agenor-spring-boot-starter\src\main\resources\META-INF\spring\org.springframework.boot.autoconfigure.AutoConfiguration.imports"
(Get-Content $importsFile -Raw) `
  -replace 'dev\.jentic','dev.agenor' `
  -replace 'JenticAutoConfiguration','AgenorAutoConfiguration' |
  Set-Content $importsFile -Encoding UTF8
```
> Probabilmente già aggiornato dalla Fase 5.3 (`jentic.` → `agenor.`); il comando è idempotente.

#### 6.2 `additional-spring-configuration-metadata.json`
Già aggiornato dalla Fase 5.3. Verifica manuale che le `properties[*].name` siano `agenor.*`.

#### 6.3 Test integrazione obbligatorio
```powershell
mvn test -pl agenor-spring-boot-starter
# Atteso: AutoConfiguration carica senza BeanDefinitionException
```
Se esiste un test `*AutoConfigurationIT`, eseguirlo. Altrimenti avviare un esempio Spring Boot:
```powershell
mvn exec:java -pl agenor-examples -Dexec.mainClass="dev.agenor.examples.spring.SpringBootStarterExample"
```

**Gate Fase 6**: applicazione Spring Boot di esempio si avvia, `AgenorAutoConfiguration` appare nei log di startup.

---

### Fase 7 — Review manuale di documentazione contestuale ⏱ 3-4 ore

I file Markdown sono stati sostituiti dalla Fase 5.2 con regex bruta. Vanno rivisti manualmente quelli che contengono ragionamenti:

| File | Cosa rivedere |
|---|---|
| `README.md` | Vetrina pubblica: badge, coordinate Maven `dev.agenor`, link al nuovo repo |
| `CHANGELOG.md` | **NON rinominare la storia**. Aggiungere entry `0.24.0` con motivazione rebrand. Le entry pre-0.24.0 devono conservare i nomi originali "Jentic" come riferimento storico. |
| `CONTRIBUTING.md` | Istruzioni per contributor, build commands |
| `docs/adr/ADR-001.md` ... `ADR-024.md` | Per ciascuno: verificare che la sostituzione `Jentic`→`Agenor` non spezzi citazioni storiche, decisioni motivate da "nome Jentic", riferimenti a ticket Jentic |
| `docs/adr/ADR-025-agenor-rebrand.md` | Lasciare inalterato — è già scritto correttamente |
| `docs/adr/ADR-016-spring-boot-starter.md` | Verificare property names `agenor.*` |
| `MIGRATION_PLAN_AGENOR.md` (V1) | **Lasciare invariato**: è storia. Non rinominare in V1.md. |
| `MIGRATION_PLAN_AGENOR_V2.md` (questo file) | Già scritto in chiave Agenor |

> **Suggerimento**: in IntelliJ usa **Ctrl+Shift+F** con pattern `jentic` (case-insensitive) sui soli `.md` per scoprire occorrenze residue accettabili (storiche) vs da rivedere.

**Commit checkpoint:**
```powershell
git add -A
git commit -m "docs(rename): manual review of ADRs, CHANGELOG, README post-rebrand"
```

---

### Fase 8 — Analyze → Inspect Code ⏱ 30 min

In IntelliJ: **Code → Inspect Code... → Whole Project**.

Categorie da controllare nel report:
- **Unresolved symbols** (errori Java): deve essere 0
- **JavaDoc**: link `{@link Jentic...}` rotti → fix manuale o `Replace in Path` mirato
- **Spring Boot**: configurazione non valida (errore tipico se `.imports` è incoerente)
- **Maven**: dependency non risolte

Risolvere tutto prima di passare alla Fase 9.

```powershell
# Verifica complementare via line search per pattern Jentic dimenticati
Select-String -Path (Get-ChildItem -Recurse -Filter *.java).FullName -Pattern 'Jentic' -List |
  Where-Object { $_.Path -notmatch '\\target\\' }
# Atteso: 0 (eccetto stringhe legittime in commenti storici)
```

**Gate Fase 8**: Inspect Code → 0 errori bloccanti; nessun residuo "Jentic" nei `.java`.

---

### Fase 9 — Build, test, coverage ⏱ 1 ora

```powershell
mvn clean install
mvn clean verify
```

Errori residui attesi: 0-30 (molto meno del V1 grazie ai gate IDE per fase).

Esecuzione esempi:
```powershell
mvn exec:java -pl agenor-examples -Dexec.mainClass="dev.agenor.examples.PingPongExample"
mvn exec:java -pl agenor-examples -Dexec.mainClass="dev.agenor.examples.LLMAgentExample"
```

Documentazione:
```powershell
mkdocs build
# Atteso: nessun warning su link rotti
```

**Gate Fase 9**:
- ✅ `mvn clean verify` verde su tutti gli 8 moduli
- ✅ JaCoCo ≥ 80% per modulo (HTML report in `agenor-*/target/site/jacoco/index.html`)
- ✅ Esempi eseguibili senza errori
- ✅ `mkdocs build` pulito

**Commit checkpoint:**
```powershell
git add -A
git commit -m "test: fix residual references post-rename, all modules green"
```

---

### Fase 10 — Versioning e release ⏱ 30 min

Il branch è già su `0.24.0-SNAPSHOT` (cfr. commit `f620947`). Per la release:

```powershell
# Rimuovi -SNAPSHOT dai POM (Maven Versions Plugin)
mvn versions:set -DnewVersion=0.24.0 -DgenerateBackupPoms=false

# Verifica
Select-String -Path (Get-ChildItem -Recurse -Filter pom.xml).FullName -Pattern '<version>' | Select-Object -First 20

# Commit + tag
git add -A
git commit -m "chore(release): set version to 0.24.0 (rebrand to Agenor)"
git tag v0.24.0

# Deploy a Maven Central (richiede credenziali per dev.agenor)
mvn clean deploy -P release
```

**Gate Fase 10**: artifact `dev.agenor:agenor-bom:0.24.0` visibile su `https://repo1.maven.org/maven2/dev/agenor/` (può richiedere ~30 min per la sync).

Subito dopo, bump alla prossima SNAPSHOT:
```powershell
mvn versions:set -DnewVersion=0.25.0-SNAPSHOT -DgenerateBackupPoms=false
git add -A
git commit -m "chore: bump release version to 0.25.0-SNAPSHOT"
```

---

### Fase 11 — GitHub repo rename + comunicazione ⏱ 30 min

1. **GitHub UI** → repository `jentic` → Settings → Repository name → `agenor`. I redirect sui clone URL esistenti sono automatici.

2. Aggiorna remote locale:
   ```powershell
   git remote set-url origin https://github.com/<org>/agenor.git
   git remote -v
   ```

3. Aggiorna link GitHub residui nei file di repository:
   ```powershell
   Select-String -Path (Get-ChildItem -Recurse -Include *.md,*.yml | Where-Object { $_.FullName -notmatch '\\target\\' }).FullName -Pattern 'github\.com.*jentic'
   # Fix manuale o Replace in Path su IntelliJ
   ```

4. **GitHub Release `v0.24.0`** con note che includono:
   - Motivazione del rebranding (link ad ADR-025)
   - Tabella Maven coordinates: `dev.jentic:jentic-* → dev.agenor:agenor-*`
   - Tabella Spring Boot properties: `jentic.* → agenor.*`
   - Tabella annotazioni rinominate (D1)

5. Topics/About su GitHub: aggiornare descrizione e topics.

6. **Comunicazione editoriale** (Substack + social): annuncio rebrand + guida migrazione.

---

## 4. Riepilogo effort

| Fase | Attività | Effort V1 | Effort V2 (IntelliJ-first) |
|---|---|---|---|
| A | Bootstrap ADR-025 (index + commit) | (mancante in V1) | **10 min** |
| 0 | Pre-flight (build + cache) | 0.5 gg | **15 min** |
| 1 | IDE Package rename | (incluso in 1a V1) | **15 min** |
| 2 | IDE Annotation rename | (incluso in 1b/c V1) | **15 min** |
| 3 | IDE Class rename | (incluso in 1b/c V1) | **30-45 min** |
| 4 | IDE Move Module (8) | (incluso in 1d V1) | **30 min** |
| 5 | PowerShell residui non-Java | parte di V1 1d + 3 | **30 min** |
| 6 | Spring Boot starter | 0.25 gg | **30 min** |
| 7 | Review manuale docs | 1-1.5 gg | **3-4 ore** |
| 8 | Inspect Code | (mancante in V1) | **30 min** |
| 9 | Build, test, examples | 1 gg | **1 ora** |
| 10 | Versioning + release | (in V1 fase 5) | **30 min** |
| 11 | GitHub + comunicazione | 0.5 gg | **30 min** |
| **Totale** | | **4.25-5.25 gg** | **~1.5-2 gg** |

Il risparmio rispetto al V1 (~3 giorni) viene da:
- Le fasi IDE sostituiscono completamente i loop shell del V1 (V1: ~2 ore, V2: ~75 min con preview di sicurezza)
- Inspect Code anticipa la scoperta dei bug residui che il V1 lasciava al `mvn compile` (V1: 1-2 ore di iterazione errori, V2: 30 min concentrati)
- Move Module fa POM + directory in un'operazione (V1: `git mv` + `sed POM` separati)

---

## 5. Mitigazioni Windows-specific

- **`-Encoding UTF8`** esplicito su ogni `Set-Content`: il default di PowerShell 5.1 è UTF-16 con BOM, che rompe Maven e i parser YAML.
- **`Get-Content … -Raw`** per evitare problemi di terminatori di linea (`-Raw` preserva CRLF originale).
- **Path lunghi**: `git config core.longpaths true` come da Fase 0.
- **Antivirus**: durante `mvn clean install` con 564 file, Windows Defender può rallentare. Escludere la directory del progetto dalla scansione real-time (opzionale).
- **Locks IntelliJ**: se IntelliJ è aperto durante `git checkout`, può bloccare file in `.idea/`. Chiudere IntelliJ prima dei commit massivi.

---

## 6. Rischi residui

Identici al V1 (cfr. `MIGRATION_PLAN_AGENOR.md` § "Rischi residui"), con queste mitigazioni aggiuntive specifiche di V2:

| Rischio aggiuntivo V2 | Mitigazione |
|---|---|
| IDE Rename rifiuta op per riferimenti in binari/JAR | Preview prima del confirm; in caso, fallback su Replace in Path per i file specifici |
| Move Module non aggiorna un POM cross-modulo | Gate Fase 4: `Select-String '<artifactId>jentic-'` cattura il caso |
| PowerShell `Set-Content` corrompe file con BOM | Comando esplicito `-Encoding UTF8` (no BOM in PS 7, BOM noto in PS 5.1 — controllare versione con `$PSVersionTable`) |
| `.idea/workspace.xml` contiene reference vecchi | Il file è git-ignored; non è un problema per il commit. Riapertura del progetto rigenera. |

---

## 7. Sequenza di esecuzione raccomandata (riassuntiva)

> Punto di partenza: branch `rename/jentic-to-agenor` già attivo, ADR-025 nello working tree
> ma untracked.

```
Fase A  Bootstrap ADR-025: index entry + dependency + commit
  └─> Fase 0  Pre-flight: mvn verify verde + Invalidate Caches (branch già attivo)
        └─> Fase 1  IDE: Rename Package dev.jentic → dev.agenor  [commit]
              └─> Fase 2  IDE: Rename 5 annotazioni (Shift+F6)  [commit]
                    └─> Fase 3  IDE: Rename 35 classi Jentic* → Agenor*  [commit]
                          └─> Fase 4  IDE: Move Module ×8  [commit]
                                └─> Fase 5  PowerShell: POM groupId + docs + YAML/JSON + SQL + git mv jentic-hitl  [commit]
                                      └─> Fase 6  Spring Boot starter + integrazione test
                                            └─> Fase 7  Review manuale ADR/CHANGELOG/README  [commit]
                                                  └─> Fase 8  Analyze → Inspect Code (gate)
                                                        └─> Fase 9  mvn clean verify + esempi + mkdocs  [commit]
                                                              └─> Fase 10 versions:set 0.24.0 + tag + deploy
                                                                    └─> Fase 11 GitHub rename + release notes + comunicazione
```

Branding asset SVG: ridisegno designer in parallelo a Fase 4-9 (file già rinominati in Fase 5.6).

# Piano di migrazione: Jentic → Agenor

## Dati di partenza (analisi codebase)

Valori rilevati prima degli Items 3-5. Al momento del rebrand (dopo 0.23.0) i conteggi
saranno superiori: +1 modulo (`adapters-persistence`), +4 ADR (021-024), +1 ADR-025,
file Java e POM in aumento proporzionale.

| Categoria | Baseline (pre-Items 3-5) |
|---|---|
| File Java | 534 (7 moduli) |
| File Maven POM | 8 (67 riferimenti a "jentic") |
| File documentazione (.md) | 65 (20 ADR inclusi) |
| File configurazione YAML/JSON | 5 |
| Asset branding SVG | 2 |
| File CI/CD (GitHub Actions) | 1 |
| File Spring Boot auto-config | 1 imports + 1 JSON metadata |
| File webapp statica | 4 (senza riferimenti a "jentic") |

---

## Decisioni — già prese

### D1 — Rinomina annotazioni ✅

| Attuale | Nuovo | Motivazione |
|---|---|---|
| `@JenticAgent` | `@Agent` | Nome strutturale, collision risk basso |
| `@JenticBehavior` | `@Behavior` | Nome strutturale, collision risk basso |
| `@JenticMessageHandler` | `@AgenorMessageHandler` | Rischio collisione concreto con Spring `@MessageMapping` |
| `@JenticPersist` | `@Persist` | Nome strutturale, collision risk basso |
| `@JenticPersistenceConfig` | `@PersistenceConfig` | Nome strutturale, collision risk basso |
| `@DialogueHandler` | invariata | Già senza prefisso, nessun conflitto noto |
| `@WithGuardrails` | invariata | Già senza prefisso, nessun conflitto noto |
| `@RequiresApproval` | invariata | Già senza prefisso, nessun conflitto noto |

### D2 — Retrocompatibilità ✅

**Taglio netto.** Nessun consumer pubblico su Maven Central con coordinate `dev.jentic`. Niente shim jar né deprecation bridge.

### D3 — Versioning ✅ (rivista)

Il rebranding continua la sequenza di versioning esistente: prima release pubblica →
**`0.24.0`** sotto `dev.agenor` (il rebrand avviene dopo che Items 3-5 portano jentic
a 0.23.0). La continuità del numero porta il segnale di maturità delle release
precedenti senza la promessa di stabilità di 1.x.

La promozione a **`1.0.0`** è rimandata a quando l'API distribuita è dimostrata stabile
dall'uso reale. Criteri osservabili (documentati in ADR-025):
- Backend distribuiti (Redis, JDBC, HITL) validati in almeno un deployment reale
- Nessuna breaking change alle interfacce core nelle ultime 2–3 release consecutive
- Nessun ADR in pipeline che richieda modifiche alle interfacce pubbliche

---

## Strumenti di automazione

| Strumento | Copertura |
|---|---|
| **IntelliJ "Rename Package"** | ~550+ file Java (534 baseline + Items 3-5): package, import, riferimenti cross-file — una sola operazione |
| **Bash + `find` + `sed`** | POM, Markdown, YAML/JSON, commenti, stringhe letterali, SQL migration files |
| **Shell script + `mv`** | Rinomina file `Jentic*.java` → `Agenor*.java` |
| **`git mv`** | Rinomina le 8 directory modulo preservando la git history |
| **`mvn clean verify`** | Build, test, JaCoCo — completamente automatizzato |
| **`mkdocs build`** | Verifica documentazione — automatizzato |

---

## Fasi di migrazione

### Fase 0 — Setup ⏱ 0.5 giorni

Le decisioni D1/D2/D3 sono già prese. Il setup tecnico è questione di minuti.

```bash
git checkout -b rename/jentic-to-agenor
```

Checklist preliminare (da completare prima di avviare le fasi successive):
- [x] **ADR-025** — documentare formalmente D1 (naming annotazioni), D2 (taglio netto),
  D3 (versioning 0.24.0 → 1.0.0 per criteri osservabili) e la motivazione del rebranding.
  È il documento canonico delle decisioni; `MIGRATION_PLAN_AGENOR.md` rimane il piano esecutivo.

Checklist esterna (non bloccante per il codice, può procedere in parallelo):
- [ ] Registrare dominio `agenor.dev` (o alternativa)
- [ ] Creare credenziali Maven Central per groupId `dev.agenor`

---

### Fase 1 — Rinomina core Java + Maven ⏱ 0.5–1 giorno

#### 1a. Package rename (≈ 15 min automazione + 1–2 ore fix residui)

Eseguire in IntelliJ: **Refactor → Rename Package** su `dev.jentic` → `dev.agenor`.
Aggiorna automaticamente tutti i file Java (~550+ al momento del rebrand: import, dichiarazioni, cross-references).

Dopo l'operazione IDE, verificare i residui non intercettati:

```bash
# Stringhe letterali nei test e nei log
grep -r "dev\.jentic" --include="*.java" -l

# Fix manuale sui file trovati (oppure second-pass sed)
find . -name "*.java" -exec sed -i 's/dev\.jentic/dev.agenor/g' {} \;
```

#### 1b + 1c. Class rename + annotazioni (≈ 1 ora automazione)

Da eseguire **dopo** il package rename dell'IDE:

```bash
# 1. Rinomina i file sorgente Jentic* → Agenor*
find . -name "Jentic*.java" | while read f; do
  mv "$f" "$(dirname "$f")/$(basename "$f" | sed 's/Jentic/Agenor/g')"
done

# 2. Sostituisce il testo nei sorgenti (classi, annotazioni, commenti)
find . -name "*.java" -exec sed -i 's/Jentic/Agenor/g' {} \;

# 3. Rinomina le annotazioni strutturali (rimozione prefisso — D1 Opzione A)
find . -name "*.java" -exec sed -i \
  's/@AgenorAgent\b/@Agent/g;
   s/AgenorAgent\.class/Agent.class/g;
   s/@AgenorBehavior\b/@Behavior/g;
   s/AgenorBehavior\.class/Behavior.class/g;
   s/@AgenorPersist\b/@Persist/g;
   s/@AgenorPersistenceConfig\b/@PersistenceConfig/g' {} \;

# 4. @AgenorMessageHandler rimane con prefisso (D1 Opzione B) — nessuna ulteriore azione
```

Rinominare anche i file delle annotazioni:

```bash
git mv src/main/java/.../AgenorAgent.java     src/main/java/.../Agent.java
git mv src/main/java/.../AgenorBehavior.java  src/main/java/.../Behavior.java
git mv src/main/java/.../AgenorPersist.java   src/main/java/.../Persist.java
git mv src/main/java/.../AgenorPersistenceConfig.java src/main/java/.../PersistenceConfig.java
# AgenorMessageHandler.java non viene rinominato
```

#### 1d. Maven POMs + directory fisiche (≈ 1 ora)

```bash
# Sostituisce groupId e artifactId nei POM
find . -name "pom.xml" -exec sed -i \
  's|<groupId>dev\.jentic</groupId>|<groupId>dev.agenor</groupId>|g;
   s|<artifactId>jentic-|<artifactId>agenor-|g;
   s|jentic-bom|agenor-bom|g' {} \;

# Rinomina le directory fisiche dei moduli (preserva git history)
for mod in bom core runtime adapters adapters-persistence spring-boot-starter tools examples; do
  git mv jentic-$mod agenor-$mod
done

# Aggiorna i <module> nel parent pom.xml (manuale o sed)
sed -i 's|<module>jentic-|<module>agenor-|g' pom.xml
```

Verifica post-rename:

```bash
grep -r "jentic" --include="pom.xml" -l   # deve restituire lista vuota
mvn clean compile -q                       # verifica compilazione
```

Aggiornare manualmente `Automatic-Module-Name` nei file `MANIFEST.MF` di ciascun modulo (8 occorrenze).

---

### Fase 2 — Spring Boot Starter ⏱ 2–3 ore

File critici — errati e l'auto-discovery Spring Boot non parte:

```bash
# META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
sed -i 's/dev\.jentic/dev.agenor/g; s/JenticAutoConfiguration/AgenorAutoConfiguration/g' \
  agenor-spring-boot-starter/src/main/resources/META-INF/spring/*.imports

# additional-spring-configuration-metadata.json
sed -i 's/jentic\./agenor./g' \
  agenor-spring-boot-starter/src/main/resources/META-INF/additional-spring-configuration-metadata.json
```

Test di integrazione obbligatorio: avviare un'app Spring Boot minimale che dichiara lo starter e verificare che `AgenorAutoConfiguration` venga caricata correttamente (nessun `BeanDefinitionException` a startup).

---

### Fase 3 — Documentazione e configurazione ⏱ 1 giorno

#### 3a. File Markdown — prima passata automatica (minuti)

```bash
find . -name "*.md" -exec sed -i \
  's/Jentic/Agenor/g; s/jentic/agenor/g; s/JENTIC/AGENOR/g' {} \;
```

**Review manuale obbligatoria** sui file che contengono ragionamenti contestuali (3–4 ore):
- I 25 ADR in `docs/adr/` (ADR-001–024 + ADR-025) — verificare che i nomi sostituiti non spezzino il filo logico
- `CHANGELOG.md` — preservare la storia ma aggiornare i nomi; aggiungere entry `0.24.0`
- `README.md` principale — vetrina pubblica, nuove coordinate Maven `dev.agenor`
- `CONTRIBUTING.md` — istruzioni per contributor

#### 3b. File YAML/JSON (minuti)

```bash
find . \( -name "*.yml" -o -name "*.yaml" -o -name "*.json" \) \
  -exec sed -i 's/jentic\./agenor./g; s/jentic-/agenor-/g; s/Jentic/Agenor/g' {} \;
```

File impattati: `mkdocs.yml`, `*-test.yml`, `mixed-env.yml`, `docker-compose.yml`, `additional-spring-configuration-metadata.json`.

#### 3c. CI/CD GitHub Actions (minuti)

```bash
sed -i 's/jentic/agenor/g; s/Jentic/Agenor/g' .github/workflows/*.yml
```

Verificare manualmente: path JaCoCo, nomi moduli, URL sito documentazione.

---

### Fase 4 — Branding ⏱ 0.5 giorni (testo) + variabile esterna (design)

**Web console** (`agenor-tools/webapp`): i 4 file statici non hanno riferimenti a "jentic" nel codice; verificare solo i titoli HTML visibili nell'UI e aggiornarli se necessario.

**Asset grafici** — lavoro del designer, dipendenza esterna, può procedere in parallelo a tutte le fasi:
- `docs/assets/jentic-icon.svg` → ridisegnare con nuovo nome/logo
- `docs/assets/jentic-wordmark.svg` → ridisegnare

---

### Fase 5 — Test, build e release ⏱ 1 giorno

```bash
# Build completo
mvn clean install -q

# Verifica con coverage
mvn clean verify
```

Dopo il rename automatico massiccio ci si aspettano 50–150 errori di compilazione residui (stringhe nei test, SPI file dimenticati, riferimenti nei log). Procedura iterativa:

```bash
mvn clean compile 2>&1 | grep "ERROR" | head -30
# fix → ripeti fino a compilazione pulita
```

Checklist finale:
- [ ] `mvn clean verify` verde su tutti i moduli
- [ ] JaCoCo ≥ 80% per modulo
- [ ] Esempi eseguibili: `mvn exec:java -pl agenor-examples -Dexec.mainClass="dev.agenor.examples.PingPongExample"`
- [ ] `mkdocs build` senza warning
- [ ] Aggiornare `pom.xml` versione a `0.24.0`
- [ ] `git tag v0.24.0`
- [ ] Release su Maven Central con groupId `dev.agenor`

---

### Fase 6 — GitHub e comunicazione ⏱ 0.5 giorni

#### GitHub repository rename

1. **Rinomina il repository** su GitHub: `Settings → General → Repository name` → `agenor` (o nome scelto).
   GitHub crea redirect automatici sui clone URL esistenti — i fork/clone pre-esistenti continuano a funzionare.

2. **Aggiornare il remote locale** dopo il rename:
   ```bash
   git remote set-url origin https://github.com/<org>/agenor.git
   git remote -v   # verifica
   ```

3. **Aggiornare i link** nel repository che puntano all'URL GitHub (README badge, CONTRIBUTING, docs):
   ```bash
   grep -r "github.com.*jentic" --include="*.md" --include="*.yml" -l
   # poi sed o fix manuale
   ```

4. **GitHub Releases**: creare release `v0.24.0` con release notes che includono:
   - Motivazione del rebranding
   - Tabella di migrazione property Spring Boot: `jentic.* → agenor.*`
   - Tabella coordinate Maven: `dev.jentic:jentic-* → dev.agenor:agenor-*`
   - Tabella annotazioni rinominate (D1)

5. **Aggiornare Topics/About** del repository su GitHub con il nuovo nome.

#### Comunicazione editoriale

- [ ] Newsletter Substack: annuncio rebranding, motivazione, guida migrazione per consumer
- [ ] GitHub README: badge versione aggiornato, nuove coordinate Maven `dev.agenor:agenor-*:0.24.0`
- [ ] Aggiornare eventuali link in profili social/bio

---

## Riepilogo effort

Il rebranding avviene **dopo il completamento degli Items 3-5** (jentic 0.21→0.23),
quindi opera su una codebase che include il modulo `jentic-adapters-persistence` e
i relativi ADR 021-024. Il delta rispetto a un rebrand anticipato è trascurabile:
un modulo in più nel loop `git mv` e 3-4 ADR extra nella review manuale (+2–3 ore).

| Fase | Attività | Piano originale | Con automazione |
|---|---|---|---|
| 0 | Setup + decisioni | 1–2 gg | **0.5 gg** |
| 1 | Java + Maven rename (8 moduli) | 3–4 gg | **0.5–1 gg** |
| 2 | Spring Boot starter | 1 gg | **0.25 gg** |
| 3 | Documentazione + config (24 ADR) | 2–3 gg | **1–1.5 gg** |
| 4 | Branding (parallela) | 1–2 gg | **0.5 gg** + design esterno |
| 5 | Test, build, release (0.24.0) | 2–3 gg | **1 gg** |
| 6 | GitHub + comunicazione | 1 gg | **0.5 gg** |
| **Totale** | | **11–16 gg** | **4.25–5.25 gg** |

Il risparmio principale (7–10 giorni) viene da tre operazioni: IntelliJ package rename,
il loop `find + sed + mv` su classi e annotazioni, e lo stesso loop sulla documentazione.
Il residuo irriducibile è la review manuale degli ADR (ora 24, ~4–5 ore) e la gestione
degli errori di compilazione post-rename (1–2 ore).

---

## Rischi residui

| Rischio | Probabilità | Impatto | Mitigazione |
|---|---|---|---|
| Annotazioni rinominate rompono codice utente | Bassa | Medio | Taglio netto dichiarato esplicitamente nelle release notes `v0.24.0` con tabella di migrazione |
| Property Spring Boot `jentic.*` hardcodate in app utente | Media | Medio | Release notes dettagliate con tabella `jentic.* → agenor.*` |
| Errori di compilazione residui dopo rename automatico | Alta | Basso | Attesi e gestiti iterativamente in Fase 5 (1–2 ore) |
| Git history persa per directory rinominate | Bassa | Basso | Usare `git mv` (non delete+create) |
| Conflitti con nomi generici (`@Agent`, `@Behavior`) | Bassa | Basso | Java risolve per fully-qualified name; `@AgenorMessageHandler` gestisce il caso più a rischio |
| GitHub redirect temporaneamente rotto | Bassa | Basso | GitHub crea redirect automatici; aggiornare subito il remote locale dopo il rename |

---

## Sequenza di esecuzione raccomandata

```
Setup branch rename/jentic-to-agenor
  └─> git mv moduli fisici (×8)
        └─> POM rename (groupId, artifactId, modules)  [sed]
              └─> IDE: package rename dev.jentic → dev.agenor
                    └─> shell: class + annotation rename  [find + sed + mv]
                          └─> Spring Boot starter  [sed + test integrazione]
                                └─> sed docs prima passata  [find + sed]
                                |     └─> review manuale ADR + CHANGELOG + README
                                |           └─> CI/CD update  [sed]
                                └─> branding  [parallela, designer]
                                      └─> mvn clean verify  [automatizzato]
                                            └─> bump versione 0.24.0 + tag
                                                  └─> GitHub rename + remote update
                                                        └─> Maven Central release (0.24.0)
                                                              └─> comunicazione
```

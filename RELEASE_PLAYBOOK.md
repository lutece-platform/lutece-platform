# Notes de Release — Plateforme Lutece

## Table des matieres

1. [Perimetre et architecture](#1-perimetre-et-architecture)
2. [Principe fondamental des releases](#2-principe-fondamental-des-releases)
3. [Types de release : stable, beta, RC](#3-types-de-release--stable-beta-rc)
4. [Lignes Lutece V7 et V8](#4-lignes-lutece-v7-et-v8)
5. [Convention de tags](#5-convention-de-tags)
6. [Pipeline Jenkins — Vue d'ensemble](#6-pipeline-jenkins--vue-densemble)
7. [Parametres de la pipeline](#7-parametres-de-la-pipeline)
8. [Scenarios d'utilisation](#8-scenarios-dutilisation)
9. [Deroulement detaille des stages](#9-deroulement-detaille-des-stages)
10. [Processus de release d'un module](#10-processus-de-release-dun-module)
11. [Gestion des erreurs et rollback](#11-gestion-des-erreurs-et-rollback)
12. [Pre-requis Jenkins](#12-pre-requis-jenkins)
13. [Notifications](#13-notifications)
14. [FAQ et depannage](#14-faq-et-depannage)

---

## 1. Perimetre et architecture

### Perimetre de la pipeline

> **La pipeline release uniquement le monorepo `lutece-platform/lutece`** :
> les 3 starters specialises, `lutece-starter` et `lutece-bom`.

**Les plugins Lutece ne sont pas releases par cette pipeline.** Leurs versions
sont maintenues a la main dans les properties du `pom.xml` racine, et doivent
deja etre des versions release au moment de lancer la pipeline. Un garde-fou
(stage `Validate Release Readiness`) marque le build `UNSTABLE` si une
property `lutece.*.version` est encore en `-SNAPSHOT`.

Le workflow complet est donc :

```
 1. Releaser les plugins concernes (hors pipeline)
 2. Mettre a jour leurs versions dans le pom.xml racine, commit + push
 3. Lancer la pipeline sur le monorepo
```

### Structure du monorepo

```
lutece/
├── pom.xml                      # POM Parent — 90+ versions de plugins en properties
├── lutece-bom/pom.xml           # BOM (Bill of Materials)
├── forms-starter/pom.xml        # Starter Formulaires
├── appointment-starter/pom.xml  # Starter Rendez-vous
├── editorial-starter/pom.xml    # Starter Editorial
└── lutece-starter/pom.xml       # Starter Complet (depend des 3 autres)
```

### Hierarchie des dependances

```
Plugins Lutece (90+ composants, versions figees dans le pom.xml racine)
      │
      ├──► forms-starter         (formulaires, workflow, GRU, address...)
      ├──► appointment-starter   (rendez-vous)
      ├──► editorial-starter     (HTML, blog, contact, menus...)
      │
      └──► lutece-starter
              ├── depend de forms-starter
              ├── depend de appointment-starter
              ├── depend de editorial-starter
              └── plugins supplementaires (auth, ELK, solr, captcha...)
                      │
                      └──► lutece-bom  (dernier, reflete l'etat final)
```

### Gestion des versions — Decouplage par module

Chaque module a sa propre property de version, ce qui permet des **releases
individuelles** :

```xml
<properties>
    <!-- Chaque module a sa propre version (independante des autres) -->
    <lutece.forms-starter.version>8.0.0-SNAPSHOT</lutece.forms-starter.version>
    <lutece.appointment-starter.version>8.0.0-SNAPSHOT</lutece.appointment-starter.version>
    <lutece.editorial-starter.version>8.0.0-SNAPSHOT</lutece.editorial-starter.version>
    <lutece.lutece-starter.version>8.0.0-SNAPSHOT</lutece.lutece-starter.version>
    <lutece.lutece-bom.version>8.0.0-SNAPSHOT</lutece.lutece-bom.version>

    <!-- 90+ versions de plugins — maintenues a la main -->
    <lutece.core.version>8.0.2</lutece.core.version>
    <lutece.plugin-forms.version>4.0.2</lutece.plugin-forms.version>
    <!-- ... -->
</properties>
```

Chaque module enfant declare sa version via la property parent :

```xml
<!-- forms-starter/pom.xml -->
<artifactId>forms-starter</artifactId>
<version>${lutece.forms-starter.version}</version>
```

La property `lutece.forms-starter.version` sert a la fois pour :
- La version propre du module
- La dependance dans `lutece-starter`
- La version managee dans `lutece-bom`

Le **flatten-maven-plugin** (configure en `flattenMode=clean`) resout la
property en valeur concrete dans le POM deploye sur Nexus.

**Release individuelle :** un seul `sed` sur `<lutece.forms-starter.version>`
suffit a mettre a jour la version partout. Les autres modules restent inchanges.

**Release `all` :** la pipeline construit une **carte de versions par module**
(chaque module peut avoir une version differente). Chaque property est mise a
jour individuellement avec sa propre version courante / release / next. Les
parent versions dans les POMs enfants sont egalement mises a jour.

---

## 2. Principe fondamental des releases

> **Regle absolue : les dependances doivent etre releasees AVANT leurs
> consommateurs.**

L'ordre est **strict et non-negociable** :

```
 1. Plugins Lutece individuels                    ◄── hors pipeline
          ↓
 2. Mise a jour du pom.xml racine (versions release)   ◄── hors pipeline
          ↓
 3. Starters specialises (forms / appointment / editorial) — en parallele
          ↓
 4. lutece-starter (depend des 3 starters)
          ↓
 5. lutece-bom (reflete l'etat final)
```

**Violations interdites :**

- Ne jamais releaser un starter si un de ses plugins est encore en SNAPSHOT
- Ne jamais releaser `lutece-starter` si les 3 starters specialises sont en SNAPSHOT
- Ne jamais releaser `lutece-bom` avant `lutece-starter`

Les etapes 3 a 5 sont automatisees par la pipeline et respectent cet ordre par
construction (stages sequentiels).

---

## 3. Types de release : stable, beta, RC

Le type est choisi par le parametre `PRERELEASE_TYPE`.

| `PRERELEASE_TYPE` | Version produite | Merge sur master | Apres deploy |
|-------------------|------------------|------------------|--------------|
| `none` (defaut)   | `8.0.0`          | Oui (cible `all`) | Version → `8.0.1-SNAPSHOT` (patch+1) |
| `beta`            | `8.0.0-beta-01`  | **Non**          | Version SNAPSHOT d'origine restauree |
| `rc`              | `8.0.0-RC-01`    | **Non**          | Version SNAPSHOT d'origine restauree |

Le numero vient de `PRERELEASE_NUMBER` et est complete sur 2 chiffres :

```
PRERELEASE_TYPE = beta, PRERELEASE_NUMBER = 1   →  8.0.0-beta-01
PRERELEASE_TYPE = beta, PRERELEASE_NUMBER = 2   →  8.0.0-beta-02
PRERELEASE_TYPE = rc,   PRERELEASE_NUMBER = 1   →  8.0.0-RC-01
```

**Cycle de vie typique :**

```
 develop
    │
    ├── beta-01 : 8.0.0-SNAPSHOT → 8.0.0-beta-01 → deploy → 8.0.0-SNAPSHOT
    │             (retours des integrateurs...)
    ├── beta-02 : 8.0.0-SNAPSHOT → 8.0.0-beta-02 → deploy → 8.0.0-SNAPSHOT
    │             (stabilisation...)
    ├── RC-01   : 8.0.0-SNAPSHOT → 8.0.0-RC-01   → deploy → 8.0.0-SNAPSHOT
    │             (validation OK)
    └── stable  : 8.0.0-SNAPSHOT → 8.0.0 → merge master → deploy → 8.0.1-SNAPSHOT
```

Beta et RC sont mecaniquement identiques ; la distinction est semantique
(beta = fonctionnalites en cours de stabilisation, RC = candidate a la
publication). Les deux peuvent s'enchainer librement, dans n'importe quel
ordre, autant de fois que necessaire sur la meme version de base.

---

## 4. Lignes Lutece V7 et V8

Le monorepo porte **deux lignes en parallele**, sur deux branches :

| Branche         | Parent `lutece-global-pom` | `targetJdk` | JDK utilise |
|-----------------|----------------------------|-------------|-------------|
| `develop`       | `8.0.1-SNAPSHOT`           | `17`        | JDK 17      |
| `develop_core7` | `7.0.8-SNAPSHOT`           | `11`        | JDK 11      |

La pipeline gere les deux avec le meme code : la structure des deux branches
est identique (memes 5 modules, memes properties de version).

### Detection de la ligne

La ligne vient **toujours des sources checkoutees** : la pipeline lit la
version du parent `lutece-global-pom` declaree dans le `pom.xml` du workspace
et en prend le major (`7.0.8-SNAPSHOT` → `7`, `8.0.1-SNAPSHOT` → `8`).

`LUTECE_MAJOR` n'est pas une surcharge mais une **assertion** : si la valeur
renseignee ne correspond pas au `pom.xml` checkoute, le build echoue.

```
LUTECE_MAJOR=7 but the checked-out pom.xml inherits lutece-global-pom 8.x, i.e. Lutece 8.
```

C'est volontaire : forcer la ligne ne change pas ce qui a ete checkoute. Seul
le SCM du job decide des sources. Renseigner `LUTECE_MAJOR` sert donc a
declarer son intention et a se faire arreter en cas d'erreur de branche, pas a
basculer de ligne.

### Detection du JDK

Le JDK vient de la property `targetJdk`, declaree par `lutece-global-pom` :

```xml
<!-- lutece-global-pom 7.0.x -->
<targetJdk>11</targetJdk>

<!-- lutece-global-pom 8.0.0 -->
<targetJdk>17</targetJdk>

<!-- lutece-global-pom 8.0.1+ -->
<java.version>17</java.version>
<targetJdk>${java.version}</targetJdk>
```

La resolution est deleguee a Maven, ce qui gere l'heritage **et**
l'indirection `${java.version}` sans la reimplementer :

```bash
mvn -N -q help:evaluate -Dexpression=targetJdk -DforceStdout
```

Le numero obtenu est ensuite traduit en nom d'outil JDK Jenkins :

```
targetJdk 11  →  temurin-11-jdk
targetJdk 17  →  temurin-17-jdk
targetJdk 21  →  temurin-21-jdk
```

Cette convention est surchargeable sans toucher au code Groovy, via le
parametre `JDK_TOOL_MAP` :

```
JDK_TOOL_MAP = 11=corretto-11,17=mon-jdk17
```

Le JDK est injecte a l'execution (`JAVA_HOME` + `PATH`) autour des commandes
Maven de build et de deploy. Le bloc `tools` du Jenkinsfile ne declare qu'un
**JDK d'amorcage** (`temurin-17-jdk`), utilise pour les etapes qui ne
compilent rien (calcul de versions, git, `help:evaluate`).

> **Si l'outil JDK n'existe pas dans Jenkins**, la pipeline emet un
> avertissement explicite et retombe sur le JDK d'amorcage plutot que
> d'echouer. Verifier les logs lors du premier build d'une ligne.

### Resolution de la branche de release

La branche de release est **celle que le workspace contient reellement**.
Elle n'est jamais deduite de la ligne Lutece : une deduction permettrait de
pousser un checkout V8 sur la branche V7 des que la branche n'est pas
detectable.

Jenkins checkoute un SHA detache (`git checkout -f <sha>`), donc
`git rev-parse --abbrev-ref HEAD` renvoie `HEAD` et ne suffit pas. La
detection enchaine donc :

1. `BRANCH_NAME` — job Multibranch Pipeline
2. `GIT_BRANCH` — plugin Git sur un job Pipeline simple (`origin/develop` → `develop`)
3. `git symbolic-ref --short HEAD` — checkout attache
4. `git for-each-ref --points-at HEAD refs/remotes/origin` — branches distantes
   pointant sur le commit checkoute

Si aucune de ces sources ne donne de reponse univoque, **le build echoue** au
lieu de deviner un nom de branche.

`MONOREPO_BRANCH` est pris en compte, mais uniquement s'il **concorde avec le
checkout** :

```
MONOREPO_BRANCH=develop_core7 but the workspace holds the branch develop.
```

La pipeline release ce qui a ete checkoute ; elle ne change pas de branche.
Pour releaser une autre ligne, il faut pointer le SCM du job dessus.

---

## 5. Convention de tags

Le tag depend de la cible de la release :

| `RELEASE_TARGET` | Tag cree | Exemple |
|------------------|----------|---------|
| `all`            | `v{version}` | `v8.0.0-beta-01`, `v8.0.0`, `v7.2.0-beta-02` |
| un starter / le BOM | `{module}-{version}` | `forms-starter-8.0.0-beta-01`, `lutece-bom-8.0.0` |

En cible `all`, un **seul** tag plateforme est cree : les 5 modules sont
releases depuis le meme commit, des tags par module y pointeraient tous.

**Garde-fou :** si les 5 versions de modules ne sont pas toutes identiques a
la version plateforme (versions heterogenes), la pipeline retombe
automatiquement sur des tags par module, afin que chaque artefact publie sur
Nexus reste tracable a un tag :

```
Versions alignees (cas courant)
  all  →  v8.0.0-beta-01

Versions heterogenes
  all  →  forms-starter-8.0.1-beta-01
          appointment-starter-8.0.0-beta-01
          editorial-starter-8.0.0-beta-01
          lutece-starter-8.0.2-beta-01
          lutece-bom-8.0.0-beta-01
```

Les tags de pre-release ne sont jamais supprimes par la release stable :
`v8.0.0-beta-01`, `v8.0.0-RC-01` et `v8.0.0` coexistent.

Le tag est cree **une seule fois**, avant tout deploy, par le stage
`Tag Release`. Le push n'est pas force : si le tag existe deja sur le remote,
le build echoue plutot que d'ecraser une release publiee.

---

## 6. Pipeline Jenkins — Vue d'ensemble

Le fichier `Jenkinsfile-release` a la racine definit une **pipeline
declarative** de **10 stages**. La logique des stages vit dans
`release-helpers.groovy`, charge via `load()` pour rester sous la limite CPS
de 64 Ko de bytecode par methode.

```
Stage 0  ► Initialize
             Ligne Lutece (V7/V8), branche, JDK, calcul des versions

Stage 1  ► Update POM Parent Versions
             Passe les properties de version en version release

Stage 2  ► Validate Release Readiness
             Refuse de releaser si un plugin est encore en SNAPSHOT

Stage 3  ► Tag Release
             Cree et pousse le tag (v{version} ou {module}-{version})

Stage 4  ► Release Specialized Starters
             forms, appointment, editorial — en parallele

Stage 5  ► Release lutece-starter
             Apres les 3 starters specialises

Stage 6  ► Release lutece-bom
             Dernier composant

Stage 7  ► Promote to master
             Merge de la branche de release — release stable `all` uniquement

Stage 8  ► Prepare Next SNAPSHOT
             Version suivante (stable) ou restauration (beta / RC)

Stage 9  ► Release Report
             Resume archive comme artifact Jenkins
```

**Points de conception :**

- `DRY_RUN = true` par defaut, pour empecher les releases accidentelles
- Le **tag** et le **merge sur master** sont des stages a part, executes une
  seule fois. Ils etaient auparavant realises dans la fonction de release des
  starters, donc simultanement par les 3 branches `parallel` sur la meme copie
  de travail — `git tag` et `git checkout master` concurrents sur un meme
  workspace le corrompent.
- Les mises a jour du POM utilisent `sed` (bien plus rapide que
  `mvn versions:set-property` sur 90+ properties)
- Pattern **prepare / perform / promote** : master n'est merge qu'apres le
  succes du deploy Nexus — rollback simple, sans force push
- Le JDK de build est choisi par ligne Lutece, pas code en dur

---

## 7. Parametres de la pipeline

| Parametre | Type | Defaut | Description |
|-----------|------|--------|-------------|
| `RELEASE_TARGET` | choice | `forms-starter` | Cible de la release (voir tableau ci-dessous) |
| `RELEASE_VERSION` | string | *(vide)* | Version release (ex: `8.0.0`). Vide = calculee depuis la version SNAPSHOT courante. |
| `NEXT_SNAPSHOT_VERSION` | string | *(vide)* | Prochaine SNAPSHOT (ex: `8.0.1-SNAPSHOT`). Vide = patch+1. |
| `DRY_RUN` | boolean | `true` | Mode simulation : aucun push, deploy ni merge. |
| `PRERELEASE_TYPE` | choice | `none` | `none` / `beta` / `rc` — voir [section 3](#3-types-de-release--stable-beta-rc). |
| `PRERELEASE_NUMBER` | string | `1` | Numero de pre-release, complete sur 2 chiffres (`1` → `beta-01`). Ignore si `PRERELEASE_TYPE = none`. |
| `LUTECE_MAJOR` | choice | `auto` | `auto` / `8` / `7` — **assertion** sur la ligne Lutece, pas une surcharge. La ligne vient du `pom.xml` checkoute ; une valeur divergente fait echouer le build. |
| `MONOREPO_BRANCH` | string | *(vide)* | Branche de release. Vide = deduite du checkout. Une valeur en desaccord avec le checkout fait echouer le build. |
| `JDK_TOOL_MAP` | string | *(vide)* | Correspondance `targetJdk` → outil JDK Jenkins, ex: `11=temurin-11-jdk,17=temurin-17-jdk`. Vide = convention `temurin-{version}-jdk`. |
| `GITHUB_CREDENTIAL_ID` | string | `github-token` | ID du credential Jenkins contenant le token GitHub (type : Secret text). |
| `MAVEN_SETTINGS_ID` | string | `maven_settings_default` | ID du Config File Provider pour le `settings.xml` Maven. |
| `GIT_USER_NAME` | string | `ryahiaoui` | Nom utilisateur des commits de release. |
| `GIT_USER_EMAIL` | string | `rafik.yahiaoui@paris.fr` | Email des commits de release. |

### Valeurs de RELEASE_TARGET

| Valeur | Composants releases | Tag |
|--------|--------------------|-----|
| `forms-starter` | `forms-starter` seul | `forms-starter-{version}` |
| `appointment-starter` | `appointment-starter` seul | `appointment-starter-{version}` |
| `editorial-starter` | `editorial-starter` seul | `editorial-starter-{version}` |
| `lutece-starter` | `lutece-starter` seul (suppose les 3 starters deja releases) | `lutece-starter-{version}` |
| `lutece-bom` | le BOM seul (suppose tout le reste deja release) | `lutece-bom-{version}` |
| `all` | 3 starters + `lutece-starter` + BOM | `v{version}` |

### Calcul automatique des versions

Si `RELEASE_VERSION` est vide, la pipeline lit la version courante et supprime
le suffixe (`-SNAPSHOT`, mais aussi un eventuel `-beta-NN` / `-RC-NN`, ce qui
rend une `beta-02` relancable juste apres une `beta-01`) :

- **Release individuelle** : lit `<lutece.{target}.version>`
- **Release `all`** : lit la version de `lutece-parent` pour le tag et
  l'affichage, puis construit une carte de versions par module

```
8.0.0-SNAPSHOT   →  version de base 8.0.0
8.0.0-beta-01    →  version de base 8.0.0
```

Si `NEXT_SNAPSHOT_VERSION` est vide, le patch est incremente :

```
8.0.0  →  8.0.1-SNAPSHOT
```

En beta / RC, la prochaine version est la version SNAPSHOT **d'origine**
(pas d'increment) : le developpement continue sur la meme version de base.

> **Attention en cible `all` :** `RELEASE_VERSION` ne pilote que la version de
> `lutece-parent`. Chaque module est publie sous sa propre
> `<lutece.{module}.version>`. Si la valeur saisie ne correspond a aucune
> version de module, le build passe `UNSTABLE` et les tags retombent par
> module — la version annoncee ne designerait aucun artefact publie. Pour
> releaser toute la plateforme sous une nouvelle version, bumper d'abord les 5
> properties de modules et `lutece-parent` dans le `pom.xml`, puis laisser
> `RELEASE_VERSION` vide.

---

## 8. Scenarios d'utilisation

### 8.1 — Premiere utilisation : simulation (DRY_RUN)

**Toujours commencer par un dry-run.**

```
RELEASE_TARGET = all
DRY_RUN        = true    ← valeur par defaut
```

Verifier dans les logs du Stage 0 :

```
 Lutece line : V8
 Branch      : develop
 targetJdk   : 17 (Jenkins tool: temurin-17-jdk)
 Build type  : Stable
```

Puis, dans les stages suivants, que les versions et le tag calcules sont
corrects, et qu'aucun plugin n'est signale en SNAPSHOT.

### 8.2 — Beta de la plateforme complete

```
RELEASE_TARGET    = all
PRERELEASE_TYPE   = beta
PRERELEASE_NUMBER = 1
DRY_RUN           = false
```

Deroulement :
1. Versions des 5 modules → `8.0.0-beta-01`
2. Tag `v8.0.0-beta-01`
3. Deploy des 5 modules sur Nexus depuis `develop`
4. **Pas** de merge sur `master`
5. Retour a `8.0.0-SNAPSHOT` sur `develop`

### 8.3 — Beta suivante apres corrections

```
RELEASE_TARGET    = all
PRERELEASE_TYPE   = beta
PRERELEASE_NUMBER = 2
DRY_RUN           = false
```

Meme processus, version `8.0.0-beta-02`, tag `v8.0.0-beta-02`. Le tag
`v8.0.0-beta-01` reste en place.

### 8.4 — Beta ciblee sur un seul starter

```
RELEASE_TARGET    = forms-starter
PRERELEASE_TYPE   = beta
PRERELEASE_NUMBER = 1
DRY_RUN           = false
```

Seul `forms-starter` est release, tag `forms-starter-8.0.0-beta-01`. Les
autres modules ne sont pas touches.

### 8.5 — Release Candidate

```
RELEASE_TARGET    = all
PRERELEASE_TYPE   = rc
PRERELEASE_NUMBER = 1
DRY_RUN           = false
```

Version `8.0.0-RC-01`, tag `v8.0.0-RC-01`. Mecanique identique a la beta.

### 8.6 — Release stable apres validation

```
RELEASE_TARGET  = all
PRERELEASE_TYPE = none
DRY_RUN         = false
```

Cette fois le processus complet s'execute : tag `v8.0.0`, deploy, merge sur
`master`, puis passage en `8.0.1-SNAPSHOT`.

### 8.7 — Release individuelle d'un starter

```
RELEASE_TARGET = forms-starter
DRY_RUN        = false
```

Deroulement :
1. Lit `<lutece.forms-starter.version>` → `8.0.0-SNAPSHOT`
2. Met a jour **uniquement** cette property → `8.0.0`
3. Verifie qu'aucun plugin n'est en SNAPSHOT
4. Tag `forms-starter-8.0.0`
5. Deploy du module (**pas** de merge master)
6. Restaure `<lutece.forms-starter.version>` → `8.0.1-SNAPSHOT`

### 8.8 — Beta de la ligne V7

```
MONOREPO_BRANCH   = develop_core7   (ou lancer le job sur cette branche)
RELEASE_TARGET    = all
PRERELEASE_TYPE   = beta
PRERELEASE_NUMBER = 1
DRY_RUN           = false
```

La pipeline detecte `lutece-global-pom 7.0.8-SNAPSHOT`, donc la ligne V7,
`targetJdk 11` et l'outil `temurin-11-jdk`. Version `7.2.0-beta-01`, tag
`v7.2.0-beta-01`.

### 8.9 — Forcer une version majeure ou mineure

Le calcul automatique n'incremente que le patch. Pour un changement de mineur
ou de majeur :

```
RELEASE_VERSION       = 8.1.0
NEXT_SNAPSHOT_VERSION = 8.2.0-SNAPSHOT
DRY_RUN               = false
```

---

## 9. Deroulement detaille des stages

### Stage 0 — Initialize

**Actions :**

1. Resout le type de release (`PRERELEASE_TYPE`) et le numero, complete sur
   2 chiffres
2. Provisionne le `settings.xml` Maven (Config File Provider)
3. Configure l'identite git des commits
4. Detecte la **ligne Lutece** depuis le parent `lutece-global-pom` du
   workspace, et echoue si `LUTECE_MAJOR` la contredit
5. Resout la **branche de release** depuis le checkout reel, et echoue si
   `MONOREPO_BRANCH` la contredit ou si elle est indeterminable, puis
   `git checkout -B {branche}`
6. Resout le **targetJdk** via `mvn help:evaluate` et le nom de l'outil JDK
7. Lit la version courante :
   - cible individuelle : `<lutece.{target}.version>`
   - cible `all` : version de `lutece-parent` + carte de versions par module
     (`MODULE_VERSIONS_JSON`), chaque entree contenant version courante,
     version release et prochaine SNAPSHOT
8. Calcule la version release et la prochaine SNAPSHOT
9. Resout la liste des modules a releaser
10. Initialise le fichier de rapport

**Variables calculees selon le type de build :**

| Variable | Stable | Beta / RC |
|----------|--------|-----------|
| `COMPUTED_RELEASE_VERSION` | `8.0.0` | `8.0.0-beta-01` |
| `COMPUTED_NEXT_SNAPSHOT` | `8.0.1-SNAPSHOT` | `8.0.0-SNAPSHOT` (identique a l'origine) |
| `ORIGINAL_SNAPSHOT_VERSION` | `8.0.0-SNAPSHOT` | `8.0.0-SNAPSHOT` (sauvegardee pour restauration) |
| `BASE_RELEASE_VERSION` | `8.0.0` | `8.0.0` (version sans qualifieur) |

**Resolution en cascade pour `RELEASE_TARGET` :**

| Cible | Modules resolus |
|-------|-----------------|
| `forms-starter` | `forms-starter` |
| `appointment-starter` | `appointment-starter` |
| `editorial-starter` | `editorial-starter` |
| `lutece-starter` | `lutece-starter` |
| `lutece-bom` | `lutece-bom` |
| `all` | `forms-starter, appointment-starter, editorial-starter, lutece-starter, lutece-bom` |

### Stage 1 — Update POM Parent Versions

**Si `RELEASE_TARGET` est un module specifique :**
- `sed` uniquement sur `<lutece.{target}.version>` dans le POM racine
- Ne touche ni les autres properties de modules, ni les parent versions des enfants
- Commit et push sur la branche de release

**Si `RELEASE_TARGET = all` :**
- Met a jour la `<version>` du projet `lutece-parent`
- Met a jour chaque property de module avec sa propre version release (la
  version courante de chaque module sert de pattern de recherche `sed`, ce qui
  gere les versions heterogenes)
- Met a jour la `<version>` du parent dans chaque module enfant
- Commit et push sur la branche de release

Les properties de versions de plugins ne sont **jamais** modifiees par la
pipeline : elles sont maintenues a la main.

### Stage 2 — Validate Release Readiness

**Actions :**
- Relit le `pom.xml` et cherche toute property `lutece.*.version` encore en
  `-SNAPSHOT`
- Ignore les 5 properties structurelles des modules
- Si des violations subsistent : build `UNSTABLE`, violations listees dans le
  rapport avec la marche a suivre

C'est le **gate de securite** de la pipeline : releaser un starter qui depend
d'un plugin SNAPSHOT produit un artefact non reproductible. Les versions de
plugins etant maintenues a la main, une violation est un prerequis manquant,
pas quelque chose que la pipeline peut corriger.

### Stage 3 — Tag Release

**Actions :**
- Calcule le ou les tags (voir [section 5](#5-convention-de-tags))
- Supprime le tag local s'il existe (idempotence sur les re-runs :
  `git fetch --tags` ramene les tags distants)
- Cree le tag annote et le pousse

Le tag est cree **avant tout deploy** : si un deploy echoue ensuite, le
rollback se limite a supprimer le tag. Le push n'est pas force, pour qu'un tag
distant existant fasse echouer le build au lieu d'ecraser une release publiee.

### Stage 4 — Release Specialized Starters

Release en parallele des starters cibles parmi `forms-starter`,
`appointment-starter` et `editorial-starter`. Voir
[section 10](#10-processus-de-release-dun-module).

### Stage 5 — Release lutece-starter

Release de `lutece-starter`, apres les 3 starters specialises. Ne s'execute
que si `lutece-starter` est dans la liste des cibles.

### Stage 6 — Release lutece-bom

Release du BOM, dernier composant du cycle.

### Stage 7 — Promote to master

**Ne s'execute que pour une release stable en cible `all`.**

```bash
git checkout master
git merge {branche-de-release} -m "Merge {branche} for release v8.0.0"
git push origin master
git checkout {branche-de-release}
```

Trois raisons de ne pas merger :
- **Beta / RC** : une pre-release ne touche jamais `master`
- **Release individuelle** : les autres modules sont encore en SNAPSHOT, les
  pousser sur la branche de production est interdit
- **`DRY_RUN`** : simulation

### Stage 8 — Prepare Next SNAPSHOT

**Release stable :**
- cible individuelle : `sed` sur `<lutece.{target}.version>` → prochaine SNAPSHOT
- cible `all` : version du parent + chaque property de module → sa prochaine
  SNAPSHOT (la version release de chaque module sert de pattern `sed`), et
  parent versions des enfants

**Beta / RC :**
- Restaure la version SNAPSHOT d'origine (parent, properties de modules,
  parent versions des enfants). Pas d'increment : le developpement continue
  sur la meme version de base.

Commit et push sur la branche de release dans les deux cas.

### Stage 9 — Release Report

- Finalise le rapport textuel
- L'affiche dans les logs Jenkins
- Archive `release-report.txt` comme artifact Jenkins

---

## 10. Processus de release d'un module

Les modules etant dans le monorepo, leur release ne demande ni clone ni tests
(les starters n'ont pas de tests). Le tag ayant deja ete cree au Stage 3 et le
merge sur master etant fait au Stage 7, la release d'un module se reduit a la
phase **perform** :

```bash
# Install le module et ses dependances reactor (sans deployer sur Nexus)
mvn clean install -pl forms-starter -am -DskipTests -DperformRelease=true
# Deploy uniquement le module cible (sans -am)
mvn deploy -pl forms-starter -DskipTests -DperformRelease=true
```

Les deux commandes tournent avec le JDK de la ligne Lutece detectee
(`JAVA_HOME` + `PATH` injectes autour de l'appel).

Le deploy est separe en deux commandes pour eviter que `-am` ne re-deploie des
modules deja publies sur Nexus, ce qui provoquerait une erreur 400.

> **Si le deploy echoue ici**, le rollback est simple : supprimer le tag.
> Master n'a pas ete touche.

### Resume visuel — release stable `all`

```
                Stage 3        Stages 4-6         Stage 7        Stage 8
develop:  ──► tag ──► push ─────────────────────────────────► next SNAPSHOT ──►
                        │            │                │
nexus:    ────────────────── deploy ─┤                │
                        │            │                │
master:   ─────────────────────────────── merge ──► push
                        │
tags:     ── v8.0.0
```

### Resume visuel — beta `all`

```
                Stage 3        Stages 4-6         Stage 7        Stage 8
develop:  ──► tag ──► push ─────────────────────────────────► SNAPSHOT restaure ──►
                        │            │
nexus:    ────────────────── deploy ─┘
                        │
master:   ──────────────────────── (inchange)
                        │
tags:     ── v8.0.0-beta-01
```

### Resume visuel — release individuelle

```
develop:  ──► tag ──► push ─────────────────────────────► next SNAPSHOT ──►
                        │            │
nexus:    ────────────────── deploy ─┘
                        │
master:   ──────────────────────── (inchange)
                        │
tags:     ── forms-starter-8.0.0
```

---

## 11. Gestion des erreurs et rollback

### Principe : Prepare / Perform / Promote elimine les rollbacks dangereux

`master` n'est **jamais touchee avant le succes du deploy Nexus**. En cas
d'echec, le rollback est donc toujours simple :

```
 Echec au TAG (Stage 3)          →  Rien sur Nexus, rien sur master
                                     Rollback : supprimer le tag

 Echec au DEPLOY (Stages 4-6)    →  Tag pousse, master intacte
                                     Rollback : supprimer le tag (automatique)

 Echec au MERGE (Stage 7)        →  Nexus OK, tag OK, master non mergee
                                     Action : relancer le merge manuellement
```

**Jamais de `git reset --hard` ni de `git push --force`.**

### Rollback automatique

En cas d'echec de la release d'un module, la pipeline supprime le ou les tags
de release (local + remote) et revient sur la branche de release :

```bash
git tag -d v8.0.0-beta-01
git push origin :refs/tags/v8.0.0-beta-01
git checkout develop
```

Le tag etant desormais unique et cree une seule fois, le rollback est
identique quel que soit le module en echec.

### Echec du rollback

Si le rollback lui-meme echoue, le rapport l'indique :

```
ROLLBACK FAILED: forms-starter — manual intervention required: <message>
```

Une intervention manuelle est alors necessaire.

### Comportement du pipeline en cas d'erreur

| Situation | Comportement |
|-----------|--------------|
| `LUTECE_MAJOR` contredit le `pom.xml` checkoute | Build `FAILURE` au Stage 0, avant toute modification. Pointer le SCM du job sur la bonne branche. |
| `MONOREPO_BRANCH` contredit le checkout | Build `FAILURE` au Stage 0. Idem. |
| Branche du workspace indeterminable | Build `FAILURE` au Stage 0. Renseigner `MONOREPO_BRANCH`. |
| `RELEASE_VERSION` ne correspond a aucune version de module (cible `all`) | Build `UNSTABLE`, tags par module. La version annoncee ne designe aucun artefact — corriger avant de publier. |
| Un plugin est encore en SNAPSHOT | Build `UNSTABLE`, violations listees dans le rapport. Le release continue — verifier avant de publier. |
| Le tag existe deja sur le remote | Build `FAILURE` au Stage 3. Supprimer le tag distant, ou incrementer `PRERELEASE_NUMBER`. |
| Un module echoue au deploy | Rollback du tag. Build `FAILURE`. Les modules deja deployes sur Nexus ne sont pas retires. |
| Outil JDK non configure | Avertissement, repli sur le JDK d'amorcage. Le build continue. |

### Reprendre apres un echec

1. Consulter le rapport archive (`release-report.txt`)
2. Corriger la cause (tag distant, credentials Nexus, version de plugin...)
3. Si des artefacts ont deja ete publies sur Nexus, les supprimer depuis
   l'interface Nexus — Nexus refuse le re-deploy d'une version release
4. Relancer la pipeline

---

## 12. Pre-requis Jenkins

### Credentials

| Parametre | ID par defaut | Type | Description |
|-----------|---------------|------|-------------|
| `GITHUB_CREDENTIAL_ID` | `github-token` | Secret text | Token GitHub avec acces en ecriture a `lutece-platform/lutece`. Permission requise : `repo`. |
| `MAVEN_SETTINGS_ID` | `maven_settings_default` | Config File Provider | `settings.xml` Maven contenant les credentials du registry Nexus pour le deploy. |

### Outils

| Outil | Nom Jenkins | Usage |
|-------|-------------|-------|
| Maven | `Maven 3.8.5` | Build et deploy |
| JDK | `temurin-17-jdk` | JDK d'amorcage + build de la ligne V8 |
| JDK | `temurin-11-jdk` | Build de la ligne V7 |

Les noms des JDK suivent la convention `temurin-{version}-jdk`. Si vos outils
portent d'autres noms, les remapper avec `JDK_TOOL_MAP` plutot que de modifier
le Groovy :

```
JDK_TOOL_MAP = 11=corretto-11,17=mon-jdk17
```

> Le premier build d'une ligne signale dans les logs si l'outil attendu est
> absent (`WARNING: Jenkins JDK tool '...' is not configured`). Un `DRY_RUN`
> suffit a le verifier.

### Plugins Jenkins recommandes

- **Pipeline** (workflow-aggregator)
- **Config File Provider Plugin** — pour `configFileProvider()`
- **Credentials Binding Plugin** — pour `withCredentials()`
- **Slack Notification Plugin** — pour les notifications `#lutece-releases`
- **Email Extension Plugin** — pour les notifications email
- **Timestamps Plugin** — pour les logs horodates

### Configuration du job

1. Creer un job de type **Pipeline** (ou **Multibranch Pipeline**)
2. Source : `https://github.com/lutece-platform/lutece`
3. Branche : `develop` pour la ligne V8, `develop_core7` pour la V7
4. Script Path : `Jenkinsfile-release`

Un seul job suffit pour les deux lignes : soit en changeant la branche source,
soit en renseignant `MONOREPO_BRANCH` au lancement.

---

## 13. Notifications

### Slack

| Evenement | Canal | Couleur |
|-----------|-------|---------|
| Succes | `#lutece-releases` | Vert |
| Echec | `#lutece-releases` | Rouge |

Le message contient la version, la cible, le type de build (stable / beta /
RC), les tags crees, la ligne Lutece et le JDK utilise, l'indicateur
`DRY_RUN`, et un lien vers le build.

### Email

| Evenement | Destinataires |
|-----------|---------------|
| Succes | Derniers auteurs de commits + lanceur du build |
| Echec | Idem + contenu du rapport complet |

---

## 14. FAQ et depannage

### Q: J'ai lance le pipeline mais rien ne s'est passe ?

**R:** Verifier que `DRY_RUN` est bien sur `false`. Par defaut la pipeline est
en simulation et n'effectue aucune modification.

### Q: Pourquoi la pipeline ne release-t-elle plus les plugins ?

**R:** C'est un choix de perimetre : la pipeline ne gere que le monorepo. Les
versions de plugins sont maintenues a la main dans le `pom.xml` racine et
doivent deja etre des versions release. Le Stage 2 marque le build `UNSTABLE`
et liste les properties fautives si ce n'est pas le cas.

### Q: Le build est UNSTABLE avec « N plugin dependencies are still SNAPSHOT » ?

**R:** Un ou plusieurs plugins references par le POM racine sont encore en
SNAPSHOT. Marche a suivre :

1. Releaser les plugins concernes (hors pipeline)
2. Mettre a jour leurs properties `<lutece.*.version>` dans le `pom.xml` racine
3. Commit + push sur la branche de release
4. Relancer la pipeline

La liste exacte des properties fautives est dans le rapport archive.

### Q: Comment lancer une beta ?

**R:**

```
RELEASE_TARGET    = all
PRERELEASE_TYPE   = beta
PRERELEASE_NUMBER = 1
DRY_RUN           = false
```

Version `8.0.0-beta-01`, tag `v8.0.0-beta-01`, pas de merge sur master,
retour a `8.0.0-SNAPSHOT` apres le deploy.

### Q: Quelle difference entre une beta et une RC ?

**R:** Aucune difference mecanique : meme absence de merge sur master, meme
restauration de la version SNAPSHOT. La distinction est semantique — beta pour
les versions en cours de stabilisation, RC pour une candidate a la
publication. Le suffixe differe (`-beta-01` contre `-RC-01`), donc les deux
peuvent s'enchainer sur la meme version de base.

### Q: Comment la pipeline sait-elle s'il faut builder en Java 11 ou 17 ?

**R:** Elle resout la property `targetJdk` du POM effectif avec
`mvn help:evaluate`, ce qui suit l'heritage depuis `lutece-global-pom` et
l'indirection `${java.version}`. La ligne V7 donne `11`, la V8 `17`. Le numero
est traduit en nom d'outil Jenkins (`temurin-11-jdk`, `temurin-17-jdk`),
surchargeable via `JDK_TOOL_MAP`.

### Q: L'outil JDK attendu n'existe pas dans mon Jenkins ?

**R:** La pipeline emet
`WARNING: Jenkins JDK tool 'temurin-11-jdk' is not configured` et retombe sur
le JDK d'amorcage. Deux options : declarer l'outil dans
**Manage Jenkins > Tools**, ou le remapper avec `JDK_TOOL_MAP`.

### Q: Comment releaser la ligne V7 ?

**R:** Il faut que le job **checkoute** `develop_core7` : changer la branche
dans la configuration SCM du job (ou utiliser un job Multibranch). La ligne et
le JDK 11 sont ensuite detectes automatiquement depuis le parent
`lutece-global-pom 7.0.x`.

Renseigner `LUTECE_MAJOR = 7` ou `MONOREPO_BRANCH = develop_core7` sur un job
qui checkoute `develop` **ne suffit pas** et fait echouer le build : ces deux
parametres sont des garde-fous, pas des moyens de changer de sources.

### Q: Le build echoue avec « LUTECE_MAJOR=7 but the checked-out pom.xml inherits lutece-global-pom 8.x » ?

**R:** Le job a checkoute la ligne V8 alors que vous demandez la V7. Deux
options :

- Pour releaser la V7 : changer la branche du SCM du job pour `develop_core7`
- Pour releaser la V8 depuis ce checkout : remettre `LUTECE_MAJOR = auto`

Ce garde-fou existe parce que sans lui la pipeline creerait une branche locale
`develop_core7` sur le commit V8, et pousserait le contenu V8 sur la branche V7.

### Q: Le build echoue avec « MONOREPO_BRANCH=X but the workspace holds the branch Y » ?

**R:** Meme cause : la pipeline release ce qui a ete checkoute et ne change
pas de branche. Pointer le SCM du job sur `X`, ou vider `MONOREPO_BRANCH` pour
releaser `Y`.

### Q: Le build echoue avec « Could not determine which branch the workspace holds » ?

**R:** Ni `BRANCH_NAME`, ni `GIT_BRANCH`, ni les refs git ne permettent
d'identifier la branche checkoutee — cela arrive par exemple si le workspace a
ete checkoute sur un commit qui n'est la tete d'aucune branche distante.
Renseigner `MONOREPO_BRANCH` explicitement, en verifiant qu'il correspond bien
a la branche du SCM du job.

### Q: Peut-on lancer la pipeline sur une autre branche que develop ?

**R:** Oui. `MONOREPO_BRANCH` permet de cibler explicitement une branche, et
sans ce parametre la pipeline utilise la branche courante du workspace si elle
est connue (`develop`, `develop_core7`, `develop_core8`). C'est ce qui rend le
support des deux lignes possible avec un seul job.

### Q: Pourquoi un seul tag `v8.0.0` en cible `all` et non un tag par module ?

**R:** Les 5 modules sont releases depuis le meme commit — des tags par module
pointeraient tous sur ce commit unique. Un tag plateforme est plus lisible et
suffit a identifier la release. Si les versions de modules divergent, la
pipeline retablit automatiquement des tags par module pour garder la
tracabilite des artefacts Nexus.

### Q: Le tag existe deja sur le remote, le build echoue ?

**R:** C'est volontaire : le push du tag n'est pas force, pour ne jamais
ecraser une release publiee. Deux options :

- Incrementer `PRERELEASE_NUMBER` (beta-02 plutot que beta-01)
- Si le tag est a jeter : `git push origin :refs/tags/v8.0.0-beta-01`, puis
  relancer

### Q: Le deploy Maven echoue (401 Unauthorized) ?

**R:** Verifier le fichier de configuration designe par `MAVEN_SETTINGS_ID` :
- le `settings.xml` contient les identifiants du serveur Nexus
- les identifiants ne sont pas expires
- l'URL du repository dans le POM correspond au serveur du settings

### Q: Le deploy echoue avec une erreur 400 sur Nexus ?

**R:** Nexus refuse le re-deploy d'une version release deja publiee. Cela
arrive lors d'un re-run apres un echec partiel. Supprimer l'artefact depuis
l'interface Nexus, puis relancer.

### Q: Comment releaser un seul starter sans toucher aux autres ?

**R:**

```
RELEASE_TARGET = forms-starter
DRY_RUN        = false
```

Seule `<lutece.forms-starter.version>` est modifiee, le tag est
`forms-starter-{version}`, et `master` n'est pas mergee.

### Q: Pourquoi pas de merge sur master lors d'une release individuelle ?

**R:** Seul un module change de version ; les autres sont toujours en
SNAPSHOT. Merger pousserait des versions SNAPSHOT sur la branche de
production. Le merge n'a lieu qu'en cible `all` stable, ou toutes les versions
sont des versions release.

### Q: Les versions calculees ne sont pas celles attendues ?

**R:** Le calcul automatique n'incremente que le patch
(`8.0.0` → `8.0.1-SNAPSHOT`). Pour un changement de mineur ou de majeur,
renseigner explicitement :

```
RELEASE_VERSION       = 8.1.0
NEXT_SNAPSHOT_VERSION = 8.2.0-SNAPSHOT
```

### Q: Que se passe-t-il si les modules ont des versions differentes en cible `all` ?

**R:** La pipeline gere les **versions heterogenes**. Le Stage 0 construit une
carte de versions par module en lisant chaque `<lutece.{module}.version>`
individuellement :

```
forms-starter:       8.0.1-SNAPSHOT → 8.0.1 → 8.0.2-SNAPSHOT
appointment-starter: 8.0.0-SNAPSHOT → 8.0.0 → 8.0.1-SNAPSHOT
editorial-starter:   8.0.0-SNAPSHOT → 8.0.0 → 8.0.1-SNAPSHOT
lutece-starter:      8.0.2-SNAPSHOT → 8.0.2 → 8.0.3-SNAPSHOT
lutece-bom:          8.0.0-SNAPSHOT → 8.0.0 → 8.0.1-SNAPSHOT
```

Chaque module est release avec sa propre version et sa propre prochaine
SNAPSHOT. Les `sed` utilisent la version courante de chaque module comme
pattern, pas une version globale unique. Dans ce cas, le tag plateforme unique
est remplace par des tags par module.

### Q: Les tags beta, RC et stables coexistent-ils ?

**R:** Oui, aucun n'est supprime par la release stable :

```
v8.0.0-beta-01
v8.0.0-beta-02
v8.0.0-RC-01
v8.0.0
```

### Q: Comment verifier le resultat d'un DRY_RUN ?

**R:** Tous les messages de simulation sont prefixes `[DRY-RUN]` dans les logs
Jenkins. Le rapport archive (`release-report.txt`) contient egalement le
resume des actions qui auraient ete effectuees, dont les tags.

---

## Annexe — Variables d'environnement

| Variable | Source | Description |
|----------|--------|-------------|
| `RELEASE_REPORT` | Jenkinsfile | Chemin du fichier rapport |
| `MAVEN_SETTINGS_XML` | Stage 0 | Chemin du `settings.xml` provisionne |
| `GITHUB_TOKEN` | `withCredentials(GITHUB_CREDENTIAL_ID)` | Token d'acces GitHub |
| `PRERELEASE_TYPE` | Stage 0 | `none`, `beta` ou `rc` |
| `PRERELEASE_NUM` | Stage 0 | Numero de pre-release sur 2 chiffres (`01`, `02`...) |
| `IS_PRERELEASE` | Stage 0 | `true` si `PRERELEASE_TYPE != none` |
| `LUTECE_MAJOR_RESOLVED` | Stage 0 | Ligne Lutece retenue (`7` ou `8`) |
| `MONOREPO_BRANCH` | Stage 0 | Branche de release resolue |
| `PLATFORM_TARGET_JDK` | Stage 0 | `targetJdk` resolu (`11`, `17`...) |
| `PLATFORM_JDK_TOOL` | Stage 0 | Nom de l'outil JDK Jenkins correspondant |
| `ORIGINAL_SNAPSHOT_VERSION` | Stage 0 | Version SNAPSHOT d'origine, restauree apres une beta / RC |
| `BASE_RELEASE_VERSION` | Stage 0 | Version de base sans qualifieur (ex: `8.0.0`) |
| `COMPUTED_RELEASE_VERSION` | Stage 0 | Version release (ex: `8.0.0-beta-01`) |
| `COMPUTED_NEXT_SNAPSHOT` | Stage 0 | Prochaine SNAPSHOT (ex: `8.0.1-SNAPSHOT`) |
| `MODULE_VERSIONS_JSON` | Stage 0 (cible `all`) | Carte JSON des versions par module : `property`, `current`, `releaseVersion`, `next` |
| `STARTERS_TO_RELEASE` | Stage 0 | Liste des modules cibles (CSV) |
| `RELEASE_TAGS` | Stage 3 | Tag(s) de release crees (CSV) |

---

## Annexe — Fonctions helper de `release-helpers.groovy`

### Versions et qualifieurs

| Fonction | Signature | Description |
|----------|-----------|-------------|
| `padPrereleaseNumber` | `(String raw) → String` | `1` → `01`, `12` → `12` |
| `qualifyVersion` | `(String base) → String` | `8.0.0` → `8.0.0-beta-01` selon le type de build |
| `stripQualifier` | `(String version) → String` | Retire `-SNAPSHOT`, `-beta-NN`, `-RC-NN` |
| `prereleaseLabel` | `() → String` | `Stable`, `Beta 01`, `Release Candidate 01` |
| `computeNextSnapshot` | `(String version) → String` | `8.0.0` → `8.0.1-SNAPSHOT` |
| `readModuleVersionMap` | `(String pomContent) → Map` | Carte `{module: {property, current, releaseVersion, next}}` pour la cible `all` |
| `getModuleReleaseVersion` | `(String module) → String` | Version release d'un module (carte en mode `all`, `COMPUTED_RELEASE_VERSION` sinon) |
| `starterVersionProperty` | `(String module) → String` | `forms-starter` → `lutece.forms-starter.version` |
| `isSingleModuleRelease` | `() → boolean` | `true` si `RELEASE_TARGET != 'all'` |
| `resolveStartersToRelease` | `(String target) → String` | Resolution en cascade des modules cibles |

### Tags

| Fonction | Signature | Description |
|----------|-----------|-------------|
| `platformTagName` | `(String version) → String` | `8.0.0-beta-01` → `v8.0.0-beta-01` |
| `moduleTagName` | `(String module, String version) → String` | → `forms-starter-8.0.0-beta-01` |
| `resolveReleaseTags` | `() → List<String>` | Tag plateforme en cible `all`, tag module sinon, repli par module si versions heterogenes |

### Ligne Lutece et JDK

| Fonction | Signature | Description |
|----------|-----------|-------------|
| `parentGlobalPomMajor` | `(String pomContent) → String` | Major du parent `lutece-global-pom` (`7` / `8`), `null` si illisible |
| `detectLuteceMajor` | `() → String` | Ligne Lutece du POM checkoute ; echoue si `LUTECE_MAJOR` la contredit |
| `detectCheckedOutBranch` | `() → String` | Branche que le workspace contient (`BRANCH_NAME` > `GIT_BRANCH` > `symbolic-ref` > refs pointant sur HEAD), `null` si indeterminable |
| `resolveMonorepoBranch` | `() → String` | Branche de release depuis le checkout ; echoue si `MONOREPO_BRANCH` la contredit ou si elle est indeterminable |
| `normalizeJdkMajor` | `(String raw) → String` | `1.8` → `8`, `17` → `17` |
| `detectTargetJdk` | `() → String` | `targetJdk` effectif via `mvn help:evaluate`, repli sur la ligne Lutece |
| `jdkToolName` | `(String major) → String` | `17` → `temurin-17-jdk`, surcharge par `JDK_TOOL_MAP` |
| `withJdk` | `(String major, Closure body) → void` | Execute la closure avec `JAVA_HOME`/`PATH` du JDK demande |

### Release et rollback

| Fonction | Signature | Description |
|----------|-----------|-------------|
| `parseSnapshotPlugins` | `(String pomContent) → List<Map>` | Properties `lutece.*.version` encore en SNAPSHOT (gate du Stage 2) |
| `releaseStarter` | `(String module) → void` | Build + deploy Nexus du module, avec le JDK de la ligne |
| `rollbackStarter` | `(String module) → void` | Supprime le(s) tag(s) et revient sur la branche de release |
| `rollbackTags` | `() → void` | Supprime les tags de `RELEASE_TAGS` (local + remote), idempotent |
| `appendReport` | `(String line) → void` | Ajoute une ligne au rapport |
| `generateReleaseReport` | `() → String` | Contenu du rapport final |

# Notes de Release — Plateforme Lutece

## Table des matieres

1. [Architecture du projet](#1-architecture-du-projet)
2. [Principe fondamental des releases](#2-principe-fondamental-des-releases)
3. [Cycle de vie d'une version](#3-cycle-de-vie-dune-version)
4. [Pipeline Jenkins — Vue d'ensemble](#4-pipeline-jenkins--vue-densemble)
5. [Parametres de la pipeline](#5-parametres-de-la-pipeline)
6. [Scenarios d'utilisation](#6-scenarios-dutilisation)
7. [Deroulement detaille des stages](#7-deroulement-detaille-des-stages)
8. [Processus de release d'un plugin](#8-processus-de-release-dun-plugin)
9. [Processus de release d'un starter](#9-processus-de-release-dun-starter)
10. [Gestion des erreurs et rollback](#10-gestion-des-erreurs-et-rollback)
11. [Pre-requis Jenkins](#11-pre-requis-jenkins)
12. [Notifications](#12-notifications)
13. [FAQ et depannage](#13-faq-et-depannage)

---

## 1. Architecture du projet

Le projet Lutece est un **monorepo Maven multi-module** organise comme suit :

```
lutece/
├── pom.xml                   # POM Parent — definit 90+ versions de plugins
├── lutece-bom/pom.xml        # BOM (Bill of Materials)
├── forms-starter/pom.xml     # Starter Formulaires
├── appointment-starter/pom.xml  # Starter Rendez-vous
├── editorial-starter/pom.xml    # Starter Editorial
└── lutece-starter/pom.xml       # Starter Complet (depend des 3 autres)
```

### Hierarchie des dependances

```
Plugins Lutece (90+ composants)
  repartis sur 2 organisations GitHub :
  - lutece-platform
  - lutece-secteur-public
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

Les versions de tous les plugins sont centralisees dans le `pom.xml` racine
sous forme de properties Maven. Chaque module (starter/BOM) a sa propre
property de version, ce qui permet des **releases individuelles** :

```xml
<properties>
    <!-- Chaque module a sa propre version (independante des autres) -->
    <lutece.forms-starter.version>8.0.0-SNAPSHOT</lutece.forms-starter.version>
    <lutece.appointment-starter.version>8.0.0-SNAPSHOT</lutece.appointment-starter.version>
    <lutece.editorial-starter.version>8.0.0-SNAPSHOT</lutece.editorial-starter.version>
    <lutece.lutece-starter.version>8.0.0-SNAPSHOT</lutece.lutece-starter.version>
    <lutece.lutece-bom.version>8.0.0-SNAPSHOT</lutece.lutece-bom.version>

    <!-- 90+ versions de plugins -->
    <lutece.core.version>8.0.0</lutece.core.version>
    <lutece.plugin-forms.version>4.0.0</lutece.plugin-forms.version>
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
- La dependance dans lutece-starter
- La version managee dans lutece-bom

Le **flatten-maven-plugin** (configure en `flattenMode=clean`) resout
la property en valeur concrete dans le POM deploye sur Nexus.

**Release individuelle :** un seul `sed` sur `<lutece.forms-starter.version>`
suffit a mettre a jour la version partout. Les autres modules restent inchanges.

**Release `all` :** la pipeline construit une **carte de versions par module**
(chaque module peut avoir une version differente). Chaque property est mise
a jour individuellement avec sa propre version courante/release/next. Les
parent versions dans les POMs enfants sont egalement mis a jour.

---

## 2. Principe fondamental des releases

> **Regle absolue : les dependances doivent etre releasees AVANT leurs
> consommateurs.**

L'ordre de release est **strict et non-negociable** :

```
 1. Plugins Lutece individuels
          ↓
 2. Mise a jour du POM Parent (versions release)
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

---

## 3. Cycle de vie d'une version

Chaque composant suit le cycle SemVer (`MAJOR.MINOR.PATCH`) :

```
 Developpement         Release              Iteration suivante
┌─────────────┐    ┌──────────────┐    ┌──────────────────────┐
│ 4.0.0-SNAPSHOT │──►│   4.0.0      │──►│   4.0.1-SNAPSHOT     │
└─────────────┘    └──────────────┘    └──────────────────────┘
    develop             master                  develop
                    tag: plugin-forms-4.0.0
```

**Branches :**

| Branche    | Role                                          |
|------------|-----------------------------------------------|
| `develop`  | Branche de travail, reference pour le developpement |
| `master`   | Branche de production, recoit le merge lors de la release |

**Tags :** convention `{artifactId}-{version}` (ex: `plugin-forms-4.0.0`,
`forms-starter-8.0.0`)

---

## 4. Pipeline Jenkins — Vue d'ensemble

Le fichier `Jenkinsfile-release` a la racine du projet definit une **pipeline
declarative** composee de **11 stages** :

```
Stage 0  ► Initialize
             Calcul des versions, resolution des cibles

Stage 1  ► Detect SNAPSHOT Plugins
             Parse pom.xml, extrait les properties *-SNAPSHOT

Stage 2  ► Locate Plugin Repos
             Resout l'organisation GitHub de chaque plugin

Stage 3  ► Release Plugins
             Clone, test, version, tag, merge, deploy (par batch de 5)

Stage 4  ► Update POM Parent Versions
             Met a jour toutes les properties + <revision>

Stage 5  ► Validate Release Readiness
             Verifie qu'aucun SNAPSHOT ne subsiste

Stage 6  ► Release Specialized Starters
             forms, appointment, editorial — en parallele (prepare/perform/promote)

Stage 7  ► Release lutece-starter
             Apres validation des 3 starters

Stage 8  ► Release lutece-bom
             Dernier composant

Stage 9  ► Prepare Next SNAPSHOT
             Remet toutes les versions en SNAPSHOT (patch+1)

Stage 10 ► Release Report
             Resume archive comme artifact Jenkins
```

**Points de conception :**

- `DRY_RUN = true` par defaut pour empecher les releases accidentelles
- Les plugins sont releasees en **batches paralleles de 5** pour equilibrer
  la vitesse et les rate-limits de l'API GitHub
- Les mises a jour du POM parent utilisent `sed` (plus rapide que
  90 invocations `mvn versions:set-property`)
- Pattern **prepare / perform / promote** : master n'est merge qu'apres
  le succes du deploy Nexus — rollback simple, sans force push

---

## 5. Parametres de la pipeline

Lors du lancement depuis l'interface Jenkins, les parametres suivants
sont disponibles :

| Parametre              | Type     | Defaut  | Description |
|------------------------|----------|---------|-------------|
| `RELEASE_TARGET`       | choice   | `forms-starter` | Cible de la release (voir tableau ci-dessous) |
| `RELEASE_VERSION`      | string   | *(vide)* | Version release (ex: `8.0.0`). Si vide, calculee automatiquement en supprimant `-SNAPSHOT` de la version courante. |
| `NEXT_SNAPSHOT_VERSION`| string   | *(vide)* | Prochaine version SNAPSHOT (ex: `8.0.1-SNAPSHOT`). Si vide, calculee en incrementant le patch. |
| `DRY_RUN`              | boolean  | `true`  | Mode simulation. Aucun push, deploy ni merge ne sera effectue. |
| `SKIP_PLUGIN_RELEASES` | boolean  | `false` | Sauter la release des plugins (utile si deja faite manuellement). |
| `SKIP_TESTS`           | boolean  | `false` | Sauter les tests des plugins (non recommande). |
| `PLUGIN_WHITELIST`     | string   | *(vide)* | Liste d'artifactIds a releaser, separes par des virgules. Si vide, tous les plugins SNAPSHOT de la cible seront releasees. |
| `RC_BUILD`             | boolean  | `false` | Release Candidate : cree une version `X.Y.Z-RC-NN` (zero-padded). Pas de merge sur master, retour au SNAPSHOT courant apres deploy. |
| `RC_NUMBER`            | string   | `1`     | Numero de la Release Candidate (1, 2, 3...). Formate avec zero-padding : `1` → `RC-01`, `2` → `RC-02`. Utilise uniquement si `RC_BUILD = true`. |

### Valeurs de RELEASE_TARGET

| Valeur                | Composants releasees |
|-----------------------|---------------------|
| `forms-starter`       | Plugins du forms-starter, puis forms-starter |
| `appointment-starter` | Plugins de l'appointment-starter, puis appointment-starter |
| `editorial-starter`   | Plugins de l'editorial-starter, puis editorial-starter |
| `lutece-starter`      | Uniquement lutece-starter (suppose que les 3 starters specialises et leurs plugins sont deja releases) |
| `lutece-bom`          | Uniquement le BOM (suppose que tout le reste est deja release) |
| `all`                 | **Tout** : plugins + 3 starters + lutece-starter + BOM |

### Calcul automatique des versions

Si `RELEASE_VERSION` est laisse vide, la pipeline lit la version courante
et supprime le suffixe `-SNAPSHOT` :

- **Release individuelle** : lit `<lutece.{target}.version>` (ex: `<lutece.forms-starter.version>8.0.0-SNAPSHOT</...>` → `8.0.0`)
- **Release `all`** : lit la version du parent `lutece-parent` pour affichage, puis construit une **carte de versions par module** en lisant chaque `<lutece.{module}.version>` individuellement (chaque module peut avoir sa propre version)

```
8.0.0-SNAPSHOT  →  RELEASE_VERSION = 8.0.0
```

Si `NEXT_SNAPSHOT_VERSION` est laisse vide, la pipeline incremente
le numero de patch :

```
8.0.0  →  NEXT_SNAPSHOT_VERSION = 8.0.1-SNAPSHOT
```

### Release Candidates (RC)

Les Release Candidates permettent de publier une version pre-release
pour validation avant la release stable. Le mode RC est active par
le parametre `RC_BUILD = true`.

**Calcul de la version RC :**

```
Version SNAPSHOT courante : 8.0.0-SNAPSHOT
RC_NUMBER = 1             → version RC = 8.0.0-RC-01
RC_NUMBER = 2             → version RC = 8.0.0-RC-02
```

**Differences entre RC et release stable :**

| Aspect | Release stable | Release Candidate |
|--------|---------------|-------------------|
| Version | `8.0.0` | `8.0.0-RC-01` |
| Tag | `plugin-forms-8.0.0` | `plugin-forms-8.0.0-RC-01` |
| Merge sur master | Oui | **Non** |
| Deploy depuis | `master` | `develop` |
| Apres deploy | Version → `8.0.1-SNAPSHOT` (increment patch) | Version → `8.0.0-SNAPSHOT` (restauration, pas d'increment) |

**Cycle de vie typique avec RC :**

```
 develop
    │
    ├── RC1: 8.0.0-SNAPSHOT → 8.0.0-RC-01 → deploy → 8.0.0-SNAPSHOT
    │        (corrections...)
    ├── RC2: 8.0.0-SNAPSHOT → 8.0.0-RC-02 → deploy → 8.0.0-SNAPSHOT
    │        (validation OK)
    └── Stable: 8.0.0-SNAPSHOT → 8.0.0 → merge master → deploy → 8.0.1-SNAPSHOT
```

---

## 6. Scenarios d'utilisation

### 6.1 — Premiere utilisation : simulation (DRY_RUN)

**Toujours commencer par un dry-run** pour verifier que la detection
des plugins et la resolution des repos fonctionnent correctement.

```
RELEASE_TARGET = all
DRY_RUN        = true    ← valeur par defaut
```

La pipeline va :
- Detecter tous les plugins SNAPSHOT
- Resoudre les repos GitHub
- Afficher les actions qu'elle effectuerait (sans rien modifier)
- Generer un rapport

Verifier dans les logs :
- Le nombre de plugins SNAPSHOT detectes
- Que chaque plugin est resolu vers le bon repo et la bonne branche
- Que les versions calculees sont correctes

### 6.2 — Release d'un seul plugin (test unitaire de la pipeline)

Pour tester la pipeline sur un composant isole :

```
RELEASE_TARGET   = forms-starter
PLUGIN_WHITELIST = plugin-forms
DRY_RUN          = false
```

Cela va releaser **uniquement** `plugin-forms`, mettre a jour le POM parent
pour ce plugin, puis releaser le forms-starter.

### 6.3 — Release individuelle du forms-starter

```
RELEASE_TARGET = forms-starter
DRY_RUN        = false
```

Deroulement :
1. Lit `<lutece.forms-starter.version>` → `8.0.0-SNAPSHOT`
2. Detecte les plugins SNAPSHOT references par `forms-starter/pom.xml`
3. Resout les repos GitHub pour chacun
4. Release les plugins en batches paralleles de 5
5. Met a jour **uniquement** `<lutece.forms-starter.version>` + les properties des plugins
6. Verifie qu'aucun SNAPSHOT ne subsiste (hors properties de modules)
7. Release le forms-starter (tag, push, deploy — **pas de merge master**)
8. Restaure `<lutece.forms-starter.version>` → `8.0.1-SNAPSHOT`

Les autres modules (appointment-starter, editorial-starter, etc.) ne sont pas impactes.

### 6.4 — Release complete (all)

```
RELEASE_TARGET = all
DRY_RUN        = false
```

Deroulement complet :
1. Lit la version de chaque module individuellement (carte de versions par module)
2. Release tous les plugins SNAPSHOT de tous les starters
3. Met a jour chaque property de module avec sa propre version release + parent versions enfants
4. Release les 3 starters specialises en parallele (chacun avec sa propre version)
5. Release `lutece-starter` (avec sa propre version)
6. Release `lutece-bom` (avec sa propre version)
7. Merge develop sur master
8. Prepare la prochaine version SNAPSHOT (patch+1 de chaque module individuellement)

### 6.5 — Release des starters uniquement (plugins deja releasees)

Si les plugins ont ete releasees manuellement :

```
RELEASE_TARGET      = all
SKIP_PLUGIN_RELEASES = true
DRY_RUN              = false
```

La pipeline sautera les Stages 2 et 3, mettra a jour le POM parent
avec les versions deja en place, puis releasera les starters et le BOM.

### 6.6 — Release urgente sans tests

```
RELEASE_TARGET = forms-starter
SKIP_TESTS     = true
DRY_RUN        = false
```

> **Attention :** les tests existent pour une raison. Ne sauter les tests
> qu'en cas d'urgence absolue et apres validation manuelle.

### 6.7 — Premiere Release Candidate (RC-01)

```
RELEASE_TARGET = all
RC_BUILD       = true
RC_NUMBER      = 1
DRY_RUN        = false
```

Deroulement :
1. Chaque plugin SNAPSHOT est release en version `X.Y.Z-RC-01`
2. Deploy depuis `develop` (pas de merge sur `master`)
3. Tag : `plugin-forms-4.0.0-RC-01`, `forms-starter-8.0.0-RC-01`, etc.
4. Apres deploy, chaque composant revient a sa version SNAPSHOT d'origine
5. On peut continuer a developper sur `develop`

### 6.8 — Deuxieme RC apres corrections

Apres corrections de bugs detectes sur la RC-01 :

```
RELEASE_TARGET = all
RC_BUILD       = true
RC_NUMBER      = 2
DRY_RUN        = false
```

Meme processus, versions `X.Y.Z-RC-02`. Les tags RC-01 restent en place.

### 6.9 — Release stable apres validation de la RC

Une fois la RC validee :

```
RELEASE_TARGET = all
RC_BUILD       = false    ← release stable
DRY_RUN        = false
```

Cette fois, la pipeline effectue le processus complet :
merge sur `master`, deploy, puis passage en `X.Y.(Z+1)-SNAPSHOT`.

### 6.10 — RC ciblee sur un seul starter

```
RELEASE_TARGET = forms-starter
RC_BUILD       = true
RC_NUMBER      = 1
DRY_RUN        = false
```

Seuls les plugins du `forms-starter` seront en RC.

---

## 7. Deroulement detaille des stages

### Stage 0 — Initialize

**Actions :**
- Lit la version courante :
  - **Si `RELEASE_TARGET` est un module specifique** : lit `<lutece.{target}.version>` dans le POM racine
  - **Si `RELEASE_TARGET = all`** : lit la version du parent `lutece-parent` pour affichage, puis construit une **carte de versions par module** (`MODULE_VERSIONS_JSON`) en lisant chaque `<lutece.{module}.version>` individuellement. Chaque entree contient : version courante, version release, prochaine SNAPSHOT.
- Calcule la version release (ex: `8.0.0`) — pour les releases individuelles et le parent POM
- Calcule la prochaine version SNAPSHOT (ex: `8.0.1-SNAPSHOT`)
- Resout la liste des starters a releaser en fonction de `RELEASE_TARGET`
- Initialise le fichier de rapport

**En mode RC :** le calcul des versions est different :

| Variable | Release stable | Release Candidate |
|----------|---------------|-------------------|
| `COMPUTED_RELEASE_VERSION` | `8.0.0` | `8.0.0-RC-01` |
| `COMPUTED_NEXT_SNAPSHOT` | `8.0.1-SNAPSHOT` | `8.0.0-SNAPSHOT` (identique a la version d'origine) |
| `ORIGINAL_SNAPSHOT_VERSION` | *(non utilisee)* | `8.0.0-SNAPSHOT` (sauvegardee pour restauration) |
| `BASE_RELEASE_VERSION` | *(non utilisee)* | `8.0.0` (version sans suffixe RC) |

**Resolution en cascade pour `RELEASE_TARGET` :**

| Cible            | Starters resolus |
|------------------|-----------------|
| `forms-starter`  | `forms-starter` |
| `appointment-starter` | `appointment-starter` |
| `editorial-starter` | `editorial-starter` |
| `lutece-starter` | `lutece-starter` |
| `lutece-bom`     | `lutece-bom` |
| `all`            | `forms-starter, appointment-starter, editorial-starter, lutece-starter, lutece-bom` |

### Stage 1 — Detect SNAPSHOT Plugins

**Actions :**
- Parse le `pom.xml` racine avec une regex sur les properties
  `<lutece.*.version>*-SNAPSHOT</>`
- Derive l'artifactId depuis le nom de la property :
  - `lutece.plugin-forms.version` → `plugin-forms`
  - `lutece.core.version` → `lutece-core`
- Filtre les plugins en croisant avec les `${lutece.*.version}` references
  dans les POMs des starters cibles
- Applique le `PLUGIN_WHITELIST` si specifie

**Exemple de sortie :**
```
All SNAPSHOT plugins detected: 87
After starter filter: 42 plugins
After whitelist filter: 1 plugin
  - plugin-forms : 4.0.0-SNAPSHOT
```

### Stage 2 — Locate Plugin Repos

**Actions :**
- Pour chaque plugin SNAPSHOT, recherche le depot via l'API Search GitHub
- Cherche d'abord dans `lutece-platform`, puis dans `lutece-secteur-public`
- Resout la branche de developpement (priorite : `develop` > `develop_core8`
  > `develop8` > `develop8.x` > `master`)

**Convention de nommage des depots Lutece :**

Le nom du depot GitHub suit le pattern `lutece-{categorie}-{artifactId}`.
L'artifactId est le suffixe du nom du depot. Exemples :

| artifactId (POM)              | Depot GitHub                              |
|-------------------------------|-------------------------------------------|
| `plugin-forms`                | `lutece-form-plugin-forms`                |
| `module-workflow-forms`       | `lutece-wf-module-workflow-forms`         |
| `library-lucene`              | `lutece-tech-library-lucene`              |
| `lutece-core`                 | `lutece-core`                             |

La pipeline utilise l'API Search GitHub pour trouver le depot dont le nom
se termine par l'artifactId :
```
GET /search/repositories?q=org:{org}+{artifactId}+in:name
→ filtre le resultat : repo.name == artifactId OU repo.name finit par "-{artifactId}"
```

**Condition de saut :** ce stage est saute si `SKIP_PLUGIN_RELEASES = true`
ou si aucun plugin SNAPSHOT n'est detecte.

### Stage 3 — Release Plugins

**Actions :** release chaque plugin selon le processus decrit en
[section 8](#8-processus-de-release-dun-plugin).

**Parallelisme :** les plugins sont regroupes en batches de 5, chaque batch
est execute en parallele. Le batch suivant ne demarre qu'apres la fin
du batch courant.

```
Batch 1: [plugin-forms, library-lucene, library-utils, plugin-workflow, library-jwt]
          ↓ (parallele)
Batch 2: [module-workflow-forms, plugin-address, ...]
          ↓ (parallele)
...
```

**En cas d'echec :** le plugin en erreur est rollback automatiquement
(voir [section 10](#10-gestion-des-erreurs-et-rollback)) et les autres
plugins du batch continuent normalement. Le pipeline passe en etat
`UNSTABLE` mais ne s'arrete pas.

**En mode RC :** chaque plugin est release en version `X.Y.Z-RC-NN` au lieu
de `X.Y.Z`. Le deploy s'effectue depuis `develop` (pas de merge sur master)
et la version SNAPSHOT d'origine est restauree apres le deploy. Voir
[section 8](#8-processus-de-release-dun-plugin) pour le detail du processus RC.

### Stage 4 — Update POM Parent Versions

Le comportement de ce stage depend du `RELEASE_TARGET` :

**Si `RELEASE_TARGET` est un module specifique (ex: `forms-starter`) :**
- `sed` uniquement sur `<lutece.forms-starter.version>` dans le POM racine
- Met a jour les properties des plugins releasees (le cas echeant)
- NE touche PAS `<revision>`, ni les parent versions des enfants, ni les autres properties de modules
- Commit et push sur develop

**Si `RELEASE_TARGET = all` :**
- Lit la carte de versions par module (`MODULE_VERSIONS_JSON`)
- Met a jour la `<version>` du projet `lutece-parent`
- Met a jour chaque property de module avec sa propre version release (chaque module utilise sa version courante comme pattern de recherche `sed`, ce qui permet de gerer des versions heterogenes)
- Met a jour la `<version>` du parent dans chaque module enfant
- Remplace chaque property SNAPSHOT des plugins releasees par la version release
- Commit et push sur develop

**Pourquoi `sed` ?** Avec 90+ properties, utiliser
`mvn versions:set-property` prendrait 90 invocations Maven (plusieurs
minutes). `sed` effectue toutes les modifications en quelques secondes.

### Stage 5 — Validate Release Readiness

**Actions :**
- Relit le `pom.xml` apres modifications
- Verifie qu'aucune property SNAPSHOT ne subsiste
- Ignore les properties structurelles des modules
  (`lutece.forms-starter.version`, `lutece.appointment-starter.version`,
  `lutece.editorial-starter.version`, `lutece.lutece-starter.version`,
  `lutece.lutece-bom.version`)
- Si des violations sont detectees : pipeline `UNSTABLE` + detail dans le rapport

Ce stage est un **gate de securite** : il empeche de releaser un starter
avec des dependances non-releasees.

### Stage 6 — Release Specialized Starters

**Actions :** release les 3 starters en parallele selon le processus
decrit en [section 9](#9-processus-de-release-dun-starter).

**Condition de saut :** seuls les starters presents dans `STARTERS_TO_RELEASE`
sont releasees.

**En mode RC :** les starters sont releases en version RC (pas de merge
sur master, deploy depuis develop). Voir
[section 9](#9-processus-de-release-dun-starter) pour le detail.

### Stage 7 — Release lutece-starter

**Actions :**
- Valide que les 3 starters specialises sont en version release
- Release `lutece-starter` (tag, push, deploy, puis merge master)

**Prerequis :** ce stage ne s'execute que si `lutece-starter` est dans
la liste des cibles et que les Stages 6 est termine.

**En mode RC :** meme adaptation que le Stage 6 — pas de merge sur master,
deploy depuis develop.

### Stage 8 — Release lutece-bom

**Actions :** release le BOM (dernier composant du cycle).

### Stage 9 — Prepare Next SNAPSHOT

Le comportement de ce stage depend du `RELEASE_TARGET` :

**Si `RELEASE_TARGET` est un module specifique (release stable) :**
- `sed` uniquement sur `<lutece.{target}.version>` → prochaine SNAPSHOT
- Met a jour les properties des plugins releasees → prochaine SNAPSHOT
- Commit et push sur develop

**Si `RELEASE_TARGET` est un module specifique (mode RC) :**
- Restaure `<lutece.{target}.version>` a la version SNAPSHOT d'origine
- Restaure les properties des plugins releasees
- Commit et push sur develop

**Si `RELEASE_TARGET = all` (release stable) :**
- Lit la carte de versions par module (`MODULE_VERSIONS_JSON`)
- Met a jour la `<version>` du projet parent avec la prochaine SNAPSHOT
- Met a jour chaque property de module avec sa propre prochaine SNAPSHOT (utilise la version release de chaque module comme pattern `sed`)
- Met a jour la `<version>` du parent dans tous les modules enfants
- Remet chaque property plugin en SNAPSHOT (version patch+1)
- Commit et push sur develop

**Si `RELEASE_TARGET = all` (mode RC) :**
- Lit la carte de versions par module
- Restaure la version du parent + chaque property de module a sa version SNAPSHOT d'origine
- Remet chaque property plugin a sa version SNAPSHOT d'origine
- Commit et push sur develop

### Stage 10 — Release Report

**Actions :**
- Finalise le rapport textuel
- Affiche le rapport dans les logs Jenkins
- Archive le fichier `release-report.txt` comme artifact Jenkins

---

## 8. Processus de release d'un plugin

Pour chaque plugin individuel, la pipeline execute les etapes suivantes
dans un repertoire de travail temporaire :

### Etape 1 — Clone

```bash
git clone -b develop --single-branch https://github.com/{org}/{repo}.git
```

### Etape 2 — Tests (conditionnel)

Les tests ne sont executes que si le `<packaging>` du plugin est
`lutece-plugin` ou `lutece-core` :

```bash
mvn lutece:exploded antrun:run -Dlutece-test-hsql test
```

Les autres types de packaging (`pom`, `jar`, `lutece-site`...) n'ont pas
ce type de test et sont sautes.

> **Note :** les starters n'ont aucun test de verification.

### Etape 3 — Version release

```bash
mvn versions:set -DnewVersion=4.0.0 -DgenerateBackupPoms=false
```

### Etape 3b — Mise a jour du descripteur XML du plugin

Le fichier XML descripteur situe dans `webapp/WEB-INF/plugins/*.xml` contient
un tag `<version>` qui doit etre synchronise avec la version du `pom.xml` :

```bash
# Met a jour le tag <version> dans chaque fichier XML du repertoire plugins
for xmlFile in webapp/WEB-INF/plugins/*.xml; do
    [ -f "$xmlFile" ] || continue
    sed -i 's|<version>[^<]*</version>|<version>4.0.0</version>|' "$xmlFile"
done
```

> **Note :** tous les plugins n'ont pas forcement un descripteur XML
> (les libraries et le core n'en ont pas). La boucle `for` ignore
> silencieusement le cas ou aucun fichier n'est present.

### Etape 4 — Commit et tag

```bash
git add -A
git commit -m "release: plugin-forms-4.0.0"
git tag -a plugin-forms-4.0.0 -m "Release plugin-forms 4.0.0"
```

### Etape 5 — Prepare (push branch + tag)

```bash
git push origin develop --tags
```

Le tag et le commit de release sont pousses sur `develop` **avant** tout
deploy. C'est l'equivalent de `mvn release:prepare`.

### Etape 6 — Perform (deploy sur Nexus)

```bash
mvn clean deploy -DskipTests -DperformRelease=true
```

Le deploy s'effectue depuis la branche `develop` (le tag reference le bon
commit). C'est l'equivalent de `mvn release:perform`.

> **Si le deploy echoue ici**, le rollback est simple : supprimer le tag
> et revert le commit sur develop. Master n'a pas ete touche.

### Etape 7 — Promote (merge sur master)

```bash
git checkout master
git merge develop -m "Merge develop for release plugin-forms-4.0.0"
git push origin master
```

Le merge sur master n'a lieu **qu'apres le succes du deploy sur Nexus**.
C'est la phase de promotion.

### Etape 8 — Prochaine iteration

```bash
git checkout develop
mvn versions:set -DnewVersion=4.0.1-SNAPSHOT -DgenerateBackupPoms=false
# Mise a jour du descripteur XML
for xmlFile in webapp/WEB-INF/plugins/*.xml; do
    [ -f "$xmlFile" ] || continue
    sed -i 's|<version>[^<]*</version>|<version>4.0.1-SNAPSHOT</version>|' "$xmlFile"
done
git add -A
git commit -m "chore: prepare next development iteration plugin-forms-4.0.1-SNAPSHOT"
git push origin develop
```

### Pattern Prepare / Perform / Promote

Ce processus suit le meme pattern que `mvn release:prepare` + `mvn release:perform` :

```
 Phase 1 — PREPARE     tag + push sur develop (si echec → rien sur Nexus, rien sur master)
       ↓
 Phase 2 — PERFORM     deploy sur Nexus (si echec → rollback = revert commit + supprimer tag)
       ↓
 Phase 3 — PROMOTE     merge sur master (seulement apres succes du deploy)
       ↓
 Phase 4 — ITERATE     next SNAPSHOT sur develop
```

**Avantage :** master n'est jamais touche avant le succes du deploy.
Le rollback ne necessite **jamais** de `git reset --hard` ni de
`git push --force`.

### Resume visuel (release stable)

```
develop:  ──[SNAPSHOT]──► tests ──► version:set ──► commit+tag ──► push ──────────────► version:set(SNAPSHOT+1) ──► push
                                                        │                     │
nexus:    ──────────────────────────────────────────────────── deploy ─────────┤
                                                        │                     │
master:   ──────────────────────────────────────────────────────────── merge ──┤
                                                        │                     │
tags:     ──────────────────────────────── plugin-forms-4.0.0 ────────────────┘
```

### Variante RC — Release d'un plugin en Release Candidate

En mode RC (`RC_BUILD = true`), le processus est simplifie : pas de merge
sur master et la version SNAPSHOT d'origine est restauree apres le deploy.

#### Etape 1 — Clone (identique)

```bash
git clone -b develop --single-branch https://github.com/{org}/{repo}.git
```

#### Etape 2 — Tests (identique, conditionnel)

```bash
mvn lutece:exploded antrun:run -Dlutece-test-hsql test
```

#### Etape 3 — Version RC

```bash
mvn versions:set -DnewVersion=4.0.0-RC-01 -DgenerateBackupPoms=false
# Mise a jour du descripteur XML
for xmlFile in webapp/WEB-INF/plugins/*.xml; do
    [ -f "$xmlFile" ] || continue
    sed -i 's|<version>[^<]*</version>|<version>4.0.0-RC-01</version>|' "$xmlFile"
done
```

#### Etape 4 — Commit et tag RC

```bash
git add -A
git commit -m "release: plugin-forms-4.0.0-RC-01"
git tag -fa plugin-forms-4.0.0-RC-01 -m "Release Candidate plugin-forms 4.0.0-RC-01"
```

#### Etape 5 — Prepare (push branch + tag)

```bash
git push origin develop --tags
```

#### Etape 6 — Perform (deploy depuis develop)

```bash
mvn clean deploy -DskipTests -DperformRelease=true
```

Le deploy s'effectue **directement depuis la branche `develop`**.
Aucun merge sur `master` n'est effectue (pas de phase promote en RC).

#### Etape 7 — Restauration de la version SNAPSHOT d'origine

```bash
mvn versions:set -DnewVersion=4.0.0-SNAPSHOT -DgenerateBackupPoms=false
# Mise a jour du descripteur XML
for xmlFile in webapp/WEB-INF/plugins/*.xml; do
    [ -f "$xmlFile" ] || continue
    sed -i 's|<version>[^<]*</version>|<version>4.0.0-SNAPSHOT</version>|' "$xmlFile"
done
git add -A
git commit -m "chore: restore SNAPSHOT after RC plugin-forms-4.0.0-RC-01"
git push origin develop
```

> **Important :** contrairement a la release stable, le numero de patch
> n'est **pas incremente**. La version revient a `4.0.0-SNAPSHOT` (pas
> `4.0.1-SNAPSHOT`), ce qui permet de lancer d'autres RC ou la release
> stable par la suite.

### Resume visuel (mode RC)

```
develop:  ──[SNAPSHOT]──► tests ──► version:set(RC) ──► commit+tag ──► push ──────────► version:set(SNAPSHOT) ──► push
                                                            │              │
nexus:    ─────────────────────────────────────────────────────── deploy ──┘
                                                            │
master:   ─────────────────────────────────────── (inchange) │
                                                            │
tags:     ──────────────────────────────── plugin-forms-4.0.0-RC-01
```

---

## 9. Processus de release d'un starter

Les starters etant des modules du monorepo, leur release est plus simple
(pas de clone, pas de tests). Le meme pattern **prepare / perform / promote**
est applique.

### Etape 1 — Prepare (tag + push)

```bash
# Supprime le tag local s'il existe (idempotence pour les re-runs)
git tag -d forms-starter-8.0.0 2>/dev/null || true
git tag -a forms-starter-8.0.0 -m "Release forms-starter 8.0.0"
git push origin forms-starter-8.0.0
```

Le tag est cree et pousse avant tout deploy. Seul le tag est pousse
(la branche develop a deja ete poussee au Stage 4).

### Etape 2 — Perform (deploy du module)

```bash
# Install le module et ses dependances reactor (sans deployer sur Nexus)
mvn clean install -pl forms-starter -am -DskipTests -DperformRelease=true
# Deploy uniquement le module cible (sans -am pour eviter de re-deployer les dependances)
mvn deploy -pl forms-starter -DskipTests -DperformRelease=true
```

Le deploy est separe en deux commandes pour eviter que `-am` ne re-deploie
des modules deja publies sur Nexus (ce qui provoquerait une erreur 400).

> **Si le deploy echoue ici**, le rollback est simple : supprimer le tag.
> Master n'a pas ete touche.

### Etape 3 — Promote (merge sur master — release `all` uniquement)

```bash
git checkout master
git merge develop -m "Merge develop for release forms-starter-8.0.0"
git push origin master
git checkout develop
```

Le merge sur master n'a lieu **qu'apres le succes du deploy sur Nexus** et
**uniquement lors d'une release `all`**. Les releases individuelles
(un seul module) ne mergent pas sur master car les autres modules
peuvent encore etre en SNAPSHOT.

### Resume visuel (release `all` d'un starter)

```
develop:  ──► tag ──► push ────────────────────────────────►
                         │              │              │
nexus:    ──────────────────── deploy ──┤              │
                         │              │              │
master:   ──────────────────────────── merge ──► push ─┘
                         │
tags:     ── forms-starter-8.0.0
```

### Resume visuel (release individuelle d'un starter)

```
develop:  ──► tag ──► push ────────────────────────────────►
                         │              │
nexus:    ──────────────────── deploy ──┘
                         │
master:   ──────────────────────── (inchange)
                         │
tags:     ── forms-starter-8.0.1
```

### Variante RC — Release d'un starter en Release Candidate

En mode RC, le processus des starters est adapte : pas de merge sur master
(pas de phase promote), deploy depuis develop.

#### Etape 1 — Prepare (tag + push)

```bash
git tag -d forms-starter-8.0.0-RC-01 2>/dev/null || true
git tag -a forms-starter-8.0.0-RC-01 -m "Release Candidate forms-starter 8.0.0-RC-01"
git push origin forms-starter-8.0.0-RC-01
```

#### Etape 2 — Perform (deploy depuis develop)

```bash
mvn clean install -pl forms-starter -am -DskipTests -DperformRelease=true
mvn deploy -pl forms-starter -DskipTests -DperformRelease=true
```

Le deploy s'effectue **directement depuis la branche `develop`**.
La branche `master` reste inchangee (pas de phase promote en RC).

> **Note :** en mode RC, il n'y a pas de retour a SNAPSHOT pour les starters
> individuellement — c'est le Stage 9 qui restaure la version globale.

---

## 10. Gestion des erreurs et rollback

### Principe : Prepare / Perform / Promote elimine les rollbacks dangereux

Grace au pattern **prepare / perform / promote**, la branche `master`
n'est **jamais touchee avant le succes du deploy Nexus**. Cela signifie
qu'en cas d'echec, le rollback est toujours simple et sur :

```
 Echec a la phase PREPARE (push Git)  →  Rien sur Nexus, rien sur master
                                          Rollback : supprimer le tag local

 Echec a la phase PERFORM (deploy)    →  Tag pousse, mais master intact
                                          Rollback : revert commit + supprimer le tag

 Echec a la phase PROMOTE (merge)     →  Nexus OK, tag OK, master non merge
                                          Action : relancer le merge manuellement
```

**Jamais de `git reset --hard` ni de `git push --force`.**

### Rollback automatique des plugins

En cas d'echec lors de la release d'un plugin, la pipeline execute
automatiquement un rollback en 2 etapes :

1. **Revert du commit de release sur develop** :
   ```bash
   git checkout develop
   git revert HEAD --no-edit
   git push origin develop
   ```

2. **Suppression du tag** (local + remote) :
   ```bash
   git tag -d plugin-forms-4.0.0
   git push origin :refs/tags/plugin-forms-4.0.0
   ```

> **Note :** master n'est pas concerne car le merge n'a lieu qu'apres
> le succes du deploy (phase promote).

### Rollback automatique des starters

Pour les starters, le rollback est simple :

1. **Suppression du tag** (local + remote) :
   ```bash
   git tag -d forms-starter-8.0.0
   git push origin :refs/tags/forms-starter-8.0.0
   ```

2. **Retour sur la branche develop** :
   ```bash
   git checkout develop
   ```

### Echec du rollback

Si le rollback lui-meme echoue, le rapport indiquera :

```
ROLLBACK FAILED: plugin-forms — manual intervention required: <message>
```

Dans ce cas, une **intervention manuelle** est necessaire.

### Comportement du pipeline en cas d'erreur

| Situation | Comportement |
|-----------|-------------|
| 1 plugin echoue dans un batch | Rollback du plugin (revert + tag delete), les autres continuent. Pipeline `UNSTABLE`. |
| Plusieurs plugins echouent | Chaque plugin est rollback individuellement. Pipeline `UNSTABLE`. |
| Un starter echoue | Rollback du starter (tag delete). Pipeline `FAILURE`. Les plugins deja releasees ne sont pas rollback. |
| Validation SNAPSHOT echoue | Pipeline `UNSTABLE`. Les starters ne seront pas deployes si la validation echoue. |

### Reprendre apres un echec

1. Consulter le rapport archive (`release-report.txt`)
2. Identifier les plugins en echec et leurs messages d'erreur
3. Corriger les problemes manuellement sur les repos concernes
4. Relancer la pipeline avec `PLUGIN_WHITELIST` pour ne releaser que les
   plugins manquants
5. Ou relancer avec `SKIP_PLUGIN_RELEASES = true` si les plugins sont
   deja tous en version release

---

## 11. Pre-requis Jenkins

### Credentials

Les credentials suivants doivent etre configures dans Jenkins :

| ID Jenkins              | Type        | Description |
|-------------------------|-------------|-------------|
| `github-token-lutece`   | Secret text | Token GitHub avec acces aux 2 organisations (`lutece-platform` et `lutece-secteur-public`). Permissions requises : `repo`, `read:org`. |
| `maven-settings-lutece` | Secret file | Fichier `settings.xml` Maven contenant les credentials du registry Nexus pour le deploy. |

### Outils

| Outil   | Nom Jenkins | Version |
|---------|------------|---------|
| Maven   | `Maven-3`  | 3.x     |
| JDK     | `JDK-17`   | 17      |

### Plugins Jenkins recommandes

- **Pipeline** (workflow-aggregator)
- **Slack Notification Plugin** — pour les notifications `#lutece-releases`
- **Email Extension Plugin** — pour les notifications email
- **Credentials Binding Plugin** — pour `credentials()`
- **Workspace Cleanup Plugin** — pour `cleanWs()`
- **Timestamps Plugin** — pour les logs horodates

### Configuration du job

1. Creer un job de type **Multibranch Pipeline** ou **Pipeline**
2. Source : `https://github.com/lutece-platform/lutece`
3. Branche : `develop`
4. Script Path : `Jenkinsfile-release`

---

## 12. Notifications

### Slack

| Evenement | Canal               | Couleur |
|-----------|---------------------|---------|
| Succes    | `#lutece-releases`  | Vert    |
| Echec     | `#lutece-releases`  | Rouge   |

Le message Slack contient :
- Version releasee
- Cible de la release
- Indicateur DRY_RUN
- Indicateur RC (si `RC_BUILD = true`, avec le numero de RC)
- Lien vers le build Jenkins

### Email

| Evenement | Destinataires |
|-----------|--------------|
| Succes    | Derniers auteurs de commits + lanceur du build |
| Echec     | Idem + contenu du rapport complet |

---

## 13. FAQ et depannage

### Q: J'ai lance le pipeline mais rien ne s'est passe ?

**R:** Verifier que `DRY_RUN` est bien sur `false`. Par defaut, la pipeline
est en mode simulation et n'effectue aucune modification.

### Q: Le pipeline detecte 0 plugins SNAPSHOT, est-ce normal ?

**R:** Cela peut arriver si :
- Tous les plugins de la cible sont deja en version release
- Le `PLUGIN_WHITELIST` ne correspond a aucun plugin

Verifier les logs du Stage 1 pour le detail.

### Q: Un plugin n'est pas trouve sur GitHub (repo not found) ?

**R:** Le pipeline cherche le repo par son artifactId exact. Verifier :
- Que le nom du repo correspond bien a l'artifactId
  (ex: `plugin-forms`, pas `lutece-plugin-forms`)
- Que le token GitHub a acces aux 2 organisations
- Que le repo n'est pas prive avec des permissions insuffisantes

### Q: Les tests d'un plugin echouent — que faire ?

**R:** Options :
1. Corriger le bug sur le repo du plugin, pusher, et relancer la pipeline
2. Si le test est un faux negatif, relancer avec `SKIP_TESTS = true`
   (en dernier recours uniquement)
3. Utiliser `PLUGIN_WHITELIST` pour exclure temporairement ce plugin

### Q: Le deploy Maven echoue (401 Unauthorized) ?

**R:** Verifier le credential `maven-settings-lutece` :
- Le `settings.xml` contient les identifiants du serveur Nexus
- Les identifiants ne sont pas expires
- L'URL du repository dans le POM correspond au serveur dans le settings

### Q: Comment relancer uniquement la partie starters ?

**R:** Utiliser les parametres suivants :

```
RELEASE_TARGET       = all       (ou le starter specifique)
SKIP_PLUGIN_RELEASES = true
DRY_RUN              = false
```

### Q: Comment releaser un seul plugin specifique ?

**R:** Utiliser `PLUGIN_WHITELIST` :

```
RELEASE_TARGET   = forms-starter
PLUGIN_WHITELIST = plugin-forms
DRY_RUN          = false
```

### Q: La pipeline depasse le timeout de 4 heures ?

**R:** Cela peut arriver si le nombre de plugins est tres eleve ou si les
tests sont lents. Solutions :
- Augmenter le timeout dans le Jenkinsfile (option `timeout`)
- Utiliser `SKIP_TESTS = true` si les tests ont deja ete valides
- Reduire le scope avec `PLUGIN_WHITELIST`

### Q: Comment verifier le resultat d'un DRY_RUN ?

**R:** Tous les messages DRY_RUN sont prefixes par `[DRY-RUN]` dans les
logs Jenkins. Le rapport archive (`release-report.txt`) contient aussi
le resume des actions qui auraient ete effectuees.

### Q: Comment releaser un seul starter sans toucher aux autres ?

**R:** Utiliser `RELEASE_TARGET` pour cibler le module :

```
RELEASE_TARGET = forms-starter
DRY_RUN        = false
```

La pipeline va :
- Lire la version depuis `<lutece.forms-starter.version>`
- Releaser les plugins SNAPSHOT du forms-starter
- Mettre a jour uniquement `<lutece.forms-starter.version>` (pas `<revision>`)
- Tag + deploy le forms-starter (pas de merge sur master)
- Restaurer `<lutece.forms-starter.version>` en next SNAPSHOT

Les autres modules restent inchanges.

### Q: Pourquoi pas de merge sur master lors d'une release individuelle ?

**R:** En release individuelle, seul un module change de version. Les autres
modules sont toujours en SNAPSHOT. Merger develop sur master pousserait
des versions SNAPSHOT sur la branche de production, ce qui est interdit.
Le merge sur master n'a lieu que lors d'une release `all` ou toutes les
versions sont en release.

### Q: Les versions calculees automatiquement ne sont pas correctes ?

**R:** Specifier manuellement les versions :

```
RELEASE_VERSION       = 8.1.0
NEXT_SNAPSHOT_VERSION = 8.2.0-SNAPSHOT
```

Le calcul automatique ne fait qu'incrementer le patch
(`8.0.0` → `8.0.1-SNAPSHOT`). Pour un changement de mineur ou majeur,
specifier les versions manuellement.

### Q: Peut-on lancer la pipeline sur une autre branche que develop ?

**R:** La pipeline suppose que la branche courante est `develop`. Modifier
le Jenkinsfile pour changer la branche source n'est pas recommande.
Si necessaire, creer une branche de release temporaire et adapter le script.

### Q: Quelle est la difference entre une RC et une release stable ?

**R:** La Release Candidate (RC) est une pre-release pour validation. Les
differences principales sont :
- La version porte le suffixe `-RC-NN` (ex: `8.0.0-RC-01`)
- Aucun merge sur `master` — le deploy s'effectue depuis `develop`
- Apres le deploy, la version SNAPSHOT d'origine est restauree (pas d'increment)
- On peut lancer plusieurs RC successives (RC-01, RC-02, RC-03...) avant la release stable

### Q: Peut-on faire une RC sur un seul starter ?

**R:** Oui. Utiliser `RELEASE_TARGET` pour cibler le starter souhaite :

```
RELEASE_TARGET = forms-starter
RC_BUILD       = true
RC_NUMBER      = 1
DRY_RUN        = false
```

### Q: Apres une RC, comment lancer la release stable ?

**R:** Relancer la pipeline avec `RC_BUILD = false` :

```
RELEASE_TARGET = all
RC_BUILD       = false
DRY_RUN        = false
```

La release stable effectuera le processus complet : merge sur master,
deploy, puis passage en version SNAPSHOT suivante (avec increment du patch).

### Q: Que se passe-t-il si les modules ont des versions differentes lors d'une release `all` ?

**R:** La pipeline gere correctement les **versions heterogenes**. En mode
`all`, le Stage 0 construit une carte de versions par module en lisant
chaque `<lutece.{module}.version>` individuellement. Exemple :

```
forms-starter:       8.0.1-SNAPSHOT → 8.0.1 → 8.0.2-SNAPSHOT
appointment-starter: 8.0.0-SNAPSHOT → 8.0.0 → 8.0.1-SNAPSHOT
editorial-starter:   8.0.0-SNAPSHOT → 8.0.0 → 8.0.1-SNAPSHOT
lutece-starter:      8.0.2-SNAPSHOT → 8.0.2 → 8.0.3-SNAPSHOT
lutece-bom:          8.0.0-SNAPSHOT → 8.0.0 → 8.0.1-SNAPSHOT
```

Chaque module est release avec sa propre version, son propre tag, et sa
propre prochaine SNAPSHOT. Les operations `sed` utilisent la version courante
de chaque module comme pattern de recherche, pas une version globale unique.

### Q: Les tags RC et les tags stables coexistent-ils ?

**R:** Oui. Les tags RC restent en place et ne sont pas supprimes lors de
la release stable. Exemple apres un cycle complet :

```
plugin-forms-4.0.0-RC-01    (tag RC-01)
plugin-forms-4.0.0-RC-02    (tag RC-02)
plugin-forms-4.0.0          (tag stable)
```

### Q: Que se passe-t-il si une RC echoue ?

**R:** Le rollback en mode RC est plus simple qu'en mode stable, car il n'y
a pas de merge sur master a annuler. Le rollback supprime le tag RC et
restaure la version SNAPSHOT sur develop.

---

## Annexe — Variables d'environnement

| Variable | Source | Description |
|----------|--------|-------------|
| `GITHUB_TOKEN` | credentials(`github-token-lutece`) | Token d'acces GitHub |
| `MAVEN_SETTINGS_XML` | credentials(`maven-settings-lutece`) | Fichier settings.xml Maven |
| `GITHUB_ORG_PLATFORM` | Jenkinsfile | `lutece-platform` |
| `GITHUB_ORG_PUBLIC` | Jenkinsfile | `lutece-secteur-public` |
| `COMPUTED_RELEASE_VERSION` | Calculee au Stage 0 | Version release du parent / module unique (ex: `8.0.0`). En mode `all`, utilisee pour le parent POM et l'affichage. |
| `COMPUTED_NEXT_SNAPSHOT` | Calculee au Stage 0 | Prochaine SNAPSHOT du parent / module unique (ex: `8.0.1-SNAPSHOT`) |
| `MODULE_VERSIONS_JSON` | Calculee au Stage 0 (mode `all` uniquement) | Carte JSON des versions par module. Chaque entree contient : `property`, `current`, `releaseVersion`, `next`. Permet de gerer des versions heterogenes entre modules. |
| `STARTERS_TO_RELEASE` | Calculee au Stage 0 | Liste des starters cibles (CSV) |
| `SNAPSHOT_PLUGINS_JSON` | Calculee au Stage 1 | Liste JSON des plugins SNAPSHOT |
| `RESOLVED_PLUGINS_JSON` | Calculee au Stage 2 | Liste JSON des plugins avec info repo |
| `RELEASED_PLUGINS` | Mise a jour au Stage 3 | artifactIds releasees (CSV) |
| `RELEASE_REPORT` | Jenkinsfile | Chemin du fichier rapport |
| `IS_RC` | Calculee au Stage 0 | `true` si `RC_BUILD` est active, `false` sinon |
| `RC_NUM` | Calculee au Stage 0 | Numero de la RC (ex: `1`, `2`) — vide si release stable |
| `ORIGINAL_SNAPSHOT_VERSION` | Calculee au Stage 0 (RC uniquement) | Version SNAPSHOT sauvegardee pour restauration apres RC (ex: `8.0.0-SNAPSHOT`) |
| `BASE_RELEASE_VERSION` | Calculee au Stage 0 (RC uniquement) | Version de base sans suffixe RC (ex: `8.0.0`) |

---

## Annexe — Fonctions helper du Jenkinsfile

| Fonction | Signature | Description |
|----------|-----------|-------------|
| `starterVersionProperty` | `(String name) → String` | Retourne le nom de la property POM pour un module (ex: `forms-starter` → `lutece.forms-starter.version`) |
| `isSingleModuleRelease` | `() → boolean` | `true` si `RELEASE_TARGET != 'all'` |
| `readModuleVersionMap` | `(String pomContent) → Map` | Lit les 5 properties de version des modules et construit une carte `{module: {property, current, releaseVersion, next}}`. Utilisee en mode `all` pour gerer les versions heterogenes. |
| `getModuleReleaseVersion` | `(String moduleName) → String` | Retourne la version release d'un module. En mode `all` : consulte `MODULE_VERSIONS_JSON`. En mode individuel : retourne `COMPUTED_RELEASE_VERSION`. |
| `computeNextSnapshot` | `(String version) → String` | `8.0.0` → `8.0.1-SNAPSHOT` |
| `resolveStartersToRelease` | `(String target) → String` | Resolution en cascade des starters cibles |
| `parseSnapshotPlugins` | `(String pomContent) → List<Map>` | Extrait les properties SNAPSHOT du POM |
| `filterPluginsForStarters` | `(List plugins, List starters) → List<Map>` | Filtre les plugins par starter |
| `resolveGitHubRepo` | `(String artifactId) → Map` | Resout org + repo + branche sur GitHub |
| `resolveDevBranch` | `(String org, String repo) → String` | Resout la branche de developpement |
| `releasePlugin` | `(Map plugin) → void` | Workflow complet de release d'un plugin |
| `rollbackPlugin` | `(Map plugin) → void` | Revert du commit + suppression du tag |
| `releaseStarter` | `(String name) → void` | Release d'un module du monorepo (prepare/perform/promote). Utilise `getModuleReleaseVersion()` pour le tag. Merge sur master uniquement si `!isSingleModuleRelease()`. |
| `rollbackStarter` | `(String name) → void` | Suppression du tag (via `getModuleReleaseVersion()`) + retour sur develop |
| `appendReport` | `(String line) → void` | Ajoute une ligne au rapport |
| `generateReleaseReport` | `() → void` | Genere le rapport final de release |

# CLAUDE.md — Projet Lutece Multi-Module

## 🏗️ Vue d'ensemble du projet

Ce projet est un **monorepo Maven multi-module** pour la plateforme **Lutece**, un framework open-source d'applications web inspiré de l'architecture Jakarta EE. Il contient des **Starters** (configurations prêtes à l'emploi) et un **BOM** (Bill of Materials) pour démarrer rapidement des applications Lutece.

**Dépôt principal :** `https://github.com/lutece-platform/lutece`

## 📦 Structure des modules

```
lutece/                          # Racine du projet
├── pom.xml                      # POM Parent — fixe les versions de TOUS les plugins Lutece
├── lutece-bom/                  # BOM — centralise les versions des dépendances
│   └── pom.xml
├── forms-starter/               # Starter pour les formulaires
│   └── pom.xml
├── appointment-starter/         # Starter pour la gestion de rendez-vous
│   └── pom.xml
├── editorial-starter/          # Starter pour le contenu éditorial
│   └── pom.xml
└── lutece-starter/              # Starter complet (tous les composants Lutece)
    └── pom.xml
```

### Hiérarchie Maven

- **POM Parent** (`lutece/pom.xml`) : définit les versions de tous les plugins/composants Lutece
- **BOM** (`lutece-bom`) : centralise les versions des dépendances, utilisé par les consommateurs externes (PAS par les starters, pour garder de la souplesse)
- **Starters spécialisés** (`forms-starter`, `appointment-starter`, `editorial-starter`) : héritent du POM parent, chacun agrège un sous-ensemble de plugins Lutece avec ses propres versions
- **lutece-starter** : le starter complet, il **dépend des 3 autres starters** + des plugins Lutece supplémentaires qui ne sont dans aucun des starters spécialisés

## 🔗 Organisations GitHub

Les plugins Lutece sont répartis sur **deux organisations GitHub** :

| Organisation | URL | Contenu |
|---|---|---|
| **lutece-platform** | `https://github.com/lutece-platform` | Core, plugins principaux, ce dépôt |
| **lutece-secteur-public** | `https://github.com/lutece-secteur-public` | Plugins spécifiques secteur public |

## 🌿 Stratégie de branches

- **Branche de développement** : `develop` (sur tous les dépôts) — branche de référence pour le travail courant
- **Branche de production** : `master` — reçoit le merge de develop lors de chaque release
- Les releases sont préparées sur `develop`, puis mergées sur `master` avant le deploy
- Convention de tags : `{artifactId}-{version}` (ex: `lutece-core-8.0.0`)

## ☕ Stack technique

- **Java** : 17
- **Build** : Maven 3.x
- **CI/CD** : Jenkins (Pipeline déclaratif)
- **SCM** : Git / GitHub

## 🚀 Workflow de release

### Graphe de dépendances

```
plugins Lutece (lutece-platform + lutece-secteur-public)
   │
   ├──► forms-starter          (sous-ensemble de plugins)
   ├──► appointment-starter    (sous-ensemble de plugins)
   ├──► editorial-starter     (sous-ensemble de plugins)
   │
   └──► lutece-starter
           ├── dépend de forms-starter
           ├── dépend de appointment-starter
           ├── dépend de editorial-starter
           └── dépend de plugins Lutece supplémentaires
                (non inclus dans les 3 starters ci-dessus)
```

### Principe fondamental

> **Les composants (plugins) d'un starter doivent être releasés AVANT le starter lui-même.**
> **Le lutece-starter ne peut être releasé qu'APRÈS les 3 autres starters.**

### Ordre de release

```
1. Releaser les plugins Lutece individuels concernés
   (sur lutece-platform et/ou lutece-secteur-public)
   ↓
2. Mettre à jour le POM parent avec les versions release des plugins
   ↓
3. Releaser le(s) starter(s) spécialisé(s) : forms / appointment / editorial
   (indépendamment les uns des autres)
   ↓
4. Releaser le lutece-starter (qui dépend des 3 starters + plugins supplémentaires)
   ↓
5. Releaser le BOM (lutece-bom) — reflète les versions finales de tout
```

### Scénarios de release

**Release d'un starter spécialisé (ex: forms-starter) :**
1. Identifier les plugins SNAPSHOT du forms-starter
2. Releaser ces plugins un par un
3. Mettre à jour le POM parent
4. Releaser le forms-starter

**Release du lutece-starter (complet) :**
1. Releaser les 3 starters spécialisés (s'ils sont en SNAPSHOT)
2. Releaser les plugins supplémentaires propres au lutece-starter
3. Mettre à jour le POM parent
4. Releaser le lutece-starter

### Détail du processus de release d'un plugin Lutece

Pour chaque plugin/composant en SNAPSHOT :

```bash
# 1. Lancer les tests avant la release (sur develop)
#    UNIQUEMENT si le packaging est lutece-plugin ou lutece-core
#    Vérifier dans le pom.xml : <packaging>lutece-plugin</packaging> ou <packaging>lutece-core</packaging>
mvn lutece:exploded antrun:run -Dlutece-test-hsql test

# 2. Passer en version release
mvn versions:set -DnewVersion=X.Y.Z

# 2b. Mettre à jour le tag <version> du descripteur XML du plugin
#     Le fichier webapp/WEB-INF/plugins/*.xml contient un tag <version>
#     qui doit être synchronisé avec la version du pom.xml
for xmlFile in webapp/WEB-INF/plugins/*.xml; do
    [ -f "$xmlFile" ] || continue
    sed -i 's|<version>[^<]*</version>|<version>X.Y.Z</version>|' "$xmlFile"
done

# 3. Commit + tag
git add -A
git commit -m "release: {artifactId}-X.Y.Z"
git tag -fa {artifactId}-X.Y.Z -m "Release {artifactId} X.Y.Z"

# 4. Prepare (push branch + tag)
git push origin develop --tags

# 5. Perform (deploy sur Nexus — depuis develop)
mvn clean deploy -DskipTests -DperformRelease=true

# 6. Promote (merge sur master — seulement apres succes du deploy)
git checkout master
git merge develop -m "Merge develop for release {artifactId}-X.Y.Z"
git push origin master

# 7. Revenir sur develop et repasser en SNAPSHOT
git checkout develop
mvn versions:set -DnewVersion=X.Y.(Z+1)-SNAPSHOT
# Mettre à jour aussi le descripteur XML du plugin
for xmlFile in webapp/WEB-INF/plugins/*.xml; do
    [ -f "$xmlFile" ] || continue
    sed -i 's|<version>[^<]*</version>|<version>X.Y.(Z+1)-SNAPSHOT</version>|' "$xmlFile"
done
git add -A
git commit -m "chore: prepare next development iteration {artifactId}-X.Y.(Z+1)-SNAPSHOT"

# 8. Push develop
git push origin develop
```

### Détail du processus de release d'un starter

Les starters **n'ont pas de tests de vérification**, le processus est plus simple :

```bash
# 1. Passer en version release (sur develop)
mvn versions:set -DnewVersion=X.Y.Z

# 2. Commit + tag
git add -A
git commit -m "release: {artifactId}-X.Y.Z"
git tag -a {artifactId}-X.Y.Z -m "Release {artifactId} X.Y.Z"

# 3. Merger sur master
git checkout master
git merge develop
git push origin master

# 4. Deploy (depuis master)
mvn clean deploy -P release

# 5. Revenir sur develop et repasser en SNAPSHOT
git checkout develop
mvn versions:set -DnewVersion=X.Y.(Z+1)-SNAPSHOT
git add -A
git commit -m "chore: prepare next development iteration {artifactId}-X.Y.(Z+1)-SNAPSHOT"

# 6. Push develop + tags
git push origin develop --tags
```

### Release d'un starter

```bash
# 1. S'assurer que TOUS les composants du starter sont en version release (non-SNAPSHOT)
# 2. Mettre à jour le pom parent avec les versions release
# 3. Suivre le même processus de release que ci-dessus pour le starter
```

### Release Candidates (RC)

Les Release Candidates permettent de publier une version pré-release pour validation
avant la release stable. Le mode RC est **optionnel**.

**Différences entre RC et release stable :**

| Aspect | Release stable | Release Candidate |
|--------|---------------|-------------------|
| Version | `X.Y.Z` | `X.Y.Z-RC-NN` (zero-padded) |
| Tag | `{artifactId}-X.Y.Z` | `{artifactId}-X.Y.Z-RC-NN` |
| Merge sur master | Oui | **Non** |
| Deploy depuis | `master` | `develop` |
| Après deploy | Version → `X.Y.(Z+1)-SNAPSHOT` | Version → `X.Y.Z-SNAPSHOT` (restauration) |

**Cycle de vie typique avec RC :**

```
develop
   │
   ├── RC-01: X.Y.Z-SNAPSHOT → X.Y.Z-RC-01 → deploy → X.Y.Z-SNAPSHOT
   │          (corrections...)
   ├── RC-02: X.Y.Z-SNAPSHOT → X.Y.Z-RC-02 → deploy → X.Y.Z-SNAPSHOT
   │        (validation OK)
   └── Stable: X.Y.Z-SNAPSHOT → X.Y.Z → merge master → deploy → X.Y.(Z+1)-SNAPSHOT
```

**Processus de release RC d'un plugin :**

```bash
# 1. Tests (identique à la release stable)
mvn lutece:exploded antrun:run -Dlutece-test-hsql test

# 2. Passer en version RC
mvn versions:set -DnewVersion=X.Y.Z-RC-NN
for xmlFile in webapp/WEB-INF/plugins/*.xml; do
    [ -f "$xmlFile" ] || continue
    sed -i 's|<version>[^<]*</version>|<version>X.Y.Z-RC-NN</version>|' "$xmlFile"
done

# 3. Commit + tag RC
git add -A
git commit -m "release: {artifactId}-X.Y.Z-RC-NN"
git tag -fa {artifactId}-X.Y.Z-RC-NN -m "Release Candidate {artifactId} X.Y.Z-RC-NN"

# 4. Deploy depuis develop (PAS de merge sur master)
mvn clean deploy -P release

# 5. Restaurer la version SNAPSHOT d'origine (pas d'incrément)
mvn versions:set -DnewVersion=X.Y.Z-SNAPSHOT
for xmlFile in webapp/WEB-INF/plugins/*.xml; do
    [ -f "$xmlFile" ] || continue
    sed -i 's|<version>[^<]*</version>|<version>X.Y.Z-SNAPSHOT</version>|' "$xmlFile"
done
git add -A
git commit -m "chore: restore SNAPSHOT after RC {artifactId}-X.Y.Z-RC-NN"
git push origin develop --tags
```

### Validation SNAPSHOT (gate de sécurité)

Avant de releaser les starters, la pipeline vérifie qu'**aucune dépendance
SNAPSHOT ne subsiste** dans le `pom.xml` racine (hors properties structurelles
comme `revision`). Si des violations sont détectées, le pipeline passe en
état `UNSTABLE` et les starters ne seront pas déployés.

### Rollback automatique

Grace au pattern **prepare / perform / promote**, la branche `master`
n'est **jamais touchee avant le succes du deploy Nexus**. Le rollback
ne necessite donc **jamais** de `git reset --hard` ni de `git push --force`.

En cas d'echec lors de la release d'un plugin :

```bash
# 1. Revert du commit de release sur develop (pas de force push)
git checkout develop
git revert HEAD --no-edit
git push origin develop

# 2. Suppression du tag (local + remote)
git tag -d {artifactId}-X.Y.Z
git push origin :refs/tags/{artifactId}-X.Y.Z
```

> **Note :** master n'est pas concerne car le merge n'a lieu qu'apres
> le succes du deploy (phase promote).

Si le rollback echoue, une **intervention manuelle** est necessaire.
Les details figurent dans le rapport archive (`release-report.txt`).

## 🔗 Convention de nommage des dépôts GitHub

Le nom du dépôt GitHub suit le pattern `lutece-{catégorie}-{artifactId}`.
L'artifactId est le **suffixe** du nom du dépôt :

| artifactId (POM)              | Dépôt GitHub                              |
|-------------------------------|-------------------------------------------|
| `plugin-forms`                | `lutece-form-plugin-forms`                |
| `module-workflow-forms`       | `lutece-wf-module-workflow-forms`         |
| `library-lucene`              | `lutece-search-library-lucene`            |
| `lutece-core`                 | `lutece-core`                             |

La pipeline utilise l'**API Search GitHub** pour résoudre le dépôt :
```
GET /search/repositories?q=org:{org}+{artifactId}+in:name
→ filtre : repo.name == artifactId OU repo.name se termine par "-{artifactId}"
```

**Cas spéciaux (REPO_OVERRIDES) :** certains dépôts ne suivent pas la
convention standard :

| artifactId POM                       | Dépôt réel                              |
|--------------------------------------|-----------------------------------------|
| `plugin-modulenotifygrumappingmanager` | `lutece-secteur-public/gru-module-notifygru-mapping-manager` |
| `plugin-galleryimage`                | `lutece-platform/lutece-tech-plugin-image-gallery` |
| `library-sql-utils`                  | `lutece-platform/lutece-build-library-sqlutils` |

## 🏭 Pipeline Jenkins — Exigences

### Pipeline souhaitée

- **Release sélective** : pouvoir choisir quel(s) starter(s) releaser
- **Résolution automatique des dépendances** : identifier et releaser d'abord les composants SNAPSHOT d'un starter
- **Multi-dépôt** : le pipeline doit pouvoir cloner et releaser des composants depuis les deux organisations GitHub
- **Paramétrable** : version de release, version SNAPSHOT suivante, dry-run mode
- **Release Candidates** : possibilité de créer des RC avant la release stable (optionnel)
- **Parallélisme par batch** : plugins releasés en batches parallèles de 5
- **Validation SNAPSHOT** : gate de sécurité avant release des starters
- **Rollback automatique** : revert des commits et suppression des tags en cas d'échec

### Variables d'environnement attendues

```groovy
GITHUB_TOKEN          // Token d'accès aux deux organisations
MAVEN_SETTINGS_XML    // Settings Maven avec credentials Nexus/registry
JAVA_HOME             // JDK 17
```

### Jenkinsfile — Conventions

- Pipeline **déclaratif** (pas scripted)
- Utiliser `mvn versions:set` (PAS le maven-release-plugin)
- `sed` pour les mises à jour en masse des properties POM (90+ properties)
- Stages clairement nommés en français ou anglais (cohérent)
- Gestion des erreurs avec rollback (revert des commits si le deploy échoue)
- `DRY_RUN = true` par défaut pour empêcher les releases accidentelles
- Notifications (Slack/email) en cas de succès ou échec

## 📋 Conventions de code

- **Commits** : format conventionnel — `type: description` (ex: `release: v3.2.0`, `chore: update dependencies`)
- **Tags** : format `{artifactId}-{version}` (ex: `lutece-core-8.0.0`, `forms-starter-3.2.0`)
- **Versioning** : SemVer (MAJOR.MINOR.PATCH)
- **POM** : toujours utiliser `${project.version}` pour les modules internes, jamais de versions en dur

## ⚠️ Points d'attention

1. **Ne jamais releaser un starter si un de ses composants est encore en SNAPSHOT**
2. **Ne jamais releaser le lutece-starter si les 3 starters spécialisés sont encore en SNAPSHOT**
3. **Le POM parent doit être mis à jour avec les versions release avant la release des starters**
4. **Les starters NE référencent PAS le BOM** — chaque starter gère ses propres versions pour plus de souplesse
5. **Le BOM est releasé en dernier** — il reflète l'état final des versions après toutes les releases
6. **Certains plugins existent sur les deux organisations** — toujours vérifier le bon dépôt source
7. **Les tests doivent passer avant toute release de plugin** (`mvn lutece:exploded antrun:run -Dlutece-test-hsql test`) — uniquement pour les packaging `lutece-plugin` et `lutece-core`
8. **Les starters n'ont pas de tests de vérification** — ils peuvent être releasés directement après mise à jour des versions
9. **Le descripteur XML du plugin** (`webapp/WEB-INF/plugins/*.xml`) contient un tag `<version>` qui doit être synchronisé avec la version du `pom.xml` à chaque changement de version
10. **Les Release Candidates ne touchent pas master** — le deploy RC se fait depuis `develop`, et la version SNAPSHOT d'origine est restaurée après (pas d'incrément)

## 🔍 Commandes utiles

```bash
# Lister toutes les dépendances SNAPSHOT d'un module
mvn dependency:list -DincludeSnapshots=true -DexcludeTransitive=true

# Vérifier les mises à jour disponibles
mvn versions:display-dependency-updates

# Analyser l'arbre de dépendances d'un starter
mvn dependency:tree -pl forms-starter

# Build complet sans tests (vérification rapide)
mvn clean package -DskipTests

# Lancer les tests d'un plugin Lutece (avec HSQL en mémoire)
# UNIQUEMENT pour les composants avec <packaging>lutece-plugin</packaging> ou <packaging>lutece-core</packaging>
mvn lutece:exploded antrun:run -Dlutece-test-hsql test

# Les autres types de packaging (pom, jar, lutece-site...) n'ont pas ce type de test
# Les starters n'ont pas de tests de vérification
```

## 📁 Fichiers importants

| Fichier | Rôle |
|---|---|
| `pom.xml` (racine) | POM parent, versions de tous les plugins |
| `lutece-bom/pom.xml` | BOM des dépendances |
| `*/pom.xml` | POM des starters |
| `Jenkinsfile-release` | Pipeline de release |
| `RELEASE_PLAYBOOK.md` | Documentation complète du processus de release |
| `.mvn/maven.config` | Options Maven par défaut (si présent) |

# Documentation Technique - Projet Lutece Starters

## 📋 Table des matières

1. [Vue d'ensemble](#vue-densemble)
2. [Architecture du projet](#architecture-du-projet)
3. [Structure des modules](#structure-des-modules)
4. [Flux de dépendances](#flux-de-dépendances)
5. [Guide d'utilisation](#guide-dutilisation)
6. [Configuration et déploiement](#configuration-et-déploiement)

---

## 🎯 Vue d'ensemble

Le projet **Lutece Starters** est un ensemble de modules Maven pré-configurés permettant de démarrer rapidement des applications basées sur la plateforme Lutece. Il suit le pattern "starter" popularisé par Spring Boot, offrant des configurations prêtes à l'emploi pour différents cas d'usage.

### Objectifs principaux

- **Simplifier** le démarrage de nouveaux projets Lutece
- **Standardiser** les dépendances et configurations
- **Modulariser** les fonctionnalités par domaine métier
- **Centraliser** la gestion des versions

### Technologies utilisées

```mermaid
graph LR
    A[Jakarta EE] --> D[Lutece Platform]
    B[FreeMarker] --> D
    C[MicroProfile] --> D
    E[Bootstrap/jQuery] --> D
    F[Maven] --> D
```

---

## 🏗️ Architecture du projet

### Vue d'ensemble de l'architecture

```mermaid
graph TD
    A[lutece-parent<br/>POM Parent] --> B[lutece-bom<br/>Bill of Materials]
    A --> C[forms-starter<br/>Gestion de formulaires]
    A --> D[appointment-starter<br/>Gestion de RDV]
    A --> E[editorial-starter<br/>Gestion éditoriale]
    A --> F[lutece-starter<br/>Starter complet]
    
    style A fill:#f9f,stroke:#333,stroke-width:4px
    style B fill:#bbf,stroke:#333,stroke-width:2px
    style C fill:#bfb,stroke:#333,stroke-width:2px
    style D fill:#bfb,stroke:#333,stroke-width:2px
    style E fill:#bfb,stroke:#333,stroke-width:2px
    style F fill:#fbf,stroke:#333,stroke-width:2px
```

### Hiérarchie Maven

```mermaid
graph TD
    A[lutece-global-pom<br/>8.0.0-SNAPSHOT] --> B[lutece-parent<br/>8.0.0-SNAPSHOT]
    B --> C[lutece-bom<br/>8.0.0-SNAPSHOT]
    B --> D[forms-starter<br/>8.0.0-SNAPSHOT]
    B --> E[appointment-starter<br/>8.0.0-SNAPSHOT]
    B --> F[editorial-starter<br/>8.0.0-SNAPSHOT]
    B --> G[lutece-starter<br/>8.0.0-SNAPSHOT]
```

---

## 📦 Structure des modules

### 1. **lutece-bom** (Bill of Materials)

Centralise la gestion des versions pour tout l'écosystème Lutece.

```mermaid
graph LR
    A[lutece-bom] --> B[Gestion centralisée<br/>des versions]
    B --> C[lutece-core]
    B --> D[Plugins]
    B --> E[Dépendances tierces]
```

### 2. **forms-starter**

Starter pour la gestion de formulaires dynamiques.

#### Dépendances principales

```mermaid
graph TD
    A[forms-starter] --> B[lutece-core]
    A --> C[plugin-forms]
    A --> D[plugin-referencelist]
    A --> E[plugin-genericattributes]
    A --> F[plugin-filegenerator]
    A --> G[plugin-html2pdf]
    A --> H[plugin-workflow]
    
    style A fill:#bfb,stroke:#333,stroke-width:4px
```

### 3. **appointment-starter**

Starter pour la gestion de rendez-vous.

```mermaid
graph TD
    A[appointment-starter] --> B[lutece-core]
    A --> C[plugin-appointment]
    A --> D[plugin-calendar]
    A --> E[plugin-workflow]
    A --> F[plugin-notificationstore]
    
    style A fill:#bfb,stroke:#333,stroke-width:4px
```

### 4. **editorial-starter**

Starter pour la gestion de contenu éditorial.

```mermaid
graph TD
    A[editorial-starter] --> B[lutece-core]
    A --> C[plugin-html]
    A --> D[plugin-htmlpage]
    A --> E[plugin-extend]
    A --> F[plugin-document]
    A --> G[plugin-blog]
    
    style A fill:#bfb,stroke:#333,stroke-width:4px
```

### 5. **lutece-starter**

Starter complet agrégeant tous les autres starters ainsi que d'autres plugins.

```mermaid
graph TD
    A[lutece-starter] --> B[forms-starter]
    A --> C[appointment-starter]
    A --> D[editorial-starter]
    A --> E[plugin-mylutece]
    A --> F[plugin-myluteceauthentication]
    A --> G[plugin-rest]
    A --> H[Autres plugins...]
    
    style A fill:#fbf,stroke:#333,stroke-width:4px
```

---

## 🔄 Flux de dépendances

### Architecture en couches

```mermaid
graph TB
    subgraph "Couche Application"
        A1[Application Lutece]
    end
    
    subgraph "Couche Starters"
        B1[forms-starter]
        B2[appointment-starter]
        B3[editorial-starter]
        B4[lutece-starter]
    end
    
    subgraph "Couche Plugins"
        C1[plugin-forms]
        C2[plugin-appointment]
        C3[plugin-html]
        C4[plugin-workflow]
        C5[Autres plugins...]
    end
    
    subgraph "Couche Core"
        D1[lutece-core]
        D2[lutece-bom]
    end
    
    A1 --> B1
    A1 --> B2
    A1 --> B3
    A1 --> B4
    
    B1 --> C1
    B1 --> C4
    B2 --> C2
    B2 --> C4
    B3 --> C3
    B4 --> B1
    B4 --> B2
    B4 --> B3
    B4 --> C5
    
    C1 --> D1
    C2 --> D1
    C3 --> D1
    C4 --> D1
    C5 --> D1
    
    B1 --> D2
    B2 --> D2
    B3 --> D2
    B4 --> D2
```

### Gestion des versions

```mermaid
graph LR
    A[lutece-bom] --> |Définit les versions| B[Starters]
    B --> |Héritent des versions| C[Applications]
    C --> |Peuvent surcharger| D[Versions spécifiques]
    
    style A fill:#bbf,stroke:#333,stroke-width:4px
```

---

## 📘 Guide d'utilisation

### 1. Créer un nouveau projet Lutece

```bash
# Cloner le projet starters
git clone [repository-url]

# Choisir votre starter
cd lutece-starter  # Pour une application complète
# OU
cd forms-starter   # Pour une application de formulaires
# OU
cd appointment-starter  # Pour une application de RDV
# OU
cd editorial-starter    # Pour un CMS
```

### 2. Configuration Maven

```xml
<!-- pom.xml de votre application -->
<parent>
    <groupId>fr.paris.lutece.starters</groupId>
    <artifactId>forms-starter</artifactId>
    <version>8.0.0-SNAPSHOT</version>
</parent>

<properties>
    <!-- Surcharger les versions si nécessaire -->
    <lutece.plugin-forms.version>1.3.2</lutece.plugin-forms.version>
</properties>
```

### 3. Processus de build

## 🚀 Configuration et déploiement

### Workflow de développement

### Architecture de déploiement

## 🚀 Bonnes pratiques

### 1. Choix du starter

| Cas d'usage | Starter recommandé | Plugins inclus |
|-------------|-------------------|----------------|
| Formulaires en ligne | forms-starter | forms, genericattributes, workflow |
| Prise de RDV | appointment-starter | appointment, calendar |
| Site éditorial | editorial-starter | html, htmlpage, extend |
| Application complète | lutece-starter | Tous les plugins |

### 2. Gestion des versions

- **Toujours** utiliser le BOM pour la cohérence des versions
- **Surcharger** les versions uniquement si nécessaire
- **Tester** la compatibilité avant mise à jour

### 3. Flux de développement

```mermaid
graph LR
    A[Choisir starter] --> B[Configurer pom.xml]
    B --> C[Développer]
    C --> D[Tester localement]
    D --> E[Builder WAR]
    E --> F[Déployer]
    
    style A fill:#bfb,stroke:#333,stroke-width:2px
    style F fill:#bbf,stroke:#333,stroke-width:2px
```

---

## 📝 Conclusion

Le projet Lutece Starters offre une approche moderne et modulaire pour démarrer rapidement des applications Lutece. En suivant les patterns établis et en utilisant les starters appropriés, les équipes de développement peuvent se concentrer sur la logique métier plutôt que sur la configuration technique.

### Points clés à retenir

1. **Modularité** : Choisissez le starter adapté à vos besoins
2. **Standardisation** : Suivez les conventions Lutece
3. **Évolutivité** : Ajoutez des plugins selon les besoins
4. **Maintenabilité** : Utilisez le BOM pour gérer les versions

### Ressources supplémentaires

- [Documentation Lutece officielle](https://lutece.paris.fr)
- [Wiki Architecture](https://dev.lutece.paris.fr/gitlab/archi/comite-achitecture/-/wikis/home)
- [Référentiel des plugins](https://dev.lutece.paris.fr/plugins)
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
    E[Bootstrap] --> D
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
    A --> G[plugin-workflow]
    A --> H[autres plugins...]
    
    
    style A fill:#bfb,stroke:#333,stroke-width:4px
```

### 3. **appointment-starter**

Starter pour la gestion de rendez-vous.

```mermaid
graph TD
    A[appointment-starter] --> B[lutece-core]
    A --> C[plugin-appointment]
    A --> D[plugin-genericattributes]
    A --> E[plugin-workflow]
    A --> F[plugin-notificationstore]
    A --> G[plugin-workflow]
    A --> H[autres plugins...]
    
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
    A --> H[autres plugins...]
    
    
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
    A --> F[module-mylutece-database]
    A --> G[plugin-rest]
    A --> H[Autres plugins...]
    
    style A fill:#fbf,stroke:#333,stroke-width:4px
```

---

## 🔄 Flux de dépendances

### Architecture en couches

```mermaid
graph TB
    subgraph APP["Couche Application"]
        A1["Application Lutece"]
    end
    
    subgraph STARTERS["Couche Starters & BOM"]
        B1[forms-starter]
        B2[appointment-starter]
        B3[editorial-starter]
        B4[lutece-starter]
        B5[lutece-bom]
    end
    
    subgraph PLUGINS ["Couche Plugins"]
        C1[plugin-forms]
        C2[plugin-appointment]
        C3[plugin-html]
        C4[plugin-workflow]
        C5[Autres plugins...]
    end
    
    subgraph CORE ["Couche Core"]
        D1[lutece-core]

    end
    
    A1 --> B1
    A1 --> B2
    A1 --> B3
    A1 --> B4
    A1 --> B5
    
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
    
    B5 --> C1 
    B5 --> C2
    B5 --> C4
    B5 --> C3
    B5 --> C5  

```
### Lutece palte-form

```mermaid
graph TB
    subgraph "Lutece Platform Starters"
        
        subgraph "lutece-starter"
            A[lutece-starter<br/>Complete Application Platform]
            
            subgraph "Core & Authentication"
                A1[lutece-core]
                A2[plugin-mylutece]
                A3[module-mylutece-directory]
                A4[module-mylutece-database]
                A5[plugin-avatar]
            end
            
            subgraph "Forms Management"
                B1[plugin-forms]
                B2[plugin-workflow]
                B3[module-workflow-forms]
                B4[module-forms-documentproducer]
                B5[plugin-genericattributes]
                B6[plugin-unittree]
                B7[module-unittree-forms]
            end
            
            subgraph "Appointment & Calendar"
                C1[plugin-appointment]
                C2[module-appointment-solr]
                C3[module-appointment-alert]
                C4[module-appointment-management]
                C5[module-appointment-desk]
            end
            
            subgraph "Content Management"
                D1[plugin-html]
                D2[plugin-htmlpage]
                D3[plugin-extend]
                D4[module-htmlpage-extend]
            end
            
            subgraph "Document & Files"
                E1[plugin-document]
                E2[plugin-filegenerator]
                E3[library-lucene]
            end
            
            subgraph "Search & Indexing"
                F1[plugin-solr]
                F2[library-elastic]
                F3[plugin-elasticdata]
                F4[module-forms-solr]
            end
            
            subgraph "Utilities & Libraries"
                G1[library-jwt]
                G2[library-httpaccess]
                G3[library-signrequest]
                G4[library-utils]
                G5[library-image]
                G6[library-freemarker]
            end
        end
        
        subgraph "forms-starter"
            H[forms-starter<br/>]
            H --> B1
            H --> B2
            H --> B3
            H --> B4
            H --> B5
            H --> B6
            H --> B7
            H --> F1
            H --> F4
            H --> A1
            H --> E2


        end
        
        subgraph "appointment-starter"
            I[appointment-starter<br/>]
            I --> C1
            I --> C2
            I --> C3
            I --> C4
            I --> C5
            I --> B2
            I --> B5
            I --> F1
            I --> A1

        end
        
        subgraph "editorial-starter"
            J[editorial-starter<br/>]
            J --> D1
            J --> D2
            J --> D3
            J --> D4
            J --> E1
            J --> A1
        end
        
        %% Styling
        classDef starter fill:#4fc3f7,stroke:#01579b,stroke-width:3px,color:#fff
        classDef core fill:#81c784,stroke:#2e7d32,stroke-width:2px
        classDef forms fill:#ffb74d,stroke:#e65100,stroke-width:2px
        classDef appointment fill:#f06292,stroke:#c2185b,stroke-width:2px
        classDef content fill:#ba68c8,stroke:#6a1b9a,stroke-width:2px
        classDef document fill:#64b5f6,stroke:#0277bd,stroke-width:2px
        classDef search fill:#4db6ac,stroke:#00695c,stroke-width:2px
        classDef utils fill:#90a4ae,stroke:#37474f,stroke-width:2px
        
        class A,H,I,J starter
        class A1,A2,A3,A4,A5 core
        class B1,B2,B3,B4,B5,B6,B7 forms
        class C1,C2,C3,C4,C5 appointment
        class D1,D2,D3,D4 content
        class E1,E2,E3 document
        class F1,F2,F3,F4 search
        class G1,G2,G3,G4,G5,G6 utils
    end
```
### Gestion des versions

```mermaid
graph LR
    A[pom-parent] --> |Définit les versions| B[Starters/BOM]
    B --> |Héritent des versions| C[Applications]
    C --> |Peuvent surcharger| D[Versions spécifiques]
    
    style A fill:#bbf,stroke:#333,stroke-width:4px
```

---

## 📘 Guide d'utilisation

### 1. Créer un nouveau projet Lutece

```bash
# Créer le pom du site
pom.xml

# Choisir votre starter
forms-starter  # Pour une application de gestion de formulaire dynamique
ou appointment-starter  # Pour une application de gestion de rdvs
ou  editorial-starter  # Pour une application de gestion de contenu
ou  lutece-starter  # Pour une application complète
ou lutece-bom # Pour choisir les plugins qui vous intéresse
#Builder le site en mode dev pour surcharger le FO
mvn liberty:dev

```

### 2. Configuration Maven

Exemple d'un pom de site forms pour la gestion de formulaires dynamiques.

```xml
<!-- pom.xml de votre application -->
<parent>
        <artifactId>lutece-site-pom</artifactId>
        <groupId>fr.paris.lutece.tools</groupId>
        <version>8.0.0</version>
</parent>
 
 <modelVersion>4.0.0</modelVersion>
 <groupId>fr.paris.lutece</groupId>
 <artifactId>lutece-site-forms</artifactId>
 <packaging>lutece-site</packaging>
 <name>Site Lutece</name>
 <version>1.0.0</version>
 <description></description>
 
<repositories>
       <repository>
           <releases>
               <enabled>true</enabled>
           </releases>
           <snapshots>
               <enabled>false</enabled>
           </snapshots>
           <id>lutece</id>
           <name>luteceRepository</name>
           <url>https://dev.lutece.paris.fr/maven_repository</url>
           <layout>default</layout>
       </repository>
</repositories>
<dependencyManagement>
      <dependencies>
            <!-- Lutece BOM -->
		<dependency>
			<groupId>fr.paris.lutece.starters</groupId>
			<artifactId>lutece-bom</artifactId>
			<version>8.0.0-SNAPSHOT</version>
			<scope>import</scope>
			<type>pom</type>
		</dependency>
	</dependencies>
</dependencyManagement>
<dependencies>
	<dependency>
	    <groupId>fr.paris.lutece.starters</groupId>
	    <artifactId>forms-starter</artifactId>
	    <version>8.0.0-SNAPSHOT</version>
	</dependency>
	<!--Dépendance non importée par le starter forms-starter -->
	<dependency>
	    <groupId>fr.paris.lutece.plugins</groupId>
	    <artifactId>module-mylutece-database</artifactId>
	    <type>lutece-plugin</type>			
	</dependency>
</dependencies>
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
| Formulaires en ligne | forms-starter | forms, genericattributes, workflow ... |
| Prise de RDV | appointment-starter | appointment, calendar, ... |
| Site éditorial | editorial-starter | html, htmlpage, extend, ... |
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

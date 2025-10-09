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
    Jakarta EE
    FreeMarker
    MicroProfile
    Bootstrap/jQuery
    Maven
```

---

## 🏗️ Architecture du projet

### Vue d'ensemble de l'architecture

```mermaid
    [lutece-parent (POM Parent)]  --> lutece-bom 			(Bill of Materials)
     						--> forms-starter 		(Gestion de formulaires)
     						--> appointment-starter 	(Gestion de RDV)
     						--> editorial-starter 		(Gestion éditoriale)
     						--> lutece-starter 		(Starter complet contenant tous les plugins lutece)
```

### Hiérarchie Maven

```
lutece-parent (8.0.0-SNAPSHOT)
├── Parent: lutece-global-pom (8.0.0-SNAPSHOT)
└── Modules:
    ├── lutece-bom (8.0.0-SNAPSHOT)
    ├── forms-starter (8.0.0-SNAPSHOT)
    ├── appointment-starter (8.0.0-SNAPSHOT)
    ├── editorial-starter (8.0.0-SNAPSHOT)
    └── lutece-starter (8.0.0-SNAPSHOT)
```

---

## 📦 Structure des modules

### 1. **lutece-bom** (Bill of Materials)

Centralise la gestion des versions pour tout l'écosystème Lutece.

### 2. **forms-starter**

Starter pour la gestion de formulaires dynamiques.

#### Dépendances principales

```
    forms-starter    
    			    lutece-core
   			     plugin-forms
  			     plugin-referencelist
    			     plugin-genericattributes
    			     plugin-filegenerator
   			     plugin-html2pdf
   			     ......
```

### 3. **appointment-starter**

Starter pour la gestion de rendez-vous.

```mermaid

    appointment-starter -->  lutece-core
   					plugin-appointment
   					.....
    
```

### 4. **editorial-starter**

Starter pour la gestion de contenu éditorial.

```
    editorial-starter --> lutece-core
   				   plugin-html
    				   plugin-htmlpage
                          plugin-extend
                          .........
```

### 5. **lutece-starter**

Starter complet agrégeant tous les autres starters ainsi que d'autre plugins.

```

    lutece-starter --> forms-starter
    				appointment-starter
    				editorial-starter
    				plugin-mylutece
    				.........
```

---

### Gestion des versions

```
   
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

### Configuration Maven

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

### Processus de build

---

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
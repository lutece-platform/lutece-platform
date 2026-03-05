/**
 * Lutece Release Pipeline — Helper Functions
 *
 * Extrait du Jenkinsfile-release pour eviter l'erreur "Method too large"
 * du moteur CPS Jenkins (limite 64 Ko de bytecode par methode JVM).
 *
 * Charge via : def helpers = load('release-helpers.groovy')
 */

/**
 * Returns the POM property name that holds the version for a given starter/BOM module.
 * Example: "forms-starter" -> "lutece.forms-starter.version"
 */
def starterVersionProperty(String starterName) {
    return "lutece.${starterName}.version"
}

/**
 * Checks whether the RELEASE_TARGET is a single module (not 'all').
 */
def isSingleModuleRelease() {
    return params.RELEASE_TARGET != 'all'
}

/**
 * Reads all 5 module version properties from root pom.xml and builds a version map.
 * Each entry contains: current (SNAPSHOT), release, next (next SNAPSHOT).
 *
 * Used by 'all' mode to handle modules with heterogeneous versions.
 *
 * @param pomContent  Root pom.xml content as String
 * @return Map keyed by module name (e.g. 'forms-starter') with version details
 */
def readModuleVersionMap(String pomContent) {
    def modules = ['forms-starter', 'appointment-starter', 'editorial-starter', 'lutece-starter', 'lutece-bom']
    def isRc = env.IS_RC == 'true'
    def rcNum = env.RC_NUM

    def versionMap = [:]
    modules.each { mod ->
        def prop = starterVersionProperty(mod)
        def matcher = pomContent =~ "<${prop}>([^<]+)</${prop}>"
        if (matcher.find()) {
            def current = matcher[0][1]
            def base = current.replace('-SNAPSHOT', '')
            def release = isRc ? "${base}-RC-${rcNum.padLeft(2, '0')}" : base
            def next = isRc ? current : computeNextSnapshot(base)
            versionMap[mod] = [
                property     : prop,
                current      : current,
                releaseVersion: release,
                next         : next
            ]
        } else {
            echo "WARNING: Could not find property <${prop}> in root pom.xml"
        }
    }
    return versionMap
}

/**
 * Returns the release version for a given module.
 *
 * For 'all' mode: looks up the module's version from the per-module version map.
 * For single-module mode: returns env.COMPUTED_RELEASE_VERSION.
 *
 * @param moduleName  Module name (e.g. 'forms-starter', 'lutece-bom')
 * @return The release version string for this module
 */
def getModuleReleaseVersion(String moduleName) {
    if (isSingleModuleRelease()) {
        return env.COMPUTED_RELEASE_VERSION
    }
    // 'all' mode: read from per-module version map
    def moduleVersions = readJSON(text: env.MODULE_VERSIONS_JSON)
    def info = moduleVersions[moduleName]
    if (info) {
        return info.releaseVersion
    }
    // Fallback (should never happen for known modules)
    echo "WARNING: No per-module version found for ${moduleName}, falling back to COMPUTED_RELEASE_VERSION"
    return env.COMPUTED_RELEASE_VERSION
}

/**
 * Computes the next SNAPSHOT version by incrementing the patch number.
 * Example: "8.0.0" -> "8.0.1-SNAPSHOT"
 */
def computeNextSnapshot(String version) {
    def parts = version.replace('-SNAPSHOT', '').split('\\.')
    if (parts.length < 3) {
        return "${version}.1-SNAPSHOT"
    }
    def patch = parts[2].toInteger() + 1
    return "${parts[0]}.${parts[1]}.${patch}-SNAPSHOT"
}

/**
 * Resolves which starters (and BOM) to release based on the target parameter.
 * Returns a comma-separated string.
 *
 * "lutece-starter" implies the 3 specialized starters must be released first.
 * "all" includes everything.
 */
def resolveStartersToRelease(String target) {
    switch (target) {
        case 'forms-starter':
            return 'forms-starter'
        case 'appointment-starter':
            return 'appointment-starter'
        case 'editorial-starter':
            return 'editorial-starter'
        case 'lutece-starter':
            return 'lutece-starter'
        case 'lutece-bom':
            return 'lutece-bom'
        case 'all':
            return 'forms-starter,appointment-starter,editorial-starter,lutece-starter,lutece-bom'
        default:
            return target
    }
}

/**
 * Parses the root pom.xml content and extracts all <lutece.*.version> properties
 * that contain a SNAPSHOT version.
 *
 * Returns a list of maps: [propertyName, artifactId, version]
 */
def parseSnapshotPlugins(String pomContent) {
    def plugins = []
    // Strip XML comments before scanning to avoid detecting commented-out properties
    def cleanContent = pomContent.replaceAll('(?s)<!--.*?-->', '')
    def matcher = cleanContent =~ '<(lutece\\.[a-zA-Z0-9._-]+\\.version)>([^<]*-SNAPSHOT)</'
    while (matcher.find()) {
        def propertyName = matcher.group(1)
        def version = matcher.group(2)
        // Derive artifactId from property name:
        // lutece.plugin-forms.version -> plugin-forms
        // lutece.library-lucene.version -> library-lucene
        // lutece.core.version -> lutece-core
        def artifactId = propertyName
            .replace('lutece.', '')
            .replace('.version', '')
        if (artifactId == 'core') {
            artifactId = 'lutece-core'
        }
        plugins.add([
            propertyName: propertyName,
            artifactId: artifactId,
            version: version
        ])
    }
    return plugins
}

/**
 * Filters the list of SNAPSHOT plugins to only those referenced by the target starters.
 *
 * Reads each starter's pom.xml and extracts which ${lutece.*.version} properties it uses,
 * then returns only the SNAPSHOT plugins that match.
 */
def filterPluginsForStarters(List allPlugins, List starters) {
    if (!starters || starters.isEmpty()) {
        return allPlugins
    }

    def referencedProperties = [] as Set

    // Map starter names to their pom files
    def starterPomMap = [
        'forms-starter'       : 'forms-starter/pom.xml',
        'appointment-starter' : 'appointment-starter/pom.xml',
        'editorial-starter'   : 'editorial-starter/pom.xml',
        'lutece-starter'      : 'lutece-starter/pom.xml',
        'lutece-bom'          : 'lutece-bom/pom.xml'
    ]

    starters.each { starter ->
        def pomPath = starterPomMap[starter]
        if (pomPath && fileExists(pomPath)) {
            def content = readFile(pomPath)
            // Extract all ${lutece.*.version} references
            def refMatcher = content =~ '\\$\\{(lutece\\.[a-zA-Z0-9._-]+\\.version)\\}'
            while (refMatcher.find()) {
                referencedProperties.add(refMatcher.group(1))
            }
        }
    }

    // If lutece-starter is in the list, it depends on the 3 specialized starters,
    // so we also need their plugins
    if (starters.contains('lutece-starter')) {
        ['forms-starter', 'appointment-starter', 'editorial-starter'].each { sub ->
            def pomPath = starterPomMap[sub]
            if (pomPath && fileExists(pomPath)) {
                def content = readFile(pomPath)
                def refMatcher = content =~ '\\$\\{(lutece\\.[a-zA-Z0-9._-]+\\.version)\\}'
                while (refMatcher.find()) {
                    referencedProperties.add(refMatcher.group(1))
                }
            }
        }
    }

    echo "Referenced properties from starters: ${referencedProperties.size()}"
    referencedProperties.each { echo "  - ${it}" }

    return allPlugins.findAll { referencedProperties.contains(it.propertyName) }
}

/**
 * Resolves the GitHub organization and repo name for a given artifactId.
 *
 * Convention Lutece : le nom du depot GitHub suit le pattern
 *   lutece-{categorie}-{artifactId}
 * Exemples :
 *   plugin-forms              -> lutece-form-plugin-forms
 *   module-workflow-forms      -> lutece-wf-module-workflow-forms
 *   library-lucene             -> lutece-tech-library-lucene
 *   lutece-core                -> lutece-core
 *
 * Certains depots ne suivent pas la convention standard (artifactId != suffixe du repo).
 * Ces cas sont geres par une table de correspondance REPO_OVERRIDES.
 *
 * Strategie de resolution :
 *   1. Verifie la table REPO_OVERRIDES
 *   2. Cherche le repo via l'API Search GitHub (nom se terminant par l'artifactId)
 *   3. Cherche dans lutece-platform, puis lutece-secteur-public
 *   4. Resout la branche de developpement
 *
 * Returns a map [org, repoName, branch] or null if not found.
 */
def resolveGitHubRepo(String artifactId) {
    // Table de correspondance pour les depots dont le nom ne suit pas
    // la convention standard lutece-{categorie}-{artifactId}
    def REPO_OVERRIDES = [
        // artifactId POM                        : [org, repoName]
        'plugin-modulenotifygrumappingmanager'    : ['lutece-secteur-public', 'gru-module-notifygru-mapping-manager'],
        'plugin-galleryimage'                     : ['lutece-platform', 'lutece-tech-plugin-image-gallery'],
        'library-sql-utils'                       : ['lutece-platform', 'lutece-build-library-sqlutils'],
        // Ajouter ici d'autres cas speciaux si necessaire
    ]

    def result = null

    withCredentials([string(credentialsId: params.GITHUB_CREDENTIAL_ID, variable: 'GITHUB_TOKEN')]) {
        // 1. Check overrides first
        if (REPO_OVERRIDES.containsKey(artifactId)) {
            def override = REPO_OVERRIDES[artifactId]
            def org = override[0]
            def repoName = override[1]
            def branch = resolveDevBranch(org, repoName)
            echo "Resolved (override): ${artifactId} -> ${org}/${repoName} (${branch})"
            result = [org: org, repoName: repoName, branch: branch]
            return
        }

        // 2. Search via GitHub API
        def orgs = [env.GITHUB_ORG_PLATFORM, env.GITHUB_ORG_PUBLIC]

        for (org in orgs) {
            env.SEARCH_ORG = org
            env.SEARCH_ARTIFACT = artifactId
            def searchJson = sh(
                script: 'curl -s \
                    -H "Authorization: token ${GITHUB_TOKEN}" \
                    -H "Accept: application/vnd.github.v3+json" \
                    "https://api.github.com/search/repositories?q=org:${SEARCH_ORG}+${SEARCH_ARTIFACT}+in:name&per_page=10"',
                returnStdout: true
            ).trim()

            def searchResult = readJSON(text: searchJson)
            if (searchResult.total_count > 0) {
                def match = searchResult.items.find { repo ->
                    repo.name == artifactId || repo.name.endsWith("-${artifactId}")
                }
                if (match) {
                    def branch = resolveDevBranch(org, match.name)
                    echo "Resolved: ${artifactId} -> ${org}/${match.name} (${branch})"
                    result = [org: org, repoName: match.name, branch: branch]
                    return
                }
            }
        }

        echo "WARNING: Repository not found for artifactId: ${artifactId}"
    }

    return result
}

/**
 * Resolves the development branch for a repo.
 * Priority: develop > develop_core8 > develop8 > develop8.x > main > master
 */
def resolveDevBranch(String org, String repoName) {
    // Note: cette fonction est appelee depuis resolveGitHubRepo
    // qui fournit deja le contexte withCredentials(GITHUB_TOKEN)
    env.BRANCH_ORG = org
    env.BRANCH_REPO = repoName
    def branchesJson = sh(
        script: 'curl -s \
            -H "Authorization: token ${GITHUB_TOKEN}" \
            "https://api.github.com/repos/${BRANCH_ORG}/${BRANCH_REPO}/branches?per_page=100"',
        returnStdout: true
    ).trim()

    def branches = readJSON(text: branchesJson)
    def branchNames = branches.collect { it.name }

    def priorities = ['develop', 'develop_core8', 'develop8', 'develop8.x']
    for (b in priorities) {
        if (branchNames.contains(b)) {
            return b
        }
    }
    return 'master'
}

/**
 * Performs the full release workflow for a single plugin:
 *
 * Stable release:
 *   1. Clone develop -> 2. Tests -> 3. Version set -> 4. Commit+tag
 *   5. Merge to master, push, deploy -> 6. Next SNAPSHOT on develop
 *
 * RC release:
 *   1. Clone develop -> 2. Tests -> 3. Version set (X.Y.Z-RCn) -> 4. Commit+tag
 *   5. Deploy from develop (NO merge to master) -> 6. Restore SNAPSHOT on develop
 */
def releasePlugin(Map plugin) {
    def workDir = "${env.PLUGIN_WORK_DIR}/${plugin.artifactId}"
    def tagName = "${plugin.artifactId}-${plugin.releaseVersion}"
    def isRc = env.IS_RC == 'true'

    echo "=== Releasing ${plugin.artifactId} : ${plugin.currentVersion} -> ${plugin.releaseVersion}${isRc ? ' (RC)' : ''} ==="

    if (params.DRY_RUN) {
        echo "[DRY-RUN] Would release ${plugin.artifactId}"
        echo "[DRY-RUN]   Clone ${plugin.org}/${plugin.repoName} (${plugin.branch})"
        echo "[DRY-RUN]   Test, set version ${plugin.releaseVersion}, update plugin XML descriptor, tag ${tagName}"
        if (isRc) {
            echo "[DRY-RUN]   RC: deploy from develop (no merge to master)"
            echo "[DRY-RUN]   RC: restore SNAPSHOT ${plugin.nextSnapshot}"
        } else {
            echo "[DRY-RUN]   Merge to master, deploy, next SNAPSHOT ${plugin.nextSnapshot}"
        }
        return
    }

    withCredentials([string(credentialsId: params.GITHUB_CREDENTIAL_ID, variable: 'GITHUB_TOKEN')]) {
    dir(workDir) {
        // 1. Clone
        env.PLUGIN_BRANCH = plugin.branch
        env.PLUGIN_ORG = plugin.org
        env.PLUGIN_REPO = plugin.repoName
        sh 'git clone -b ${PLUGIN_BRANCH} --single-branch https://${GITHUB_TOKEN}@github.com/${PLUGIN_ORG}/${PLUGIN_REPO}.git .'
        env.GIT_AUTHOR_EMAIL = params.GIT_USER_EMAIL
        env.GIT_AUTHOR_NAME = params.GIT_USER_NAME
        sh 'git config user.email "${GIT_AUTHOR_EMAIL}"'
        sh 'git config user.name "${GIT_AUTHOR_NAME}"'

        // 2. Check packaging and run tests
        if (!params.SKIP_TESTS) {
            def pluginPom = readFile('pom.xml')
            def packagingMatch = pluginPom =~ '<packaging>([^<]+)</packaging>'
            def packaging = packagingMatch ? packagingMatch[0][1] : 'jar'

            if (packaging in ['lutece-plugin', 'lutece-core']) {
                echo "Running tests for ${plugin.artifactId} (packaging: ${packaging})..."
                sh "mvn -s ${env.MAVEN_SETTINGS_XML} lutece:exploded antrun:run -Dlutece-test-hsql test"
            } else {
                echo "Skipping tests for ${plugin.artifactId} (packaging: ${packaging})"
            }
        }

        // 3. Set release version
        sh "mvn -s ${env.MAVEN_SETTINGS_XML} versions:set -DnewVersion=${plugin.releaseVersion} -DgenerateBackupPoms=false"

        // 3b. Update <version> in plugin XML descriptor (webapp/WEB-INF/plugins/*.xml)
        sh """
            for xmlFile in webapp/WEB-INF/plugins/*.xml; do
                [ -f "\$xmlFile" ] || continue
                sed -i 's|<version>[^<]*</version>|<version>${plugin.releaseVersion}</version>|' "\$xmlFile"
                echo "Updated version in \$xmlFile"
            done
        """

        // 4. Commit + tag
        sh """
            git add -A
            git commit -m "release: ${tagName}"
            git tag -fa ${tagName} -m "Release ${plugin.artifactId} ${plugin.releaseVersion}"
        """

        // -- Phase 1: prepare (push branch + tag) --
        sh "git push origin ${plugin.branch} --tags"

        // -- Phase 2: perform (deploy to Nexus) --
        sh "mvn -s ${env.MAVEN_SETTINGS_XML} clean deploy -DskipTests -DperformRelease=true"

        if (isRc) {
            // -- Phase 3-RC: restore SNAPSHOT on develop --
            sh """
                mvn -s ${env.MAVEN_SETTINGS_XML} versions:set -DnewVersion=${plugin.nextSnapshot} -DgenerateBackupPoms=false
                for xmlFile in webapp/WEB-INF/plugins/*.xml; do
                    [ -f "\$xmlFile" ] || continue
                    sed -i 's|<version>[^<]*</version>|<version>${plugin.nextSnapshot}</version>|' "\$xmlFile"
                done
                git add -A
                git commit -m "chore: restore SNAPSHOT after RC ${plugin.artifactId}-${plugin.releaseVersion}"
                git push origin ${plugin.branch}
            """
        } else {
            // -- Phase 3: promote (merge to master) --
            sh """
                git checkout master || git checkout -b master origin/master
                git merge ${plugin.branch} -m "Merge ${plugin.branch} for release ${tagName}"
                git push origin master
            """

            // -- Phase 4: next SNAPSHOT on develop --
            sh """
                git checkout ${plugin.branch}
                mvn -s ${env.MAVEN_SETTINGS_XML} versions:set -DnewVersion=${plugin.nextSnapshot} -DgenerateBackupPoms=false
                for xmlFile in webapp/WEB-INF/plugins/*.xml; do
                    [ -f "\$xmlFile" ] || continue
                    sed -i 's|<version>[^<]*</version>|<version>${plugin.nextSnapshot}</version>|' "\$xmlFile"
                done
                git add -A
                git commit -m "chore: prepare next development iteration ${plugin.artifactId}-${plugin.nextSnapshot}"
                git push origin ${plugin.branch}
            """
        }
    }
    } // withCredentials

    echo "Released ${plugin.artifactId} ${plugin.releaseVersion} successfully."
}

/**
 * Rollback a failed plugin release:
 * - Reset develop to pre-release state (undo release commit)
 * - Delete the release tag (local + remote)
 *
 * Master is never touched before deploy succeeds, so no master rollback needed.
 */
def rollbackPlugin(Map plugin) {
    def workDir = "${env.PLUGIN_WORK_DIR}/${plugin.artifactId}"
    def tagName = "${plugin.artifactId}-${plugin.releaseVersion}"

    echo "Rolling back ${plugin.artifactId}..."

    if (params.DRY_RUN) {
        echo "[DRY-RUN] Would rollback ${plugin.artifactId}"
        return
    }

    dir(workDir) {
        // Reset develop (undo release version commit that was pushed)
        try {
            sh """
                git checkout ${plugin.branch}
                git revert HEAD --no-edit
                git push origin ${plugin.branch}
            """
        } catch (Throwable e) {
            echo "WARNING: Could not revert release commit on ${plugin.branch} for ${plugin.artifactId}: ${e.message}"
        }

        // Delete tag (local + remote)
        try {
            sh """
                git tag -d ${tagName} || true
                git push origin :refs/tags/${tagName} || true
            """
        } catch (Throwable e) {
            echo "WARNING: Could not delete tag ${tagName}: ${e.message}"
        }
    }
}

/**
 * Releases a starter or BOM module within this monorepo.
 *
 * Follows the mvn release:prepare + release:perform pattern:
 *   1. Tag + push to Git (prepare)
 *   2. Deploy to Nexus (perform)
 *   3. Merge to master (promote — stable only, after successful deploy)
 *
 * Master is never touched before deploy succeeds -> simple rollback (tag delete only).
 */
def releaseStarter(String starterName) {
    def moduleVersion = getModuleReleaseVersion(starterName)
    def tagName = "${starterName}-${moduleVersion}"
    def isRc = env.IS_RC == 'true'

    echo "=== Releasing ${starterName} ${moduleVersion}${isRc ? ' (RC)' : ''} ==="

    def singleModule = isSingleModuleRelease()

    if (params.DRY_RUN) {
        echo "[DRY-RUN] Would release ${starterName}"
        echo "[DRY-RUN]   Tag: ${tagName}"
        if (isRc) {
            echo "[DRY-RUN]   RC: tag + push, then deploy from develop"
        } else if (singleModule) {
            echo "[DRY-RUN]   Single module: tag + push, then deploy (no merge to master)"
        } else {
            echo "[DRY-RUN]   Stable: tag + push, then deploy, then merge to master"
        }
        return
    }

    // -- Phase 1: prepare (tag + push tag only — develop already pushed in Stage 4) --
    // Delete local tag if it exists (git fetch --tags brings back remote tags on re-runs)
    sh "git tag -d ${tagName} 2>/dev/null || true"
    sh "git tag -a ${tagName} -m \"Release ${starterName} ${moduleVersion}\""
    sh "git push origin ${tagName}"

    // -- Phase 2: perform (deploy to Nexus) --
    // Build the module and its reactor dependencies (install only), then deploy ONLY the target module.
    // Using -am with deploy would re-deploy already-published dependencies -> Nexus 400 Bad Request.
    sh "mvn -s ${env.MAVEN_SETTINGS_XML} clean install -pl ${starterName} -am -DskipTests -DperformRelease=true"
    sh "mvn -s ${env.MAVEN_SETTINGS_XML} deploy -pl ${starterName} -DskipTests -DperformRelease=true"

    // -- Phase 3: promote (merge to master — stable 'all' release only) --
    // Skip merge for single-module releases (other modules may still be in SNAPSHOT)
    if (!isRc && !singleModule) {
        sh "git checkout master"
        sh "git merge develop -m \"Merge develop for release ${tagName}\""
        sh "git push origin master"
        sh "git checkout develop"
    }

    echo "Released ${starterName} ${moduleVersion} successfully."
}

/**
 * Rolls back a failed starter release within the monorepo.
 *
 * Master is never touched before deploy succeeds, so rollback is simple:
 * - Delete the git tag (local + remote)
 * - Ensure we are back on the develop branch
 */
def rollbackStarter(String starterName) {
    def moduleVersion = getModuleReleaseVersion(starterName)
    def tagName = "${starterName}-${moduleVersion}"

    echo "=== Rolling back ${starterName} ==="

    if (params.DRY_RUN) {
        echo "[DRY-RUN] Would rollback ${starterName}"
        return
    }

    // Delete tag (local + remote)
    try {
        sh """
            git tag -d ${tagName} || true
            git push origin :refs/tags/${tagName} || true
        """
    } catch (Throwable e) {
        echo "WARNING: Could not delete tag ${tagName}: ${e.message}"
    }

    // Ensure we are back on develop
    try {
        sh "git checkout develop"
    } catch (Throwable e) {
        echo "WARNING: Could not checkout develop: ${e.message}"
    }
}

/**
 * Appends a line to the release report file.
 */
def appendReport(String line) {
    def safe = line.replace("'", "'\\''")
    sh "printf '%s\\n' '${safe}' >> ${env.RELEASE_REPORT}"
}

/**
 * Generates and returns the full release report content.
 */
def generateReleaseReport() {
    return readFile(env.RELEASE_REPORT)
}

// Required: return 'this' so that load() can assign the script to a variable
return this

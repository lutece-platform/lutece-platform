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
    // Monorepo module version properties — these are NOT external plugins
    def monorepoProperties = [
        'lutece.forms-starter.version',
        'lutece.appointment-starter.version',
        'lutece.editorial-starter.version',
        'lutece.lutece-starter.version',
        'lutece.lutece-bom.version'
    ] as Set
    def matcher = cleanContent =~ '<(lutece\\.[a-zA-Z0-9._-]+\\.version)>([^<]*-SNAPSHOT)</'
    while (matcher.find()) {
        def propertyName = matcher.group(1)
        def version = matcher.group(2)
        // Skip monorepo module properties — they have their own release lifecycle (Stages 6-8)
        if (monorepoProperties.contains(propertyName)) {
            continue
        }
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

// ========================================================================
// Stage body methods — extracted from Jenkinsfile to avoid CPS
// "Method too large" (64 KB JVM bytecode limit per method).
// Each method encapsulates the logic of one pipeline stage.
// ========================================================================

/**
 * Stage 0 — Initialize: configure git, compute versions, create report.
 */
def stageInitialize() {
    env.IS_RC = "${params.RC_BUILD}"
    env.RC_NUM = params.RC_NUMBER?.trim() ?: '1'

    echo "=========================================="
    echo " Lutece Release Pipeline"
    echo " Target  : ${params.RELEASE_TARGET}"
    echo " Dry-Run : ${params.DRY_RUN}"
    echo " RC Build: ${env.IS_RC}${env.IS_RC == 'true' ? ' (RC' + env.RC_NUM + ')' : ''}"
    echo "=========================================="

    configFileProvider([configFile(fileId: params.MAVEN_SETTINGS_ID, variable: 'MVN_SETTINGS_TMP')]) {
        sh "cp \${MVN_SETTINGS_TMP} ${WORKSPACE}/maven-settings.xml"
    }
    env.MAVEN_SETTINGS_XML = "${WORKSPACE}/maven-settings.xml"
    echo "Maven settings provisioned: ${env.MAVEN_SETTINGS_XML}"

    sh "git config user.email '${params.GIT_USER_EMAIL}'"
    sh "git config user.name '${params.GIT_USER_NAME}'"
    sh "git checkout -B develop"

    withCredentials([string(credentialsId: params.GITHUB_CREDENTIAL_ID, variable: 'GITHUB_TOKEN')]) {
        sh 'git remote set-url origin https://$GITHUB_TOKEN@github.com/lutece-platform/lutece.git'
    }

    def pomContent = readFile('pom.xml')
    def currentVersion
    if (isSingleModuleRelease()) {
        def versionProp = starterVersionProperty(params.RELEASE_TARGET)
        def propMatcher = pomContent =~ "<${versionProp}>([^<]+)</${versionProp}>"
        if (propMatcher.find()) {
            currentVersion = propMatcher[0][1]
        } else {
            error("Could not find property <${versionProp}> in root pom.xml")
        }
        echo "Single module release: ${params.RELEASE_TARGET}"
        echo "Version property: ${versionProp}"
    } else {
        currentVersion = (pomContent =~ '<artifactId>lutece-parent</artifactId>\\s*\\n\\s*<version>([^<]+)</version>')[0][1]
        def versionMap = readModuleVersionMap(pomContent)
        writeJSON file: "${WORKSPACE}/module-versions.json", json: versionMap
        env.MODULE_VERSIONS_JSON = readFile("${WORKSPACE}/module-versions.json")
        echo "Per-module versions:"
        versionMap.each { mod, info ->
            echo "  ${mod}: ${info.current} -> ${info.releaseVersion} -> ${info.next}"
        }
    }
    echo "Current project version: ${currentVersion}"
    env.ORIGINAL_SNAPSHOT_VERSION = currentVersion

    def baseVersion
    if (params.RELEASE_VERSION?.trim()) {
        baseVersion = params.RELEASE_VERSION.trim().replaceAll('-RC-?\\d+$', '')
    } else {
        baseVersion = currentVersion.replace('-SNAPSHOT', '')
    }
    env.BASE_RELEASE_VERSION = baseVersion

    if (env.IS_RC == 'true') {
        env.COMPUTED_RELEASE_VERSION = "${baseVersion}-RC-${env.RC_NUM.padLeft(2, '0')}"
    } else {
        env.COMPUTED_RELEASE_VERSION = baseVersion
    }

    if (env.IS_RC == 'true') {
        env.COMPUTED_NEXT_SNAPSHOT = env.ORIGINAL_SNAPSHOT_VERSION
    } else if (params.NEXT_SNAPSHOT_VERSION?.trim()) {
        env.COMPUTED_NEXT_SNAPSHOT = params.NEXT_SNAPSHOT_VERSION.trim()
    } else {
        env.COMPUTED_NEXT_SNAPSHOT = computeNextSnapshot(baseVersion)
    }

    echo "Release version     : ${env.COMPUTED_RELEASE_VERSION}"
    echo "Next SNAPSHOT version: ${env.COMPUTED_NEXT_SNAPSHOT}"
    if (env.IS_RC == 'true') {
        echo "RC Mode             : RC will NOT merge to master, deploy from develop"
    }

    env.STARTERS_TO_RELEASE = resolveStartersToRelease(params.RELEASE_TARGET)
    echo "Starters to release : ${env.STARTERS_TO_RELEASE}"

    def rcLabel = env.IS_RC == 'true' ? " (Release Candidate ${env.RC_NUM})" : ''
    writeFile file: env.RELEASE_REPORT, text: """Lutece Release Report${rcLabel}
====================================
Date        : ${new Date()}
Target      : ${params.RELEASE_TARGET}
Release Ver : ${env.COMPUTED_RELEASE_VERSION}
Next SNAPSHOT: ${env.COMPUTED_NEXT_SNAPSHOT}
RC Build    : ${env.IS_RC}
Dry-Run     : ${params.DRY_RUN}
====================================

"""
    sh "mkdir -p ${env.PLUGIN_WORK_DIR}"
}

/**
 * Stage 1 — Detect SNAPSHOT plugins from root pom.xml, filter by starters and whitelist.
 */
def stageDetectSnapshotPlugins() {
    def pomContent = readFile('pom.xml')
    def allSnapshotPlugins = parseSnapshotPlugins(pomContent)
    echo "All SNAPSHOT plugins detected: ${allSnapshotPlugins.size()}"
    allSnapshotPlugins.each { echo "  - ${it.propertyName} = ${it.version}" }

    def startersList = env.STARTERS_TO_RELEASE.split(',').collect { it.trim() }.findAll { it }
    def filtered = filterPluginsForStarters(allSnapshotPlugins, startersList)

    if (params.PLUGIN_WHITELIST?.trim()) {
        def whitelist = params.PLUGIN_WHITELIST.split(',').collect { it.trim() }
        filtered = filtered.findAll { plugin ->
            whitelist.any { w -> plugin.artifactId.contains(w) || plugin.propertyName.contains(w) }
        }
        echo "After whitelist filter: ${filtered.size()} plugins"
    }

    env.SNAPSHOT_PLUGIN_COUNT = "${filtered.size()}"
    def isRc = env.IS_RC == 'true'
    def rcNum = env.RC_NUM
    def pluginList = filtered.collect { plugin ->
        def baseVer = plugin.version.replace('-SNAPSHOT', '')
        def relVer = isRc ? "${baseVer}-RC-${rcNum.padLeft(2, '0')}" : baseVer
        def nextVer = isRc ? plugin.version : computeNextSnapshot(baseVer)
        [
            propertyName: plugin.propertyName,
            artifactId: plugin.artifactId,
            currentVersion: plugin.version,
            releaseVersion: relVer,
            nextSnapshot: nextVer
        ]
    }
    writeJSON file: "${WORKSPACE}/snapshot-plugins.json", json: pluginList
    env.SNAPSHOT_PLUGINS_JSON = readFile("${WORKSPACE}/snapshot-plugins.json")

    appendReport("SNAPSHOT Plugins Detected: ${filtered.size()}")
    filtered.each { appendReport("  - ${it.artifactId} : ${it.version}") }
    appendReport('')
}

/**
 * Stage 2 — Locate plugin repositories on GitHub.
 */
def stageLocatePluginRepos() {
    def plugins = readJSON(text: env.SNAPSHOT_PLUGINS_JSON)
    def resolved = []

    plugins.each { plugin ->
        def repoInfo = resolveGitHubRepo(plugin.artifactId)
        if (repoInfo) {
            plugin.org = repoInfo.org
            plugin.repoName = repoInfo.repoName
            plugin.branch = repoInfo.branch
            resolved.add(plugin)
            echo "Resolved: ${plugin.artifactId} -> ${repoInfo.org}/${repoInfo.repoName} (${repoInfo.branch})"
        } else {
            echo "WARNING: Could not resolve repo for ${plugin.artifactId}"
            appendReport("WARNING: Repo not found for ${plugin.artifactId} — skipped")
        }
    }

    writeJSON file: "${WORKSPACE}/resolved-plugins.json", json: resolved
    env.RESOLVED_PLUGINS_JSON = readFile("${WORKSPACE}/resolved-plugins.json")
    env.RESOLVED_PLUGIN_COUNT = "${resolved.size()}"
    echo "Resolved ${resolved.size()} / ${plugins.size()} plugin repos"
}

/**
 * Stage 3 — Release plugins in parallel batches of 5.
 */
def stageReleasePlugins() {
    def plugins = readJSON(text: env.RESOLVED_PLUGINS_JSON)
    def batchSize = 5
    def batches = plugins.collate(batchSize)
    def failedPlugins = []
    def releasedPlugins = []

    batches.eachWithIndex { batch, batchIndex ->
        echo "--- Batch ${batchIndex + 1} / ${batches.size()} (${batch.size()} plugins) ---"

        def parallelSteps = [:]
        batch.each { plugin ->
            parallelSteps["${plugin.artifactId}"] = {
                try {
                    releasePlugin(plugin)
                    releasedPlugins.add(plugin.artifactId)
                } catch (Throwable e) {
                    echo "FAILED: ${plugin.artifactId} — ${e.message}"
                    failedPlugins.add([artifactId: plugin.artifactId, error: e.message])
                    appendReport("FAILED: ${plugin.artifactId} — ${e.message}")
                    try {
                        rollbackPlugin(plugin)
                        appendReport("ROLLBACK OK: ${plugin.artifactId}")
                    } catch (Throwable re) {
                        appendReport("ROLLBACK FAILED: ${plugin.artifactId} — manual intervention required: ${re.message}")
                    }
                }
            }
        }
        parallel parallelSteps
    }

    appendReport("\nPlugin Release Summary:")
    appendReport("  Released : ${releasedPlugins.size()}")
    appendReport("  Failed   : ${failedPlugins.size()}")
    releasedPlugins.each { appendReport("  OK   : ${it}") }
    failedPlugins.each { appendReport("  FAIL : ${it.artifactId} — ${it.error}") }
    appendReport('')

    env.RELEASED_PLUGINS = releasedPlugins.join(',')
    env.FAILED_PLUGIN_COUNT = "${failedPlugins.size()}"

    if (failedPlugins.size() > 0) {
        unstable("${failedPlugins.size()} plugin(s) failed to release")
    }
}

/**
 * Stage 4 — Update POM parent versions (plugin properties + module/parent versions).
 */
def stageUpdatePomVersions() {
    echo "Updating root pom.xml with release versions..."
    def pomFile = 'pom.xml'
    def singleModule = isSingleModuleRelease()

    if (singleModule) {
        _updatePomSingleModule(pomFile)
    } else {
        _updatePomAllModules(pomFile)
    }

    if (singleModule) {
        appendReport("POM Updated: ${params.RELEASE_TARGET} version set to ${env.COMPUTED_RELEASE_VERSION}")
    } else {
        def moduleVersions = readJSON(text: env.MODULE_VERSIONS_JSON)
        appendReport("POM Updated: per-module versions:")
        moduleVersions.each { mod, info ->
            appendReport("  ${mod}: ${info.current} -> ${info.releaseVersion}")
        }
    }
}

/** Internal: update POM for single-module release. */
def _updatePomSingleModule(String pomFile) {
    def versionProp = starterVersionProperty(params.RELEASE_TARGET)
    echo "Single module: updating only <${versionProp}>"

    if (!params.SKIP_PLUGIN_RELEASES) {
        _sedUpdateReleasedPlugins(pomFile)
    }

    if (!params.DRY_RUN) {
        sh "sed -i 's|<${versionProp}>${env.ORIGINAL_SNAPSHOT_VERSION}</${versionProp}>|<${versionProp}>${env.COMPUTED_RELEASE_VERSION}</${versionProp}>|g' ${pomFile}"
        sh """
            git add pom.xml
            git diff --cached --quiet && echo 'Version already at ${env.COMPUTED_RELEASE_VERSION} — nothing to commit' || git commit -m "release: update ${params.RELEASE_TARGET} version to ${env.COMPUTED_RELEASE_VERSION}"
        """
        sh "git push origin develop"
    } else {
        echo "[DRY-RUN] Would update <${versionProp}> to ${env.COMPUTED_RELEASE_VERSION}"
    }
}

/** Internal: update POM for 'all' release. */
def _updatePomAllModules(String pomFile) {
    def moduleVersions = readJSON(text: env.MODULE_VERSIONS_JSON)

    if (!params.SKIP_PLUGIN_RELEASES) {
        _sedUpdateReleasedPlugins(pomFile)
    }

    if (!params.DRY_RUN) {
        sh "sed -i '/<artifactId>lutece-parent<\\/artifactId>/{n;s|<version>[^<]*</version>|<version>${env.COMPUTED_RELEASE_VERSION}</version>|}' ${pomFile}"

        moduleVersions.each { mod, info ->
            sh "sed -i 's|<${info.property}>${info.current}</${info.property}>|<${info.property}>${info.releaseVersion}</${info.property}>|g' ${pomFile}"
            echo "Updated ${info.property}: ${info.current} -> ${info.releaseVersion}"
        }

        def modules = ['lutece-bom', 'forms-starter', 'appointment-starter', 'editorial-starter', 'lutece-starter']
        modules.each { mod ->
            def modPom = "${mod}/pom.xml"
            if (fileExists(modPom)) {
                sh "sed -i '/<artifactId>lutece-parent<\\/artifactId>/{n;s|<version>[^<]*</version>|<version>${env.COMPUTED_RELEASE_VERSION}</version>|}' ${modPom}"
            }
        }

        sh """
            git add pom.xml */pom.xml
            git diff --cached --quiet && echo 'Versions already at release — nothing to commit' || git commit -m "release: update versions for release"
        """
        sh "git push origin develop"
    } else {
        echo "[DRY-RUN] Would update POM parent and per-module versions:"
        moduleVersions.each { mod, info ->
            echo "[DRY-RUN]   ${info.property}: ${info.current} -> ${info.releaseVersion}"
        }
    }
}

/** Internal: sed-update released plugin properties in root pom. */
def _sedUpdateReleasedPlugins(String pomFile) {
    def resolvedPlugins = env.RESOLVED_PLUGINS_JSON ? readJSON(text: env.RESOLVED_PLUGINS_JSON) : []
    def releasedList = env.RELEASED_PLUGINS ? env.RELEASED_PLUGINS.split(',').collect { it.trim() } : []

    resolvedPlugins.each { plugin ->
        if (releasedList.contains(plugin.artifactId)) {
            def sedExpr = "s|<${plugin.propertyName}>${plugin.currentVersion}</${plugin.propertyName}>|<${plugin.propertyName}>${plugin.releaseVersion}</${plugin.propertyName}>|g"
            if (params.DRY_RUN) {
                echo "[DRY-RUN] Would update ${plugin.propertyName}: ${plugin.currentVersion} -> ${plugin.releaseVersion}"
            } else {
                sh "sed -i '${sedExpr}' ${pomFile}"
            }
        }
    }
}

/**
 * Stage 5 — Validate no SNAPSHOT dependencies remain.
 */
def stageValidateReleaseReadiness() {
    echo "Checking for remaining SNAPSHOT dependencies..."

    def pomContent = readFile('pom.xml')
    def remainingSnapshots = parseSnapshotPlugins(pomContent)

    def ignoredProps = ['lutece.forms-starter.version',
                        'lutece.appointment-starter.version',
                        'lutece.editorial-starter.version',
                        'lutece.lutece-starter.version',
                        'lutece.lutece-bom.version']
    def violations = remainingSnapshots.findAll { !ignoredProps.contains(it.propertyName) }

    if (violations.size() > 0) {
        echo "WARNING: ${violations.size()} SNAPSHOT dependencies remain:"
        violations.each { echo "  - ${it.propertyName} = ${it.version}" }
        appendReport("\nSNAPSHOT Violations: ${violations.size()}")
        violations.each { appendReport("  - ${it.propertyName} = ${it.version}") }

        if (!params.SKIP_PLUGIN_RELEASES) {
            unstable("SNAPSHOT dependencies remain — review the report")
        }
    } else {
        echo "All plugin dependencies are in release version."
        appendReport("Validation: All dependencies are release versions.")
    }
}

/**
 * Stage 6 — Release specialized starters in parallel.
 */
def stageReleaseSpecializedStarters() {
    def starters = env.STARTERS_TO_RELEASE.split(',').collect { it.trim() }.findAll { it }
    def specializedStarters = starters.findAll { it in ['forms-starter', 'appointment-starter', 'editorial-starter'] }

    def parallelSteps = [:]
    specializedStarters.each { starter ->
        parallelSteps[starter] = {
            def starterVersion = getModuleReleaseVersion(starter)
            try {
                releaseStarter(starter)
                appendReport("Starter Released: ${starter} ${starterVersion}")
            } catch (Throwable e) {
                appendReport("FAILED Starter: ${starter} — ${e.message}")
                appendReport("  -> Si le tag existe deja : git push origin :refs/tags/${starter}-${starterVersion}")
                appendReport("  -> Si l'artefact est deja sur Nexus : supprimer manuellement depuis l'interface Nexus")
                appendReport("  -> Puis relancer le build")
                try {
                    rollbackStarter(starter)
                    appendReport("ROLLBACK OK: ${starter}")
                } catch (Throwable re) {
                    appendReport("ROLLBACK FAILED: ${starter} — manual intervention required: ${re.message}")
                }
                error("Failed to release ${starter}: ${e.message}")
            }
        }
    }
    parallel parallelSteps
}

/**
 * Stage 7 — Release lutece-starter.
 */
def stageReleaseLuteceStarter() {
    echo "Validating specialized starters are in release version before releasing lutece-starter..."

    def luteceStarterPom = readFile('lutece-starter/pom.xml')
    def starterRefs = ['forms-starter', 'appointment-starter', 'editorial-starter']
    starterRefs.each { ref ->
        if (luteceStarterPom.contains('SNAPSHOT') && luteceStarterPom.contains(ref)) {
            echo "Note: ${ref} version resolved via \${lutece.${ref}.version} parent property"
        }
    }

    def lsVersion = getModuleReleaseVersion('lutece-starter')
    try {
        releaseStarter('lutece-starter')
        appendReport("Starter Released: lutece-starter ${lsVersion}")
    } catch (Throwable e) {
        appendReport("FAILED Starter: lutece-starter — ${e.message}")
        appendReport("  -> Si le tag existe deja : git push origin :refs/tags/lutece-starter-${lsVersion}")
        appendReport("  -> Si l'artefact est deja sur Nexus : supprimer manuellement depuis l'interface Nexus")
        appendReport("  -> Puis relancer le build")
        try {
            rollbackStarter('lutece-starter')
            appendReport("ROLLBACK OK: lutece-starter")
        } catch (Throwable re) {
            appendReport("ROLLBACK FAILED: lutece-starter — manual intervention required: ${re.message}")
        }
        error("Failed to release lutece-starter: ${e.message}")
    }
}

/**
 * Stage 8 — Release lutece-bom.
 */
def stageReleaseBom() {
    def bomVersion = getModuleReleaseVersion('lutece-bom')
    try {
        releaseStarter('lutece-bom')
        appendReport("BOM Released: lutece-bom ${bomVersion}")
    } catch (Throwable e) {
        appendReport("FAILED: lutece-bom — ${e.message}")
        appendReport("  -> Si le tag existe deja : git push origin :refs/tags/lutece-bom-${bomVersion}")
        appendReport("  -> Si l'artefact est deja sur Nexus : supprimer manuellement depuis l'interface Nexus")
        appendReport("  -> Puis relancer le build")
        try {
            rollbackStarter('lutece-bom')
            appendReport("ROLLBACK OK: lutece-bom")
        } catch (Throwable re) {
            appendReport("ROLLBACK FAILED: lutece-bom — manual intervention required: ${re.message}")
        }
        error("Failed to release lutece-bom: ${e.message}")
    }
}

/**
 * Stage 9 — Prepare next SNAPSHOT versions.
 */
def stagePrepareNextSnapshot() {
    def isRc = env.IS_RC == 'true'
    def singleModule = isSingleModuleRelease()

    if (isRc) {
        echo "RC Mode: restoring original SNAPSHOT version ${env.ORIGINAL_SNAPSHOT_VERSION}"
        echo "(Plugin repos already restored individually in Stage 3)"
    } else {
        echo "Preparing next development iteration: ${env.COMPUTED_NEXT_SNAPSHOT}"
    }

    if (params.DRY_RUN) {
        _nextSnapshotDryRun(isRc, singleModule)
    } else if (singleModule) {
        _nextSnapshotSingleModule(isRc)
    } else {
        _nextSnapshotAllModules(isRc)
    }

    appendReport(isRc ?
        "\nRestored SNAPSHOT: ${env.ORIGINAL_SNAPSHOT_VERSION}" :
        "\nNext SNAPSHOT: ${env.COMPUTED_NEXT_SNAPSHOT}")
}

/** Internal: dry-run output for next snapshot. */
def _nextSnapshotDryRun(boolean isRc, boolean singleModule) {
    if (singleModule) {
        def versionProp = starterVersionProperty(params.RELEASE_TARGET)
        echo "[DRY-RUN] Would set <${versionProp}> to ${isRc ? env.ORIGINAL_SNAPSHOT_VERSION : env.COMPUTED_NEXT_SNAPSHOT}"
    } else {
        def moduleVersions = readJSON(text: env.MODULE_VERSIONS_JSON)
        echo "[DRY-RUN] Would restore per-module versions:"
        moduleVersions.each { mod, info ->
            def nextVer = isRc ? info.current : info.next
            echo "[DRY-RUN]   ${info.property}: ${info.releaseVersion} -> ${nextVer}"
        }
    }
}

/** Internal: next snapshot for single-module release. */
def _nextSnapshotSingleModule(boolean isRc) {
    def pomFile = 'pom.xml'
    def versionProp = starterVersionProperty(params.RELEASE_TARGET)
    def targetVersion = isRc ? env.ORIGINAL_SNAPSHOT_VERSION : env.COMPUTED_NEXT_SNAPSHOT

    sh "sed -i 's|<${versionProp}>${env.COMPUTED_RELEASE_VERSION}</${versionProp}>|<${versionProp}>${targetVersion}</${versionProp}>|g' ${pomFile}"

    if (env.RESOLVED_PLUGINS_JSON) {
        def resolvedPlugins = readJSON(text: env.RESOLVED_PLUGINS_JSON)
        def releasedList = env.RELEASED_PLUGINS ? env.RELEASED_PLUGINS.split(',').collect { it.trim() } : []

        resolvedPlugins.each { plugin ->
            if (releasedList.contains(plugin.artifactId)) {
                def nextVer = isRc ? plugin.currentVersion : plugin.nextSnapshot
                sh "sed -i 's|<${plugin.propertyName}>${plugin.releaseVersion}</${plugin.propertyName}>|<${plugin.propertyName}>${nextVer}</${plugin.propertyName}>|g' ${pomFile}"
            }
        }
    }

    def commitMsg = isRc ?
        "chore: restore SNAPSHOT for ${params.RELEASE_TARGET} after RC ${env.COMPUTED_RELEASE_VERSION}" :
        "chore: prepare next development iteration ${params.RELEASE_TARGET} ${targetVersion}"
    sh """
        git add pom.xml
        git diff --cached --quiet && echo 'Version already at target — nothing to commit' || git commit -m "${commitMsg}"
        git push origin develop
    """
}

/** Internal: next snapshot for 'all' release. */
def _nextSnapshotAllModules(boolean isRc) {
    def pomFile = 'pom.xml'
    def moduleVersions = readJSON(text: env.MODULE_VERSIONS_JSON)

    def parentTarget = isRc ? env.ORIGINAL_SNAPSHOT_VERSION : env.COMPUTED_NEXT_SNAPSHOT
    sh "sed -i '/<artifactId>lutece-parent<\\/artifactId>/{n;s|<version>[^<]*</version>|<version>${parentTarget}</version>|}' ${pomFile}"

    moduleVersions.each { mod, info ->
        def nextVer = isRc ? info.current : info.next
        sh "sed -i 's|<${info.property}>${info.releaseVersion}</${info.property}>|<${info.property}>${nextVer}</${info.property}>|g' ${pomFile}"
        echo "Restored ${info.property}: ${info.releaseVersion} -> ${nextVer}"
    }

    if (env.RESOLVED_PLUGINS_JSON) {
        def resolvedPlugins = readJSON(text: env.RESOLVED_PLUGINS_JSON)
        def releasedList = env.RELEASED_PLUGINS ? env.RELEASED_PLUGINS.split(',').collect { it.trim() } : []

        resolvedPlugins.each { plugin ->
            if (releasedList.contains(plugin.artifactId)) {
                def nextVer = isRc ? plugin.currentVersion : plugin.nextSnapshot
                sh "sed -i 's|<${plugin.propertyName}>${plugin.releaseVersion}</${plugin.propertyName}>|<${plugin.propertyName}>${nextVer}</${plugin.propertyName}>|g' ${pomFile}"
            }
        }
    }

    def modules = ['lutece-bom', 'forms-starter', 'appointment-starter', 'editorial-starter', 'lutece-starter']
    modules.each { mod ->
        def modPom = "${mod}/pom.xml"
        if (fileExists(modPom)) {
            sh "sed -i '/<artifactId>lutece-parent<\\/artifactId>/{n;s|<version>[^<]*</version>|<version>${parentTarget}</version>|}' ${modPom}"
        }
    }

    def commitMsg = isRc ?
        "chore: restore SNAPSHOT after RC" :
        "chore: prepare next development iteration"
    sh """
        git add pom.xml */pom.xml
        git diff --cached --quiet && echo 'Versions already at target — nothing to commit' || git commit -m "${commitMsg}"
        git push origin develop
    """
}

/**
 * Stage 10 — Generate and archive the release report.
 */
def stageReleaseReport() {
    appendReport("\n====================================")
    appendReport("Pipeline completed: ${new Date()}")
    appendReport("Status: ${currentBuild.result ?: 'SUCCESS'}")
    appendReport("====================================")

    def report = readFile(env.RELEASE_REPORT)
    echo report
}

/**
 * Post — Success notifications.
 */
def postSuccess() {
    try {
        def report = fileExists(env.RELEASE_REPORT) ? readFile(env.RELEASE_REPORT) : 'No report generated.'
        def rcInfo = env.IS_RC == 'true' ? '\nType: Release Candidate' : ''
        try {
            slackSend(
                channel: '#lutece-releases',
                color: 'good',
                message: """*Lutece Release ${env.COMPUTED_RELEASE_VERSION} — SUCCESS*
Target: ${params.RELEASE_TARGET}${rcInfo}
Dry-Run: ${params.DRY_RUN}
${params.DRY_RUN ? '(Simulation only — no changes were pushed)' : ''}
<${env.BUILD_URL}|Build #${env.BUILD_NUMBER}>"""
            )
        } catch (Throwable e) {
            echo "Slack notification skipped: ${e.message}"
        }
        try {
            emailext(
                subject: "Lutece Release ${env.COMPUTED_RELEASE_VERSION} — SUCCESS",
                body: report,
                recipientProviders: [culprits(), requestor()]
            )
        } catch (Throwable e) {
            echo "Email notification skipped: ${e.message}"
        }
    } catch (Throwable e) {
        echo "Post-success actions skipped: ${e.message}"
    }
}

/**
 * Post — Failure notifications.
 */
def postFailure() {
    try {
        def report = fileExists(env.RELEASE_REPORT) ? readFile(env.RELEASE_REPORT) : 'No report generated — pipeline failed early.'
        try {
            slackSend(
                channel: '#lutece-releases',
                color: 'danger',
                message: """*Lutece Release ${env.COMPUTED_RELEASE_VERSION ?: 'UNKNOWN'} — FAILED*
Target: ${params.RELEASE_TARGET}
Check the report for rollback actions required.
<${env.BUILD_URL}|Build #${env.BUILD_NUMBER}>"""
            )
        } catch (Throwable e) {
            echo "Slack notification skipped: ${e.message}"
        }
        try {
            emailext(
                subject: "Lutece Release ${env.COMPUTED_RELEASE_VERSION ?: 'UNKNOWN'} — FAILED",
                body: report,
                recipientProviders: [culprits(), requestor()]
            )
        } catch (Throwable e) {
            echo "Email notification skipped: ${e.message}"
        }
    } catch (Throwable e) {
        echo "Post-failure actions skipped: ${e.message}"
    }
}

// Required: return 'this' so that load() can assign the script to a variable
return this

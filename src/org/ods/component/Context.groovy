package org.ods.component

import org.ods.util.Logger
import org.ods.services.ServiceRegistry
import org.ods.services.BitbucketService
import org.ods.services.GitService
import org.ods.services.NexusService
import org.ods.services.OpenShiftService
import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonSlurperClassic
import groovy.json.JsonOutput

@SuppressWarnings('MethodCount')
class Context implements IContext {

    final List excludeFromContextDebugConfig = ['nexusPassword', 'nexusUsername']
    // script is the context of the Jenkinsfile. That means that things like "sh" need to be called on script.
    private final def script
    // config is a map of config properties to customise the behaviour.
    private final Map config
    private final Logger logger
    // artifact store, the interface to MRP
    private final def artifactUriStore = [builds: [:], deployments: [:]]

    // is the library checking out the code again, or relying on check'd out source?
    private final boolean localCheckoutEnabled

    private String appDomain

    Context(def script, Map config, Logger logger, boolean localCheckoutEnabled = true) {
        this.script = script
        this.config = config
        this.logger = logger
        this.localCheckoutEnabled = localCheckoutEnabled
    }

    @SuppressWarnings(['AbcMetric', 'CyclomaticComplexity', 'MethodSize', 'Instanceof'])
    def assemble() {
        logger.debug 'Validating input ...'
        // branchToEnvironmentMapping must be given, but it is OK to be empty - e.g.
        // if the repository should not be deployed to OpenShift at all.
        if (!config.containsKey('branchToEnvironmentMapping') ||
            !(config.branchToEnvironmentMapping instanceof Map)) {
            throw new IllegalArgumentException("Param 'branchToEnvironmentMapping, type: Map' is required")
        }

        logger.debug 'Collecting environment variables ...'
        config.jobName = script.env.JOB_NAME
        config.buildNumber = script.env.BUILD_NUMBER
        config.buildUrl = script.env.BUILD_URL
        config.buildTag = script.env.BUILD_TAG
        config.buildTime = new Date()
        config.openshiftHost = script.env.OPENSHIFT_API_URL
        config << BitbucketService.readConfigFromEnv(script.env)
        config << NexusService.readConfigFromEnv(script.env)

        config.odsBitbucketProject = script.env.ODS_BITBUCKET_PROJECT ?: 'opendevstack'

        config.globalExtensionImageLabels = getExtensionBuildParams()

        logger.debug("Got external build labels: ${config.globalExtensionImageLabels}")

        config.odsSharedLibVersion = script.sh(
            script: "env | grep 'library.ods-jenkins-shared-library.version' | cut -d= -f2",
            returnStdout: true,
            label: 'getting ODS shared lib version'
        ).trim()

        logger.debug 'Validating environment variables ...'
        if (!config.jobName) {
            throw new IllegalArgumentException('JOB_NAME is required, but not set (usually provided by Jenkins)')
        }
        if (!config.buildNumber) {
            throw new IllegalArgumentException('BUILD_NUMBER is required, but not set (usually provided by Jenkins)')
        }
        if (!config.buildTag) {
            throw new IllegalArgumentException('BUILD_TAG is required, but not set (usually provided by Jenkins)')
        }
        if (!config.openshiftHost) {
            throw new IllegalArgumentException('OPENSHIFT_API_URL is required, but not set')
        }
        if (!config.buildUrl) {
            logger.info 'BUILD_URL is required to set a proper build status in ' +
                'BitBucket, but it is not present. Normally, it is provided ' +
                'by Jenkins - please check your JenkinsUrl configuration.'
        }

        logger.debug 'Deriving configuration from input ...'
        config.openshiftProjectId = "${config.projectId}-cd"
        config.credentialsId = config.openshiftProjectId + '-cd-user-with-password'

        logger.debug 'Setting defaults ...'
        if (!config.containsKey('autoCloneEnvironmentsFromSourceMapping')) {
            config.autoCloneEnvironmentsFromSourceMapping = [:]
        }
        if (!config.containsKey('cloneProjectScriptBranch')) {
            config.cloneProjectScriptBranch = 'master'
        }
        if (config.containsKey('sonarQubeBranch')) {
            script.echo "Setting option 'sonarQubeBranch' of the pipeline is deprecated, " +
                "please use option 'branch' of the stage."
        } else {
            config.sonarQubeBranch = 'master'
        }
        if (!config.containsKey('failOnSnykScanVulnerabilities')) {
            config.failOnSnykScanVulnerabilities = true
        }
        if (!config.containsKey('environmentLimit')) {
            config.environmentLimit = 5
        }
        if (!config.containsKey('openshiftBuildTimeout')) {
            config.openshiftBuildTimeout = 15 // minutes
        }
        if (!config.containsKey('openshiftRolloutTimeout')) {
            config.openshiftRolloutTimeout = 5 // minutes
        }
        if (!config.groupId) {
            config.groupId = "org.opendevstack.${config.projectId}"
        }

        logger.debug 'Retrieving Git information ...'
        config.gitUrl = retrieveGitUrl()
        config.gitBranch = retrieveGitBranch()
        config.gitCommit = retrieveGitCommit()
        config.gitCommitAuthor = retrieveGitCommitAuthor()
        config.gitCommitMessage = retrieveGitCommitMessage()
        config.gitCommitTime = retrieveGitCommitTime()
        config.tagversion = "${config.buildNumber}-${config.gitCommit.take(8)}"

        if (!config.containsKey('testResults')) {
            config.testResults = ''
        }

        if (!config.containsKey('dockerDir')) {
            config.dockerDir = 'docker'
        }

        logger.debug 'Setting target OCP environment ...'
        determineEnvironment()
        if (config.environment) {
            config.targetProject = "${config.projectId}-${config.environment}"
        }
        // clone the map and overwrite exclusions
        Map debugConfig = new JsonSlurperClassic().
            parseText(JsonOutput.toJson(config))

        excludeFromContextDebugConfig.each { exclusion ->
            if (debugConfig[exclusion]) {
                debugConfig[exclusion] = '****'
            }
        }

        logger.debug "Assembled configuration: ${debugConfig}"
    }

    boolean getDebug() {
        config.debug
    }

    void setDebug(def debug) {
        config.debug = debug
    }

    String getJobName() {
        config.jobName
    }

    String getBuildNumber() {
        config.buildNumber
    }

    String getBuildUrl() {
        config.buildUrl
    }

    String getBuildTag() {
        config.buildTag
    }

    String getBuildTime() {
        config.buildTime
    }

    String getGitBranch() {
        config.gitBranch
    }

    @NonCPS
    String getCredentialsId() {
        config.credentialsId
    }

    String getGitUrl() {
        config.gitUrl
    }

    @NonCPS
    String getTagversion() {
        config.tagversion
    }

    String getLastSuccessfulCommit() {
        retrieveLastSuccessfulCommit()
    }

    String[] getCommittedFiles() {
        def lastSuccessfulCommit = getLastSuccessfulCommit()
        retrieveGitCommitFiles(lastSuccessfulCommit)
    }

    @NonCPS
    String getNexusUrl() {
        config.nexusUrl
    }

    @NonCPS
    String getNexusHost() {
        getNexusUrl()
    }

    @NonCPS
    String getNexusHostWithoutScheme() {
        getNexusUrl().minus(~/^https?:\/\//)
    }

    @NonCPS
    String getNexusUsername() {
        config.nexusUsername
    }

    @NonCPS
    String getNexusPassword() {
        config.nexusPassword
    }

    @NonCPS
    String getNexusUrlWithBasicAuth() {
        String un = URLEncoder.encode(config.nexusUsername as String, 'UTF-8')
        String pw = URLEncoder.encode(config.nexusPassword as String, 'UTF-8')
        config.nexusUrl.replace('://', "://${un}:${pw}@")
    }

    @NonCPS
    String getNexusHostWithBasicAuth() {
        getNexusUrlWithBasicAuth()
    }

    @NonCPS
    Map<String, String> getBranchToEnvironmentMapping() {
        config.branchToEnvironmentMapping
    }

    String getAutoCloneEnvironmentsFromSourceMapping() {
        config.autoCloneEnvironmentsFromSourceMapping
    }

    String getCloneSourceEnv() {
        config.cloneSourceEnv
    }

    void setCloneSourceEnv(String cloneSourceEnv) {
        config.cloneSourceEnv = cloneSourceEnv
    }

    String getCloneProjectScriptBranch() {
        config.cloneProjectScriptBranch
    }

    String getEnvironment() {
        config.environment
    }

    void setEnvironment(String environment) {
        config.environment = environment
    }

    @NonCPS
    String getGroupId() {
        config.groupId
    }

    @NonCPS
    String getProjectId() {
        config.projectId
    }

    @NonCPS
    String getComponentId() {
        config.componentId
    }

    @NonCPS
    String getRepoName() {
        config.repoName
    }

    String getGitCommit() {
        config.gitCommit
    }

    String getGitCommitAuthor() {
        config.gitCommitAuthor
    }

    String getGitCommitMessage() {
        config.gitCommitMessage
    }

    String getGitCommitTime() {
        config.gitCommitTime
    }

    String getTargetProject() {
        config.targetProject
    }

    @NonCPS
    String getSonarQubeBranch() {
        config.sonarQubeBranch
    }

    void setSonarQubeBranch(String sonarQubeBranch) {
        config.sonarQubeBranch = sonarQubeBranch
    }

    @NonCPS
    boolean getFailOnSnykScanVulnerabilities() {
        config.failOnSnykScanVulnerabilities
    }

    int getEnvironmentLimit() {
        config.environmentLimit
    }

    String getOpenshiftHost() {
        config.openshiftHost
    }

    String getOdsSharedLibVersion() {
        config.odsSharedLibVersion
    }

    String getBitbucketUrl() {
        config.bitbucketUrl
    }

    String getBitbucketHost() {
        getBitbucketUrl()
    }

    @NonCPS
    String getBitbucketHostWithoutScheme() {
        getBitbucketUrl().minus(~/^https?:\/\//)
    }

    @NonCPS
    Integer getOpenshiftBuildTimeout() {
        config.openshiftBuildTimeout
    }

    @NonCPS
    Integer getOpenshiftRolloutTimeout() {
        config.openshiftRolloutTimeout
    }

    String getTestResults() {
        return config.testResults
    }

    @NonCPS
    String getDockerDir() {
        return config.dockerDir
    }

    boolean environmentExists(String name) {
        def statusCode = script.sh(
            script: "oc project ${name} &> /dev/null",
            label: "check if OCP environment ${name} exists",
            returnStatus: true
        )
        return statusCode == 0
    }

    String getIssueId() {
        GitService.issueIdFromBranch(config.gitBranch, config.projectId)
    }

    // This logic must be consistent with what is described in README.md.
    // To make it easier to follow the logic, it is broken down by workflow (at
    // the cost of having some duplication).
    void determineEnvironment() {
        if (config.environment) {
            // environment already set
            return
        }
        // Fixed name
        def env = config.branchToEnvironmentMapping[config.gitBranch]
        if (env) {
            config.environment = env
            config.cloneSourceEnv = environmentExists(env)
                ? false
                : config.autoCloneEnvironmentsFromSourceMapping[env]
            return
        }

        // Prefix
        // Loop needs to be done like this due to
        // https://issues.jenkins-ci.org/browse/JENKINS-27421 and
        // https://issues.jenkins-ci.org/browse/JENKINS-35191.
        for (def key : config.branchToEnvironmentMapping.keySet()) {
            if (config.gitBranch.startsWith(key)) {
                setMostSpecificEnvironment(
                    config.branchToEnvironmentMapping[key],
                    config.gitBranch.replace(key, '')
                )
                return
            }
        }

        // Any branch
        def genericEnv = config.branchToEnvironmentMapping['*']
        if (genericEnv) {
            setMostSpecificEnvironment(
                genericEnv,
                config.gitBranch.replace('/', '')
            )
            return
        }

        logger.info 'No environment to deploy to was determined, returning..\r' +
            "[gitBranch=${config.gitBranch}, projectId=${config.projectId}]"
        config.environment = ''
        config.cloneSourceEnv = ''
    }

    Map<String, String> getCloneProjectScriptUrls() {
        def scripts = ['clone-project.sh', 'import-project.sh', 'export-project.sh',]
        def m = [:]
        def branch = getCloneProjectScriptBranch().replace('/', '%2F')
        def baseUrl = "${config.bitbucketUrl}/projects/${config.odsBitbucketProject}/repos/ods-core/raw/ocp-scripts"
        for (script in scripts) {
            def url = "${baseUrl}/${script}?at=refs%2Fheads%2F${branch}"
            m.put(script, url)
        }
        return m
    }

    Map<String, Object> getBuildArtifactURIs() {
        return artifactUriStore.asImmutable()
    }

    void addArtifactURI(String key, value) {
        artifactUriStore.put(key, value)
    }

    void addBuildToArtifactURIs (String buildConfigName, Map <String, String> buildInformation) {
        artifactUriStore.builds [buildConfigName] = buildInformation
    }

    void addDeploymentToArtifactURIs (String deploymentConfigName, Map deploymentInformation) {
        artifactUriStore.deployments [deploymentConfigName] = deploymentInformation
    }

    // get extension image labels
    @NonCPS
    Map<String, String> getExtensionImageLabels () {
        return config.globalExtensionImageLabels
    }

    // set and add image labels
    @NonCPS
    void setExtensionImageLabels (Map <String, String> extensionLabels) {
        if (extensionLabels) {
            config.globalExtensionImageLabels.putAll(extensionLabels)
        }
    }

    Map<String,String> getExtensionBuildParams () {
        String rawEnv = script.sh(
            returnStdout: true, script: 'env | grep ods.build. || true',
            label: 'getting extension environment labels'
          ).trim()

        if (rawEnv.size() == 0 ) {
            return [:]
        }

        return rawEnv.normalize().split(System.getProperty('line.separator')).inject([ : ] ) { kvMap, line ->
            Iterator kv = line.toString().tokenize('=').iterator()
            kvMap.put(kv.next(), kv.hasNext() ? kv.next() : '')
            kvMap
        }
    }

    String getOpenshiftApplicationDomain () {
        if (!config.environment) {
            return ''
        }
        if (!this.appDomain) {
            logger.startClocked("${config.componentId}-get-oc-app-domain")
            this.appDomain = ServiceRegistry.instance.get(OpenShiftService).applicationDomain
            logger.debugClocked("${config.componentId}-get-oc-app-domain")
        }
        this.appDomain
    }

    private String retrieveGitUrl() {
        def gitUrl = script.sh(
            returnStdout: true,
            script: 'git config --get remote.origin.url',
            label: 'getting GIT url'
        ).trim()
        return gitUrl
    }

    private String retrieveGitCommit() {
        script.sh(
            returnStdout: true, script: 'git rev-parse HEAD',
            label: 'getting GIT commit'
        ).trim()
    }

    private String retrieveGitCommitAuthor() {
        script.sh(
            returnStdout: true, script: "git --no-pager show -s --format='%an (%ae)' HEAD",
            label: 'getting GIT commit author'
        ).trim()
    }

    private String retrieveGitCommitMessage() {
        script.sh(
            returnStdout: true, script: 'git log -1 --pretty=%B HEAD',
            label: 'getting GIT commit message'
        ).trim()
    }

    private String retrieveLastSuccessfulCommit() {
        def lastSuccessfulBuild = script.currentBuild.rawBuild.getPreviousSuccessfulBuild()
        if (!lastSuccessfulBuild) {
            logger.info 'There seems to be no last successful build.'
            return ''
        }
        return commitHashForBuild(lastSuccessfulBuild)
    }

    private String commitHashForBuild(build) {
        return build
            .getActions(hudson.plugins.git.util.BuildData.class)
            .find { action -> action.getRemoteUrls().contains(config.gitUrl) }
            .getLastBuiltRevision().getSha1String()
    }

    private String[] retrieveGitCommitFiles(String lastSuccessfulCommitHash) {
        if (!lastSuccessfulCommitHash) {
            logger.info("Didn't find the last successful commit. Can't return the committed files.")
            return []
        }
        return script.sh(
            returnStdout: true,
            label: 'getting git commit files',
            script: "git diff-tree --no-commit-id --name-only -r ${config.gitCommit}"
        ).trim().split()
    }

    private String retrieveGitCommitTime() {
        script.sh(
            returnStdout: true,
            script: 'git show -s --format=%ci HEAD',
            label: 'getting GIT commit date/time'
        ).trim()
    }

    private String retrieveGitBranch() {
        def branch
        if (this.localCheckoutEnabled) {
            def pipelinePrefix = "${config.openshiftProjectId}/${config.openshiftProjectId}-"
            def buildConfigName = config.jobName.substring(pipelinePrefix.size())

            def n = config.openshiftProjectId
            branch = script.sh(
                returnStdout: true,
                label: 'getting GIT branch to build',
                script: "oc get bc/${buildConfigName} -n ${n} -o jsonpath='{.spec.source.git.ref}'"
            ).trim()
        } else {
            // in case code is already checked out, OpenShift build config can not be used for retrieving branch
            branch = script.sh(
                returnStdout: true,
                script: 'git rev-parse --abbrev-ref HEAD',
                label: 'getting GIT branch to build').trim()
            branch = script.sh(
                returnStdout: true,
                script: "git name-rev ${branch} | cut -d ' ' -f2  | sed -e 's|remotes/origin/||g'",
                label: 'resolving to real GIT branch to build').trim()
        }
        logger.debug "resolved branch ${branch}"
        return branch
    }

    // Based on given +genericEnv+ (e.g. "preview") and +branchSuffix+ (e.g.
    // "foo-123-bar"), it finds the most specific environment. This is either:
    // - the +genericEnv+ suffixed with a numeric ticket ID
    // - the +genericEnv+ suffixed with the +branchSuffix+
    // - the +genericEnv+ without suffix
    private void setMostSpecificEnvironment(String genericEnv, String branchSuffix) {
        def specifcEnv = genericEnv + '-' + branchSuffix

        def ticketId = GitService.issueIdFromBranch(config.gitBranch, config.projectId)
        if (ticketId) {
            specifcEnv = genericEnv + '-' + ticketId
        }

        config.cloneSourceEnv = config.autoCloneEnvironmentsFromSourceMapping[genericEnv]
        def autoCloneEnabled = !!config.cloneSourceEnv
        if (autoCloneEnabled || environmentExists(specifcEnv)) {
            config.environment = specifcEnv
        } else {
            config.environment = genericEnv
        }
    }

}

package eu.spaziodati.azkaban.jobtype

import azkaban.jobExecutor.AbstractProcessJob
import azkaban.utils.Props
import com.aestasit.infrastructure.ssh.DefaultSsh
import com.aestasit.infrastructure.ssh.ExecOptions
import com.aestasit.infrastructure.ssh.ScpOptions
import com.aestasit.infrastructure.ssh.SshOptions
import com.aestasit.infrastructure.ssh.dsl.SessionDelegate
import com.aestasit.infrastructure.ssh.dsl.SshDslEngine
import com.jcraft.jsch.JSch
import eu.spaziodati.azkaban.JobUtils
import eu.spaziodati.azkaban.Reflection
import org.apache.commons.io.FileUtils
import org.apache.log4j.Logger

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.security.SecureRandom
import java.math.BigInteger

import com.aestasit.infrastructure.ssh.DefaultSsh

class GroovyRemoteJob extends GroovyProcessJob {

    static {
        JSch.setConfig("StrictHostKeyChecking", "no")
    }

    static def cloneOptions(SshOptions opt) {
        return new SshOptions(
                defaultHost: opt.defaultHost,
                defaultKeyFile: opt.defaultKeyFile,
                defaultPassPhrase: opt.defaultPassPhrase,
                defaultPassword: opt.defaultPassword,
                defaultPort: opt.defaultPort,
                defaultUser: opt.defaultUser,
                trustUnknownHosts: opt.trustUnknownHosts,
                reuseConnection: opt.reuseConnection,
                verbose: opt.verbose,
                logger: opt.logger,
                failOnError:  opt.failOnError,
                execOptions: new ExecOptions(opt.execOptions),
                scpOptions: new ScpOptions(opt.scpOptions)
        )
    }
    static safeRemoteSession(SshOptions options, @DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = SessionDelegate) Closure cl) {
        new SshDslEngine(options).remoteSession(cl)
    }
    static sessionOptions(@DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = SshOptions) Closure cl) {
        def opts = cloneOptions(DefaultSsh.options)
        cl.delegate = opts
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        return opts
    }


    static final HOST = "groovy.remote.host"
    static final PORT = "groovy.remote.port"
    static final USERNAME = "groovy.remote.username"
    static final PASSWORD = "groovy.remote.password"
    static final KEY_FILE = "groovy.remote.keyFile"
    static final REMOTE_DIR = "groovy.remote.working.dir"
    static final VERBOSE = "groovy.remote.verbose"
    static final JAVA_INSTALLER = "groovy.remote.javaInstaller"
    static final SUDO_JAVA_INSTALLER = "groovy.remote.sudo.javaInstaller"
    static final SUDO = "groovy.remote.sudo"
    static final CLEANUP = "groovy.remote.cleanup"
    static final INIT_SCRIPT = "groovy.remote.initScript"
    static final RETRY = "groovy.remote.retry"

    static def DISCARD_LOG = ~/\d+ bytes transferred/
    static def FIRST_DELAY = 5
    static def random = new SecureRandom()

    public GroovyRemoteJob(String jobid, Props sysProps, Props jobProps, Logger log) {
        super(jobid, sysProps, new Props(sysProps, jobProps), log)
    }

    def temporaryFiles = []
    volatile Future task = null
    File outputFile = null;
    File parametersFile = null;

    def extractEmbeddedJavaInstaller() {
        def shpath = Files.createTempFile(Paths.get(workingDirectory), "java-installer", ".sh");
        getClass().getResourceAsStream("/embedded/default-java-installer.sh").withStream {
            Files.copy(it, shpath, StandardCopyOption.REPLACE_EXISTING)
        }
        temporaryFiles.add(shpath)
        return shpath.toString()
    }

    def extractEmbeddedGroovyExecutor() {
        def jarpath = Files.createTempFile(Paths.get(workingDirectory), "groovy-executor", ".jar");
        getClass().getResourceAsStream("/embedded/groovy-executor.jar").withStream {
            Files.copy(it, jarpath, StandardCopyOption.REPLACE_EXISTING)
        }
        temporaryFiles.add(jarpath)
        return jarpath.toString()
    }

    String getNextRand() {
        new BigInteger(64, random).toString(32)
    }

    @Override
    void run() {

        try {
            resolveProps()
        } catch (e) {
            throw new Exception("Bad property definition! " + e.getMessage(), e)
        }
        Props preconditionProps = JobUtils.checkPreconditions(getId(), jobProps, getLog());
        if (preconditionProps != null) {
            Reflection.set(AbstractProcessJob.class, this, "generatedProperties", preconditionProps);
            return;
        }


        def config = [:]
        try {
            config[HOST] = jobProps.getString(HOST)
            config[PORT] = jobProps.getInt(PORT, 22)
            config[USERNAME] = jobProps.getString(USERNAME)
            def defaultRemoteWorkingDir = "/tmp/azkaban-${jobProps.get('azkaban.flow.flowid')}-${getNextRand()}"
            config[REMOTE_DIR] = jobProps.getString(REMOTE_DIR, defaultRemoteWorkingDir)
            config[PASSWORD] = jobProps.getString(PASSWORD, "")
            config[KEY_FILE] = jobProps.getString(KEY_FILE, null)
            config[JAVA_INSTALLER] = jobProps.getString(JAVA_INSTALLER, null)
            config[SUDO] = jobProps.getBoolean(SUDO, false)
            config[VERBOSE] = jobProps.getBoolean(VERBOSE, false)
            config[SUDO_JAVA_INSTALLER] = jobProps.getBoolean(SUDO_JAVA_INSTALLER, true)
            config[CLEANUP] = jobProps.getBoolean(CLEANUP, true)
            config[INIT_SCRIPT] = jobProps.getString(INIT_SCRIPT, "")
            config[RETRY] = jobProps.getInt(RETRY, 5)

            if (!config[PASSWORD] && !config[KEY_FILE]) throw new Exception("No password nor key file has been defined")

            config["jar"] = extractEmbeddedGroovyExecutor()
            jarfile = Paths.get(config["jar"]).getFileName().toString()

            // create property files ( input / output )
            File[] ff = initPropsFiles()
            parametersFile = ff[0]
            outputFile = ff[1]
            manageWorkingDirectory()

        } catch (e) {
            throw new Exception("Unable to setup job: "+e.getMessage(), e)
        }

        def executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "GroovyRemoteExecutor");
                t.setDaemon(true);
                return t;
            }
        })


        try {
            task = executor.submit({

                info("Trying to connect to ${config[HOST]} ...")

                //CONFIGURATION
                // Configuration in DefaultSsh is not thread-safe!!!! it is static!
                // so we have to create on our own an SshOptions object and use that
                // to init the SshDelegate
//                trustUnknownHosts = true
//                verbose = config[VERBOSE]
//                def prefixcmd = "";
//                if (config[REMOTE_DIR] != "./") prefixcmd += "cd ${config[REMOTE_DIR]}; "
//                if (config[SUDO]) prefixcmd += "sudo "
//                execOptions { prefix = prefixcmd }
//
//                logger = new com.aestasit.infrastructure.ssh.log.Logger() {
//                    @Override void info(String message) {
//                        if (!DISCARD_LOG.matcher(message).matches() )
//                            GroovyRemoteJob.this.info("[ssh] " + message)
//                    }
//                    @Override void warn(String message) {
//                        GroovyRemoteJob.this.warn("[ssh] " + message)
//                    }
//                    @Override void debug(String message) {
//                        GroovyRemoteJob.this.debug("[ssh] " + message)
//                    }
//                }

                def options = sessionOptions {
                    trustUnknownHosts = true
                    verbose = config[VERBOSE]
                    def prefixcmd = "";
                    if (config[REMOTE_DIR] != "./") prefixcmd += "cd ${config[REMOTE_DIR]}; "
                    if (config[SUDO]) prefixcmd += "sudo "
                    execOptions { prefix = prefixcmd }
                    scpOptions { showProgress = false }
                    logger = new com.aestasit.infrastructure.ssh.log.Logger() {
                        @Override void info(String message) {
                            if (!DISCARD_LOG.matcher(message).matches() )
                                GroovyRemoteJob.this.info("[ssh] " + message)
                        }
                        @Override void warn(String message) {
                            GroovyRemoteJob.this.warn("[ssh] " + message)
                        }
                        @Override void debug(String message) {
                            GroovyRemoteJob.this.debug("[ssh] " + message)
                        }
                    }
                };

                safeRemoteSession(options) {

                    host = config[HOST]
                    port = config[PORT] as int
                    user = config[USERNAME]
                    password = config[PASSWORD]
                    if (config[KEY_FILE]) {
                        if (!Paths.get(config[KEY_FILE]).isAbsolute()) {
                            keyFile = new File(getWorkingDirectory(), (String) config[KEY_FILE])
                        } else
                            keyFile = new File(config[KEY_FILE])
                    }


                    //CONNECTION
                    def attempt = 0, maxAttempt = config[RETRY] as int, delay = FIRST_DELAY
                    if (maxAttempt <= 0) maxAttempt = 1
                    def connected = false
                    while(!connected) {
                        try {
                            attempt++
                            info("Try to connect (attempt n.${attempt})")
                            connect()
                            connected = true
                        } catch (Exception e) {
                            if (attempt >= maxAttempt) throw new RuntimeException("Unable to connect after $attempt attempt/s", e)
                            else {
                                warn("Connection attempt n.${attempt} failed: "+e)
                                info("Waiting $delay seconds...")
                                Thread.sleep(delay*1000)
                                delay *= 2
                            }
                        }
                    }


                    info("Connected")

                    info("Setting up remote environment ")

                    //init script
                    if (config[INIT_SCRIPT]) {
                        prefix("") {
                            info("Invoking init script ${config[INIT_SCRIPT]}")
                            def script = Paths.get(config[INIT_SCRIPT]).isAbsolute() ?
                                    new File(config[INIT_SCRIPT]) : new File(getWorkingDirectory(), config[INIT_SCRIPT])
                            def remoteScript = ".init-${jobProps.get('azkaban.flow.flowid')}-${jobProps.get('azkaban.flow.execid')}.sh"
                            remoteFile("./$remoteScript").text = script.getText("UTF-8")
                            exec "bash $remoteScript"
                            exec "rm $remoteScript"
                            info("Init script executed")
                        }
                    }

                    //create remote working dir
                    prefix(config[SUDO] ? "sudo " : "") {
                        exec "mkdir -p ${config[REMOTE_DIR]}"
                        if (config[SUDO])
                            exec "chmod 777 ${config[REMOTE_DIR]}"
                    }

                    try {
                        info("Making a copy of files to be uploaded...")
                        def tempdir = Files.createTempDirectory("groovy_remote_job")
                        //we have to exclude the log files, otherwise they will be replaced
                        //when results are copied back from remote
                        FileUtils.copyDirectory(new File(getWorkingDirectory()), tempdir.toFile(), {
                            file ->
                                !(file.name ==~ /_(flow|job)\..+\.log/) &&
                                        !(file.name ==~ /java-installer.*/)
                        } as FileFilter)

                        try {
                            info("Copying files remotely...")
                            scp {
                                from { localDir(tempdir.toFile()) }
                                into { remoteDir(config[REMOTE_DIR]) }
                            }
                        } finally {
                            info("Deleting temporary copy in $tempdir")
                            if ( tempdir.deleteDir() )
                                info("$tempdir deleted")
                            else
                                warn("Unable to clean directory $tempdir")
                        }
                        // check java installation
                        def javacheck = exec(failOnError: false, command: 'java -version')
                        if (javacheck.exitStatus != 0) {
                            info("No java installation found, now installing")
                            if (!config[JAVA_INSTALLER]) {
                                info("Using default java installer")
                                def embeddedInstaller = extractEmbeddedJavaInstaller()
                                scp {
                                    from { localFile embeddedInstaller }
                                    into { remoteFile "${config[REMOTE_DIR]}/.java-installer.sh" }
                                }
                                config[JAVA_INSTALLER] = ".java-installer.sh"

                            } else info("Java installer: " + config[JAVA_INSTALLER])

                            if (!config[SUDO] && config[SUDO_JAVA_INSTALLER])
                                exec "sudo /bin/bash ${config[JAVA_INSTALLER]}"
                            else
                                exec "/bin/bash ${config[JAVA_INSTALLER]}"
                        }

                        // create launcher script
                        def launcher = ""
                        def workingDirAbsolutePath = new File(getWorkingDirectory()).getAbsolutePath();
                        getEnvironmentVariables().each {
                            def val = it.value.contains(workingDirAbsolutePath) ?
                                    it.value.replace(workingDirAbsolutePath, "./") :
                                    it.value;

                            launcher += "export ${it.key}='$val'\n"
                        }
                        launcher += "\n${createCommandLine()}\n"
                        if (config[VERBOSE])
                            info("Created launcher script: \n" + launcher)

                        // launch
                        info("Running job...")
                        remoteFile("${config[REMOTE_DIR]}/launcher.sh").text = launcher
                        exec "/bin/bash launcher.sh"
                        exec "rm -f launcher.sh $jarfile"

                        info("Execution completed, downloading results...")
                        scp {
                            from { remoteDir(config[REMOTE_DIR]) }
                            into { localDir(getWorkingDirectory()) }
                        }

                    } finally {
                        if (config[CLEANUP]) {
                            try {
                                info("Cleaning...")
                                prefix(config[SUDO] ? "sudo " : "") {
                                    exec "rm -rf ${config[REMOTE_DIR]}"
                                }
                                info("Cleaning completed.")
                            } catch (Exception e) {
                                warn("Unable to cleanup remote server: "+e.toString())
                            }
                        }
                    }
                }

            } as Callable)

            task.get()

            generateProperties(outputFile)

        } catch (Exception e) {

            // I don't know why those files are removed ONLY if job fails...
            // see try/catch in ProcessJob.run
            temporaryFiles.add(parametersFile.toPath())
            temporaryFiles.add(outputFile.toPath())
            throw new RuntimeException(e)

        } finally {
            executor.shutdownNow()
            temporaryFiles.each {
                try {
                    info("Deleting $it")
                    Files.deleteIfExists(it as Path)
                } catch (Exception e) {
                    warn("Unable to cleanup file "+it+ " : "+e.getMessage())
                }
            }
        }


    }

    // Managing working directory:
    // for remote execution, working dir is always the current dir
    // because we prefix each command with 'cd ${remote_dir}'
    // so here we have to make sure that working.dir prop is not set
    // because when it's not set, the default behavior for GroovyRun is to
    // use to current dir ./
    // Additionally, each value containing a ref to the absolute path of the local
    // working directory has to be updated with ./ for the same reason
    //
    // NOTE: This is a incomplete workaround, because there could be other properties that
    // contain local path to azkaban instance and cannot be managed and also
    // user can easily create scenarios where this function is not enough and
    // script could die because of path-not-found errors. It's up to the user
    // to make sure that script doesn't rely on those properties.
    // If the script always use ${working.dir} to create other parameters, this
    // should be safe.
    // So, technically speaking, your script should be somehow aware of the fact
    // that it is running in a remote machine...
    void manageWorkingDirectory() {
        Properties parameters = new Properties();
        parameters.load(Files.newBufferedReader(parametersFile.toPath(), StandardCharsets.UTF_8))
        def changed = false

        info("Updating parameters file to manage working.dir property for remote executor")

        if (parameters.containsKey(WORKING_DIR)) {
            info("... removing "+WORKING_DIR)
            parameters.remove(WORKING_DIR)
            changed = true

        }

        def workingDirAbsolutePath = new File(getWorkingDirectory()).getAbsolutePath();
        def allkeys = parameters.stringPropertyNames()
        allkeys.each {
            String val = properties.get(it)
            if ( val && val.contains( workingDirAbsolutePath ) ) {
                info("... overriding "+it+"="+val)
                properties.put(it, val.replace(workingDirAbsolutePath, "./"))
                changed = true
            }
        }

        if (changed) {
            parametersFile.delete()
            parametersFile.withPrintWriter("UTF-8") {
                parameters.store(it, null)
            }
        }

    }


    @Override
    void cancel() {
        if (task == null) throw new IllegalStateException("Job is not yet started")
        def done = task.cancel(true)
        if (!done)
            throw new Exception("Unable to stop the job, it could be already completed.");
    }
}

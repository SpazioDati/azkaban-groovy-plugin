   * [Azkaban Groovy Plugins](#azkaban-groovy-plugins)
      * [Why Groovy?](#why-groovy)
      * [Installation](#installation)
         * [Requirements](#requirements)
         * [Packaging](#packaging)
         * [Deployment](#deployment)
      * [Jobtypes](#jobtypes)
         * [Basic properties](#basic-properties)
         * [Groovy Job](#groovy-job)
            * [Registering flow finish handler](#registering-flow-finish-handler)
            * [Trigger execution of another flow](#trigger-execution-of-another-flow)
         * [Job GroovyProcess](#job-groovyprocess)
            * [Logging](#logging)
         * [Job GroovyRemote](#job-groovyremote)
            * [Workflow](#workflow)
            * [Additional properties](#additional-properties)
            * [Working directory](#working-directory)
            * [Logging](#logging-1)
      * [Control flow](#control-flow)
         * [Evaluation](#evaluation)
         * [Skipping](#skipping)
         * [NOOP](#noop)
      * [Debugging of groovy jobs](#debugging-of-groovy-jobs)
         * [Usage](#usage)
         * [Configuration](#configuration)
         * [Binding](#binding)

# Azkaban Groovy Plugins

This is a collection of plugins for Azkaban workflow manager, for running Groovy script as Azkaban job.
It also includes a simple but very useful implementation of control flow management, such as 
automatic skipping/disabling jobs based on property value. 

##### Status of the project

These plugins have been widely tested for our internal flows, but we haven't invested too much on this project, so you won't find any test and code is not very well structured. We started developing plugins in Java and later we moved to Groovy, so you will find both Java and Groovy classes. Any bug report, patch, improvement, is welcome.

## Why Groovy?

Because I hate bash scripts longer than one line... 
I think that Groovy can efficiently replace small as well as complex bash scripts:

- no compilation required, the job package can be created without using any build tool (maven or sbt)
- you can use a simple self-contained script, no boilerplate or extra-definitions for a simple tasks
- even if it's a script, you can declare dependencies inside the script itself, using Grape
- it's jvm based, so if your company develops java libraries or applications, the script can directly interact with them
- Groovy also embeds Ant (but in a json-fashion way) so IO tasks et simila are very easy to execute
- Groovy also provides several facilities to manage external process, synch and pipes
- Using sshoogr https://github.com/aestasit/sshoogr , ssh/scp tasks are easy as well


## Installation

### Requirements
 - Java 7
 - Maven 3

### Packaging

First, you'll need azkaban JARs into your local maven; this is due to the fact that Linkedin is not publishing
azkaban JARs on any central repository (apart from [version 2.5.0](https://mvnrepository.com/artifact/com.linkedin.azkaban/azkaban)).

Checkout the [azkaban](https://github.com/azkaban/azkaban/) project, then edit the root `build.gradle` to enable publishing
to your local maven:

```diff
diff --git a/build.gradle b/build.gradle
index a048d5a..fa42bd3 100644
--- a/build.gradle
+++ b/build.gradle
@@ -16,6 +16,7 @@

 buildscript {
     repositories {
+        mavenLocal()
         mavenCentral()
         maven {
             url 'https://plugins.gradle.org/m2/'
@@ -115,6 +116,7 @@ ext.deps = [
 subprojects {
     apply plugin: 'java'
     apply plugin: 'net.ltgt.errorprone'
+    apply plugin: 'maven-publish'

     // Set the same version for all sub-projects to root project version
     version = rootProject.version
@@ -183,6 +185,14 @@ subprojects {
         options.compilerArgs += ["-Werror"]
     }

+    publishing {
+        publications {
+            mavenJava(MavenPublication) {
+                from components.java
+            }
+        }
+    }
+
     /**
      * Print test execution summary when informational logging is enabled.
      */

```

Then just run (from the azkaban project root):
```bash
> ./gradlew publishToMavenLocal

```

Once done, move to the groovy-plugin folder and run:
```
> mvn clean package
```

### Deployment
 - copy `target/azkaban-groovy-plugins-{version}.jar` to `{azkaban_home}/extlib`
 - copy folder `jobtypes/` to `{azkaban_home}/plugins`
 
Then restart Azkaban server

*Note*: If you use the `Groovy` jobtype, I strongly recommend to increase significantly the PermGen space (hundreds of MBs) and
to set the JVM properties for Azkaban server process as described here: http://groovy.codehaus.org/Running#Running-AvoidingPermGenoutofmemory 

## Jobtypes

### Basic properties

If the Groovy script returns a Map, the value is used as output of the job, namely it is dumped as json object
in file referenced by `JOB_OUTPUT_PROP_FILE` environment variable.

All types of jobs can accepts the following properties:

  - `groovy.script` (*required* or use `groovy.command`) the path of the groovy script file, relative to the working directory. Note that
  job type `Groovy` also supports for commands in job definition that can replace this configuration.
  - `groovy.command` or `groovy.command.<n>` that can be used instead of the `groovy.script` parameter.
  If `groovy.script` is not defined, the plugin will execute the command/s provided using this property/es.
  `<n>` can be any string, in case you need multiple lines. Lines are then sorted using alphabetic ordering on
  `<n>` values.
  - `groovy.classpath` the list of path (separated by `:`) of folders containing other Groovy scripts or
  class definitions that maybe referenced by the main script. The current working directory is always added to this list,
  automatically
  - `groovy.forwardParameters` (*default*: `false`) if true, all parameters received by this job are automatically
  forwarded to the next jobs in the workflow, by adding them to the output. Note that even if this is set to true,
  result of the script overrides any input parameter. Additionally, the property `working.dir` and any other property
  starting with `azkaban.` or `groovy.` are never forwarded (so this parameter is never forwarded)
  - `groovy.checkOutput` (*default*: `false`) if true, the result of the script cannot be null and must be an instance
  of Map, otherwise the job will fail.


### Groovy Job

`type:Groovy`

This is a job that can execute a groovy script in the same JVM of Azkaban executor.
The script is executed asynchronosly and cancel operation is fully supported, 
so it should not be a concern for Azkaban server stability.

In addition to the common parameters listed above, this job accepts also: 

  - `groovy.timeout` (*default:* `0`) timeout for the script in seconds. If less than 1, timeout is disabled.

The Groovy script is executed with the following bindings (ie you can reference these variables in your script)

  - `props` the properties of the job, contained in an object of type `azkaban.utils.Props`
  - `config` the properties of the job, contained in an simple and flat Map
  - `progress` a reference to an `AtomicReference<Double>`, initially set to 0, and that can be updated by the script
  to track the completion of the job: UI bar will be automatically updated. Value must be in the range `0.0 ... 1.0`.
  - `log` reference to `org.apache.log4j.Logger` used for the output of the job, useful to print exeception
  information and stacktrace. However, note that standard output of the script is automatically redirected to this
  Logger with level `INFO`.
  - `flowrunner` reference to `azkaban.execapp.FlowRunner` object that is managing this flow execution
  - `jobrunner` reference to `azkaban.execapp.JobRunner` object that is managing this job execution
  - `azkaban` reference to script helper that provides some functions to interact with azkanban instance
     - `azkaban.onfinish( Closure )` excecutes the closure when flow finishes. If an error is raised by the closure,
     the status of the flow is set to `FAILED`
     - `azkaban.execute( [optional] String projectName, String flowId, [optional] Map params, [optional] ExecutionOptions options )`
     triggers the execution of the flow `flowId` from project `projectName`.
     Optionally you can pass a parameters map that will be used to set input properties for the flow, and these will
     override flow parameters.
     If the `projectName` is not provided, the same project of the running flow will be used. See below for further details

One of the main advantages of this job type is the ability to interact with Azkaban configuration. 
If used with caution, this can be very helpful. Eg. pay attention that if you execute a `System.exit(0)`, you are shutting down the Azkaban executor!


#### Registering flow finish handler

You can register event handler for the workflow, in order to execute cleanup operation when flow is completed.
Eg: you want to create a new instance on Amazon EC2 to execute a heavy job, 
but you want to make sure that the instance is delete when the job finishes, regardless the result of the job.

```groovy
// import amazon sdk
@Grab(group='com.amazonaws', module='aws-java-sdk-ec2', version='1.9.13') 
import com.amazonaws.services.ec2.*

// create ec2 instance using Amazon SDK and save a ref to the new instance

// Eg. read an input parameter
def instanceType = config['aws.instance.type']
...
def instanceId = ....

// register a cleanup-function when flow has finished
azkaban.onfinish {
  
     // cleanup ec2 instance using instanceId
     flowrunner.logger.info "Cleanup function"
     ...
}

return [ 'aws.instance.host' : '.....'] // result of the script must be a map and it is used as output of the job

```

*Note1*: always recall to use `flowrunner.logger` to print message inside that closure, because the `log` object is bind
to the logger of the job, and most likely that logger has been already closed when the flow is going to finish.

*Note2*: To register execution of function when flow finishes, you could use `flowrunner.addEventListener` and waiting for
event type `Event.Type.FLOW_FINISHED`. This is correct but when Azkaban executes that code, the logger of the `FlowRunner` 
has been already closed, so any message printed by the listener will be hidden. Additionally, if an exception is 
thrown during the execution of the listener, no message will be printed and status of the flow won't be affected.
So you should always use `azkaban.onfinish` that already manages this scenario.


#### Trigger execution of another flow

Using the `azkaban` object you can also trigger execution of other flows.

Basic scenario is when you have a job that checks for a condition if it is true than trigger another flow.

However this can be used also to manage scenario where you need to execute the same flow with many different configurations:
instead of creating a macro-flow where all configurations are directly specified in different job definitions
(so you may need for a script to generate all job nodes) you can create a simple flow and trigger executions of
same flow with different parameter configuration

```groovy
import azkaban.executor.*

def langs = ['it', 'en', 'fr', 'de', 'pt' ]

langs.each { l ->

    def opts = new ExecutionOptions()
    // set execution options, for more information about this object see
    // http://grepcode.com/file/repo1.maven.org/maven2/com.linkedin.azkaban/azkaban/2.5.0/azkaban/executor/ExecutionOptions.java
    // more documentation is available at http://azkaban.github.io/azkaban/docs/2.5/#api-execute-a-flow
    // NOTE: currently, only disabled jobs and success/failure emails are supported
    opts.disabledJobs = ["job1", "job3"]
    opts.failureEmails = ["me@acme.com"]

    def result = azkaban.execute(
        "download-wiki", //name of the flow
        [ lang: l ], //parameters for the flow, will be available for all jobs of the flow
        opts  //execution options
    )

    log.info("Submitted flow for lang $l : $result")
}

```

For this feature, the job needs for username and password of a valid user that has the right to execute that job.
The following properties should be set:

  - `groovy.execute.endpoint` the URI of the Azkaban web UI (including protocol, domain, port and eventually path)
  - `groovy.execute.username`
  - `groovy.execute.password`

Including these properties in the job definition could be annoying and maybe you don't want to put username and password 
of such a user in the `.job` files. So these properties can be also set in private executor properties file, and this plugin 
is able to read them from that file (this applies only for those 3 properties). 
Namely you can put these values in 

`<azkaban-executor-home>/conf/azkaban.private.properties`

If job definition doesn't
contain those 3 properties, plugin will try to read them from that file. The drawback is that that file is read during
executor startup phase, so any change to that file requires Azkaban reboot.

### Job GroovyProcess

`type:GroovyProcess`

This execute the Groovy script in a new JVM.
This is a subtype of `JavaProcess` so you can define any other property of that job type 
(http://azkaban.github.io/azkaban/docs/2.5/#builtin-jobtypes). For instance, you can specify the maximum heap memory 
for the JVM using `Xmx` property.

The main difference is that the main class is fixed and classpath always contains the current working directory.
The main advantage is that you can avoid build tools to create a simple job, the script is self contained.

The script binding is limited with respect to the `Groovy` job, and it includes only the `config` variable 
containing all job parameters in a Map.

In addition to the common parameters listed above, this job also accepts 

  - `groovy.resolver.<name>` this parameter can be repeated with different `<name>` and you can define a reference to
  a custom maven artifact repository. The difference with respect to standard Groovy `GrabResolver` annotation,
  is that the URL can contain username and password for basic HTTP authentication that is not currently supported
  in `GrabResolver` (you should use `~/.groovy/grapeConfig.xml` instead, but this could annoying, see
  http://groovy-lang.org/grape.html ). So this let you define a property like this
  `groovy.resolver.my-private-repo:https//user:password@repo.mycompany.com/nexus/content/repositories/releases/`.
  This can be also set globally in `<azkaban-home>/conf/global.properties`


#### Logging

The executor jar embeds Log4J and SLF4J binding for Log4J, but doesn't embed any Log4J configuration and by default Log4J just prints out a warning message if it hasn't been initialized, discarding any log message. So, if you are using third party libraries and you need for logs, you can configure log4j programatically in your script or just put a `log4j.properties` in your working dir and add that path to the classpath or use `jvm.args` configuration property to initialize Log4J (ig `jvm.args= -Dlog4j.configuration=file://${working.dir}/log4j.properties` )

### Job GroovyRemote

`type:GroovyRemote`

#### Workflow

This executes a Groovy script on a remote machine. The flow is the following:

 - try to connect to the remote host
 - if defined, it executes an initialization script
 - create remote working directory
 - copy all content of local working directory to the remote one (all but logs are copied)
 - check that java is installed, otherwise try to install a JVM on-the-fly
 - launch the Groovy script using same properties and environment variables as `GroovyProcess` job type
 - copy back the content of the remote directory to the local one
 
So basically, the only requirement on the remote machine is to have a running ssh daemon.


#### Additional properties

This job accepts also:

 - `groovy.resolver.<name>` see `GroovyProcess` for further details
 - `groovy.remote.host` (*required*) the host of the remote machine
 - `groovy.remote.username` (*required*) the username
 - `groovy.remote.port` (*default*: `22`) the ssh connection port
 - `groovy.remote.password` the password, must be set if `groovy.remote.keyFile` is not set
 - `groovy.remote.retry` (*default*: `5`) number of connection attempts. If connection cannot be established the plugin retries to connect to the host after 5 seconds (it may be useful in case of on-demand EC2 instances that are not immediately ready for ssh). After each attempt, the delay is doubled (so 5, 10, 20...)
 - `groovy.remote.keyFile` the path of file containing the ssh key, must be set if `groovy.remote.password` is not set
 - `groovy.remote.working.dir` the working directory on the remote machine, is created if not found.
 By default is set to `/tmp/azkaban-{azkaban-flowid}-{azkaban.execid}`.
 If you change this, recall that once the job finishes, the content of the folder is copied back to the local machine,
 so make sure that directory on remote server doesn't contain huge unwanted files.
 - `groovy.remote.initScript` (*default*: `none`) a path to a file that contains a script that will be executed at the beginning of the session. This will be executed even before setting up the working directory. The script will be executed using command `bash <scriptfile>`, no prefix or `cd` will be used.
 - `groovy.remote.javaInstaller` the path (relative to the working dir) of the file containing a script required
 for installing JVM on-the-fly, if necessary. By default the plugin deploys a shell script embedded in this jar
 for installing Oracle JVM version 7 using `apt-get`.
 - `groovy.remote.sudo.javaInstaller` (*default*: `true`) if set to true, the `javaInstaller` will be executed with `sudo`
 - `groovy.remote.sudo` (*default*: `false`) if set to true, any command executed to the remote machine will be prefixed with `sudo`
 - `groovy.remote.cleanup` (*default*: `true`) if set to true, the remote directory will be deleted once this job is completed

The script binding is the same as `GroovyProcess` job type, so limited to `config` variable.

This job relies on `GroovyProcess` that, in turn, is a subtype of `JavaProcess` so you can define any other property 
of that job type (http://azkaban.github.io/azkaban/docs/2.5/#builtin-jobtypes).
For instance, you can specify the maximum heap memory for the newly spawned JVM by using `Xmx` property.


#### Working directory

*TL;DR*: If the script always uses `${working.dir}` instead of absolute path, it should be safe.

Using this plugin, the working directory is always the current directory for the groovy script,
because we prefix each ssh command with `cd ${remote_dir}; `
So the job setup process makes sure that `working.dir` property is set to `./`
Additionally, each value of the property containing a ref to the absolute path of the local
working directory has to be updated with `./` for the same reason.
Using this behavior, the script can be agnostic about to the fact that it is running on Azkaban machine or
on a remote one, in 99% of the cases.

However, this is a incomplete workaround, because there could be other properties that
contain local path to Azkaban machine and cannot be managed and also
user can easily create scenarios where that patch is not enough and
script could die because of path-not-found errors. It's up to the user
to make sure that the script doesn't rely on those properties.

#### Logging

See section *Logging* for GroovyProcess

## Control flow

Execution of all jobs can be automatically skipped or disabled based on the value of certain properties.

Before starting any operation on a certain job, this plugin checks the value of two properties

 - `flow.skip`
 - `flow.noop`

These properties can be added in job definition, or dynamically created by the upstream job. 

### Evaluation

The evaluation of those properties is done as follows:

 1. plugin resolves property variables, applying standard substitution provided by Azkaban
 2. if the property has the pattern `/.../` the value inside back slashes is compiled as a regex and if it matches (entirely) the job name, the value is true, otherwise false.
 3. plugin tries to evaluate the value of the property as a Groovy statement. In this case the resulting object is used as raw value to parse. For the evaluation `config` variable is bind to the map containing input parameters (already resolved).
 4. if the evaluation succeeded, the result is parsed, otherwise the original value of the property is parsed
    1. if the value is empty or null, return false
    2. if the value is a number differnt than zero, return true
    3. if equals (ignoring case) to `yes`,`y`,`true`,`t`,`ok`, return true
    4. else return false

### Skipping

If `flow.skip` is evaluted as `True`, than the current job is skipped. The status on Azkaban flow is set to
`SKIPPED` and all downstream jobs are set to `SKIPPED` as well. This means that final status of the flow will be `SKIPPED`.

This property is very useful in a scenario like this: you have a workflow where you have to check something and based on that
 you could create a remote EC2 instance and execute the rest of the job on that instance. You could split this flow in 3 jobs:
 
 1. check (`Groovy` or `GroovyProcess` job)
 2. create instance (`Groovy` job, because it has to register event handlers)
 3. do something (`GroovyRemote` job)
 
so that "*create instance*" step could be re-used in other flows. But if the check fails (it doesn't mean that an error occurred
but that the job is not required to run now), than you have to notify all downstream jobs to skip execution, and jobs must be
aware that there's a property or something like that they must check at the beginning. This is very annoying, because all of
your jobs should share the same logic, but for `GroovyRemote` job type is even impossible, because it runs on a remote
server, and this may never exist! So in this case, it would be enough that first job set `flow.skip` to `True` and
all other jobs will be skipped.

  
### NOOP
  
If `flow.noop` is evaluated as `True`, than the current job is not executed, but the status of the job is set to
`SUCCESS`, that means that downstream jobs will be executed normally.
Note that if `groovy.forwardParameters` was set to `true`, than input parameters will be forwarded to the following job,
as usual (see above), including `flow.noop` parameter.

Note that this property is evaluated after `flow.skip`.

This can be useful if you have a flow like this:

```
       [main]
     /    |  \
  [J1]  [J2] [J3]
    |     |    |  
  [J1b]   |  [J3b]
     \    |   /
     [job.join]
```

Say you are on job `main` and want to select which downstream job has to be executed.
This job can output a property like `flow.noop=/J1.*/`.
Then all other jobs can be configured with `groovy.forwardParameters=true`:
this way `J1` and `J1b` won't run, but their status will be set to `SUCCESS` so that `job.join`
will be correctly executed when `J2` and `J3b` will finish.
`groovy.forwardProperties` is required to let `flow.noop` be passed to downstream jobs, in this case
is required to noop the execution of job `J1b`.

## Debugging of groovy jobs

This plugin also provides a groovy script for debugging your Azkaban groovy jobs.

This is very useful for `Groovy` job types becasue usually they rely on `azkaban.onfinish` or `azkaban.execute` built-in functions
However it can be used also for other `Groovy*` job types.


### Usage

To debug the script

`> ./debug.groovy [options] <script-file>`

use option `-h` to print out all available options

You can also avoid checking out all the git repo, using groovy remote script execution:

`> groovy https://raw.githubusercontent.com/SpazioDati/azkaban-groovy-plugin/master/scripts/debug.groovy ...`

### Configuration

The script can collect configuration from several sources, merge them and make them available to the script, using `config` binding.
This way you can avoid making script aware of debug/production mode, just always use `config` map.
Properties are read from (in this order, so the latter override the others)

  - System properties, if cli option is set
  - any `.properties` files contained in `./global` directory 
  - parsing of `./debug.properties`
  - any in-line parameter using standard java syntax `-D<name>=<value>`
  
### Binding

The script is executed with the following binding:

  - `config`, the configuration map
  - `flowrunner.logger`, `log`
  - `azkaban.onfinish`, `azkaban.execute`
  
Namely, if your script invoke `azkaban.onfinish`, the function is invoked at the end of the flow.
If your script invoke `azkaban.execute`, a message containing execution parameters will be printed out.

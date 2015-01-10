# Azkaban Groovy Plugins

This is a collection of plugins for Azkaban workflow manager, for running Groovy script as Azkaban job.

## Why Groovy?

Because I hate bash scripts longer than one line... 
I think that Groovy can efficiently replace small as well as complex bash scripts:

- no compilation required, the job package can be created without using any build tool (maven or sbt)
- you can use a simple self-contained script, no boilerplate or extra-definitions for a simple tasks
- even if it's a script, you can declare dependencies inside the script itself, using Grape
- it's jvm based, so if your company develops java libraries or applications, the script can directly interact with them
- Groovy also embeds Ant (but in a json-fashion way) so IO tasks et simila are very easy to execute
- Groovy also provides several facilities to manage external process, synch and piping
- Using sshoogr https://github.com/aestasit/sshoogr , ssh/scp tasks are easy as well


## Installation

Requirements
 
 - Java 7
 - Maven 3

Packaging

```
> mvn clean package
```

Deployment

 - copy `target/azkaban-groovy-plugins-{version}.jar` to `{azkaban_home}/extlib`
 - copy folder `jobtype/` to `{azkaban_home}/plugins`
 
Then restart Azkaban server

*Note*: If you use the `Groovy` jobtype, I strongly recommend to set the JVM properties for Azkaben server process as described here: http://groovy.codehaus.org/Running#Running-AvoidingPermGenoutofmemory 

## Jobtypes

### Common properties

If the Groovy script returns a Map, the value is used as output of the job, hence dumped as json object in file referenced by `JOB_OUTPUT_PROP_FILE` environment variable.

All jobs can accepts the following properties:

  - `groovy.script` (*required*) the path of the groovy script file, relative to the working directory 
  - `groovy.classpath` the list of path (separated by `:`) of folders containing other Groovy scripts or class definitions that maybe referenced by the main script. The current working directory is always added to this list, automatically
  - `groovy.resolver.<name>` this parameter can be repeated with different `<name>` and you can define a reference to a custom maven artifact repository. The difference with respect to standard Groovy `GrabResolver` annotation, is that the URL can contain username and password for basic HTTP authentication that is not currently supported in `GrabResolver` (you should use `~/.groovy/grapeConfig.xml` instead, but this could annoying, see http://groovy-lang.org/grape.html ). So this let you define a property like this `groovy.resolver.my-private-repo:https//user:password@repo.mycompany.com/nexus/content/repositories/releases/`. This can be also set globally in `<azkaban-home>/conf/global.properties`
  - `groovy.forwardParameters` (*default*: `false`) if true, all parameters received by this job are automatically forwarded to the next jobs in the workflow, by adding them to the output. Note that even if this is set to true, result of the script overrides any input parameter. Additionally, the property `working.dir` and any other property starting with `azkaban.` are never forwarded.
  - `groovy.checkOutput` (*default*: `false`) if true, the result of the script cannot be null and must be an instance of Map, otherwise the job will fail.

### Groovy Job

`type:Groovy`

This is a job that can execute a groovy script in the same JVM of Azkaban executor.
The script is executed asynchronosly and cancel operation is fully supported, 
so it should not be a concern for Azkaban stability. 

This job accepts also:

  - `groovy.timeout` (*default:* `0`) timeout for the script in seconds. If less than 1, timeout is disabled.
  
The Groovy script is executed with the following bindings (ie you can reference these variables in your script)

  - `props` the properties of the job, contained in an object of type `azkaban.utils.Props`
  - `config` the properties of the job, contained in an simple and flat Map
  - `progress` a reference to an `AtomicReference<Double>`, initially set to 0, and that can be updated by the script to track the completion of the job: UI bar will be automatically updated. Value must be in the range `0.0 ... 1.0`.
  - `log` reference to `org.apache.log4j.Logger` used for the output of the job, useful to print exeception information and stacktrace. However, note that standard output of the script is automatically redirected to this Logger with level `INFO`.
  
One of the main advantages of this job type is the ability to interact with Azkaban configuration. 
If used with caution, this can be very helpful. 
For instance you can register event handler for the workflow, in order to execute cleanup operation when flow is completed.
Eg: you want to create a new instance on Amazon EC2 to execute a heavy job, 
but you want to make sure that the instance is delete when the job finishes, regardless the result of the job.

```java
// import amazon sdk
@Grab(group='com.amazonaws', module='aws-java-sdk-ec2', version='1.9.13') 
import com.amazonaws.services.ec2.*
import azkaban.*
import azkaban.execapp.*
import azkaban.execapp.event.*

// config is the map containing job params
def myflowid = config['azkaban.flow.flowid'] as int
// getting FlowRunner object
def myflow = AzkabanExecutorServer.app.flowRunnerManager.runningFlows.find { it.key == myflowid }.value

// create ec2 instance using Amazon SDK and save a ref to the new instance
...
def instanceId = ....

// register a cleanup-function when flow has finished
myflow.addListener({ event ->
  
  if (event.type == Event.Type.FLOW_FINISHED) {
      
     // cleanup ec2 instance using instanceId
     ...

  }
} as EventListener )

return [ 'aws.instance.host' : '.....'] // result of the script must be a map and it is used as output of the job

```

*Note*: the `groovy.resolver.<name>` properties couldn't be fetched correctly if some other plugins of your 
Azkaban installation is linking Ivy.
This because I had to change the implementation of org.apache.ivy.util.url.CredentialsStore and 
if the class has been already loaded because of some other plugin, the patch
included in this plugin doens't apply. 
I should user jarjar to embed the patched version of Ivy, but it is too much expensive and you can always fix
this issue by using `~/.groovy/grapeConfig.xml` file.
This issue will never be faced for other job types, because they run on a different JVM.

### Job GroovyProcess

`type:GroovyProcess`

This execute the Groovy script in a new JVM.
This is a subtype of `JavaProcess` so you can define any other property of that job type 
(http://azkaban.github.io/azkaban/docs/2.5/#builtin-jobtypes)

The main difference is that the main class is fixed and classpath always contains the current working directory.
The main advantage is that you can avoid build tools to create a simple job, the script is self contained.

The script binding is limited with respect to the `Groovy` job, and it includes only the `config` variable 
containing all job parameters in a Map.

### Job GroovyRemoteJob

`type:GroovyRemote`

This executes a Groovy script on a remote machine. The flow is the following:

 - try to connect to the remote host
 - create remote working directory
 - copy all content of local working directory to the remote one (all but logs are copied)
 - check that java is installed, otherwise try to install a JVM on-the-fly
 - launch the Groovy script using same properties and environment variables as `GroovyProcess` job type
 - copy back the content of the remote directory to the local one
 
So basically, the only requirement on the remote machine is to have a running ssh daemon.
 
This job accepts also:

 - `groovy.remote.host` (*required*) the host of the remote machine
 - `groovy.remote.username` (*required*) the username
 - `groovy.remote.port` (*default*: `22`) the ssh connection port
 - `groovy.remote.password` the password, must be set if `groovy.remote.keyFile` is not set
 - `groovy.remote.keyFile` the path of file containing the ssh key, must be set if `groovy.remote.password` is not set
 - `groovy.remote.working.dir` the working directory on the remote machine, is create if not found. By default is set to `/tmp/azkaban-{azkaban-flowid}-{azkaban.execid}`. If you change this, recall that once the job finishes, the content of the folder is copied back to the local machine, so make sure that directory on remote server doesn't contain huge unwanted files.
 - `groovy.remote.javaInstaller` the path of the file containing a script required for installing JVM on-the-fly, if necessary. By default the plugin deploys a shell script embedded in this jar for installing Oracle JVM version 7 using `apt-get`.
 - `groovy.remote.sudo` (*default*: `false`) if set to true, any command executed to the remote machine will be prefixed with `sudo`

The script binding is the same as `GroovyProcess` job type, so limited to `config` variable.

A note about the working directory on remote machine. Using this plugin, the
working directory is always the current directory for the groovy script, 
because we prefix each ssh command with `cd ${remote_dir}; `
So the job setup process has to make sure that `working.dir` property is set to `./`
Additionally, each value of the property containing a ref to the absolute path of the local
working directory has to be updated with `./` for the same reason.
Using this behavior, the script can be agnostic about to the fact that it is running on Azkaban machine or
on a remote one, in 99% of the cases.

However, this is a incomplete workaround, because there could be other properties that
contain local path to Azkaban machine and cannot be managed and also
user can easily create scenarios where that patch is not enough and
script could die because of path-not-found errors. It's up to the user
to make sure that the script doesn't rely on those properties.
If the script always uses `${working.dir}`, it should be safe.
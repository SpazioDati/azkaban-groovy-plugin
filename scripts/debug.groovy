#!/usr/bin/env groovy
@groovy.lang.Grapes([
        @Grab(group='org.slf4j', module='slf4j-api', version='1.7.10'),
        @Grab(group='log4j', module='log4j', version='1.2.16')
])
import org.apache.log4j.*

import java.nio.file.Path
import java.nio.file.Paths

Logger.rootLogger.removeAllAppenders()
Logger.rootLogger.addAppender(new ConsoleAppender(new PatternLayout("[%d - SCRIPT] %m%n")))
Logger.rootLogger.setLevel(Level.WARN)
def log = Logger.getLogger("SCRIPT")
log.setLevel(Level.ALL)

def config = [:]

def cli = new CliBuilder(usage: "debug.groovy [options] <script file>", header: 'Debugger for Azkaban Groovy jobs, options:')
cli.D(args:2, valueSeparator:'=', argName:'property=value', "System property to be used as inline config of the job")
cli.f(args:1, argName: "file", "File containing debug properties for the job, by default 'debug.properties'")
cli.h("Print this")
cli.a("Include all system properties as job properties")
def opts = cli.parse(args)

if (opts.h) {
    cli.usage()
    System.exit(0)
}

if (!opts.arguments()) {
    cli.usage()
    throw new RuntimeException("Script to debug is not specified")
}
def scriptfile = new File(opts.arguments()[0])
if (!scriptfile.exists()) {
    cli.usage()
    throw new RuntimeException("File not found: ${scriptfile.absolutePath}")
}

config['working.dir'] = new File("./").absolutePath

if (opts.a) {
    println "Loading debug properties from System properties"
    config.putAll(System.getProperties());
}
if (new File("global").exists()) {
    Paths.get("global").eachFile { Path f ->
        if (f.toString().endsWith(".properties")) {
            println "Loading debug properties from ${f.toString()}"
            def pp = new Properties()
            f.withReader("UTF-8") {
                pp.load(it)
            }
            config.putAll(pp)
        }
    }
}

def propfile = new File(opts.f?:"debug.properties")
if (propfile.exists()) {
    println "Loading debug properties from ${propfile.getAbsolutePath()}"
    propfile.withReader("UTF-8") {
        def prop = new Properties()
        prop.load(it);
        config.putAll(prop)
    }
}
if (opts.Ds) {
    println "Loading debug properties from command line"
    for (i=0; i<opts.Ds.size(); i+=2) {
        config[opts.Ds[i]] = opts.Ds[i + 1]
    }
}
println "Debug properties: "
config.each { println "    ${it.key} = ${it.value}" }

println()
println()
println "Debugging script ${scriptfile.absolutePath} ..."

def scriptengine = new GroovyScriptEngine("./")

def finishScript = null;

def onfinish = [
    register: { hdl ->
        finishScript = hdl;
    }
]
def azkaban = [
    onfinish: { hdl ->
        finishScript = hdl;
    },
    execute: { Object... params ->
        println ">>> Execution with parameters:"
        params.each { println "    $it" }
    }
]
def flowrunner = [
    logger: log
]

if (!config["azkaban.flowid"])
    config["azkaban.flowid"] = "debug_flow"
if (!config["azkaban.execid"])
    config["azkaban.execid"] = "debug_exec"



def scriptresult = scriptengine.run(scriptfile.absolutePath, new Binding([
        'config':config,
        'log':log,
        'onfinish': onfinish,
        'azkaban': azkaban,
        'flowrunner': flowrunner,
        'out': new PrintStream(new StreamToLogger(log))
]))

println()
println "Script execution completed."
println "RESULT:"
println scriptresult
println()


if (! Boolean.parseBoolean(System.getProperty(".debug.skipfinish", "false")) ) {
    if (!finishScript)
        println "No onFinish script registered"
    else {
        println "Invoking onFinish script..."
        finishScript()
    }
}



/*********************************************************************************
 *
 *     STREAM TO LOGGER UTILITY
 *
 *********************************************************************************/

// 99% taken from https://community.oracle.com/thread/1164250

/**
 * Stream that flushes data to a Log4j Logger: once a newline
 * is received a new log message is sent
 * The class is not thread-safe
 */

public class StreamToLogger extends OutputStream {

    ///Logger that we log to
    private final Logger mLogger;

    ///The buffered output so far
    private final StringBuffer mOutput = new StringBuffer();

    ///Flag set to true once stream closed
    private boolean mClosed;
    /**
     * Construct LoggingOutputStream to write to a particular logger at a particular level.
     *
     * @param logger the logger to write to
     * @param level the level at which to log
     */
    public StreamToLogger( final Logger logger) {
        mLogger = logger;
    }

    /**
     * Shutdown stream.
     * @exception java.io.IOException if an error occurs while closing the stream
     */
    public void close() throws IOException {
        flush();
        super.close();
        mClosed = true;
    }

    /**
     * Write a single byte of data to output stream.
     *
     * @param data the byte of data
     * @exception java.io.IOException if an error occurs
     */
    public void write( final int data ) throws IOException {
        checkValid();

        char c = data as char
        mOutput.append( c );

        if( '\n' as char == c){
            flush();
        }

    }

    public void write(byte[] b, int off, int len) throws IOException {
        super.write(b, off, len);
    }

    public void write(byte[] b) throws IOException {
        super.write(b);
    }


    /**
     * Flush data to underlying logger.
     *
     * @exception java.io.IOException if an error occurs
     */
    public synchronized void flush() throws IOException {
        checkValid();
        if (mOutput.length() > 0) {
            if (mOutput.charAt(mOutput.length() - 1) == '\n' as char)
                mOutput.deleteCharAt(mOutput.length() - 1);
            mLogger.info(mOutput.toString());
            mOutput.setLength(0);
        }
    }

    /**
     * Make sure stream is valid.
     *
     * @exception java.io.IOException if an error occurs
     */
    private void checkValid() throws IOException {
        if( mClosed ) {
            throw new EOFException( "LoggingOutputStream closed" );
        }
    }
}
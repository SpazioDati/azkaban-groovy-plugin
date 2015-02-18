package eu.spaziodati.azkaban.jobtype;

import azkaban.execapp.FlowRunner;
import azkaban.execapp.JobRunner;
import azkaban.jobExecutor.AbstractProcessJob;
import azkaban.utils.Props;
import azkaban.utils.PropsUtils;
import eu.spaziodati.azkaban.GroovyResolversConfig;
import eu.spaziodati.azkaban.JobUtils;
import groovy.lang.Binding;
import groovy.util.GroovyScriptEngine;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Groovy creates classes dynamically, but the default Java VM does not GC the PermGen.
 * Add -XX:+CMSClassUnloadingEnabled -XX:+UseConcMarkSweepGC to JVM OPTS to avoid out of memory
 */

public class GroovyJob extends AbstractProcessJob {

    /**
     * A list of folders that contain groovy source files.
     * Path are relative to the working dir, the working dir
     * is always included. Optional property
     */
    public static final String CLASSPATH = "groovy.classpath";
    /**
     * The groovy script file, it must be contained in one the folder
     * listed in the classpath (see property above). Mandatory property
     */
    public static final String SCRIPT = "groovy.script";
    /**
     * The timeout for the groovy job, in seconds. If less than 1,
     * it is disabled. By default is 0, means no timeout.
     */
    public static final String TIMEOUT = "groovy.timeout";
    // if true, it will parse the output of the script as the output of the job
    // the result must be a Map
    public static final String CHECK_OUTPUT = "groovy.checkOutput";
    // if true, this job will forward incoming parameters as job's output
    // to automatically propagate all properties to the next step of the flow
    public static final String FORWARD_PARAMETERS = "groovy.forwardParameters";


    public GroovyJob(String jobid, Props sysProps, Props jobProps, Logger log) {
        super(jobid, sysProps, new Props(sysProps, jobProps), log);
    }

    AtomicReference<Double> progress = new AtomicReference<Double>(0.0);
    volatile Future<Object> task = null;
    volatile Props resultProps = null;

    @SuppressWarnings("unchecked")
    @Override
    public void run() throws Exception {

        try {
            resolveProps();
        } catch (Exception e) {
            throw new Exception("Bad property definition! " + e.getMessage(), e);
        }
        int execid = jobProps.getInt("azkaban.flow.execid");
        
        Props preconditionProps = JobUtils.checkPreconditions(getId(), jobProps, getLog());
        if (preconditionProps != null) {
            resultProps = preconditionProps;
            return;
        }

        final GroovyScriptEngine engine;
        final Binding scriptVars;
        final String scriptFile;
        int timeout;
        try {
            File wd = new File(getWorkingDirectory());
            info("Current working dir: "+wd.getAbsolutePath());

            scriptFile = jobProps.getString(SCRIPT);
            timeout = jobProps.getInt(TIMEOUT, 0);

            String cp = jobProps.getString(CLASSPATH, "");
            String[] urls= cp.split(":");
            // always add job's directory
            urls = Arrays.copyOf(urls, urls.length + 1);
            urls[urls.length-1] = "./";
            // transform all paths to absolute paths
            for (int i=0; i<urls.length; i++) {
                File f = new File(urls[i]);
                if (! f.isAbsolute())
                    urls[i] = new File(wd, urls[i]).getAbsolutePath();
            }
            info("Classpath for Groovy script: "+Arrays.toString(urls));

            info("Creating engine...");
            FlowRunner flowRunner = JobUtils.myFlowRunner(execid);
            JobRunner jobRunner = JobUtils.myJobRunner(execid, getId());
            
            engine = new GroovyScriptEngine(urls);
            scriptVars = new Binding();
            scriptVars.setVariable("props", jobProps);
            scriptVars.setVariable("config", PropsUtils.toStringMap(jobProps, false));
            scriptVars.setVariable("progress", progress);
            scriptVars.setVariable("flowrunner", flowRunner);
            scriptVars.setVariable("onfinish", new FlowRunnerFinishHandler(flowRunner, jobRunner));
            scriptVars.setVariable("jobrunner", jobRunner);
            scriptVars.setVariable("log", getLog());
            scriptVars.setProperty("out", new PrintStream(new StreamToLogger(getLog(), Level.INFO, "[groovy] ")));
            info("Setup done");

        } catch (Exception e) {
            throw new Exception("Job setup failed! "+e.getMessage(), e);
        }

        ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "GroovyExecutor");
                t.setDaemon(true);
                return t;
            }
        });

        boolean success = false;
        final Map allproperties = new HashMap();
        allproperties.putAll(System.getProperties());
        for (String k : jobProps.getKeySet())
            allproperties.put(k, jobProps.get(k));

        long startMS = System.currentTimeMillis();
        try {
            info("Launching script...");
            task = executor.submit(new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    GroovyResolversConfig config = GroovyResolversConfig.fromMap(allproperties);
                    try {
                        return engine.run(scriptFile, scriptVars);
                    } finally {
                        config.restore();
                    }
                }
            });

            Object result = null;
            if (timeout <= 0) {
                result = task.get();
            } else {
                try {
                    result = task.get(timeout, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    error("Groovy script execution timed-out! ("+timeout+"s)");
                    throw new Exception("Groovy script execution timed-out! ("+timeout+"s)", te);
                }
            }

            boolean forwardParams = jobProps.getBoolean(FORWARD_PARAMETERS, false);
            Props resultProps = new Props();
            if (forwardParams)
                resultProps = JobUtils.forwardParameters(jobProps);
            
            
            boolean checkOutput = jobProps.getBoolean(CHECK_OUTPUT, false);
            if (result != null) {
                if (result instanceof Props)
                    resultProps = new Props(resultProps, (Props)result);
                else if (result instanceof Map) {
                    // convert any object to String
                    Map resultmap = (Map) result;
                    for (Object k : resultmap.keySet()) 
                        resultmap.put(k, resultmap.get(k).toString());
                    resultProps = new Props(resultProps, (Map) result);
                } else {
                    if (checkOutput)
                        throw new Exception("Script didn't generate a valid output. (" + result.getClass() + "): " + result.toString());
                    else
                        warn("Script didn't generate a valid output. (" + result.getClass() + "): " + result.toString());
                }
            } else {
                if (checkOutput)
                    throw new Exception("Script didn't generate output.");
                else
                    info("Script didn't generate output.");
            }
            Map flattenresult = PropsUtils.toStringMap(resultProps, false);
            this.resultProps = new Props(null, flattenresult);
            
            
            progress.set(1.0);
            success = true;

        } finally {
            executor.shutdownNow();
            if (success)
                info("Job completed successfully!");
            else
                error("Job miserably failed...");
        }
    }

    @Override
    public Props getJobGeneratedProperties() {
        return resultProps;
    }

    @Override
    public double getProgress() {
        return progress.get();
    }

    @Override
    public void cancel() throws Exception {
        if(task == null)
            throw new IllegalStateException("Not started.");
        boolean done = task.cancel(true);
        if (!done) throw new Exception("Unable to stop the job, it could be already completed.");

    }


}

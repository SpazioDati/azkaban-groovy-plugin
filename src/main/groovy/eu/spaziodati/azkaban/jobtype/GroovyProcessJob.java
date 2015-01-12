package eu.spaziodati.azkaban.jobtype;

import azkaban.jobExecutor.AbstractProcessJob;
import azkaban.jobExecutor.JavaProcessJob;
import azkaban.utils.Props;
import azkaban.utils.PropsUtils;
import eu.spaziodati.azkaban.JobUtils;
import eu.spaziodati.azkaban.Reflection;
import org.apache.log4j.Logger;

import java.io.InputStream;
import java.nio.file.*;
import java.util.List;

/**
 * Check JavaProcessJob for properties references.
 * The only differences are:
 *  (a) the main class is fixed and it is eu.spaziodati.azkaban.GroovyRun, check
 *  that class to see other properties
 *  (b) before executing the groovy script, it extracts the groovy-executor jar in the
 *  working dir, and adds it to the classpath for the spawned java process
 */
public class GroovyProcessJob extends JavaProcessJob {

    public GroovyProcessJob(String jobid, Props sysProps, Props jobProps, Logger logger) {
        super(jobid, sysProps, jobProps, logger);
    }

    protected String jarfile = null;


    @Override
    protected String getJavaClass() {
        return "eu.spaziodati.azkaban.AzkabanGroovyRunner";
    }

    // this is called in super.run().
    // see JavaProcessJob and ProcessJob
    @Override
    protected List<String> getClassPaths() {
        List<String> paths = super.getClassPaths();
        if (!paths.contains(jarfile)) paths.add(jarfile);
        return paths;
    }

    @Override
    public void run() throws Exception {

        Props resolvedProps = PropsUtils.resolveProps(jobProps);
        Props preconditionProps = JobUtils.checkPreconditions(getId(), resolvedProps, getLog());
        if (preconditionProps != null) {
            Reflection.set(AbstractProcessJob.class, this, "generatedProperties", preconditionProps);
            return;
        }


        try {
            Path tmp = Files.createTempFile(Paths.get(getWorkingDirectory()), "groovy-executor", ".jar");
            jarfile = tmp.toString();

            try (InputStream is = getClass().getResourceAsStream("/embedded/groovy-executor.jar")) {
                Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
            }

        } catch (Exception e ) {
            throw new Exception("Unable to install groovy executor jar. Location: "+jarfile, e);
        }

        try {
            super.run();
        } finally {
            Files.deleteIfExists(Paths.get(jarfile));
        }
    }
}

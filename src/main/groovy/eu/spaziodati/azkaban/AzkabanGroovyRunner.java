package eu.spaziodati.azkaban;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import groovy.lang.Binding;
import groovy.util.GroovyScriptEngine;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;


/**
 * Groovy executor for Azkaban Flows
 */
public class AzkabanGroovyRunner {

    static final String PROP_FILE_ENV = "JOB_PROP_FILE";
    static final String OUT_PROP_FILE_ENV = "JOB_OUTPUT_PROP_FILE";

    public static final String WORKING_DIR = "working.dir";
    // the path of the groovy script
    public static final String GROOVY_SCRIPT = "groovy.script";
    // the list of folder containing groovy scripts
    public static final String GROOVY_CLASSPATH = "groovy.classpath";
    // if true, it will parse the output of the script as the output of the job
    // the result must be a Map
    public static final String CHECK_OUTPUT = "groovy.checkOutput";
    // if true, this job will forward incoming parameters as job's output
    // to automatically propagate all properties to the next step of the flow
    public static final String FORWARD_PARAMETERS = "groovy.forwardParameters";

    public static Properties params = new Properties();

    public static void main (String[] args) throws Exception {

        String propertyfile = System.getenv(PROP_FILE_ENV);

        try {
            params.load(new InputStreamReader(new FileInputStream(propertyfile), StandardCharsets.UTF_8));
            params.store(System.out, "CONFIGURATION");
        } catch (Exception e) {
            throw new RuntimeException("Unable to locate configuration: " + propertyfile, e);
        }

        String outputfile = System.getenv(OUT_PROP_FILE_ENV);
        if (not(outputfile)) throw new RuntimeException ("No "+OUT_PROP_FILE_ENV+" env-var has been found");
        else System.out.println("Output will be dumped in "+outputfile);

        String scriptfile = getStringParam(GROOVY_SCRIPT);
        String workdir = params.getProperty(WORKING_DIR, "./");

        String classpath = params.getProperty(GROOVY_CLASSPATH);
        String[] urls = is(classpath) ? classpath.split(":") : new String[0];
        urls = Arrays.copyOf(urls, urls.length+1);
        urls[urls.length-1] = new File(workdir).getAbsolutePath()+"/";
        for (int i = 0; i<urls.length; i++)
            if (!new File(urls[i]).isAbsolute())
                urls[i] = new File(workdir, urls[i]).getAbsolutePath()+"/";


        GroovyScriptEngine engine = new GroovyScriptEngine(urls);
        Binding vars = new Binding();
        vars.setVariable("config", params);
        Map allproperties = new HashMap();
        allproperties.putAll(System.getProperties());
        allproperties.putAll(params);
        GroovyResolversConfig.fromMap(allproperties);

        System.out.println("Running script: "+scriptfile);

        Object result = engine.run(scriptfile, vars);

        Map jobOutput = new HashMap();
        if (getBooleanParam(FORWARD_PARAMETERS, false)) {
            for (String key : params.stringPropertyNames())
                if (!isReservedKey(key))
                    jobOutput.put(key, params.getProperty(key));
        }
        
        boolean checkOutput = getBooleanParam(CHECK_OUTPUT, false);
        if (result == null && checkOutput) throw new RuntimeException(CHECK_OUTPUT +" is set to true, but no output from script");
        if (! (result instanceof Map) && checkOutput) throw new RuntimeException(CHECK_OUTPUT +" is set to true, but I got output from script");
        if (result != null && result instanceof Map) {
            System.out.println("Fetching result from script");
            jobOutput.putAll((Map) result);
        }

        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try (PrintWriter writer = new PrintWriter(outputfile, "UTF-8")) {
            gson.toJson(jobOutput, writer);

            System.out.println("Script succeded, result:");
            System.out.println("=======================");
            gson.toJson(jobOutput, System.out);
            System.out.println("\n=======================");


        } catch (Exception e) {
            throw new RuntimeException("Script execution succeded, but I was unable to dump result: " + e.getMessage(), e);
        }

        System.exit(0);
    }

    static boolean isReservedKey(String key) {
        return key.startsWith("azkaban.") && key.equals("working.dir");
    }

    static String getStringParam(String key) throws Exception {
        String val = params.getProperty(key);
        if (not(val)) throw new RuntimeException ("No property with key ["+key+"] has been found");
        else return val;
    }
    static boolean getBooleanParam(String key, boolean def) {
        String v = params.getProperty(key, Boolean.toString(def));
        return "true".equalsIgnoreCase(v);
    }


    static boolean is(String s) {
        return s != null && s.length() > 0;
    }
    static boolean not(String s) {
        return s == null || s.length() == 0;
    }


}

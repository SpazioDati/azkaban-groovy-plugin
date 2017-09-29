package eu.spaziodati.azkaban;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import groovy.lang.Binding;
import groovy.util.GroovyScriptEngine;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;


/**
 * Groovy executor for Azkaban Flows
 */
public class AzkabanGroovyRunner {

    static final String PROP_FILE_ENV = "JOB_PROP_FILE";
    static final String OUT_PROP_FILE_ENV = "JOB_OUTPUT_PROP_FILE";

    public static final String WORKING_DIR = "working.dir";
    // the path of the groovy script
    public static final String GROOVY_VERBOSE = "groovy.remote.verbose";
    public static final String GROOVY_SCRIPT = "groovy.script";
    public static final String GROOVY_COMMAND = "groovy.command";
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
        boolean verbose;

        try {
            params.load(new InputStreamReader(new FileInputStream(propertyfile), StandardCharsets.UTF_8));
            verbose = Boolean.parseBoolean(params.getProperty(GROOVY_VERBOSE, "false"));

            if (verbose)
              params.store(System.out, "CONFIGURATION");
        } catch (Exception e) {
            throw new RuntimeException("Unable to locate configuration: " + propertyfile, e);
        }

        String outputfile = System.getenv(OUT_PROP_FILE_ENV);
        if (not(outputfile))
            throw new RuntimeException ("No "+OUT_PROP_FILE_ENV+" env-var has been found");
        else
            System.out.println("Output will be dumped in "+outputfile);

        String workdir = params.getProperty(WORKING_DIR, "./");

        Map<String, String> commandsmap = getMapParam(GROOVY_COMMAND);
        String scriptfile;
        if (commandsmap.size() > 0)
            scriptfile = params.getProperty(GROOVY_SCRIPT, null);
        else
            scriptfile = getStringParam(GROOVY_SCRIPT);

        if (scriptfile == null)
            scriptfile = createScriptFromCommand(commandsmap, new File(workdir), System.out);


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

    static String getStringParam(String key) throws Exception {
        String val = params.getProperty(key);
        if (not(val)) throw new RuntimeException ("No property with key ["+key+"] has been found");
        else return val;
    }
    static boolean getBooleanParam(String key, boolean def) {
        String v = params.getProperty(key, Boolean.toString(def));
        return "true".equalsIgnoreCase(v);
    }

    static Map<String,String> getMapParam(String prefix) {
        Map<String, String> map = new HashMap<>();
        for (String k : params.stringPropertyNames())
            if (k.startsWith(prefix))
                map.put(k.substring(prefix.length()), params.getProperty(k));
        return map;
    }

    static boolean is(String s) {
        return s != null && s.length() > 0;
    }
    static boolean not(String s) {
        return s == null || s.length() == 0;
    }


    public static boolean isReservedKey(String key) {
        return key.startsWith("azkaban.") && key.equals("working.dir") && key.startsWith("groovy.");
    }

    public static String createScriptFromCommand(Map<String, String> commandmap, File wd, Appendable log)
            throws IOException {
        List<Map.Entry<String, String>> entrylist = new ArrayList<>(commandmap.entrySet());
        Collections.sort(entrylist, new Comparator<Map.Entry<String, String>>() {
            @Override
            public int compare(Map.Entry<String, String> o1, Map.Entry<String, String> o2) {
                return o1.getKey().compareTo(o2.getKey());
            }
        });
        String commandstring = "";
        List<String> commands = new ArrayList<>();
        for (Map.Entry<String, String> entry : entrylist) {
            commands.add(entry.getValue());
            commandstring += entry.getValue() +'\n';
        }

        log.append("Using command list:\n" + commandstring);

        File fscript = new File(wd, System.currentTimeMillis()+".groovy");
        FileUtils.writeLines(fscript, "UTF-8", commands);
        return fscript.getName();
    }
}

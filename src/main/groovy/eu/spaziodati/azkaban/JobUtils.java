package eu.spaziodati.azkaban;


import azkaban.execapp.AzkabanExecutorServer;
import azkaban.execapp.FlowRunner;
import azkaban.execapp.FlowRunnerManager;
import azkaban.execapp.JobRunner;
import azkaban.executor.Status;
import azkaban.utils.Props;
import azkaban.utils.PropsUtils;
import groovy.util.Eval;
import org.apache.log4j.Logger;

import java.util.Map;
import java.util.Set;

public class JobUtils {
    
    public static final String SKIP_PROP = "azkaban.flow.skip";
    public static final String NOOP_PROP = "azkaban.flow.noop";
    public static final String FORWARD_PARAMETERS = "groovy.forwardParameters";


    public static Props checkPreconditions(String jobId, Props props, Logger log) {
        log.info("Checking pre-conditions...");
        
        int execId = props.getInt("azkaban.flow.execid");
        JobRunner runner = myJobRunner(execId, jobId);
        if (props.containsKey(SKIP_PROP)) {
            String raw = props.get(SKIP_PROP);
            boolean skip = isTrue(raw, props);
            if (skip) {
                log.info("SKIP for this job: " + SKIP_PROP + "=" + raw);
                runner.getNode().setStatus(Status.SKIPPED);
                runner.getNode().setUpdateTime(System.currentTimeMillis());
                Props p = new Props();
                p.put("azkaban.flow.skip", "true");
                return p;
            }
        } 
        
        if (props.containsKey(NOOP_PROP)) {
            String raw = props.get(NOOP_PROP);
            if (isTrue(raw, props)) {
                log.info("NOOP for this job: " + NOOP_PROP + "=" + raw);
                if (props.getBoolean(FORWARD_PARAMETERS, false))
                    return forwardParameters(props);
                else
                    return new Props();
            }
        }
        
        log.info("Pre-conditions satisfied");
        
        return null;
        
    }
    
    public static Props forwardParameters(Props jobProps) {

        Props newprops = new Props();
        for (String k : jobProps.getKeySet()) {
            if (!k.startsWith("azkaban.") && !k.equals("working.dir")) {
                newprops.put(k, jobProps.get(k));
            }
        }
        return newprops;

    }
    
    static boolean isTrue(String raw, Props props) {
        
        Object result = null;
        try {
            Map cfg = PropsUtils.toStringMap(props, false);
            result = Eval.me("config", cfg, raw);
        } catch (Exception e) {}
        
        if (result != null) {
            if (result instanceof Boolean) return (Boolean) result;
            if (result instanceof Integer) return ((Integer)result) != 0;
            if (result instanceof Long) return ((Long)result) != 0;
            if (result instanceof String) raw = (String)result;
        }
        
        if (raw == null || raw.length() == 0) 
            return false;
        try {
            return Integer.parseInt(raw) != 0;
        } catch (Exception e){}
        
        return "true".equalsIgnoreCase(raw) || "yes".equalsIgnoreCase(raw) ||
                "ok".equalsIgnoreCase(raw) || "y".equalsIgnoreCase(raw) ||
                "t".equalsIgnoreCase(raw);
    }

    public static FlowRunner myFlowRunner(int execid) {
        AzkabanExecutorServer server = Reflection.get(AzkabanExecutorServer.class, "app");
        FlowRunnerManager flowmanager = server.getFlowRunnerManager();
        Map<Integer,FlowRunner> runners = Reflection.get(flowmanager, "runningFlows");
        if (runners.containsKey(execid))
            return runners.get(execid);
        else
            return null;
    }
    public static JobRunner myJobRunner(int execId, String jobId) {
        FlowRunner runner = myFlowRunner(execId);
        Set<JobRunner> jobRunners = Reflection.get(runner, "activeJobRunners");
        for (JobRunner jr : jobRunners) {
            String jobId2 = Reflection.get(jr, "jobId");
            if (jobId.equals(jobId2))
                return jr;
        }
        return null;
    }

}

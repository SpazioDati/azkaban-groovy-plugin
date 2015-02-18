package eu.spaziodati.azkaban.jobtype;

import azkaban.execapp.FlowRunner;
import azkaban.execapp.JobRunner;
import azkaban.execapp.event.Event;
import azkaban.execapp.event.EventListener;
import azkaban.executor.ExecutableFlowBase;
import azkaban.executor.Status;
import eu.spaziodati.azkaban.Reflection;
import groovy.lang.Closure;
import org.apache.log4j.Logger;

public class FlowRunnerFinishHandler {
    
    FlowRunner flowrunner;
    JobRunner jobrunner;
    public FlowRunnerFinishHandler(FlowRunner flowrunner, JobRunner jobrunner) {
        this.flowrunner = flowrunner;
        this.jobrunner = jobrunner;
    }
    
    public void register(final Closure closure) {

        final String myFlowid = jobrunner.getNode().getParentFlow().getFlowId();
        final Logger logger = Reflection.get(flowrunner, "logger");
        
        
        EventListener listener = new EventListener() {
            boolean done = false;
            @Override
            public void handleEvent(Event event) {
                if (! done && event.getType() == Event.Type.JOB_FINISHED) {
                    if (event.getData() != null && 
                            event.getData() instanceof ExecutableFlowBase &&
                            // to match nested flows
                            ((ExecutableFlowBase)event.getData()).getFlowId().equals(myFlowid) &&
                            event.getRunner() instanceof FlowRunner) {

                        try {
                            done = true;
                            closure.call();
                        } catch (Exception e) {
                            logger.error("Error during execution of onFinish handler", e);
                            logger.error("Switching state to FAILED");
                            ((ExecutableFlowBase)event.getData()).setStatus(Status.FAILED);
                        }
                    }
                }
            }
        };
        
        flowrunner.addListener(listener);
        
    }
    
}

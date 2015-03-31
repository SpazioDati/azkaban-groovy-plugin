package eu.spaziodati.azkaban.jobtype

import azkaban.execapp.AzkabanExecutorServer
import azkaban.execapp.FlowRunner
import azkaban.execapp.JobRunner
import azkaban.execapp.event.Event
import azkaban.execapp.event.EventListener
import azkaban.executor.ExecutableFlow
import azkaban.executor.ExecutableFlowBase
import azkaban.executor.ExecutionOptions
import azkaban.executor.Status
import azkaban.webapp.AzkabanWebServer
import org.apache.log4j.WriterAppender

public class ScriptHelper {

    FlowRunner flowrunner
    JobRunner jobrunner

    public ScriptHelper(FlowRunner flowrunner, JobRunner jobrunner) {
        this.flowrunner = flowrunner
        this.jobrunner = jobrunner
    }
    def execute(String flowid, Map params = [:], ExecutionOptions options = null) {
        execute("", flowid, params, options)
    }
    def execute(String projectName, String flowid, Map params = [:], ExecutionOptions options = null) {
        def user = flowrunner.executableFlow.submitUser
        def proxyUsers = flowrunner.executableFlow.proxyUsers

        def azkaban = AzkabanWebServer.app
        def project = projectName? fetchProject(projectName) : fetchProject(flowrunner.executableFlow.projectId)
        if (!project) throw new RuntimeException("Unable to find project with name [$projectName]")
        def flow = project.getFlow(flowid)
        if (!flow) throw new RuntimeException("Unable to find flow [$flowid] for project $projectName")

        log("Submitting flow $flowid of project $projectName by user $user ...")

        def execflow = new ExecutableFlow(project, flow)
        execflow.submitUser = user
        execflow.addAllProxyUsers(proxyUsers)

        options = options ?: new ExecutionOptions();
        if (params) options.addAllFlowParameters(params)

        if (!options.isFailureEmailsOverridden())
            options.setFailureEmails(flow.getFailureEmails());
        if (!options.isSuccessEmailsOverridden())
            options.setSuccessEmails(flow.getSuccessEmails());
        options.setMailCreator(flow.getMailCreator());
        execflow.setExecutionOptions(options)

        def result = execute(execflow)

        log("Execution result: $result")

    }

    def fetchProject(String name) {
        if (AzkabanWebServer.app)
            return AzkabanWebServer.app.projectManager.getProject(name)
        else
            return AzkabanExecutorServer.app.projectLoader.fetchAllActiveProjects().find { it.name == name}
    }
    def fetchProject(int id) {
        if (AzkabanWebServer.app)
            return AzkabanWebServer.app.projectManager.getProject(id)
        else
            return AzkabanExecutorServer.app.projectLoader.fetchProjectById(id)
    }
    def execute(ExecutableFlow execflow){
        if (AzkabanWebServer.app)
            return AzkabanWebServer.app.executorManager.submitExecutableFlow(execflow, execflow.submitUser)
        else
            return AzkabanExecutorServer.app.executorLoader.uploadExecutableFlow(execflow)
    }

    def log(String msg, Exception e = null) {

        def logger;

        if (jobrunner.logger &&
                jobrunner.jobAppender &&
                !(jobrunner.jobAppender as WriterAppender).closed)
            logger = jobrunner.logger
        else
            logger = flowrunner.logger

        if (e) logger.error(msg, e)
        else logger.info(msg)
    }

    def onfinish (Closure c) {
        def myFlowid = jobrunner.getNode().getParentFlow().getFlowId();

        EventListener listener = new EventListener() {
            def done = false
            public void handleEvent(Event event) {
                if (! done && event.type == Event.Type.JOB_FINISHED) {
                    if (event.data &&
                            event.data instanceof ExecutableFlowBase &&
                            // to match nested flows
                            (event.data as ExecutableFlowBase).flowId.equals(myFlowid) &&
                            event.runner instanceof FlowRunner) {

                        try {
                            done = true;
                            c.call();
                        } catch (Exception e) {
                            flowrunner.logger.error("Error during execution of onFinish handler", e);
                            flowrunner.logger.error("Switching state to FAILED");
                            (event.getData() as ExecutableFlowBase).setStatus(Status.FAILED);
                        }
                    }
                }
            }
        };

        flowrunner.addListener(listener);
    }

    def register(Closure c) {
        onfinish(c)
    }

}

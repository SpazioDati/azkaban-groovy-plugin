package eu.spaziodati.azkaban.jobtype

import azkaban.execapp.AzkabanExecutorServer
import azkaban.execapp.FlowRunner
import azkaban.execapp.JobRunner
import azkaban.execapp.event.Event
import azkaban.execapp.event.EventListener
import azkaban.executor.ExecutableFlowBase
import azkaban.executor.ExecutionOptions
import azkaban.executor.Status
import azkaban.flow.CommonJobProperties
import azkaban.utils.Props
import azkaban.utils.UndefinedPropertyException
import azkaban.webapp.AzkabanWebServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.utils.URIBuilder
import org.apache.http.impl.client.BasicResponseHandler
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.log4j.WriterAppender

public class ScriptHelper {

    static final def EXECUTE_ENDPOINT = "groovy.execute.endpoint"
    static final def EXECUTE_USERNAME = "groovy.execute.username"
    static final def EXECUTE_PASSWORD = "groovy.execute.password"

    FlowRunner flowrunner
    JobRunner jobrunner
    Props props

    public ScriptHelper(FlowRunner flowrunner, JobRunner jobrunner, Props props) {
        this.flowrunner = flowrunner
        this.jobrunner = jobrunner
        this.props = props
    }

    def execute(String flowid, Map params = [:], ExecutionOptions options = null) {
        execute("", flowid, params, options)
    }

    def execute(String projectName, String flowid, Map params = [:], ExecutionOptions options = null) {

        def http = new DefaultHttpClient();
        try {
            String sessionid = login(http)

            def endpoint = systemProp(EXECUTE_ENDPOINT)
            projectName = projectName?: fetchProject(flowrunner.executableFlow.projectId).name

            log("Trying to execute flow '$flowid' of project '$projectName' ...")

            def uri = new URIBuilder(endpoint)
                .setPath("/executor")
                .addParameter("session.id", sessionid)
                .addParameter("ajax", "executeFlow")
                .addParameter("project", projectName)
                .addParameter("flow", flowid)

            if (options) {
                if (options.disabledJobs)
                    uri.addParameter(ExecutionOptions.DISABLE, JsonOutput.toJson(options.disabledJobs))
                if (options.successEmails) {
                    uri.addParameter(ExecutionOptions.SUCCESS_EMAILS, options.successEmails.join(","))
                    uri.addParameter(ExecutionOptions.SUCCESS_EMAILS_OVERRIDE, "true")
                }
                if (options.failureEmails) {
                    uri.addParameter(ExecutionOptions.FAILURE_EMAILS, options.failureEmails.join(","))
                    uri.addParameter(ExecutionOptions.FAILURE_EMAILS_OVERRIDE, "true")
                }
                
                // TODO add other parameters: http://azkaban.github.io/azkaban/docs/2.5/#api-execute-a-flow
            }
            if (params)
                params.each { k,v -> uri.addParameter("flowOverride[$k]", v.toString()) }

            try {
                def response = http.execute(new HttpGet(uri.build()), new BasicResponseHandler())
                def result = new JsonSlurper().parseText(response)
                if (!result) throw new Exception("No body returned")
                if (result.error) throw new Exception((String)result.error)

                log("Execution of flow '$flowid' of project '$projectName' successfully submitted:\n ${JsonOutput.prettyPrint(response)}")

            } catch (Exception e) {
                throw new Exception("Error while sending execution command to executor: $e", e);
            }
        } finally {
            try { http.getConnectionManager().shutdown();}
            catch (Exception e) {}
        }

    }

    def login(HttpClient client) {
        def endpoint = systemProp(EXECUTE_ENDPOINT)
        def username = systemProp(EXECUTE_USERNAME)
        def password = systemProp(EXECUTE_PASSWORD)

        def loginuri = new URIBuilder(endpoint+"/")
                .addParameter("action", "login")
                .addParameter("username", username)
                .addParameter("password", password)
                .build()
        try {
            def resp = client.execute(new HttpPost(loginuri), new BasicResponseHandler())
            def result = new JsonSlurper().parseText(resp)
            if (!result) throw new Exception("No body returned")
            if (result.error) throw new Exception("Login failed: "+result.error)
            if (!result['session.id']) throw new Exception("No session.id returned by executor: [$result]")
            return result['session.id']
        } catch (Exception e) {
            throw new Exception("Error while login to executor: $e", e);
        }
    }

    def systemProp(String name) {
        if (!props.containsKey(name) && !AzkabanExecutorServer.app.azkabanProps.containsKey(name))
            throw new UndefinedPropertyException("Missing required property '$name'");
        else if (props.containsKey(name)) return props.get(name)
        else return AzkabanExecutorServer.app.azkabanProps.get(name)
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
        //this is not reliable, i don't know why. It looks like the parentFlow is shared
        //even if two jobs are executed in different branches
        //  def myFlowid = jobrunner.getNode().getParentFlow().nestedId;
        //so we have to parse the property attached to the jobrunner
        def myFlowid = props.get(CommonJobProperties.NESTED_FLOW_PATH)
        if (myFlowid && myFlowid.contains(":")) {
            myFlowid = myFlowid[0..<myFlowid.lastIndexOf(":")]
        } else {
            myFlowid = jobrunner.getNode().getParentFlow().getFlowId()
        }

        EventListener listener = new EventListener() {
            def done = false
            public void handleEvent(Event event) {

                if (!done && event.type == Event.Type.JOB_FINISHED) {

                    if (event.data &&
                            event.data instanceof ExecutableFlowBase &&
                            // to match nested flows
                            (event.data as ExecutableFlowBase).nestedId.equals(myFlowid) &&
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

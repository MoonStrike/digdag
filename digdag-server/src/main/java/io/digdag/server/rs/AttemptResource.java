package io.digdag.server.rs;

import java.util.List;
import java.util.stream.Collectors;
import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.PUT;
import javax.ws.rs.POST;
import javax.ws.rs.GET;
import javax.ws.rs.core.Response;

import com.google.inject.Inject;
import com.google.common.base.Optional;
import io.digdag.core.session.SessionStore;
import io.digdag.core.session.SessionStoreManager;
import io.digdag.core.workflow.*;
import io.digdag.core.session.*;
import io.digdag.core.repository.*;
import io.digdag.core.schedule.SchedulerManager;
import io.digdag.client.config.ConfigFactory;
import io.digdag.client.api.*;
import io.digdag.spi.ScheduleTime;

@Path("/")
@Produces("application/json")
public class AttemptResource
    extends AuthenticatedResource
{
    // GET  /api/attempts                                    # list attempts from recent to old
    // GET  /api/attempts?include_retried=1                  # list attempts from recent to old
    // GET  /api/attempts?project=<name>                     # list attempts that belong to a particular project
    // GET  /api/attempts?project=<name>&workflow=<name>     # list attempts that belong to a particular workflow
    // GET  /api/attempts/{id}                               # show a session
    // GET  /api/attempts/{id}/tasks                         # list tasks of a session
    // GET  /api/attempts/{id}/retries                       # list retried attempts of this session
    // PUT  /api/attempts                                    # starts a new session
    // POST /api/attempts/{id}/kill                          # kill a session

    private final ProjectStoreManager rm;
    private final SessionStoreManager sm;
    private final SchedulerManager srm;
    private final AttemptBuilder attemptBuilder;
    private final WorkflowExecutor executor;
    private final ConfigFactory cf;

    @Inject
    public AttemptResource(
            ProjectStoreManager rm,
            SessionStoreManager sm,
            SchedulerManager srm,
            AttemptBuilder attemptBuilder,
            WorkflowExecutor executor,
            ConfigFactory cf)
    {
        this.rm = rm;
        this.sm = sm;
        this.srm = srm;
        this.attemptBuilder = attemptBuilder;
        this.executor = executor;
        this.cf = cf;
    }

    @GET
    @Path("/api/attempts")
    public List<RestSessionAttempt> getAttempts(
            @QueryParam("project") String projName,
            @QueryParam("workflow") String wfName,
            @QueryParam("include_retried") boolean includeRetried,
            @QueryParam("last_id") Long lastId)
        throws ResourceNotFoundException
    {
        List<StoredSessionAttemptWithSession> attempts;

        ProjectStore rs = rm.getProjectStore(getSiteId());
        SessionStore ss = sm.getSessionStore(getSiteId());
        if (projName != null) {
            StoredProject proj = rs.getProjectByName(projName);
            if (wfName != null) {
                // of workflow
                StoredWorkflowDefinition wf = rs.getLatestWorkflowDefinitionByName(proj.getId(), wfName);
                attempts = ss.getAttemptsOfWorkflow(includeRetried, wf.getId(), 100, Optional.fromNullable(lastId));
            }
            else {
                // of project
                attempts = ss.getAttemptsOfProject(includeRetried, proj.getId(), 100, Optional.fromNullable(lastId));
            }
        }
        else {
            // of site
            attempts = ss.getAttempts(includeRetried, 100, Optional.fromNullable(lastId));
        }

        return RestModels.attemptModels(rm, getSiteId(), attempts);
    }

    @GET
    @Path("/api/attempts/{id}")
    public RestSessionAttempt getAttempt(@PathParam("id") long id)
        throws ResourceNotFoundException
    {
        StoredSessionAttemptWithSession attempt = sm.getSessionStore(getSiteId())
            .getAttemptById(id);
        StoredProject proj = rm.getProjectStore(getSiteId())
                .getProjectById(attempt.getSession().getProjectId());

        return RestModels.attempt(attempt, proj.getName());
    }

    @GET
    @Path("/api/attempts/{id}/retries")
    public List<RestSessionAttempt> getAttemptRetries(@PathParam("id") long id)
        throws ResourceNotFoundException
    {
        List<StoredSessionAttemptWithSession> attempts = sm.getSessionStore(getSiteId())
            .getOtherAttempts(id);

        return RestModels.attemptModels(rm, getSiteId(), attempts);
    }

    @GET
    @Path("/api/attempts/{id}/tasks")
    public List<RestTask> getTasks(@PathParam("id") long id)
    {
        return sm.getSessionStore(getSiteId())
            .getTasksOfAttempt(id)
            .stream()
            .map(task -> RestModels.task(task))
            .collect(Collectors.toList());
    }

    @PUT
    @Consumes("application/json")
    @Path("/api/attempts")
    public Response startAttempt(RestSessionAttemptRequest request)
        throws ResourceNotFoundException, ResourceConflictException
    {
        ProjectStore rs = rm.getProjectStore(getSiteId());

        StoredWorkflowDefinitionWithProject def = rs.getWorkflowDefinitionById(request.getWorkflowId());

        // use the HTTP request time as the runTime
        AttemptRequest ar = attemptBuilder.buildFromStoredWorkflow(
                def,
                request.getParams(),
                ScheduleTime.runNow(request.getSessionTime()),
                request.getRetryAttemptName());

        try {
            StoredSessionAttemptWithSession attempt = executor.submitWorkflow(getSiteId(), ar, def);
            RestSessionAttempt res = RestModels.attempt(attempt, def.getProject().getName());
            return Response.ok(res).build();
        }
        catch (SessionAttemptConflictException ex) {
            StoredSessionAttemptWithSession conflicted = ex.getConflictedSession();
            RestSessionAttempt res = RestModels.attempt(conflicted, def.getProject().getName());
            return Response.status(Response.Status.CONFLICT).entity(res).build();
        }
    }

    @POST
    @Consumes("application/json")
    @Path("/api/attempts/{id}/kill")
    public void killAttempt(@PathParam("id") long id)
        throws ResourceNotFoundException, ResourceConflictException
    {
        boolean updated = executor.killAttemptById(getSiteId(), id);
        if (!updated) {
            throw new ResourceConflictException("Session attempt already killed or finished");
        }
    }
}
package org.marvelution.jji.trigger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletResponse;

import org.marvelution.jji.security.SyncTokenSecurityContext;

import hudson.model.*;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

import static org.marvelution.jji.JiraUtils.getJsonFromRequest;

public class JiraBuildTriggerAction<JobT extends Job<JobT, ?> & Queue.Task>
        implements Action
{

    static final String ISSUE_KEY = "issueKey";
    static final String ISSUE_URL = "issueUrl";
    static final String PARAMETERS = "parameters";
    static final String NAME = "name";
    private static final Logger LOGGER = Logger.getLogger(JiraBuildTriggerAction.class.getName());
    private final JobT target;

    public JiraBuildTriggerAction(JobT target)
    {
        this.target = target;
    }

    @Override
    public String getIconFileName()
    {
        return null;
    }

    @Override
    public String getDisplayName()
    {
        return null;
    }

    @Override
    public String getUrlName()
    {
        return "jji";
    }

    @RequirePOST
    @SuppressWarnings("lgtm[jenkins/no-permission-check]")
    public void doBuild(
            StaplerRequest request,
            StaplerResponse response)
            throws IOException
    {
        SyncTokenSecurityContext.checkSyncTokenAuthentication(request);
        if (target.isBuildable())
        {
            List<Action> actions = new ArrayList<>();

            JSONObject data = getJsonFromRequest(request);
            LOGGER.log(Level.INFO,
                    "Received build trigger form Jira for {0}; {1}",
                    new Object[]{target.getDisplayName(),
                            data.toString()});
            String by = data.getString("by");
            String issueKey = data.optString(ISSUE_KEY, null);
            String issueUrl = data.optString(ISSUE_URL, null);
            actions.add(new CauseAction(new JiraCause(issueKey, by)));
            if (issueKey != null && issueUrl != null)
            {
                actions.add(new JiraIssueAction(issueUrl, issueKey));
            }

            ParametersDefinitionProperty parametersDefinitionProperty = target.getProperty(ParametersDefinitionProperty.class);
            if (parametersDefinitionProperty != null)
            {
                List<ParameterValue> parameterValues = new ArrayList<>();
                if (issueKey != null)
                {
                    ParameterDefinition issueKeyParameter = getParameterDefinition(parametersDefinitionProperty, ISSUE_KEY, "issue_key");
                    if (issueKeyParameter != null)
                    {
                        parameterValues.add(new StringParameterValue(issueKeyParameter.getName(),
                                issueKey,
                                issueKeyParameter.getDescription()));
                    }
                }
                if (issueUrl != null)
                {
                    ParameterDefinition issueUrlParameter = getParameterDefinition(parametersDefinitionProperty, ISSUE_URL, "issue_url");
                    if (issueUrlParameter != null)
                    {
                        parameterValues.add(new StringParameterValue(issueUrlParameter.getName(),
                                issueUrl,
                                issueUrlParameter.getDescription()));
                    }
                }

                JSONArray parameters = data.optJSONArray(PARAMETERS);
                if (parameters != null)
                {
                    LOGGER.log(Level.FINE, "Matching job parameters to values provided by Jira; {0}", parameters.toString());

                    for (Object object : parameters)
                    {
                        JSONObject parameter = (JSONObject) object;
                        String name = parameter.getString(NAME);

                        ParameterDefinition parameterDefinition = getParameterDefinition(parametersDefinitionProperty, name);
                        if (parameterDefinition != null)
                        {
                            parameter.element(NAME, parameterDefinition.getName());
                            ParameterValue parameterValue = parameterDefinition.createValue(request, parameter);
                            if (parameterValue != null)
                            {
                                parameterValues.add(parameterValue);
                            }
                            else if ((parameterValue = parameterDefinition.getDefaultParameterValue()) != null)
                            {
                                parameterValues.add(parameterValue);
                            }
                            else
                            {
                                LOGGER.log(Level.WARNING,
                                        "Unable to match a value for parameter {0}. " +
                                        "Jira didn't provide a value and the parameter definition has no default value.",
                                        parameterDefinition.getName());
                            }
                        }
                    }
                }
                actions.add(new ParametersAction(parameterValues));
            }

            Optional.ofNullable(Jenkins.getInstanceOrNull())
                    .orElseThrow(() -> new IllegalStateException("No Jenkins instance found"))
                    .getQueue()
                    .schedule2(target, 0, actions);

            response.setStatus(HttpServletResponse.SC_CREATED);
        }
        else
        {
            response.setStatus(HttpServletResponse.SC_NOT_ACCEPTABLE);
        }
    }

    @Nullable
    private ParameterDefinition getParameterDefinition(
            ParametersDefinitionProperty parametersDefinitionProperty,
            String... names)
    {
        LOGGER.log(Level.FINE, "Looking for a build parameter that matches: {0}", String.join(",", names));
        for (String name : names)
        {
            for (ParameterDefinition parameterDefinition : parametersDefinitionProperty.getParameterDefinitions())
            {
                if (parameterDefinition.getName()
                        .equalsIgnoreCase(name))
                {
                    LOGGER.log(Level.FINE,
                            "Found build parameter {0} of type {1}",
                            new Object[]{parameterDefinition.getName(),
                                    parameterDefinition.getType()});
                    return parameterDefinition;
                }
            }
        }
        LOGGER.log(Level.FINE, "No matching build parameter found");
        return null;
    }
}

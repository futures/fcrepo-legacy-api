/**
 * Copyright 2013 DuraSpace, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fcrepo.legacy;

import static com.google.common.collect.ImmutableMap.builder;
import static javax.jcr.Repository.REP_NAME_DESC;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.APPLICATION_XML;
import static javax.ws.rs.core.MediaType.TEXT_HTML;
import static javax.ws.rs.core.MediaType.TEXT_XML;
import static javax.ws.rs.core.Response.ok;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;

import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.NodeTypeIterator;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

import org.fcrepo.http.commons.AbstractResource;
import org.fcrepo.http.commons.session.InjectedSession;
import org.fcrepo.jaxb.responses.access.DescribeRepository;
import org.fcrepo.kernel.services.ObjectService;
import org.fcrepo.kernel.services.RepositoryService;
import org.fcrepo.provider.VelocityViewer;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.codahale.metrics.annotation.Timed;
import com.google.common.collect.ImmutableMap.Builder;

@Component("fedoraLegacyRepository")
@Scope("prototype")
@Path("/v3/describe")
public class FedoraRepository extends AbstractResource {

    private static final Logger logger = getLogger(FedoraRepository.class);

    @Autowired
    private Repository repo;

    @Autowired
    private ObjectService objectService;

    @InjectedSession
    protected Session session;

    @GET
    @Path("modeshape")
    @Timed
    public Response describeModeshape() throws IOException, RepositoryException {
        logger.debug("Repository name: " + repo.getDescriptor(REP_NAME_DESC));
        final Builder<String, Object> repoproperties = builder();
        for (final String key : repo.getDescriptorKeys()) {
            if (repo.getDescriptor(key) != null) {
                repoproperties.put(key, repo.getDescriptor(key));
            }
        }

        // add in node namespaces
        final Builder<String, String> namespaces = builder();
        namespaces.putAll(RepositoryService.getRepositoryNamespaces(session));
        repoproperties.put("node.namespaces", namespaces.build());

        // add in node types
        final Builder<String, String> nodetypes = builder();
        final NodeTypeIterator i = objectService.getAllNodeTypes(session);
        while (i.hasNext()) {
            final NodeType nt = i.nextNodeType();
            nodetypes.put(nt.getName(), nt.toString());
        }
        repoproperties.put("node.types", nodetypes.build());
        session.logout();
        return ok(repoproperties.build().toString()).build();
    }

    @GET
    @Timed
    @Produces({TEXT_XML, APPLICATION_XML, APPLICATION_JSON})
    public DescribeRepository describe() throws RepositoryException {

        final DescribeRepository description = new DescribeRepository();
        description.repositoryBaseURL = uriInfo.getBaseUri();
        description.sampleOAIURL =
                uriInfo.getBaseUriBuilder().path(
                        LegacyPathHelpers.OBJECT_PATH + "/123/oai_dc").build();
        description.repositorySize = objectService.getRepositorySize();
        description.numberOfObjects = objectService.getRepositoryObjectCount();
        session.logout();
        return description;
    }

    @GET
    @Timed
    @Produces(TEXT_HTML)
    public String describeHtml() throws RepositoryException {

        final VelocityViewer view = new VelocityViewer();
        return view.getRepoInfo(describe());
    }

    /**
     * A testing convenience setter for otherwise injected resources
     * 
     * @param repo
     */
    public void setRepository(final Repository repo) {
        this.repo = repo;
    }

    public void setObjectService(ObjectService objectService) {
        this.objectService = objectService;
    }

    public void setSession(final Session session) {
        this.session = session;
    }

}

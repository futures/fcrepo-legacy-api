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

import static com.google.common.collect.ImmutableList.builder;
import static java.lang.Integer.parseInt;
import static javax.jcr.query.Query.JCR_SQL2;
import static javax.ws.rs.core.MediaType.TEXT_HTML;
import static org.slf4j.LoggerFactory.getLogger;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.ValueFactory;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

import org.fcrepo.http.commons.AbstractResource;
import org.fcrepo.http.commons.session.InjectedSession;
import org.fcrepo.jaxb.search.FieldSearchResult;
import org.fcrepo.jaxb.search.ObjectFields;
import org.fcrepo.jcr.FedoraJcrTypes;
import org.fcrepo.provider.VelocityViewer;
import org.slf4j.Logger;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.google.common.collect.ImmutableList;
import com.codahale.metrics.annotation.Timed;

/**
 * @author Vincent Nguyen
 */

@Component("fedoraLegacySearch")
@Scope("prototype")
@Path("/v3/search")
public class FedoraFieldSearch extends AbstractResource implements
        FedoraJcrTypes {

    private static final Logger logger = getLogger(FedoraFieldSearch.class);

    private static final String QUERY_STRING = buildQueryString();

    @InjectedSession
    protected Session session;

    @GET
    @Timed
    @Produces(TEXT_HTML)
    public String searchForm() throws RepositoryException {
        return new VelocityViewer().getFieldSearch(null);
    }

    @POST
    @Timed
    @Produces(TEXT_HTML)
    public String searchSubmit(@FormParam("terms")
    final String terms, @FormParam("offSet")
    @DefaultValue("0")
    final String offSet, @FormParam("maxResults")
    final String maxResults) throws RepositoryException {

        final QueryManager queryManager =
                session.getWorkspace().getQueryManager();
        final ValueFactory valueFactory = session.getValueFactory();
        final VelocityViewer view = new VelocityViewer();

        logger.debug("Searching for " + terms);

        final Query query = getQuery(queryManager, valueFactory, terms);
        logger.debug("statement is " + query.getStatement());

        final FieldSearchResult fsr =
                search(query, parseInt(offSet), parseInt(maxResults));
        fsr.setSearchTerms(terms);

        session.logout();

        return view.getFieldSearch(fsr);
    }

    Query getQuery(final QueryManager queryManager,
            final ValueFactory valueFactory, final String terms)
        throws RepositoryException {
        final Query query = queryManager.createQuery(QUERY_STRING, JCR_SQL2);
        query.bindValue("sterm", valueFactory.createValue("%" + terms + "%"));
        logger.debug("statement is " + query.getStatement());
        return query;
    }

    /**
     * Searches the repository using JCR SQL2 queries and returns a
     * FieldSearchResult object
     * 
     * @param sqlExpression
     * @param offSet
     * @param maxResults
     * @return
     * @throws LoginException
     * @throws RepositoryException
     */
    public FieldSearchResult search(final Query query, final int offSet,
            final int maxResults) throws RepositoryException {

        final ImmutableList.Builder<ObjectFields> fieldObjects = builder();

        final QueryResult queryResults = query.execute();

        final NodeIterator nodeIter = queryResults.getNodes();
        final int size = (int) nodeIter.getSize();
        logger.debug(size + " results found");

        // add the next set of results to the fieldObjects starting at offSet
        // for pagination
        int i = offSet;
        nodeIter.skip(offSet);
        while (nodeIter.hasNext() && i < offSet + maxResults) {
            final ObjectFields obj = new ObjectFields();
            try {
                final Node node = nodeIter.nextNode();
                obj.setPid(node.getName());
                obj.setPath(node.getPath());
                fieldObjects.add(obj);
            } catch (final RepositoryException ex) {
                logger.error(ex.getMessage());
            }
            i++;
        }

        final FieldSearchResult fsr =
                new FieldSearchResult(fieldObjects.build(), offSet, maxResults,
                        size);
        fsr.setStart(offSet);
        fsr.setMaxResults(maxResults);

        return fsr;
    }

    public static String buildQueryString() {
        // TODO expand to more fields
        final String sqlExpression =
                "SELECT * FROM [" + FEDORA_OBJECT +
                        "] WHERE [dc:identifier] like $sterm OR [dc:title] like $sterm";
        return sqlExpression;
    }

    public void setSession(final Session session) {
        this.session = session;
    }
}

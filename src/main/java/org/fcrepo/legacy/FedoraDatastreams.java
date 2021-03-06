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

import static com.google.common.collect.ImmutableSet.copyOf;
import static com.google.common.collect.Iterators.filter;
import static com.google.common.collect.Iterators.transform;
import static java.util.Collections.singletonList;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.APPLICATION_OCTET_STREAM_TYPE;
import static javax.ws.rs.core.MediaType.MULTIPART_FORM_DATA;
import static javax.ws.rs.core.MediaType.TEXT_XML;
import static javax.ws.rs.core.Response.created;
import static javax.ws.rs.core.Response.noContent;
import static org.fcrepo.jaxb.responses.management.DatastreamProfile.DatastreamStates.A;
import static org.fcrepo.legacy.LegacyPathHelpers.getObjectPath;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;

import org.fcrepo.http.commons.AbstractResource;
import org.fcrepo.http.commons.session.InjectedSession;
import org.fcrepo.jaxb.responses.access.ObjectDatastreams;
import org.fcrepo.jaxb.responses.access.ObjectDatastreams.DatastreamElement;
import org.fcrepo.jaxb.responses.management.DatastreamHistory;
import org.fcrepo.jaxb.responses.management.DatastreamProfile;
import org.fcrepo.kernel.Datastream;
import org.fcrepo.kernel.exception.InvalidChecksumException;
import org.fcrepo.kernel.utils.ContentDigest;
import org.fcrepo.kernel.utils.FedoraTypesUtils;
import org.slf4j.Logger;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.codahale.metrics.annotation.Timed;
import com.google.common.base.Function;
import com.sun.jersey.multipart.BodyPart;
import com.sun.jersey.multipart.BodyPartEntity;
import com.sun.jersey.multipart.MultiPart;

@Component("fedoraLegacyDatastreams")
@Scope("prototype")
@Path("/v3/objects/{pid}/datastreams")
public class FedoraDatastreams extends AbstractResource {

    private final Logger logger = getLogger(FedoraDatastreams.class);

    @InjectedSession
    protected Session session;

    /**
     * Returns a list of datastreams for the object
     * 
     * @param pid persistent identifier of the digital object
     * @return the list of datastreams
     * @throws RepositoryException
     * @throws IOException
     */

    @GET
    @Timed
    @Produces({TEXT_XML, APPLICATION_JSON})
    public ObjectDatastreams getDatastreams(@PathParam("pid")
    final String pid) throws RepositoryException, IOException {

        try {
            final ObjectDatastreams objectDatastreams = new ObjectDatastreams();

            objectDatastreams.datastreams =
                    getDatastreamsForPath(session, getObjectPath(pid));

            return objectDatastreams;
        } finally {
            session.logout();
        }

    }

    private Set<DatastreamElement> getDatastreamsForPath(Session session,
            String objectPath) throws RepositoryException {
        final NodeIterator nodes =
                nodeService.getObject(session, objectPath).getNode().getNodes();
        return copyOf(transform(filter(
                new org.fcrepo.kernel.utils.NodeIterator(nodes),
                FedoraTypesUtils.isFedoraDatastream), ds2dsElement));
    }

    @POST
    @Timed
    public Response modifyDatastreams(@PathParam("pid")
    final String pid, @QueryParam("delete")
    final List<String> dsidList, final MultiPart multipart)
        throws RepositoryException, IOException, InvalidChecksumException {

        try {
            for (final String dsid : dsidList) {
                logger.debug("Purging datastream: " + dsid);
                nodeService.deleteObject(session, LegacyPathHelpers
                        .getDatastreamsPath(pid, dsid));
            }

            for (final BodyPart part : multipart.getBodyParts()) {
                final String dsid =
                        part.getContentDisposition().getParameters()
                                .get("name");
                logger.debug("Adding datastream: " + dsid);
                final String dsPath =
                        LegacyPathHelpers.getDatastreamsPath(pid, dsid);
                final Object obj = part.getEntity();
                InputStream src = null;
                if (obj instanceof BodyPartEntity) {
                    final BodyPartEntity entity =
                            (BodyPartEntity) part.getEntity();
                    src = entity.getInputStream();
                } else if (obj instanceof InputStream) {
                    src = (InputStream) obj;
                }
                datastreamService.createDatastreamNode(session, dsPath, part
                        .getMediaType().toString(), src);
            }

            session.save();
            return created(uriInfo.getRequestUri()).build();
        } finally {
            session.logout();
        }
    }

    @DELETE
    @Timed
    public Response deleteDatastreams(@PathParam("pid")
    final String pid, @QueryParam("dsid")
    final List<String> dsidList) throws RepositoryException {
        try {
            for (final String dsid : dsidList) {
                logger.debug("purging datastream " + dsid);
                nodeService.deleteObject(session, LegacyPathHelpers
                        .getDatastreamsPath(pid, dsid));
            }
            session.save();
            return noContent().build();
        } finally {
            session.logout();
        }
    }

    @GET
    @Path("/__content__")
    @Produces("multipart/mixed")
    @Timed
    public Response getDatastreamsContents(@PathParam("pid")
    final String pid, @QueryParam("dsid")
    final List<String> dsids) throws RepositoryException, IOException {

        try {
            if (dsids.isEmpty()) {
                final NodeIterator ni =
                        objectService
                                .getObjectNode(session, getObjectPath(pid))
                                .getNodes();
                while (ni.hasNext()) {
                    dsids.add(ni.nextNode().getName());
                }
            }

            final MultiPart multipart = new MultiPart();

            final Iterator<String> i = dsids.iterator();
            while (i.hasNext()) {
                final String dsid = i.next();

                try {
                    final Datastream ds =
                            datastreamService.getDatastream(session,
                                    LegacyPathHelpers.getDatastreamsPath(pid,
                                            dsid));
                    multipart.bodyPart(ds.getContent(), MediaType.valueOf(ds
                            .getMimeType()));
                } catch (final PathNotFoundException e) {

                }
            }
            return Response.ok(multipart, MULTIPART_FORM_DATA).build();
        } finally {
            session.logout();
        }
    }

    /**
     * Create a new datastream with user provided checksum for validation
     * 
     * @param pid persistent identifier of the digital object
     * @param dsid datastream identifier
     * @param contentType Content-Type header
     * @param requestBodyStream Binary blob
     * @return 201 Created
     * @throws RepositoryException
     * @throws IOException
     * @throws InvalidChecksumException
     */
    @POST
    @Path("/{dsid}")
    @Timed
    public Response addDatastream(@PathParam("pid")
    final String pid, @QueryParam("checksum")
    final String checksum, @PathParam("dsid")
    final String dsid, @HeaderParam("Content-Type")
    final MediaType requestContentType, final InputStream requestBodyStream)
        throws IOException, InvalidChecksumException, RepositoryException {
        final MediaType contentType =
                requestContentType != null ? requestContentType
                        : APPLICATION_OCTET_STREAM_TYPE;

        try {
            final String dsPath =
                    LegacyPathHelpers.getDatastreamsPath(pid, dsid);
            logger.debug("addDatastream {}", dsPath);
            final URI checksumURI;

            if (checksum != null && !checksum.isEmpty()) {
                checksumURI = ContentDigest.asURI("SHA-1", checksum);
            } else {
                checksumURI = null;
            }
            datastreamService.createDatastreamNode(session, dsPath, contentType
                    .toString(), requestBodyStream, checksumURI);
            session.save();
            return created(uriInfo.getAbsolutePath()).build();
        } finally {
            session.logout();
        }

    }

    /**
     * Modify an existing datastream's content
     * 
     * @param pid persistent identifier of the digital object
     * @param dsid datastream identifier
     * @param contentType Content-Type header
     * @param requestBodyStream Binary blob
     * @return 201 Created
     * @throws RepositoryException
     * @throws IOException
     * @throws InvalidChecksumException
     */
    @PUT
    @Path("/{dsid}")
    @Timed
    public Response modifyDatastream(@PathParam("pid")
    final String pid, @PathParam("dsid")
    final String dsid, @HeaderParam("Content-Type")
    final MediaType requestContentType, final InputStream requestBodyStream)
        throws RepositoryException, IOException, InvalidChecksumException {

        try {
            final MediaType contentType =
                    requestContentType != null ? requestContentType
                            : APPLICATION_OCTET_STREAM_TYPE;
            final String dsPath =
                    LegacyPathHelpers.getDatastreamsPath(pid, dsid);

            datastreamService.createDatastreamNode(session, dsPath, contentType
                    .toString(), requestBodyStream);
            session.save();
            return created(uriInfo.getRequestUri()).build();
        } finally {
            session.logout();
        }

    }

    /**
     * Get the datastream profile of a datastream
     * 
     * @param pid persistent identifier of the digital object
     * @param dsid datastream identifier
     * @return 200
     * @throws RepositoryException
     * @throws IOException
     * @throws TemplateException
     */
    @GET
    @Path("/{dsid}")
    @Timed
    @Produces({TEXT_XML, APPLICATION_JSON})
    public DatastreamProfile getDatastream(@PathParam("pid")
    final String pid, @PathParam("dsid")
    final String dsid) throws RepositoryException, IOException {

        try {
            logger.trace("Executing getDatastream() with dsId: " + dsid);
            return getDSProfile(datastreamService.getDatastream(session,
                    LegacyPathHelpers.getDatastreamsPath(pid, dsid)));
        } finally {
            session.logout();
        }

    }

    /**
     * Get the binary content of a datastream
     * 
     * @param pid persistent identifier of the digital object
     * @param dsid datastream identifier
     * @return Binary blob
     * @throws RepositoryException
     */
    @GET
    @Path("/{dsid}/content")
    public Response getDatastreamContent(@PathParam("pid")
    final String pid, @PathParam("dsid")
    final String dsid, @Context
    final Request request) throws RepositoryException {

        try {
            final Datastream ds =
                    datastreamService.getDatastream(session, LegacyPathHelpers
                            .getDatastreamsPath(pid, dsid));

            final EntityTag etag =
                    new EntityTag(ds.getContentDigest().toString());
            final Date date = ds.getLastModifiedDate();
            final Date roundedDate = new Date();
            roundedDate.setTime(date.getTime() - date.getTime() % 1000);
            ResponseBuilder builder =
                    request.evaluatePreconditions(roundedDate, etag);

            final CacheControl cc = new CacheControl();
            cc.setMaxAge(0);
            cc.setMustRevalidate(true);

            if (builder == null) {
                builder = Response.ok(ds.getContent(), ds.getMimeType());
            }

            return builder.cacheControl(cc).lastModified(date).tag(etag)
                    .build();
        } finally {
            session.logout();
        }
    }

    /**
     * Get previous version information for this datastream
     * 
     * @param pid persistent identifier of the digital object
     * @param dsid datastream identifier
     * @return 200
     * @throws RepositoryException
     * @throws IOException
     * @throws TemplateException
     */
    @GET
    @Path("/{dsid}/versions")
    @Timed
    @Produces({TEXT_XML, APPLICATION_JSON})
    public DatastreamHistory getDatastreamHistory(@PathParam("pid")
    final String pid, @PathParam("dsid")
    final String dsid) throws RepositoryException, IOException {

        try {
            // TODO implement this after deciding on a versioning model
            final Datastream ds =
                    datastreamService.getDatastream(session, LegacyPathHelpers
                            .getDatastreamsPath(pid, dsid));
            final DatastreamHistory dsHistory =
                    new DatastreamHistory(singletonList(getDSProfile(ds)));
            dsHistory.dsID = dsid;
            dsHistory.pid = pid;
            return dsHistory;
        } finally {
            session.logout();
        }
    }

    /**
     * Purge the datastream
     * 
     * @param pid persistent identifier of the digital object
     * @param dsid datastream identifier
     * @return 204
     * @throws RepositoryException
     */
    @DELETE
    @Path("/{dsid}")
    @Timed
    public Response deleteDatastream(@PathParam("pid")
    final String pid, @PathParam("dsid")
    final String dsid) throws RepositoryException {
        try {
            nodeService.deleteObject(session, LegacyPathHelpers
                    .getDatastreamsPath(pid, dsid));
            session.save();
            return noContent().build();
        } finally {
            session.logout();
        }
    }

    private DatastreamProfile getDSProfile(final Datastream ds)
        throws RepositoryException, IOException {
        logger.trace("Executing getDSProfile() with node: " + ds.getDsId());
        final DatastreamProfile dsProfile = new DatastreamProfile();
        dsProfile.dsID = ds.getDsId();
        dsProfile.pid = ds.getObject().getName();
        logger.trace("Retrieved datastream " + ds.getDsId() + "'s parent: " +
                dsProfile.pid);
        if (ds.getContentDigest() != null) {
            dsProfile.dsChecksumType =
                    ContentDigest.getAlgorithm(ds.getContentDigest());
            dsProfile.dsChecksum = ds.getContentDigest();
        }
        dsProfile.dsState = A;
        dsProfile.dsMIME = ds.getMimeType();
        dsProfile.dsSize = ds.getSize();
        dsProfile.dsCreateDate = ds.getCreatedDate();
        return dsProfile;
    }

    private Function<Node, DatastreamElement> ds2dsElement =
            new Function<Node, DatastreamElement>() {

                @Override
                public DatastreamElement apply(final Node node) {
                    final Datastream ds = new Datastream(node);
                    try {
                        return new DatastreamElement(ds.getDsId(),
                                ds.getDsId(), ds.getMimeType());
                    } catch (final RepositoryException e) {
                        throw new IllegalStateException(e);
                    }
                }
            };

    public void setSession(final Session session) {
        this.session = session;
    }

}

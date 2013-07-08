/*******************************************************************************
 * Copyright (c) 2013 Jens Kristian Villadsen.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 * 
 * Contributors:
 *     Jens Kristian Villadsen - Lead developer, owner and creator
 ******************************************************************************/
package org.dyndns.jkiddo.service.dmap;

import java.io.IOException;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.dyndns.jkiddo.NotImplementedException;
import org.dyndns.jkiddo.service.daap.server.IMusicLibrary;
import org.dyndns.jkiddo.service.dacp.client.ITouchRemoteResource;
import org.dyndns.jkiddo.service.dacp.server.ITouchAbleServerResource;
import org.dyndns.jkiddo.service.dpap.server.IImageLibrary;

import com.google.common.base.Strings;

@Path("/")
@Singleton
public class DMAPInterface implements ITouchAbleServerResource, ITouchRemoteResource, IMusicLibrary, IImageLibrary
{
	@Context
	private UriInfo uriInfo;

	@Context
	private ServletContext context;

	@Context
	private HttpServletRequest httpServletRequest;

	@Context
	private HttpServletResponse httpServletResponse;

	private final ITouchAbleServerResource remoteControlResource;
	private final ITouchRemoteResource pairingResource;
	private final IMusicLibrary musicLibraryResource;
	private final IImageLibrary imageLibraryResource;

	private static final String CONTROLLER_HEADER_NAME = "Viewer-Only-Client";
	private static final String CONTROLLER_USER_AGENT = "Remote";
	private static final String DAAP_HEADER_NAME = "Client-DAAP-Version";
	private static final String DPAP_HEADER_NAME = "Client-DPAP-Version";

	private static boolean isDaapRequest(HttpServletRequest httpServletRequest)
	{
		if(Strings.isNullOrEmpty(httpServletRequest.getHeader(DAAP_HEADER_NAME)))
			return false;
		return true;
	}

	private static boolean isDpapRequest(HttpServletRequest httpServletRequest)
	{
		if(Strings.isNullOrEmpty(httpServletRequest.getHeader(DPAP_HEADER_NAME)))
			return false;
		return true;
	}

	private static boolean isRemoteControlRequest(HttpServletRequest httpServletRequest)
	{
		if(Strings.isNullOrEmpty(httpServletRequest.getHeader(CONTROLLER_HEADER_NAME)))
			return false;
		if(!httpServletRequest.getHeader(HttpHeaders.USER_AGENT).startsWith(CONTROLLER_USER_AGENT))
			return false;
		return true;
	}

	@Inject
	public DMAPInterface(ITouchAbleServerResource remoteControlResource, ITouchRemoteResource pairingResource, IMusicLibrary musicLibraryResource, IImageLibrary imageLibraryResource)
	{
		this.remoteControlResource = remoteControlResource;
		this.pairingResource = pairingResource;
		this.musicLibraryResource = musicLibraryResource;
		this.imageLibraryResource = imageLibraryResource;
	}

	@Override
	@Path("server-info")
	@GET
	public Response serverInfo() throws IOException
	{
		if(isDaapRequest(httpServletRequest))
			return musicLibraryResource.serverInfo();
		if(isDpapRequest(httpServletRequest))
			return imageLibraryResource.serverInfo();
		throw new NotImplementedException();
	}

	// @Override
	// public Response login() throws IOException
	// {
	// if(isDaapRequest(httpServletRequest))
	// return musicLibraryResource.login();
	// if(isDpapRequest(httpServletRequest))
	// return imageLibraryResource.login();
	// throw new NotImplementedException();
	// }
	@Override
	@Path("login")
	@GET
	public Response login(@QueryParam("pairing-guid") String guid, @QueryParam("hasFP") int value) throws IOException
	{
		if(isRemoteControlRequest(httpServletRequest))
			return remoteControlResource.login(guid, value);
		if(isDaapRequest(httpServletRequest))
			return musicLibraryResource.login(guid, value);
		if(isDpapRequest(httpServletRequest))
			return imageLibraryResource.login(guid, value);
		throw new NotImplementedException();
	}

	@Override
	@Path("update")
	@GET
	public Response update(@QueryParam("session-id") long sessionId, @QueryParam("revision-number") long revisionNumber, @QueryParam("delta") long delta, @QueryParam("daap-no-disconnect") int daapNoDisconnect) throws IOException
	{
		if(isRemoteControlRequest(httpServletRequest))
			// return remoteControlResource.update(httpServletRequest, httpServletResponse, info, sessionId, revisionNumber, delta, daapNoDisconnect);
			return musicLibraryResource.update(sessionId, revisionNumber, delta, daapNoDisconnect);
		if(isDaapRequest(httpServletRequest))
			return musicLibraryResource.update(sessionId, revisionNumber, delta, daapNoDisconnect);
		if(isDpapRequest(httpServletRequest))
			return imageLibraryResource.update(sessionId, revisionNumber, delta, daapNoDisconnect);
		throw new NotImplementedException();
	}

	@Override
	@Path("databases")
	@GET
	public Response databases(@QueryParam("session-id") long sessionId, @QueryParam("revision-number") long revisionNumber, @QueryParam("delta") long delta) throws IOException
	{
		if(isDaapRequest(httpServletRequest))
			return musicLibraryResource.databases(sessionId, revisionNumber, delta);
		if(isDpapRequest(httpServletRequest))
			return imageLibraryResource.databases(sessionId, revisionNumber, delta);
		throw new NotImplementedException();
	}

	@Override
	@Path("databases/{databaseId}/items")
	@GET
	public Response items(@PathParam("databaseId") long databaseId, @QueryParam("session-id") long sessionId, @QueryParam("revision-number") long revisionNumber, @QueryParam("delta") long delta, @QueryParam("type") String type, @QueryParam("meta") String meta, @QueryParam("query") String query) throws IOException
	{
		if(isDaapRequest(httpServletRequest))
			return musicLibraryResource.items(databaseId, sessionId, revisionNumber, delta, type, meta, query);
		if(isDpapRequest(httpServletRequest))
			return imageLibraryResource.items(databaseId, sessionId, revisionNumber, delta, type, meta, query);
		throw new NotImplementedException();
	}

	@Override
	@Path("databases/{databaseId}/containers")
	@GET
	public Response containers(@PathParam("databaseId") long databaseId, @QueryParam("session-id") long sessionId, @QueryParam("revision-number") long revisionNumber, @QueryParam("delta") long delta, @QueryParam("meta") String meta) throws IOException
	{
		if(isDaapRequest(httpServletRequest))
			return musicLibraryResource.containers(databaseId, sessionId, revisionNumber, delta, meta);
		if(isDpapRequest(httpServletRequest))
			return imageLibraryResource.containers(databaseId, sessionId, revisionNumber, delta, meta);
		throw new NotImplementedException();
	}

	@Override
	@Path("databases/{databaseId}/containers/{containerId}/items")
	@GET
	public Response containerItems(@PathParam("containerId") long containerId, @PathParam("databaseId") long databaseId, @QueryParam("session-id") long sessionId, @QueryParam("revision-number") long revisionNumber, @QueryParam("delta") long delta, @QueryParam("meta") String meta, @QueryParam("type") String type, @QueryParam("group-type") String group_type, @QueryParam("sort") String sort, @QueryParam("include-sort-headers") String include_sort_headers, @QueryParam("query") String query, @QueryParam("index") String index) throws IOException
	{
		if(isDaapRequest(httpServletRequest))
			return musicLibraryResource.containerItems(containerId, databaseId, sessionId, revisionNumber, delta, meta, type, group_type, sort, include_sort_headers, query, index);
		if(isDpapRequest(httpServletRequest))
			return imageLibraryResource.containerItems(containerId, databaseId, sessionId, revisionNumber, delta, meta, type, group_type, sort, include_sort_headers, query, index);
		throw new NotImplementedException();
	}

	@Override
	@Path("content-codes")
	@GET
	public Response contentCodes() throws IOException
	{
		if(isDaapRequest(httpServletRequest))
			return musicLibraryResource.contentCodes();
		if(isDpapRequest(httpServletRequest))
			return imageLibraryResource.contentCodes();
		throw new NotImplementedException();
	}

	@Override
	@Path("databases/{databaseId}/items/{itemId}.{format}")
	@GET
	public Response item(@PathParam("databaseId") long databaseId, @PathParam("itemId") long itemId, @PathParam("format") String format, @HeaderParam("Range") String rangeHeader) throws IOException
	{
		return musicLibraryResource.item(databaseId, itemId, format, rangeHeader);
	}

	@Override
	@GET
	@Path("pair")
	public Response pair(@Context HttpServletRequest httpServletRequest, @Context HttpServletResponse httpServletResponse, @QueryParam("pairingcode") String pairingcode, @QueryParam("servicename") String servicename) throws IOException
	{
		return pairingResource.pair(httpServletRequest, httpServletResponse, pairingcode, servicename);
	}

	@Override
	@GET
	@Path("logout")
	public Response logout(@QueryParam("session-id") long sessionId)
	{
		if(isRemoteControlRequest(httpServletRequest))
			return remoteControlResource.logout(sessionId);
		if(isDaapRequest(httpServletRequest))
			return musicLibraryResource.logout(sessionId);
		if(isDpapRequest(httpServletRequest))
			return imageLibraryResource.logout(sessionId);
		throw new NotImplementedException();
	}

	@Override
	@GET
	@Path("ctrl-int/1/pause")
	public String pause(@QueryParam("session-id") long session_id)
	{
		return remoteControlResource.pause(session_id);
	}

	@Override
	@GET
	@Path("ctrl-int/1/stop")
	public String stop(@QueryParam("session-id") long session_id)
	{
		return remoteControlResource.stop(session_id);
	}

	@Override
	@GET
	@Path("ctrl-int/1/playpause")
	public String playpause(@QueryParam("session-id") long session_id)
	{
		return remoteControlResource.playpause(session_id);
	}

	@Override
	@GET
	@Path("ctrl-int/1/nextitem")
	public String nextitem(@QueryParam("session-id") long session_id)
	{
		return remoteControlResource.nextitem(session_id);
	}

	@Override
	@GET
	@Path("ctrl-int/1/previtem")
	public String previtem(@QueryParam("session-id") long session_id)
	{
		return remoteControlResource.previtem(session_id);
	}

	@Override
	@GET
	@Path("ctrl-int/1/playlist")
	public String playlist(@QueryParam("session-id") long session_id)
	{
		return remoteControlResource.playlist(session_id);
	}

	@Override
	@GET
	@Path("ctrl-int/1/playstatusupdate")
	public Response playstatusupdate(@QueryParam("revision-number") long revisionNumber, @QueryParam("session-id") long session_id) throws IOException
	{
		return remoteControlResource.playstatusupdate(revisionNumber, session_id);
	}

	@Override
	@GET
	@Path("ctrl-int/1/getspeakers")
	public Response getspeakers(@QueryParam("session-id") long session_id) throws IOException
	{
		return remoteControlResource.getspeakers(session_id);
	}

	@Override
	@GET
	@Path("ctrl-int/1/setspeakers")
	public String setspeakers(@QueryParam("speaker-id") String speaker_id, @QueryParam("session-id") long session_id)
	{
		return remoteControlResource.setspeakers(speaker_id, session_id);
	}

	@Override
	@GET
	@Path("ctrl-int/1/playspec")
	public String playspec(@QueryParam("container-item-spec") String container_item_spec, @QueryParam("item-spec") String item_spec, @QueryParam("container-spec") String container_spec, @QueryParam("dacp.shufflestate") String dacp_shufflestate, @QueryParam("database-spec") String database_spec, @QueryParam("playlist-spec") String playlist_spec, @QueryParam("session-id") long session_id)
	{
		return remoteControlResource.playspec(container_item_spec, item_spec, container_spec, dacp_shufflestate, database_spec, playlist_spec, session_id);
	}

	@Override
	@Path("databases/{databaseId}/groups")
	@GET
	public Response groups(@PathParam("databaseId") long databaseId, @QueryParam("session-id") long sessionId, @QueryParam("meta") String meta, @QueryParam("type") String type, @QueryParam("group-type") String group_type, @QueryParam("sort") String sort, @QueryParam("include-sort-headers") String include_sort_headers) throws IOException
	{
		if(isDaapRequest(httpServletRequest))
			return musicLibraryResource.groups(databaseId, sessionId, meta, type, group_type, sort, include_sort_headers);
		if(isDpapRequest(httpServletRequest))
			return imageLibraryResource.groups(databaseId, sessionId, meta, type, group_type, sort, include_sort_headers);
		throw new NotImplementedException();
	}

	@Override
	@GET
	@Path("ctrl-int/1/setproperty")
	public String setproperty(@Context UriInfo uriInfo, @QueryParam("dmcp.volume") String dmcpVolume, @QueryParam("dacp.playingtime") String dacpPlayingtime, @QueryParam("dacp.shufflestate") String dacpShufflestate, @QueryParam("dacp.repeatstate") String dacpRepeatstate, @QueryParam("session-id") long session_id)
	{
		return remoteControlResource.setproperty(uriInfo, dmcpVolume, dacpPlayingtime, dacpShufflestate, dacpRepeatstate, session_id);
	}

	@Override
	@GET
	@Path("ctrl-int/1/getproperty")
	public Response getproperty(@Context UriInfo uriInfo, @QueryParam("properties") String properties, @QueryParam("session-id") long session_id) throws IOException
	{
		return remoteControlResource.getproperty(uriInfo, properties, session_id);
	}

	@Override
	@GET
	@Path("ctrl-int/1/cue")
	public String cue(@QueryParam("commmand") String command, @QueryParam("query") String query, @QueryParam("index") String index, @QueryParam("sort") String sort, @QueryParam("session-id") long session_id)
	{
		return remoteControlResource.cue(command, query, index, sort, session_id);
	}

	@Override
	@GET
	@Path("ctrl-int/1/nowplayingartwork")
	public String nowplayingartwork(@QueryParam("mw") String mw, @QueryParam("mh") String mh, @QueryParam("session-id") long session_id)
	{
		return remoteControlResource.nowplayingartwork(mw, mh, session_id);
	}

	@Override
	@GET
	@Path("ctrl-int/1/set-genius-seed")
	public String editGenius(@QueryParam("database-spec") String database_spec, @QueryParam("item-spec") String item_spec, @QueryParam("session-id") long session_id)
	{
		return remoteControlResource.editGenius(database_spec, item_spec, session_id);
	}

	@Override
	@Path("databases/{databaseId}/edit")
	@GET
	public String editPlaylist(@PathParam("databaseId") long databaseId, @QueryParam("session-id") long sessionId, @QueryParam("action") String action, @QueryParam("edit-params") String edit_params) throws IOException
	{
		return remoteControlResource.editPlaylist(databaseId, sessionId, action, edit_params);
	}

	@Override
	@Path("databases/{databaseId}/items/{itemId}/extra_data/artwork")
	@GET
	public Response artwork(@PathParam("databaseId") long databaseId, @PathParam("itemId") long itemId, @QueryParam("session-id") long sessionId, @QueryParam("revision-number") long revisionNumber, @QueryParam("mw") String mw, @QueryParam("mh") String mh) throws IOException
	{
		return musicLibraryResource.artwork(databaseId, itemId, sessionId, revisionNumber, mw, mh);
	}
	@Override
	@GET
	@Path("ctrl-int/1/playqueue-contents")
	public String playQueueContents(@QueryParam("span") int span, @QueryParam("session-id") long session_id)
	{
		return remoteControlResource.playQueueContents(span, session_id);
	}
	@Override
	@GET
	@Path("fp-setup")
	public String fpSetup(@QueryParam("session-id") long session_id)
	{
		return remoteControlResource.fpSetup(session_id);
	}

	@Override
	@GET
	@Path("ctrl-int")
	public Response ctrlInt(@Context HttpServletRequest httpServletRequest, @Context HttpServletResponse httpServletResponse) throws IOException
	{
		return remoteControlResource.ctrlInt(httpServletRequest, httpServletResponse);
	}

	@Override
	@GET
	@Path("ctrl-int/1/playqueue-edit")
	public Response playQueueEdit(@Context HttpServletRequest httpServletRequest, @Context HttpServletResponse httpServletResponse, @QueryParam("commmand") String command, @QueryParam("query") String query, @QueryParam("queuefilter") String index, @QueryParam("sort") String sort, @QueryParam("session-id") long session_id) throws Exception
	{
		return remoteControlResource.playQueueEdit(httpServletRequest, httpServletResponse, command, query, index, sort, session_id);
	}

	@Override
	@Path("databases/{databaseId}/groups/{groupdId}/extra_data/artwork")
	@GET
	public Response artwork(@PathParam("databaseId") long databaseId, @PathParam("groupId") long groupId, @QueryParam("session-id") long sessionId, @QueryParam("mw") String mw, @QueryParam("mh") String mh, @QueryParam("group-type") String group_type) throws IOException
	{
		if(isDaapRequest(httpServletRequest))
			return musicLibraryResource.artwork(databaseId, groupId, sessionId, mw, mh, group_type);
		if(isDpapRequest(httpServletRequest))
			return imageLibraryResource.artwork(databaseId, groupId, sessionId, mw, mh, group_type);
		throw new NotImplementedException();
	}
}
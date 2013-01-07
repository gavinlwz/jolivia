/*
 * Digital Audio Access Protocol (DAAP) Library
 * Copyright (C) 2004-2010 Roger Kapsi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ardverk.daap.chunks.impl.dmap;

import java.util.Collection;

import org.ardverk.daap.chunks.Chunk;
import org.ardverk.daap.chunks.ContainerChunk;

/**
 * The ContentCodesResponse is a list of ContentCodes-Numbers, Names and Types. It is needed to build a list of capabilities of the Server which is send to the client...
 * 
 * @author Roger Kapsi
 */
public class ContentCodesResponse extends ContainerChunk
{

	public ContentCodesResponse()
	{
		super("mccr", "dmap.contentcodesresponse");
	}

	public Collection<? extends Chunk> getChuncks()
	{
		return collection;
	}
}
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
package org.dyndns.jkiddo.dmap.chunks.audio.extension;

import org.dyndns.jkiddo.dmp.chunks.UByteChunk;

import org.dyndns.jkiddo.dmp.IDmapProtocolDefinition.DmapChunkDefinition;
import org.dyndns.jkiddo.dmp.DMAPAnnotation;

@DMAPAnnotation(type=DmapChunkDefinition.aeMK)
public class MediaKind extends UByteChunk
{

	public final static int KIND_1 = 1;
	public final static int KIND_4 = 4; // PodCast, according to forked-daapd
	public final static int KIND_6 = 6; // PodCast, according to forked-daapd
	public final static int KIND_7 = 7; // PodCast, according to forked-daapd
	public final static int KIND_8 = 8;
	public final static int KIND_32 = 32;

	public MediaKind()
	{
		this(1);
	}

	public MediaKind(int mode)
	{
		super("aeMK", "com.apple.itunes.mediakind", mode);
	}
}

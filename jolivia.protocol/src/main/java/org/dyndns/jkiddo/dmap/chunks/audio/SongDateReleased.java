package org.dyndns.jkiddo.dmap.chunks.audio;

import org.dyndns.jkiddo.dmp.chunks.DateChunk;

import org.dyndns.jkiddo.dmp.IDmapProtocolDefinition.DmapChunkDefinition;
import org.dyndns.jkiddo.dmp.DMAPAnnotation;

@DMAPAnnotation(type=DmapChunkDefinition.asdr)
public class SongDateReleased extends DateChunk
{
	public SongDateReleased()
	{
		this(0l);
	}

	public SongDateReleased(long l)
	{
		super("asdr", "daap.songdatereleased", l);
	}
}

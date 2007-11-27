/* Copyright 2007 Freenet Project Inc.
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package freenet.io;

import freenet.support.SimpleFieldSet;

/**
 * Tracks communication to/from a specific address. That address can be a specific IP:port, a specific IP,
 * or some completely different type of address, so we don't store it in this class; subclasses will do.
 * @author toad
 */
public class AddressTrackerItem {

	/** The time at which the first packet was received from this address. */
	private long timeFirstReceivedPacket;
	/** The time at which the first packet was sent to this address. */
	private long timeFirstSentPacket;
	/** The earliest time, before timeFirstReceivedPacket, at which we know for 
	 * certain that there was no packet received. This is typically the startup 
	 * time of the server socket. It may be later if the cache has to be 
	 * flushed. */
	private long timeDefinitelyNoPacketsReceived;
	/** The earliest time, before timeFirstSentPacket, at which we know for 
	 * certain that there was no packet sent. This is typically the startup 
	 * time of the node. It may be later if the cache has to be flushed. */
	private long timeDefinitelyNoPacketsSent;
	/** The time at which we received the most recent packet */
	private long timeLastReceivedPacket;
	/** The time at which we sent the most recent packet */
	private long timeLastSentPacket;
	/** The total number of packets sent to this address */
	private long packetsSent;
	/** The total number of packets received from this address */
	private long packetsReceived;
	public static final int TRACK_GAPS = 5;
	private long[] gapLengths;
	private long[] gapLengthRecvTimes;
	static final int GAP_THRESHOLD = AddressTracker.MAX_TUNNEL_LENGTH;
	
	public AddressTrackerItem(long timeDefinitelyNoPacketsReceived, long timeDefinitelyNoPacketsSent) {
		timeFirstReceivedPacket = -1;
		timeFirstSentPacket = -1;
		timeLastReceivedPacket = -1;
		timeLastSentPacket = -1;
		packetsSent = 0;
		packetsReceived = 0;
		this.timeDefinitelyNoPacketsReceived = timeDefinitelyNoPacketsReceived;
		this.timeDefinitelyNoPacketsSent = timeDefinitelyNoPacketsSent;
		gapLengths = new long[TRACK_GAPS];
		gapLengthRecvTimes = new long[TRACK_GAPS];
	}
	
	public synchronized void sentPacket(long now) {
		packetsSent++;
		if(timeFirstSentPacket < 0)
			timeFirstSentPacket = now;
		timeLastSentPacket = now;
	}
	
	public synchronized void receivedPacket(long now) {
		packetsReceived++;
		if(timeFirstReceivedPacket < 0)
			timeFirstReceivedPacket = now;
		timeLastReceivedPacket = now;
		// Establish the interval
		long startTime;
		if(timeLastSentPacket > 0) startTime = timeLastSentPacket;
		else startTime = timeDefinitelyNoPacketsSent;
		if(now - startTime > GAP_THRESHOLD) {
			// Not necessarily a new gap
			// If no packets sent since last one, just replace it
			if(timeLastSentPacket >= gapLengthRecvTimes[0]) {
				System.out.println("Rotating");
				// Rotate gaps array
				for(int i=TRACK_GAPS-1;i>=1;i--) {
					gapLengths[i] = gapLengths[i-1];
					gapLengthRecvTimes[i] = gapLengthRecvTimes[i-1];
				}
			} else {
				System.out.println("Not rotating");
				// else overwrite [0]
			}
			gapLengths[0] = (now - startTime);
			gapLengthRecvTimes[0] = now;
		}
	}
	
	public synchronized boolean hasLongTunnel(long horizon) {
		return gapLengthRecvTimes[0] > System.currentTimeMillis() - horizon;
	}

	public class Gap {
		public final long gapLength;
		public final long receivedPacketAt;
		Gap(long gapLength, long receivedPacketAt) {
			this.gapLength = gapLength;
			this.receivedPacketAt = receivedPacketAt;
		}
	}
	
	public synchronized Gap[] getGaps() {
		Gap[] gaps = new Gap[GAP_THRESHOLD];
		for(int i=0;i<TRACK_GAPS;i++) {
			gaps[i] = new Gap(gapLengths[i], gapLengthRecvTimes[i]);
		}
		return gaps;
	}
	
	public synchronized long firstReceivedPacket() {
		return timeFirstReceivedPacket;
	}
	
	public synchronized long firstSentPacket() {
		return timeFirstSentPacket;
	}
	
	public synchronized long lastReceivedPacket() {
		return timeLastReceivedPacket;
	}
	
	public synchronized long lastSentPacket() {
		return timeLastSentPacket;
	}
	
	public synchronized long timeDefinitelyNoPacketsSent() {
		return timeDefinitelyNoPacketsSent;
	}
	
	public synchronized long timeDefinitelyNoPacketsReceived() {
		return timeDefinitelyNoPacketsReceived;
	}
	
	public synchronized long packetsSent() {
		return packetsSent;
	}
	
	public synchronized long packetsReceived() {
		return packetsReceived;
	}
	
	public synchronized boolean weSentFirst() {
		if(timeFirstReceivedPacket == -1) return true;
		if(timeFirstSentPacket == -1) return false;
		return timeFirstSentPacket < timeFirstReceivedPacket; 
	}
	
	public synchronized long timeFromStartupToFirstSentPacket() {
		if(packetsSent == 0) return -1;
		return timeFirstSentPacket - timeDefinitelyNoPacketsSent;
	}

	public synchronized long timeFromStartupToFirstReceivedPacket() {
		if(packetsReceived == 0) return -1;
		return timeFirstReceivedPacket - timeDefinitelyNoPacketsReceived;
	}

	public SimpleFieldSet toFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.put("timeFirstReceivedPacket", timeFirstReceivedPacket);
		fs.put("timeFirstSentPacket", timeFirstSentPacket);
		fs.put("timeDefinitelyNoPacketsSent", timeDefinitelyNoPacketsSent);
		fs.put("timeDefinitelyNoPacketsReceived", timeDefinitelyNoPacketsReceived);
		fs.put("timeLastReceivedPacket", timeLastReceivedPacket);
		fs.put("timeLastSentPacket", timeLastSentPacket);
		fs.put("packetsSent", packetsSent);
		fs.put("packetsReceived", packetsReceived);
		SimpleFieldSet gaps = new SimpleFieldSet(true);
		fs.put("gaps", gaps);
		for(int i=0;i<TRACK_GAPS;i++) {
			SimpleFieldSet gap = new SimpleFieldSet(true);
			gaps.put(Integer.toString(i), gap);
			gap.put("length", gapLengths[i]);
			gap.put("received", gapLengthRecvTimes[i]);
		}
		return fs;
	}

}

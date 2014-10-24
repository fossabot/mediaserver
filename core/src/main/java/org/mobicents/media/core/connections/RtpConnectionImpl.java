/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat, Inc. and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mobicents.media.core.connections;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.text.ParseException;

import javax.sdp.SdpException;

import org.apache.log4j.Logger;
import org.mobicents.media.core.MediaTypes;
import org.mobicents.media.core.SdpTemplate;
import org.mobicents.media.core.WebRTCSdpTemplate;
import org.mobicents.media.io.ice.CandidatePair;
import org.mobicents.media.io.ice.IceAgent;
import org.mobicents.media.io.ice.IceFactory;
import org.mobicents.media.io.ice.events.IceEventListener;
import org.mobicents.media.io.ice.events.SelectedCandidatesEvent;
import org.mobicents.media.io.ice.harvest.HarvestException;
import org.mobicents.media.io.ice.sdp.IceSdpNegotiator;
import org.mobicents.media.server.component.audio.AudioComponent;
import org.mobicents.media.server.component.oob.OOBComponent;
import org.mobicents.media.server.impl.rtcp.RtcpChannel;
import org.mobicents.media.server.impl.rtp.ChannelsManager;
import org.mobicents.media.server.impl.rtp.RtpChannel;
import org.mobicents.media.server.impl.rtp.RtpClock;
import org.mobicents.media.server.impl.rtp.RtpListener;
import org.mobicents.media.server.impl.rtp.sdp.AVProfile;
import org.mobicents.media.server.impl.rtp.sdp.MediaDescriptorField;
import org.mobicents.media.server.impl.rtp.sdp.MediaType;
import org.mobicents.media.server.impl.rtp.sdp.RTPFormat;
import org.mobicents.media.server.impl.rtp.sdp.RTPFormats;
import org.mobicents.media.server.impl.rtp.sdp.SessionDescription;
import org.mobicents.media.server.impl.rtp.statistics.RtpStatistics;
import org.mobicents.media.server.io.network.PortManager;
import org.mobicents.media.server.scheduler.Scheduler;
import org.mobicents.media.server.spi.Connection;
import org.mobicents.media.server.spi.ConnectionFailureListener;
import org.mobicents.media.server.spi.ConnectionMode;
import org.mobicents.media.server.spi.ConnectionType;
import org.mobicents.media.server.spi.FormatNotSupportedException;
import org.mobicents.media.server.spi.ModeNotSupportedException;
import org.mobicents.media.server.spi.dsp.Codec;
import org.mobicents.media.server.spi.dsp.DspFactory;
import org.mobicents.media.server.spi.format.AudioFormat;
import org.mobicents.media.server.spi.format.FormatFactory;
import org.mobicents.media.server.spi.format.Formats;
import org.mobicents.media.server.utils.Text;

/**
 * 
 * @author Oifa Yulian
 * @author amit bhayani
 * @author Henrique Rosa (henrique.rosa@telestax.com)
 */
public class RtpConnectionImpl extends BaseConnection implements RtpListener {
	
	private static final Logger logger = Logger.getLogger(RtpConnectionImpl.class);
	
	// Core elements
	private final ChannelsManager channelsManager;
	private final Scheduler scheduler;
	
	// RTP session elements
	private long ssrc;
	private String cname;
	
	// Audio Channel
	private RtpStatistics audioStatistics;
	private final RtpClock audioClock;
	private final RtpClock audioOobClock;
	private RtpChannel rtpAudioChannel;
	private RtcpChannel rtcpAudioChannel;
	private boolean audioCapabale;
	private boolean audioRtcpMux;

	// Session Description
	private SessionDescription sdp = new SessionDescription();
	protected SdpTemplate offerTemplate;
	protected SdpTemplate answerTemplate;
	private String sdpOffer;
	private String sdpAnswer;

	// Registered formats
	private final static AudioFormat DTMF_FORMAT = FormatFactory.createAudioFormat("telephone-event", 8000);
	private final static AudioFormat LINEAR_FORMAT = FormatFactory.createAudioFormat("LINEAR", 8000, 16, 1);

	// Supported formats
	protected RTPFormats audioFormats;
	protected RTPFormats videoFormats;
	protected RTPFormats applicationFormats;

	// ICE
	private boolean ice;
	private IceAgent iceAgent;
	private IceListener iceListener;
	private PortManager icePorts;

	// WebRTC
	private boolean webrtc;

	// Connection status
	private boolean isLocal = false;
	private ConnectionFailureListener connectionFailureListener;

	public RtpConnectionImpl(int id, ChannelsManager channelsManager, DspFactory dspFactory) {
		super(id, channelsManager.getScheduler());
		
		// Core elements
		this.channelsManager = channelsManager;
		this.scheduler = channelsManager.getScheduler();
		
		// Audio Channel
		this.audioClock = new RtpClock(this.scheduler.getClock());
		this.audioOobClock = new RtpClock(this.scheduler.getClock());
		this.audioStatistics = new RtpStatistics(this.audioClock);
		this.cname = this.audioStatistics.getCname();
		this.ssrc= this.audioStatistics.getSsrc();
		this.rtpAudioChannel = channelsManager.getRtpChannel(audioStatistics, audioClock, audioOobClock);
		this.rtpAudioChannel.setRtpListener(this);
		try {
			this.rtpAudioChannel.setInputDsp(dspFactory.newProcessor());
			this.rtpAudioChannel.setOutputDsp(dspFactory.newProcessor());
		} catch (Exception e) {
			// exception may happen only if invalid classes have been set in configuration
			throw new RuntimeException("There are probably invalid classes specified in the configuration.", e);
		}
		this.rtcpAudioChannel = channelsManager.getRtcpChannel(audioStatistics);
		this.audioRtcpMux = false;
		this.audioCapabale = true;

		// create sdp template
		this.audioFormats = getRTPMap(AVProfile.audio);
		this.videoFormats = getRTPMap(AVProfile.video);
		this.applicationFormats = getRTPMap(AVProfile.application);

		offerTemplate = new SdpTemplate(this.audioFormats, this.videoFormats);

		this.icePorts = new PortManager();
		icePorts.setLowestPort(61000);
		icePorts.setHighestPort(62000);
	}

	/**
	 * Gets the ICE agent for this connection.<br>
	 * An ICE agent is only available if the connection is WebRTC and only after
	 * the remote peer was set.
	 * 
	 * @return The ICE-lite agent for the WebRTC connection.
	 */
	public IceAgent getIceAgent() {
		return iceAgent;
	}

	/**
	 * Indicates whether this connection was set for a WebRTC call.<br>
	 * This can only be known after the SDP offer is received and processed.
	 * 
	 * @return Returns whether this connection addresses a WebRTC call.
	 */
	public boolean isUsingWebRtc() {
		return webrtc;
	}

	/**
	 * Indicates whether this connection is using ICE.<br>
	 * This can only be known after the SDP offer is received.
	 * 
	 * @return Returns whether this connection uses ICE to process a call.
	 */
	public boolean isUsingIce() {
		return ice;
	}

	public AudioComponent getAudioComponent() {
		return this.rtpAudioChannel.getAudioComponent();
	}

	public OOBComponent getOOBComponent() {
		return this.rtpAudioChannel.getOobComponent();
	}

	/**
	 * Gets whether connection should be bound to local or remote interface ,
	 * supported only for rtp connections.
	 * 
	 * @return boolean value
	 */
	@Override
	public boolean getIsLocal() {
		return this.isLocal;
	}

	/**
	 * Gets whether connection should be bound to local or remote interface ,
	 * supported only for rtp connections.
	 * 
	 * @return boolean value
	 */
	@Override
	public void setIsLocal(boolean isLocal) {
		this.isLocal = isLocal;
	}

	/**
	 * Constructs RTP payloads for given channel.
	 * 
	 * @param channel
	 *            the media channel
	 * @param profile
	 *            AVProfile part for media type of given channel
	 * @return collection of RTP formats.
	 */
	private RTPFormats getRTPMap(RTPFormats profile) {
		RTPFormats list = new RTPFormats();
		Formats fmts = new Formats();
		if (rtpAudioChannel.getOutputDsp() != null) {
			Codec[] currCodecs = rtpAudioChannel.getOutputDsp().getCodecs();
			for (int i = 0; i < currCodecs.length; i++) {
				if (currCodecs[i].getSupportedInputFormat().matches(LINEAR_FORMAT)) {
					fmts.add(currCodecs[i].getSupportedOutputFormat());
				}
			}
		}

		fmts.add(DTMF_FORMAT);

		if (fmts != null) {
			for (int i = 0; i < fmts.size(); i++) {
				RTPFormat f = profile.find(fmts.get(i));
				if (f != null) {
					list.add(f.clone());
				}
			}
		}

		return list;
	}

	@Override
	public void setOtherParty(Connection other) throws IOException {
		throw new IOException("Applicable only for a local connection");
	}

	@Override
	public void setMode(ConnectionMode mode) throws ModeNotSupportedException {
		rtpAudioChannel.updateMode(mode);
		super.setMode(mode);
	}

	/**
	 * Reads the SDP offer and generates an answer.<br>
	 * This is where we know if this is a WebRTC call or not, and the resources
	 * are allocated and configured accordingly.<br>
	 * <p>
	 * <b>For a WebRTC call:</b><br>
	 * An ICE-lite agent is created. This agent assumes a controlled role, and
	 * starts listening for connectivity checks. This agent is responsible for
	 * providing a socket for DTLS hanshake and RTP streaming, once <u>the
	 * connection is established with the remote peer</u>.<br>
	 * So, we can only bind the audio channel and set its remote peer once the
	 * ICE agent has selected the candidate pairs and made such socket
	 * available.
	 * </p>
	 * <p>
	 * <b>For a regular SIP call:</b><br>
	 * The RTP audio channel can be immediately bound to the configured bind
	 * address.<br>
	 * By reading connection fields of the SDP offer, we can set the remote peer
	 * of the audio channel.
	 * </p>
	 * 
	 * @throws IOException
	 */
	private void setOtherParty() throws IOException {
		if (sdp != null && sdp.getAudioDescriptor() != null && sdp.getAudioDescriptor().getFormats() != null) {
			logger.info("Audio Formats" + sdp.getAudioDescriptor().getFormats());
		}

		// Process the SDP offer to know whether this is a WebRTC call or not
		processSdpOffer();

		/*
		 * For ICE-enabled calls, we need to wait for the ICE agent to provide a
		 * socket. This only happens once the SDP has been exchanged between
		 * both parties and ICE agent replies to remote connectivity checks.
		 */
		if (!ice) {
			setAudioChannelRemotePeer(this.sdp.getAudioDescriptor());
		} else {
			// Start ICE agent before we send the SDP answer.
			// The ICE agent will start listening for connectivity checks.
			// FULL ICE implementations will also start connectivity checks.
			this.iceAgent.start();
		}
		
		// Process the SDP answer
		try {
			generateSdpAnswer();
		} catch (SdpException e1) {
			throw new IOException(e1.getMessage(), e1);
		}

		RTPFormats audio = this.answerTemplate.getNegotiatedAudioFormats();
		if (audio.isEmpty() || !audio.hasNonDTMF()) {
			throw new IOException("Audio codecs are not negotiated");
		}

		rtpAudioChannel.setFormatMap(audio);
		try {
			rtpAudioChannel.setOutputFormats(audio.getFormats());
		} catch (FormatNotSupportedException e) {
			// never happen
			throw new IOException(e);
		}

		// Change the state of this RTP connection from HALF_OPEN to OPEN
		try {
			this.join();
		} catch (Exception e) {
			// exception is possible here when already joined , should not log
		}
	}

	public void setOtherParty(byte[] descriptor) throws IOException {
		try {
			sdp.parse(descriptor);
		} catch (ParseException e) {
			throw new IOException(e.getMessage());
		}
		setOtherParty();
	}

	public void setOtherParty(Text descriptor) throws IOException {
		try {
			sdp.init(descriptor);
		} catch (ParseException e) {
			throw new IOException(e.getMessage());
		}
		setOtherParty();
	}

	/**
	 * (Non Java-doc).
	 * 
	 * @see org.mobicents.media.server.spi.Connection#getDescriptor()
	 */
	@Override
	public String getDescriptor() {
		return (sdpAnswer != null && !sdpAnswer.isEmpty()) ? sdpAnswer : sdpOffer;
	}
	
	public void generateLocalDescriptor() throws IOException {
		// Only open and bind a new channel if not currently configured
		if(this.audioCapabale && !this.rtpAudioChannel.isBound()) {
			this.audioRtcpMux = true;
			this.rtpAudioChannel.bind(this.isLocal);
		}
		
		// Generate SDP offer based on rtp channel
		String bindAddress = rtpAudioChannel.getLocalHost();
		int rtcpPort = this.audioRtcpMux ? rtpAudioChannel.getLocalPort() : rtcpAudioChannel.getLocalPort();
		this.sdpOffer = offerTemplate.getSDP(bindAddress, "IN", "IP4", bindAddress, rtcpPort, 0);
		this.sdpAnswer = "";
	}
	
	@Override
	public String getLocalDescriptor() {
		return sdpAnswer;
	}
	
	@Override
	public String getRemoteDescriptor() {
		return sdpOffer;
	}

	public long getPacketsReceived() {
		return rtpAudioChannel.getPacketsReceived();
	}

	public long getBytesReceived() {
		return 0;
	}

	/**
	 * (Non Java-doc).
	 * 
	 * @see org.mobicents.media.server.spi.Connection#getPacketsTransmitted(org.mobicents.media.server.spi.MediaType)
	 */
	public long getPacketsTransmitted() {
		return rtpAudioChannel.getPacketsTransmitted();
	}

	/**
	 * (Non Java-doc).
	 * 
	 * @see org.mobicents.media.server.spi.Connection#getBytesTransmitted()
	 */
	public long getBytesTransmitted() {
		return 0;
	}

	/**
	 * (Non Java-doc).
	 * 
	 * @see org.mobicents.media.server.spi.Connection#getJitter()
	 */
	public double getJitter() {
		return 0;
	}
	
	public boolean isAvailable() {
		// Ignore RTCP channel availability. Only RTP is mandatory on a call.
		return rtpAudioChannel.isAvailable();
	}

	@Override
	public String toString() {
		return "RTP Connection [" + getEndpoint().getLocalName() + "]";
	}
	
	public void closeResources() {
		if(this.audioCapabale) {
			this.rtpAudioChannel.close();
			this.rtcpAudioChannel.close();
		}
		
		if(this.ice && this.iceAgent.isRunning()) {
			this.iceAgent.stop();
		}
	}
	
	public void reset() {
		// Reset SDP
		this.sdpOffer = "";
		this.sdpAnswer = "";
		this.sdp.reset();
		
		// Reset statistics
		this.audioStatistics.reset();
		this.cname = this.audioStatistics.getCname();
		this.ssrc = this.audioStatistics.getSsrc();
		
		// Reset channels
		this.audioRtcpMux = false;
		this.rtpAudioChannel.enableRtcpMux(false);
		
		// Reset ICE
		this.ice = false;
		if(this.iceAgent != null) {
			this.iceAgent.reset();
		}
		
		// Reset WebRTC
		this.webrtc = false;
		this.rtpAudioChannel.disableSRTP();
		this.rtcpAudioChannel.disableSRTCP();
	}

	public void onRtpFailure(String message) {
		if(this.audioCapabale) {
			logger.warn(message);
			// RTP is mandatory, if it fails close everything
			onFailed();
		}
	}
	
	public void onRtpFailure(Throwable e) {
		String message = "RTP failure!";
		if(e != null) {
			message += " Reason: "+ e.getMessage();
		}
		onRtpFailure(message);
	}
	
	public void onRtcpFailure(String e) {
		if (this.audioCapabale) {
			logger.warn(e);
			// Close the RTCP channel only
			// Keep the RTP channel open because RTCP is not mandatory
			this.rtcpAudioChannel.close();
		}
	}
	
	public void onRtcpFailure(Throwable e) {
		String message = "RTCP failure!";
		if(e != null) {
			message += " Reason: "+ e.getMessage();
		}
		onRtcpFailure(message);
	}
	
	@Override
	public void setConnectionFailureListener(ConnectionFailureListener connectionFailureListener) {
		this.connectionFailureListener = connectionFailureListener;
	}

	@Override
	protected void onCreated() throws Exception {
		// Reset components so they can be re-used in new calls
		reset();
		// XXX for backwards compatibility
		String bindAddress = isLocal ? channelsManager.getLocalBindAddress() : channelsManager.getBindAddress();
		this.sdpOffer = offerTemplate.getSDP(bindAddress, "IN", "IP4", bindAddress, rtpAudioChannel.getLocalPort(), 0);
	}

	@Override
	protected void onFailed() {
		closeResources();
		if (this.connectionFailureListener != null) {
			this.connectionFailureListener.onFailure();
		}
	}

	@Override
	protected void onOpened() throws Exception {
		// TODO not implemented
	}

	@Override
	protected void onClosed() {
		closeResources();
		try {
			setMode(ConnectionMode.INACTIVE);
		} catch (ModeNotSupportedException e) {
		}
		releaseConnection(ConnectionType.RTP);
		this.connectionFailureListener = null;
	}
	
	private void configureIceAgent(String externalAddress) throws UnknownHostException {
		if(this.iceAgent == null) {
			this.iceAgent = IceFactory.createLiteAgent();
			this.iceListener = new IceListener();
		}
		this.iceAgent.addIceListener(this.iceListener);
		this.iceAgent.generateIceCredentials();
		this.iceAgent.addMediaStream(MediaTypes.AUDIO.lowerName(), true, this.audioRtcpMux);
		
		// Add SRFLX candidate harvester if external address is defined
		if(externalAddress != null && !externalAddress.isEmpty()) {
			this.iceAgent.setExternalAddress(InetAddress.getByName(externalAddress));
		}
	}

	/**
	 * Reads the SDP offer and sets up the available resources according to the
	 * call type.<br>
	 * In case of a WebRTC call, an ICE-lite agent is created. The agent will
	 * start listening for connectivity checks from the remote peer.<br>
	 * Also, a WebRTC handler will be enabled on the corresponding audio
	 * channel.
	 * 
	 * @throws IOException
	 *             When binding the audio data channel. Non-WebRTC calls only.
	 * @throws SocketException
	 *             When binding the audio data channel. Non-WebRTC calls only.
	 */
	private void processSdpOffer() throws SocketException, IOException {
		this.ice = this.sdp.isAudioIce();
		this.webrtc = this.sdp.getAudioDescriptor().isWebRTCProfile();
		this.audioRtcpMux = this.sdp.getAudioDescriptor().isRtcpMux();
		this.rtpAudioChannel.enableRtcpMux(this.audioRtcpMux);

		/*
		 * For ICE-enabled calls, the RTP channels can only be bound after the
		 * ICE agent selected the candidate pairs. Since Media Server only
		 * implements ICE-lite, we need to wait for the SDP to be exchanged
		 * before we know which candidate socket to use.
		 */
		if (this.ice) {
			// Configure ICE Agent and harvest candidates
			configureIceAgent(this.rtpAudioChannel.getExternalAddress());
			try {
				this.iceAgent.harvest(icePorts);
			} catch (HarvestException e) {
				throw new IOException("Could not harvest ICE candidates: " + e.getMessage(), e);
			}
		} else {
			// For non-ICE calls the RTP audio channel can be bound immediately
			this.rtpAudioChannel.bind(this.isLocal);
			this.rtcpAudioChannel.bind(this.isLocal, this.rtpAudioChannel.getLocalPort() + 1);
		}
		
		// Configure WebRTC-related resources on audio channel
		if (this.webrtc) {
			Text remotePeerFingerprint = this.sdp.getFingerprint(MediaType.AUDIO);
			rtpAudioChannel.enableSRTP(remotePeerFingerprint, this.iceAgent);
			if(!this.audioRtcpMux) {
				rtcpAudioChannel.enableSRTCP(remotePeerFingerprint, this.iceAgent);
			}
		}
	}

	/**
	 * Generates an SDP answer to be sent to the remote peer.
	 * 
	 * @return The template of the SDP answer
	 * @throws SdpException
	 *             In case the SDP is malformed
	 */
	private void generateSdpAnswer() throws SdpException {
		if (!isLocal && this.webrtc) {
			// Create a WebRTC SDP template
			WebRTCSdpTemplate webRtcTemplate = new WebRTCSdpTemplate(this.sdp);
			// Set fingerprint from DTLS server certificate
			webRtcTemplate.setLocalFingerprint(this.rtpAudioChannel.getWebRtcLocalFingerprint());
			this.answerTemplate = webRtcTemplate;
		} else {
			// Create a common SDP template
			this.answerTemplate = new SdpTemplate(this.sdp);
		}

		// Session-level descriptors
		String bindAddress = isLocal ? channelsManager.getLocalBindAddress() : channelsManager.getBindAddress();
		this.answerTemplate.setBindAddress(bindAddress);
		this.answerTemplate.setNetworkType("IN");
		this.answerTemplate.setAddressType("IP4");
		this.answerTemplate.setConnectionAddress(bindAddress);
		// XXX hardcoded fake connection mode!!! - hrosa
		this.answerTemplate.setConnectionMode("sendrecv");
		this.answerTemplate.setSsrc(this.ssrc);
		this.answerTemplate.setCname(this.cname);

		// Media Descriptors
		this.answerTemplate.setSupportedAudioFormats(this.audioFormats);
		this.answerTemplate.setRtpAudioPort(this.rtpAudioChannel.getLocalPort());
		this.answerTemplate.setRtcpAudioPort(this.rtcpAudioChannel.getLocalPort());

		if (this.answerTemplate instanceof WebRTCSdpTemplate) {
			// TODO Update video port based on video channel configuration
			this.answerTemplate.setSupportedVideoFormats(this.videoFormats);
			this.answerTemplate.setVideoPort(0);
			// TODO Update application port based on video channel configuration
			this.answerTemplate.setSupportedApplicationFormats(this.applicationFormats);
			this.answerTemplate.setApplicationPort(0);
		}

		this.sdpAnswer = this.answerTemplate.build();
		if (this.ice) {
			this.sdpAnswer = IceSdpNegotiator.updateAnswer(sdpAnswer,this.iceAgent).toString();
		}
	}

	/**
	 * Binds the audio channel to its internal <code>bindAddress</code> and sets
	 * the remote peer.
	 * 
	 * @param address
	 *            The address of the remote peer.
	 * @param port
	 *            The port of the remote peer.
	 * @throws SocketException
	 * @throws IOException
	 */
	private void setAudioChannelRemotePeer(String address, int port) throws SocketException, IOException {
		if (this.audioCapabale) {
			rtpAudioChannel.setRemotePeer(new InetSocketAddress(address, port));
		}
	}

	/**
	 * Binds the audio channel to its internal <code>bindAddress</code> and sets
	 * the remote peer.
	 * 
	 * @param descriptor
	 *            The audio descriptor taken from the SDP template.
	 * @throws SocketException
	 * @throws IOException
	 */
	private void setAudioChannelRemotePeer(MediaDescriptorField descriptor) throws SocketException, IOException {
		int peerPort = descriptor.getPort();
		String peerAddress;

		// The connection description defined at media level
		// takes priority over the session-wide connection line
		if (descriptor.getConnection() != null) {
			peerAddress = descriptor.getConnection().getAddress();
		} else {
			peerAddress = sdp.getConnection().getAddress();
		}
		setAudioChannelRemotePeer(peerAddress, peerPort);
	}
	
	private class IceListener implements IceEventListener {

		public void onSelectedCandidates(SelectedCandidatesEvent event) {
			try {
				logger.info("Finished ICE candidates selection! Preparing for binding.");
				
				// Get selected RTP candidate for audio channel
				IceAgent agent = event.getSource();
				CandidatePair rtpCandidate = agent.getSelectedRtpCandidate("audio");
				// Bind candidate to RTP audio channel
				rtpAudioChannel.bind(rtpCandidate.getChannel());

				CandidatePair rtcpCandidate = agent.getSelectedRtcpCandidate("audio");
				if(rtcpCandidate != null) {
					rtcpAudioChannel.bind(rtcpCandidate.getChannel());
				}
			} catch (IOException e) {
				onRtpFailure("Could not select ICE candidates: "+ e.getMessage());
			}
		}
	}

}

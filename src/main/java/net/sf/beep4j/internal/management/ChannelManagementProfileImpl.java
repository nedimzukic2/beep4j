/*
 *  Copyright 2006 Simon Raess
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package net.sf.beep4j.internal.management;

import net.sf.beep4j.ChannelHandler;
import net.sf.beep4j.Message;
import net.sf.beep4j.MessageBuilder;
import net.sf.beep4j.ProfileInfo;
import net.sf.beep4j.ProtocolException;
import net.sf.beep4j.Reply;
import net.sf.beep4j.ReplyHandler;
import net.sf.beep4j.internal.DefaultCloseChannelRequest;
import net.sf.beep4j.internal.SessionManager;
import net.sf.beep4j.internal.StartChannelResponse;
import net.sf.beep4j.internal.message.DefaultMessageBuilder;
import net.sf.beep4j.internal.util.IntegerSequence;
import net.sf.beep4j.internal.util.Sequence;

/**
 * Implementation of ChannelManagementProfile interface.
 * 
 * @author Simon Raess
 */
public class ChannelManagementProfileImpl implements ChannelManagementProfile {
	
	private SessionManager manager;
	
	private final boolean initiator;
	
	private final ChannelManagementMessageBuilder builder;
	
	private final ChannelManagementMessageParser parser; 
	
	private final Sequence<Integer> messageNumberSequence = new IntegerSequence(1, 1);
	
	public ChannelManagementProfileImpl(boolean initiator) {
		this.initiator = initiator;
		this.builder = createChannelManagementMessageBuilder();
		this.parser = createChannelManagementMessageParser();
	}

	protected ChannelManagementMessageBuilder createChannelManagementMessageBuilder() {
		return new SaxMessageBuilder();
	}
	
	protected ChannelManagementMessageParser createChannelManagementMessageParser() {
		return new SaxMessageParser();
	}
	
	protected MessageBuilder createMessageBuilder() {
		MessageBuilder builder = new DefaultMessageBuilder();
		builder.setContentType("application", "beep+xml");
		builder.setCharsetName("UTF-8");
		return builder;
	}
		
	public ChannelHandler createChannelHandler(SessionManager manager) {
		this.manager = manager;
		return new ManagementChannelHandler(this, parser);
	}
	
	public final void sendGreeting(String[] profiles, Reply reply) {
		reply.sendRPY(createGreeting(profiles));
	}
	
	public final void sendSessionStartDeclined(int errorCode, String message, Reply reply) {
		reply.sendERR(createError(errorCode, message));
	}
	
	protected Message createGreeting(String[] profiles) {
		return builder.createGreeting(createMessageBuilder(), profiles);
	}
	
	protected Message createError(int code, String diagnostics) {
		return builder.createError(createMessageBuilder(), code, diagnostics);
	}

	public final Greeting receivedGreeting(Message message) {
		return parser.parseGreeting(message);
	}

	public final BEEPError receivedError(Message message) {
		return parser.parseError(message);
	}
	
	public final void startChannel(
			final int channelNumber, 
			final ProfileInfo[] infos, 
			final StartChannelCallback callback) {
		int messageNumber = messageNumberSequence.next(); 
		Message message = builder.createStart(createMessageBuilder(), channelNumber, infos);
		manager.sendChannelManagementMessage(messageNumber, message, new ManagementReplyHandler() {
			
			public void receivedRPY(Message message) {
				ProfileInfo profile = parser.parseProfile(message);
				callback.channelCreated(profile);
			}
			
			public void receivedERR(Message message) {
				BEEPError error = parser.parseError(message);
				callback.channelFailed(error.getCode(), error.getMessage());
			}
			
		});
	}
	
	public final void closeChannel(final int channelNumber, final CloseCallback callback) {
		int messageNumber = messageNumberSequence.next();
		Message message = builder.createClose(createMessageBuilder(), channelNumber, 200);
		manager.sendChannelManagementMessage(messageNumber, message, new ManagementReplyHandler() {
		
			public void receivedRPY(Message message) {
				parser.parseOk(message);
				callback.closeAccepted();
			}
		
			public void receivedERR(Message message) {
				BEEPError error = parser.parseError(message);
				callback.closeDeclined(error.getCode(), error.getMessage());
			}
		
		});
	}
	
	public final void closeSession(final CloseCallback callback) {
		// closing the session is done by closing channel 0
		closeChannel(0, callback);
	}
	
	public final void startChannelRequested(
			final int channelNumber, 
			final ProfileInfo[] profiles, 
			final Reply reply) {
		validateStartChannelRequest(channelNumber, reply); 

		StartChannelResponse response = manager.channelStartRequested(channelNumber, profiles);			
		
		if (response.isCancelled()) {
			reply.sendERR(builder.createError(
					createMessageBuilder(), response.getCode(), response.getMessage()));			
		} else {
			reply.sendRPY(builder.createProfile(
					createMessageBuilder(), response.getProfile()));
		}
	}

	private void validateStartChannelRequest(
			final int channelNumber,
			final Reply reply) {
		if (channelNumber <= 0) {
			throw new ProtocolException(channelNumber + " is an illegal channel number");
		}
		if (initiator && channelNumber % 2 != 0) {
			reply.sendERR(builder.createError(
					createMessageBuilder(), 501, "number attribute in <start> element must be odd valued"));
		} else if (!initiator && channelNumber % 2 != 1) {
			reply.sendERR(builder.createError(
					createMessageBuilder(), 501, "number attribute in <start> element must be even valued"));
		}
	}

	public final void closeChannelRequested(final int channelNumber, final Reply reply) {
		DefaultCloseChannelRequest request = new DefaultCloseChannelRequest();
		manager.channelCloseRequested(channelNumber, request);
		if (request.isAccepted()) {
			reply.sendRPY(builder.createOk(createMessageBuilder()));
		} else {
			reply.sendERR(builder.createError(createMessageBuilder(), 550, "still working"));
		}
	}

	public final void closeSessionRequested(final Reply reply) {
		manager.sessionCloseRequested(new CloseCallback() {
			public void closeDeclined(int code, String message) {
				reply.sendERR(builder.createError(createMessageBuilder(), code, message));
			}
		
			public void closeAccepted() {
				reply.sendRPY(builder.createOk(createMessageBuilder()));
			}
		});
	}
	
	// --> end of ChannelManagementProfile methods <--
	
	/**
	 * ReplyHandler for channel management messages.
	 */
	private static abstract class ManagementReplyHandler implements ReplyHandler {
		
		public final void receivedANS(Message message) {
			throw new ProtocolException("ANS is not a valid response to a channel management request");		
		}
		
		public final void receivedNUL() {
			throw new ProtocolException("NUL is not a valid response to a channel management request");		
		}
		
	}
	
}
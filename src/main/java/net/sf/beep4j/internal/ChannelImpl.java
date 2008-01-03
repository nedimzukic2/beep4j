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
package net.sf.beep4j.internal;

import net.sf.beep4j.Channel;
import net.sf.beep4j.ChannelHandler;
import net.sf.beep4j.CloseChannelCallback;
import net.sf.beep4j.CloseChannelRequest;
import net.sf.beep4j.Message;
import net.sf.beep4j.MessageBuilder;
import net.sf.beep4j.Reply;
import net.sf.beep4j.ReplyHandler;
import net.sf.beep4j.Session;
import net.sf.beep4j.internal.message.DefaultMessageBuilder;
import net.sf.beep4j.internal.util.Assert;
import net.sf.beep4j.internal.util.IntegerSequence;
import net.sf.beep4j.internal.util.Sequence;

class ChannelImpl implements Channel, ChannelHandler, InternalChannel {
	
	private final InternalSession session;
	
	private final String profile;
	
	private final int channelNumber;
	
	private final Sequence<Integer> messageNumberSequence = new IntegerSequence(1, 1);

	private ChannelHandler channelHandler;
	
	private State state = new Alive();
	
	/**
	 * Counter that counts how many messages we have sent but to which we
	 * have not received a reply.
	 */
	private int outstandingReplyCount;
	
	/**
	 * Counter that counts how many messages we have received but to which
	 * we have not sent a response.
	 */
	private int outstandingResponseCount;
	
	public ChannelImpl(
			InternalSession session, 
			String profile, 
			int channelNumber) {
		this.session = session;
		this.profile = profile;
		this.channelNumber = channelNumber;
	}
	
	public ChannelHandler initChannel(ChannelHandler channelHandler) {
		Assert.notNull("channelHandler", channelHandler);
		this.channelHandler = channelHandler;
		return this;
	}
	
	public boolean isAlive() {
		return state instanceof Alive;
	}
	
	public boolean isDead() {
		return state instanceof Dead;
	}
	
	public boolean isShuttingDown() {
		return !isAlive() && !isDead();
	}
	
	public String getProfile() {
		return profile;
	}

	public Session getSession() {
		return session;
	}
	
	public MessageBuilder createMessageBuilder() {
		return new DefaultMessageBuilder();
	}
	
	protected void setState(State state) {
		this.state = state;
		this.state.checkCondition();
	}
	
	public void sendMessage(Message message, ReplyHandler reply) {
		Assert.notNull("message", message);
		Assert.notNull("listener", reply);
		state.sendMessage(message, wrapReplyHandler(reply));
	}

	private ReplyHandler wrapReplyHandler(ReplyHandler reply) {
		return new ReplyHandlerWrapper(reply);
	}
	
	public void close(CloseChannelCallback callback) {
		Assert.notNull("callback", callback);
		state.closeInitiated(callback);
	}
	
	// --> start of ChannelHandler methods <--
	
	public void channelStartFailed(int code, String message) {
		channelHandler.channelStartFailed(code, message);
	}
	
	public void channelOpened(Channel c) {
		channelHandler.channelOpened(this);		
	}
	
	public void messageReceived(Message message, Reply reply) {
		state.messageReceived(message, wrapReply(reply));		
	}

	protected Reply wrapReply(Reply reply) {
		incrementOutstandingResponseCount();
		return new ReplyWrapper(reply);
	}
	
	public void channelClosed() {
		channelHandler.channelClosed();
		setState(new Dead());
	}
	
	public void channelCloseRequested(CloseChannelRequest request) {
		state.closeRequested(request);
	}
	
	// --> end of ChannelHandler methods <--
	
	private synchronized void incrementOutstandingReplyCount() {
		outstandingReplyCount++;
	}
	
	private synchronized void decrementOutstandingReplyCount() {
		outstandingReplyCount--;
		state.checkCondition();
	}
	
	private synchronized boolean hasOutstandingReplies() {
		return outstandingReplyCount > 0;
	}
	
	private synchronized void incrementOutstandingResponseCount() {
		outstandingResponseCount++;
	}
	
	private synchronized void decrementOutstandingResponseCount() {
		outstandingResponseCount--;
		state.checkCondition();
	}
	
	private synchronized boolean hasOutstandingResponses() {
		return outstandingResponseCount > 0;
	}
	
	private synchronized boolean isReadyToShutdown() {
		return !hasOutstandingReplies() && !hasOutstandingResponses();
	}

	/*
	 * Wrapper for ReplyHandler that decrements a counter whenever
	 * a complete message has been received. Intercepts calls to 
	 * the real ReplyHandler from the application to make this
	 * book-keeping possible.
	 */
	private class ReplyHandlerWrapper implements ReplyHandler {

		private final ReplyHandler target;
		
		private ReplyHandlerWrapper(ReplyHandler target) {
			Assert.notNull("target", target);
			this.target = target;
			incrementOutstandingReplyCount();
		}
		
		public void receivedANS(Message message) {
			target.receivedANS(message);			
		}
		
		public void receivedNUL() {
			decrementOutstandingReplyCount();
			target.receivedNUL();
		}
		
		public void receivedERR(Message message) {
			decrementOutstandingReplyCount();
			target.receivedERR(message);
		}
		
		public void receivedRPY(Message message) {
			decrementOutstandingReplyCount();
			target.receivedRPY(message);
		}
	}
	
	/*
	 * The ReplyWrapper is used to count outstanding replies. This information
	 * is needed to know when a channel close can be accepted.
	 */
	private class ReplyWrapper implements Reply {
		
		private final Reply target;
		
		private ReplyWrapper(Reply target) {
			Assert.notNull("target", target);
			this.target = target;
		}
		
		public MessageBuilder createMessageBuilder() {
			return target.createMessageBuilder();
		}
		
		public void sendANS(Message message) {
			target.sendANS(message);			
		}
		
		public void sendNUL() {
			decrementOutstandingResponseCount();
			target.sendNUL();
		}
		
		public void sendERR(Message message) {
			decrementOutstandingResponseCount();
			target.sendERR(message);
		}
		
		public void sendRPY(Message message) {
			decrementOutstandingResponseCount();
			target.sendRPY(message);
		}
	}
	
	private static interface State {
		
		void checkCondition();
		
		void sendMessage(Message message, ReplyHandler replyHandler);
		
		void closeInitiated(CloseChannelCallback callback);
		
		void closeRequested(CloseChannelRequest request);
		
		void messageReceived(Message message, Reply reply);
		
	}
	
	private static abstract class AbstractState implements State {
		
		public void checkCondition() {
			// nothing to check
		}
		
		public void sendMessage(Message message, ReplyHandler replyHandler) {
			throw new IllegalStateException();
		}
		
		public void closeInitiated(CloseChannelCallback callback) {
			throw new IllegalStateException();
		}
		
		public void closeRequested(CloseChannelRequest request) {
			throw new IllegalStateException();
		}
		
		public void messageReceived(Message message, Reply reply) {
			throw new IllegalStateException();
		}
	}
	
	private class Alive extends AbstractState {
		
		@Override
		public void sendMessage(final Message message, final ReplyHandler replyHandler) {
			int messageNumber = messageNumberSequence.next();
			session.sendMessage(channelNumber, messageNumber, message, replyHandler);
		}
		
		@Override
		public void messageReceived(Message message, Reply reply) {
			channelHandler.messageReceived(message, reply);
		}
		
		@Override
		public void closeInitiated(CloseChannelCallback callback) {
			setState(new CloseInitiated(callback));
		}
		
		@Override
		public void closeRequested(CloseChannelRequest request) {
			setState(new CloseRequested(request));
		}
		
	}
	
	private class CloseInitiated extends AbstractState {
		
		private final CloseChannelCallback callback;
		
		private CloseInitiated(CloseChannelCallback callback) {
			this.callback = callback;
		}
		
		@Override
		public void messageReceived(Message message, Reply handler) {
			channelHandler.messageReceived(message, handler);
		}
		
		@Override
		public void checkCondition() {
			if (isReadyToShutdown()) {
				session.requestChannelClose(channelNumber, new CloseChannelCallback() {
					public void closeDeclined(int code, String message) {
						callback.closeDeclined(code, message);
						setState(new Alive());
					}
					public void closeAccepted() {
						channelClosed();
						callback.closeAccepted();
					}
				});
			}
		}
		
		/*
		 * If we receive a close request in this state, we accept the close
		 * request immediately without consulting the application. The
		 * reasoning is that the application already requested to close
		 * the channel, so it makes no sense to let it change that 
		 * decision.
		 */
		@Override
		public void closeRequested(CloseChannelRequest request) {
			callback.closeAccepted();
			request.accept();
		}
		
	}
	
	private class CloseRequested extends AbstractState {
		
		private final CloseChannelRequest request;
		
		private CloseRequested(CloseChannelRequest request) {
			this.request = request;
		}
		
		@Override
		public void messageReceived(Message message, Reply handler) {
			channelHandler.messageReceived(message, handler);
		}
		
		@Override
		public void checkCondition() {
			if (isReadyToShutdown()) {
				DefaultCloseChannelRequest request = new DefaultCloseChannelRequest();
				channelHandler.channelCloseRequested(request);
				if (request.isAccepted()) {
					this.request.accept();
				} else {
					this.request.reject();
					setState(new Alive());
				}
			}
		}
	}
	
	private class Dead extends AbstractState {
		// dead is dead ;)
	}
	
}

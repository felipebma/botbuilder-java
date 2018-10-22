package com.microsoft.bot.builder.adapters;
import com.microsoft.bot.builder.BotCallbackHandler;
import com.microsoft.bot.schema.models.Activity;
import com.microsoft.bot.schema.models.ActivityTypes;

import java.util.*;
import java.time.*;

// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.



/** 
 A mock adapter that can be used for unit testing of bot logic.
 
 {@link TestFlow}
*/
public class TestAdapter extends BotAdapter
{
	private boolean _sendTraceActivity;
	private Object _conversationLock = new Object();
	private Object _activeQueueLock = new Object();

	private int _nextId = 0;

	/** 
	 Initializes a new instance of the <see cref="TestAdapter"/> class.
	 
	 @param conversation A reference to the conversation to begin the adapter state with.
	 @param sendTraceActivity Indicates whether the adapter should add to its <see cref="ActiveQueue"/>
	 any trace activities generated by the bot.
	*/

	public TestAdapter(ConversationReference conversation)
	{
		this(conversation, false);
	}

	public TestAdapter()
	{
		this(null, false);
	}

//C# TO JAVA CONVERTER NOTE: Java does not support optional parameters. Overloaded method(s) are created above:
//ORIGINAL LINE: public TestAdapter(ConversationReference conversation = null, bool sendTraceActivity = false)
	public TestAdapter(ConversationReference conversation, boolean sendTraceActivity)
	{
		_sendTraceActivity = sendTraceActivity;
		if (conversation != null)
		{
			setConversation(conversation);
		}
		else
		{
			setConversation(new ConversationReference());
			getConversation().ChannelId = "test";
			getConversation().ServiceUrl = "https://test.com";

			getConversation().User = new ChannelAccount("user1", "User1");
			getConversation().Bot = new ChannelAccount("bot", "Bot");
			getConversation().Conversation = new ConversationAccount(false, "convo1", "Conversation1");
		}
	}

	/** 
	 Gets the queue of responses from the bot.
	 
	 <value>The queue of responses from the bot.</value>
	*/
	private LinkedList<Activity> ActiveQueue = new LinkedList<Activity> ();
	public final LinkedList<Activity> getActiveQueue()
	{
		return ActiveQueue;
	}

	/** 
	 Gets or sets a reference to the current coversation.
	 
	 <value>A reference to the current conversation.</value>
	*/
	private ConversationReference Conversation;
	public final ConversationReference getConversation()
	{
		return Conversation;
	}
	public final void setConversation(ConversationReference value)
	{
		Conversation = value;
	}

	/** 
	 Adds middleware to the adapter's pipeline.
	 
	 @param middleware The middleware to add.
	 @return The updated adapter object.
	 Middleware is added to the adapter at initialization time.
	 For each turn, the adapter calls middleware in the order in which you added it.
	 
	*/
//C# TO JAVA CONVERTER WARNING: There is no Java equivalent to C#'s shadowing via the 'new' keyword:
//ORIGINAL LINE: public new TestAdapter Use(Middleware middleware)
	public final TestAdapter Use(IMiddleware middleware)
	{
		super.Use(middleware);
		return this;
	}

	/** 
	 Receives an activity and runs it through the middleware pipeline.
	 
	 @param activity The activity to process.
	 @param callback The bot logic to invoke.

	 @return A task that represents the work queued to execute.
	*/

	public final void ProcessActivityAsync(Activity activity, BotCallbackHandler callback)
	{
		return ProcessActivityAsync(activity, callback, null);
	}

//C# TO JAVA CONVERTER TODO TASK: There is no equivalent in Java to the 'async' keyword:
//ORIGINAL LINE: public async void ProcessActivityAsync(Activity activity, BotCallbackHandler callback, CancellationToken cancellationToken = default(CancellationToken))
//C# TO JAVA CONVERTER NOTE: Java does not support optional parameters. Overloaded method(s) are created above:
	public final void ProcessActivityAsync(Activity activity, BotCallbackHandler callback)
	{
		synchronized (_conversationLock)
		{
			// ready for next reply
			if (activity.type() == null)
			{
				activity.type() = ActivityTypes.MESSAGE;
			}

			activity.ChannelId = getConversation().ChannelId;
			activity.From = getConversation().User;
			activity.Recipient = getConversation().Bot;
			activity.Conversation = getConversation().Conversation;
			activity.ServiceUrl = getConversation().ServiceUrl;

			String id = activity.Id = (_nextId++).toString();
		}

		if (activity.Timestamp == null || activity.Timestamp == new DateTimeOffset())
		{
			activity.Timestamp = LocalDateTime.UtcNow;
		}

		try (TurnContext context = new TurnContext(this, activity))
		{
//C# TO JAVA CONVERTER TODO TASK: There is no equivalent to 'await' in Java:
			await RunPipelineAsync(context, callback, cancellationToken);
		}
	}

	/** 
	 Sends activities to the conversation.
	 
	 @param turnContext The context object for the turn.
	 @param activities The activities to send.

	 @return A task that represents the work queued to execute.
	 If the activities are successfully sent, the task result contains
	 an array of <see cref="ResourceResponse"/> objects containing the IDs that
	 the receiving channel assigned to the activities.
	 {@link ITurnContext.OnSendActivities(SendActivitiesHandler)}
	*/
//C# TO JAVA CONVERTER TODO TASK: There is no equivalent in Java to the 'async' keyword:
//ORIGINAL LINE: public async override CompletableFuture<ResourceResponse[]> SendActivitiesAsync(TurnContext turnContext, Activity[] activities)
	@Override
	public CompletableFuture<ResourceResponse[]> SendActivitiesAsync(TurnContext turnContext, Activity[] activities)
	{
		if (turnContext == null)
		{
			throw new NullPointerException("turnContext");
		}

		if (activities == null)
		{
			throw new NullPointerException("activities");
		}

		if (activities.length == 0)
		{
			throw new IllegalArgumentException("Expecting one or more activities, but the array was empty.", "activities");
		}

		ResourceResponse[] responses = new ResourceResponse[activities.length];

		// NOTE: we're using for here (vs. foreach) because we want to simultaneously index into the
		// activities array to get the activity to process as well as use that index to assign
		// the response to the responses array and this is the most cost effective way to do that.
		for (int index = 0; index < activities.length; index++)
		{
			Activity activity = activities[index];

			if (StringUtils.isBlank(activity.Id))
			{
				activity.Id = UUID.NewGuid().toString("n");
			}

			if (activity.Timestamp == null)
			{
				activity.Timestamp = LocalDateTime.UtcNow;
			}

			if (activity.Type == ActivityTypesEx.Delay)
			{
				// The BotFrameworkAdapter and Console adapter implement this
				// hack directly in the POST method. Replicating that here
				// to keep the behavior as close as possible to facillitate
				// more realistic tests.
				int delayMs = (int)activity.Value;

//C# TO JAVA CONVERTER TODO TASK: There is no equivalent to 'await' in Java:
				await Task.Delay(delayMs);
			}
			else if (activity.Type == ActivityTypes.Trace)
			{
				if (_sendTraceActivity)
				{
					synchronized (_activeQueueLock)
					{
						getActiveQueue().offer(activity);
					}
				}
			}
			else
			{
				synchronized (_activeQueueLock)
				{
					getActiveQueue().offer(activity);
				}
			}

			responses[index] = new ResourceResponse(activity.Id);
		}

		return responses;
	}

	/** 
	 Replaces an existing activity in the <see cref="ActiveQueue"/>.
	 
	 @param turnContext The context object for the turn.
	 @param activity New replacement activity.

	 @return A task that represents the work queued to execute.
	 If the activity is successfully sent, the task result contains
	 a <see cref="ResourceResponse"/> object containing the ID that the receiving
	 channel assigned to the activity.
	 <p>Before calling this, set the ID of the replacement activity to the ID
	 of the activity to replace.</p>
	 {@link ITurnContext.OnUpdateActivity(UpdateActivityHandler)}
	*/
	@Override
	public CompletableFuture<ResourceResponse> UpdateActivityAsync(TurnContext turnContext, Activity activity)
	{
		synchronized (_activeQueueLock)
		{
			ArrayList<Object> replies = getActiveQueue().ToList();
			for (int i = 0; i < getActiveQueue().size(); i++)
			{
				if (replies.get(i).Id == activity.Id)
				{
					replies.set(i, activity);
					getActiveQueue().clear();
					for (Object item : replies)
					{
						getActiveQueue().offer(item);
					}

					return Task.FromResult(new ResourceResponse(activity.Id));
				}
			}
		}

		return Task.FromResult(new ResourceResponse());
	}

	/** 
	 Deletes an existing activity in the <see cref="ActiveQueue"/>.
	 
	 @param turnContext The context object for the turn.
	 @param reference Conversation reference for the activity to delete.

	 @return A task that represents the work queued to execute.
	 The <see cref="ConversationReference.ActivityId"/> of the conversation
	 reference identifies the activity to delete.
	 {@link ITurnContext.OnDeleteActivity(DeleteActivityHandler)}
	*/
	@Override
	public void DeleteActivityAsync(TurnContext turnContext, ConversationReference reference)
	{
		synchronized (_activeQueueLock)
		{
			ArrayList<Object> replies = getActiveQueue().ToList();
			for (int i = 0; i < getActiveQueue().size(); i++)
			{
				if (replies.get(i).Id == reference.ActivityId)
				{
					replies.remove(i);
					getActiveQueue().clear();
					for (Object item : replies)
					{
						getActiveQueue().offer(item);
					}

					break;
				}
			}
		}

		return Task.CompletedTask;
	}

	/** 
	 Creates a new conversation on the specified channel.
	 
	 @param channelId The ID of the channel.
	 @param callback The bot logic to call when the conversation is created.

	 @return A task that represents the work queued to execute.
	 This resets the <see cref="ActiveQueue"/>, and does not maintain multiple converstion queues.
	*/
	public final void CreateConversationAsync(String channelId, BotCallbackHandler callback)
	{
		getActiveQueue().clear();
//C# TO JAVA CONVERTER TODO TASK: There is no equivalent to implicit typing in Java unless the Java 10 inferred typing option is selected:
		var update = Activity.CreateConversationUpdateActivity();
		update.Conversation = new ConversationAccount();
		update.Conversation.Id = UUID.NewGuid().toString("n");
		TurnContext context = new TurnContext(this, (Activity)update);
		return callback.invoke(context, cancellationToken);
	}

	/** 
	 Dequeues and returns the next bot response from the <see cref="ActiveQueue"/>.
	 
	 @return The next activity in the queue; or null, if the queue is empty.
	 A <see cref="TestFlow"/> object calls this to get the next response from the bot.
	*/
	public final IActivity GetNextReply()
	{
		synchronized (_activeQueueLock)
		{
			if (!getActiveQueue().isEmpty())
			{
				return getActiveQueue().poll();
			}
		}

		return null;
	}

	/** 
	 Creates a message activity from text and the current conversational context.
	 
	 @param text The message text.
	 @return An appropriate message activity.
	 A <see cref="TestFlow"/> object calls this to get a message activity
	 appropriate to the current conversation.
	*/

	public final Activity MakeActivity()
	{
		return MakeActivity(null);
	}

//C# TO JAVA CONVERTER NOTE: Java does not support optional parameters. Overloaded method(s) are created above:
//ORIGINAL LINE: public Activity MakeActivity(string text = null)
	public final Activity MakeActivity(String text)
	{
		Activity activity = new Activity();
		activity.Type = ActivityTypes.Message;
		activity.From = getConversation().User;
		activity.Recipient = getConversation().Bot;
		activity.Conversation = getConversation().Conversation;
		activity.ServiceUrl = getConversation().ServiceUrl;
		activity.Id = (_nextId++).toString();
		activity.Text = text;

		return activity;
	}

	/** 
	 Processes a message activity from a user.
	 
	 @param userSays The text of the user's message.
	 @param callback The turn processing logic to use.
	 @param cancellationToken The cancellation token.
	 @return A task that represents the work queued to execute.
	 {@link TestFlow.Send(string)}
	*/
	public final void SendTextToBotAsync(String userSays, BotCallbackHandler callback)
	{
		return ProcessActivityAsync(MakeActivity(userSays), callback, cancellationToken);
	}
}
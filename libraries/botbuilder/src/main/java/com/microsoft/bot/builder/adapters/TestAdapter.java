package com.microsoft.bot.builder.adapters;
import com.microsoft.bot.builder.*;
import com.microsoft.bot.schema.ActivityImpl;
import com.microsoft.bot.schema.models.*;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.time.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

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


	public TestAdapter(ConversationReference conversation)
	{
		this(conversation, false);
	}

	public TestAdapter()
	{
		this(null, false);
	}

    /**
     Initializes a new instance of the <see cref="TestAdapter"/> class.

     @param conversation A reference to the conversation to begin the adapter state with.
     @param sendTraceActivity Indicates whether the adapter should add to its <see cref="ActiveQueue"/>
     any trace activities generated by the bot.
     */
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
			getConversation().withChannelId("test") ;
			getConversation().withServiceUrl("https://test.com");

			getConversation().withUser(new ChannelAccount().withId("user1").withName("User1"));
			getConversation().withUser(new ChannelAccount().withId("user1").withName("User1"));
			getConversation().withBot(new ChannelAccount().withId("bot").withName("Bot"));
			getConversation().withConversation(new ConversationAccount()
									.withIsGroup(false)
									.withConversationType("convo1")
									.withId("Conversation1"));
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
	public final TestAdapter Use(Middleware middleware)
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

	public final CompletableFuture ProcessActivityAsync(Activity activity, BotCallbackHandler callback)
	{
	    return CompletableFuture.runAsync(() -> {
            synchronized (_conversationLock)
            {
                // ready for next reply
                if (activity.type() == null)
                {
                    activity.withType(ActivityTypes.MESSAGE.toString());
                }

                activity.withChannelId(getConversation().channelId());
                activity.withFrom(getConversation().user());
                activity.withRecipient(getConversation().bot());
                activity.withConversation(getConversation().conversation());
                activity.withServiceUrl(getConversation().serviceUrl());
                activity.withId(Integer.toString(_nextId++));
            }

            if (activity.timestamp() == null || activity.timestamp() == null)
            {
                activity.withTimestamp(OffsetDateTime.now());
            }

            try (TurnContextImpl context = new TurnContextImpl(this, (ActivityImpl)activity))
            {
                RunPipelineAsync(context, callback).join();
            } catch (Exception e) {
                e.printStackTrace();
                throw new CompletionException(e);
            }

        });
	}

	/** 
	 Sends activities to the conversation.
	 
	 @param turnContext The context object for the turn.
	 @param activities The activities to send.

	 @return A task that represents the work queued to execute.
	 If the activities are successfully sent, the task result contains
	 an array of <see cref="ResourceResponse"/> objects containing the IDs that
	 the receiving channel assigned to the activities.
	 {@link TurnContext.OnSendActivities(SendActivitiesHandler)}
	*/
	@Override
	public CompletableFuture<ResourceResponse[]> SendActivitiesAsync(TurnContext turnContext, Activity[] activities)
	{
	    return CompletableFuture.supplyAsync(() -> {
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
                throw new IllegalArgumentException("Expecting one or more activities, but the array was empty.");
            }

            ResourceResponse[] responses = new ResourceResponse[activities.length];

            // NOTE: we're using for here (vs. foreach) because we want to simultaneously index into the
            // activities array to get the activity to process as well as use that index to assign
            // the response to the responses array and this is the most cost effective way to do that.
            for (int index = 0; index < activities.length; index++)
            {
                Activity activity = activities[index];

                if (StringUtils.isBlank(activity.id()))
                {
                    activity.withId(UUID.randomUUID().toString());
                }

                if (activity.timestamp() == null)
                {
                    activity.withTimestamp(OffsetDateTime.now());
                }

                if (activity.type().compareToIgnoreCase("delay") == 0)
                {
                    // The BotFrameworkAdapter and Console adapter implement this
                    // hack directly in the POST method. Replicating that here
                    // to keep the behavior as close as possible to facillitate
                    // more realistic tests.
                    int delayMs = (int)activity.value();

                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        throw new CompletionException(e);
                    }
                }
                else if (activity.type().compareToIgnoreCase(ActivityTypes.TRACE.toString()) == 0)
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

                responses[index] = new ResourceResponse().withId(activity.id());
            }

            return responses;
        });
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
	    return CompletableFuture.supplyAsync(() ->
        {
            synchronized (_activeQueueLock)
            {
                LinkedList<Activity> replies = getActiveQueue();
                for (int i = 0; i < getActiveQueue().size(); i++)
                {
                    if (replies.get(i).id() == activity.id())
                    {
                        replies.set(i, activity);
                        getActiveQueue().clear();
                        for (Activity item : replies)
                        {
                            getActiveQueue().offer(item);
                        }
                        return new ResourceResponse().withId(activity.id());
                    }
                }
            }
            return new ResourceResponse();
        });
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
	public CompletableFuture DeleteActivityAsync(TurnContext turnContext, ConversationReference reference)
	{
	    return CompletableFuture.runAsync(() -> {
            synchronized (_activeQueueLock)
            {
                LinkedList<Activity> replies = getActiveQueue();
                for (int i = 0; i < getActiveQueue().size(); i++)
                {
                    if (replies.get(i).id().compareToIgnoreCase(reference.activityId()) == 0)
                    {
                        replies.remove(i);
                        getActiveQueue().clear();
                        for (Activity item : replies)
                        {
                            getActiveQueue().offer(item);
                        }

                        break;
                    }
                }
            }

        });
	}

	/** 
	 Creates a new conversation on the specified channel.
	 
	 @param channelId The ID of the channel.
	 @param callback The bot logic to call when the conversation is created.

	 @return A task that represents the work queued to execute.
	 This resets the <see cref="ActiveQueue"/>, and does not maintain multiple converstion queues.
	*/
	public final CompletableFuture CreateConversationAsync(String channelId, BotCallbackHandler callback)
	{
		return CompletableFuture.runAsync(() ->{
			getActiveQueue().clear();
			ActivityImpl update = ActivityImpl.CreateConversationUpdateActivity();
			update.withConversation(new ConversationAccount());
			update.conversation().withId(UUID.randomUUID().toString());
			TurnContextImpl context = new TurnContextImpl(this, update);
			try {
				callback.invoke(context).get();
			} catch (Exception e) {
				e.printStackTrace();
				throw new CompletionException(e);
			}
		});
	}

	/** 
	 Dequeues and returns the next bot response from the <see cref="ActiveQueue"/>.
	 
	 @return The next activity in the queue; or null, if the queue is empty.
	 A <see cref="TestFlow"/> object calls this to get the next response from the bot.
	*/
	public final Activity GetNextReply()
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

     @return An appropriate message activity.
     A <see cref="TestFlow"/> object calls this to get a message activity
     appropriate to the current conversation.
     */
	public final Activity MakeActivity()
	{
		return MakeActivity(null);
	}

    /**
     Creates a message activity from text and the current conversational context.

     @param text The message text.
     @return An appropriate message activity.
     A <see cref="TestFlow"/> object calls this to get a message activity
     appropriate to the current conversation.
     */
	public final Activity MakeActivity(String text)
	{
		Activity activity = new Activity();
		activity.withType(ActivityTypes.MESSAGE.toString());
		activity.withFrom(getConversation().user());
		activity.withRecipient(getConversation().bot());
		activity.withConversation(getConversation().conversation());
		activity.withServiceUrl(getConversation().serviceUrl());
		activity.withId(Integer.toString(_nextId++));
		activity.withText(text);

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
	public final CompletableFuture SendTextToBotAsync(String userSays, BotCallbackHandler callback)
	{
		return ProcessActivityAsync(MakeActivity(userSays), callback);
	}
}
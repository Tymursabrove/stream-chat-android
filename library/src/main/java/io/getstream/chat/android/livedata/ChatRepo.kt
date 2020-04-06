package io.getstream.chat.android.livedata

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.liveData
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import io.getstream.chat.android.client.ChatClient
import io.getstream.chat.android.client.api.models.QueryChannelsRequest
import io.getstream.chat.android.client.api.models.QuerySort
import io.getstream.chat.android.client.call.Call
import io.getstream.chat.android.client.errors.ChatError
import io.getstream.chat.android.client.events.*
import io.getstream.chat.android.client.logger.ChatLogLevel
import io.getstream.chat.android.client.logger.ChatLogger
import io.getstream.chat.android.client.logger.ChatLoggerHandler
import io.getstream.chat.android.client.logger.TaggedLoggerImpl
import io.getstream.chat.android.client.models.*
import io.getstream.chat.android.client.models.Filters.`in`
import io.getstream.chat.android.client.notifications.options.ChatNotificationConfig
import io.getstream.chat.android.client.utils.FilterObject
import io.getstream.chat.android.client.utils.SyncStatus
import io.getstream.chat.android.client.utils.observable.Subscription
import io.getstream.chat.android.livedata.dao.*
import io.getstream.chat.android.livedata.entity.*
import io.getstream.chat.android.livedata.entity.UserEntity
import io.getstream.chat.android.livedata.requests.QueryChannelsPaginationRequest
import kotlinx.coroutines.*
import java.lang.Thread.sleep
import java.util.*


/**
 * The Chat Repository exposes livedata objects to make it easier to build your chat UI.
 * It intercepts the various low level events to ensure data stays in sync.
 * Offline storage is handled using Room
 *
 * A different Room database is used for different users. That's why it's mandatory to specify the user id when
 * initializing the ChatRepository
 *
 * repo.channel(type, id) returns a repo object with channel specific livedata object
 * repo.queryChannels(query) returns a livedata object for the specific queryChannels query
 *
 * repo.online livedata object indicates if you're online or not
 * repo.totalUnreadCount livedata object returns the current unread count for this user
 * repo.channelUnreadCount livedata object returns the number of unread channels for this user
 * repo.errorEvents events for errors that happen while interacting with the chat
 *
 */
class ChatRepo private constructor(var context: Context, var client: ChatClient, var currentUser: User, var offlineEnabled: Boolean = false, var userPresence: Boolean=false) {
    private lateinit var channelQueryDao: ChannelQueryDao
    private lateinit var userDao: UserDao
    private lateinit var reactionDao: ReactionDao
    private lateinit var messageDao: MessageDao
    private lateinit var channelStateDao: ChannelStateDao
    private lateinit var channelConfigDao: ChannelConfigDao
    private var baseLogger: ChatLogger = ChatLogger.instance
    private var logger = ChatLogger.get("Repo")

    /** The retry policy for retrying failed requests */
    val retryPolicy: RetryPolicy = DefaultRetryPolicy()

    internal constructor(context: Context, client: ChatClient, currentUser: User, offlineEnabled: Boolean = true, userPresence: Boolean = true, db: ChatDatabase? = null) : this(context, client, currentUser, offlineEnabled, userPresence) {
        val chatDatabase = db ?: createDatabase()
        setupDao(chatDatabase)

        // load channel configs from Room into memory
        GlobalScope.launch {
            loadConfigs()
        }

        // verify that you're not connecting 2 different users
        if (client.getCurrentUser()!= null && client.getCurrentUser()?.id != currentUser.id) {
            throw IllegalArgumentException("client.getCurrentUser() returns ${client.getCurrentUser()} which is not equal to the user passed to this repo ${currentUser.id} ")
        }

        // start listening for events
        startListening()
    }

    private fun setupDao(database: ChatDatabase) {
        channelQueryDao = database.queryChannelsQDao()
        userDao = database.userDao()
        reactionDao = database.reactionDao()
        messageDao = database.messageDao()
        channelStateDao = database.channelStateDao()
        channelConfigDao = database.channelConfigDao()
    }

    private fun createDatabase(): ChatDatabase {
        val database = if (offlineEnabled) {
            ChatDatabase.getDatabase(context, currentUser.id)
        } else {
            Room.inMemoryDatabaseBuilder(
                context, ChatDatabase::class.java
            ).build()
        }
        return database
    }

    suspend fun runAndRetry(runnable: () -> Call<Any>, attempt: Int=1) {
        val result = runnable().execute()
        if (result.isError) {

            val shouldRetry = retryPolicy.shouldRetry(client, attempt, result.error())
            val timeout = retryPolicy.retryTimeout(client, attempt, result.error())

            if (shouldRetry) {
                logger.logI("API call failed (attempt ${attempt}), retrying in ${timeout} seconds")
                if (timeout!= null) {
                    delay(timeout.toLong())
                }
                runAndRetry(runnable, attempt+1)
            } else {
                logger.logI("API call failed (attempt ${attempt}). Giving up for now, will retry when connection recovers.")
            }

        }
    }

    fun createChannel(c: Channel)  {
        GlobalScope.launch(Dispatchers.IO) {
            _createChannel(c)
        }
    }

    suspend fun _createChannel(c: Channel)  {
        c.createdAt = c.createdAt ?: Date()
        c.syncStatus = SyncStatus.SYNC_NEEDED

        // update livedata
        val channelRepo = channel(c.cid)
        channelRepo.updateChannel(c)
        val channelController = client.channel(c.type, c.id)

        // Update Room State
        insertChannel(c)

        // make the API call and follow retry policy
        val runnable = {
            channelController.watch() as Call<Any>
        }
        runAndRetry(runnable)

    }

    private suspend fun loadConfigs() {
        val configEntities = channelConfigDao.selectAll()
        for (configEntity in configEntities) {
            channelConfigs[configEntity.channelType] = configEntity.config
        }
    }

    private val _initialized = MutableLiveData<Boolean>(false)
    val initialized: LiveData<Boolean> = _initialized

    private val _online = MutableLiveData<Boolean>(false)
    /**
     * LiveData<Boolean> that indicates if we are currently online
     */
    val online: LiveData<Boolean> = _online
    // TODO 1.1: We should accelerate online/offline detection

    private val _totalUnreadCount = MutableLiveData<Int>()

    /**
     * The total unread message count for the current user.
     * Depending on your app you'll want to show this or the channelUnreadCount
     */
    val totalUnreadCount: LiveData<Int> = _totalUnreadCount

    private val _channelUnreadCount = MutableLiveData<Int>()

    /**
     * the number of unread channels for the current user
     */
    val channelUnreadCount: LiveData<Int> = _channelUnreadCount

    private val _errorEvent = MutableLiveData<io.getstream.chat.android.livedata.Event<ChatError>>()
    /**
     * The error event livedata object is triggered when errors in the underlying components occure.
     * The following example shows how to observe these errors
     *
     *  repo.errorEvent.observe(this, EventObserver {
     *       // create a toast
     *   })
     *
     */
    val errorEvents: LiveData<io.getstream.chat.android.livedata.Event<ChatError>> = _errorEvent

    fun addError(error: ChatError) {
        _errorEvent.value = io.getstream.chat.android.livedata.Event(error)
    }



    /** the event subscription, cancel using repo.stopListening */
    private var eventSubscription: Subscription? = null
    /** stores the mapping from cid to channelRepository */
    var activeChannelMap: MutableMap<String, io.getstream.chat.android.livedata.ChannelRepo> =
        mutableMapOf()

    /** stores the mapping from cid to channelRepository */
    var activeQueryMap: MutableMap<QueryChannelsEntity, QueryChannelsRepo> =
        mutableMapOf()

    var channelConfigs: MutableMap<String, Config> = mutableMapOf()

    suspend fun handleEvent(event : ChatEvent) {
        // keep the data in Room updated based on the various events..
        // TODO 1.1: cache users, messages and channels to reduce number of Room queries


        // any event can have channel and unread count information
        event.unreadChannels?.let { setChannelUnreadCount(it) }
        event.totalUnreadCount?.let { setTotalUnreadCount(it) }

        // if this is a channel level event, let the channel repo handle it
        if (!event.cid.isNullOrEmpty()) {
            val cid = event.cid!!
            if (activeChannelMap.containsKey(cid)) {
                val channelRepo = activeChannelMap.get(cid)!!
                channelRepo.handleEvent(event)
            }
        }

        // connection events
        when (event) {
            is DisconnectedEvent -> {
                _online.postValue(false)
            }
            is ConnectedEvent -> {
                val recovered = _initialized.value ?: false
                _online.postValue(true)
                _initialized.postValue(true)
                if (recovered) {
                    connectionRecovered()
                }
            }
        }

        // queryRepo mainly monitors for the notification added to channel event
        for ((_, queryRepo) in activeQueryMap) {
            queryRepo.handleEvent(event)
        }

        if (offlineEnabled) {

            when (event) {
                is NewMessageEvent, is MessageDeletedEvent, is MessageUpdatedEvent -> {
                    insertMessage(event.message)
                }
                is MessageReadEvent -> {
                    // get the channel, update reads, write the channel
                    val channel = channelStateDao.select(event.cid)
                    val read = ChannelUserRead()
                    read.user = event.user!!
                    read.lastRead = event.createdAt
                    channel?.let {
                        it.updateReads(read)
                        insertChannelStateEntity(it)
                    }
                }
                is ReactionNewEvent -> {
                    // get the message, update the reaction data, update the message
                    // note that we need to use event.reaction and not event.message
                    // event.message only has a subset of reactions
                    val message = selectMessageEntity(event.reaction!!.messageId)
                    message?.let {
                        val userId = event.reaction!!.user!!.id
                        it.addReaction(event.reaction!!, currentUser.id == userId)
                        insertMessageEntity(it)
                    }
                }
                is ReactionDeletedEvent -> {
                    // get the message, update the reaction data, update the message
                    val message = selectMessageEntity(event.reaction!!.messageId)
                    message?.let {
                        val userId = event.reaction!!.user!!.id
                        it.removeReaction(event.reaction!!, false)
                        it.reactionCounts = event.message.reactionCounts
                        insertMessageEntity(it)
                    }
                }
                is UserPresenceChanged, is UserUpdated -> {
                    insertUser(event.user!!)
                }
                is MemberAddedEvent, is MemberRemovedEvent, is MemberUpdatedEvent -> {
                    // get the channel, update members, write the channel
                    val channelEntity = selectChannelEntity(event.cid!!)
                    if (channelEntity != null) {
                        var member = event.member
                        val userId = event.member!!.user.id
                        if (event is MemberRemovedEvent) {
                            member = null
                        }
                        channelEntity.setMember(userId, member)
                        insertChannelStateEntity(channelEntity)
                    }


                }
                is ChannelUpdatedEvent, is ChannelHiddenEvent, is ChannelDeletedEvent -> {
                    // get the channel, update members, write the channel
                    event.channel?.let {
                        insertChannel(it)
                    }
                }

            }
        }
    }

    private fun setChannelUnreadCount(newCount: Int) {
        val currentCount = _channelUnreadCount.value ?: 0
        if (currentCount != newCount) {
            _channelUnreadCount.value = newCount
        }
    }

    private fun setTotalUnreadCount(newCount: Int) {
        val currentCount = _totalUnreadCount.value ?: 0
        if (currentCount != newCount) {
            _totalUnreadCount.value = newCount
        }
    }

    /**
     * Start listening to chat events and keep the room database in sync
     */
    fun startListening() {
        if (eventSubscription != null) {
            return
        }
        eventSubscription = client.events().subscribe {
            GlobalScope.launch(Dispatchers.IO) {
                handleEvent(it)
            }
        }
    }

    /**
     * Stop listening to chat events
     */
    fun stopListening() {
        eventSubscription?.let { it.unsubscribe() }
    }

    fun channel(c: Channel): ChannelRepo {
        return channel(c.type, c.id)
    }

    fun channel(cid: String): ChannelRepo {
        val parts = cid.split(":")
        check(parts.size==2) {"Received invalid cid, expected format messaging:123, got ${cid}"}
        return channel(parts[0], parts[1])
    }

    /**
     * repo.channel("messaging", "12") return a ChatChannelRepository
     */
    fun channel(
        channelType: String,
        channelId: String
    ): io.getstream.chat.android.livedata.ChannelRepo {
        val cid = "%s:%s".format(channelType, channelId)
        if (!activeChannelMap.containsKey(cid)) {
            val channelRepo =
                io.getstream.chat.android.livedata.ChannelRepo(
                    channelType,
                    channelId,
                    client,
                    this
                )
            activeChannelMap.put(cid, channelRepo)
        }
        return activeChannelMap.getValue(cid)
    }

    fun generateMessageId(): String {
        return currentUser.getUserId() + "-" + UUID.randomUUID().toString()
    }

    suspend fun selectMessageEntity(messageId: String): MessageEntity? {
        return messageDao.select(messageId)
    }


    fun setOffline() {
        _online.value = false
    }

    fun setOnline() {
        _online.value = true
    }

    fun isOnline(): Boolean {
        val online = _online.value!!
        return online
    }

    fun isOffline(): Boolean {
        return !_online.value!!
    }

    /**
     * queryChannels
     * - first read the current results from Room
     * - if we are online make the API call to update results
     */
    fun queryChannels(
        filter: FilterObject,
        sort: QuerySort? = null
    ): QueryChannelsRepo {
        // mark this query as active
        val queryChannelsEntity  = QueryChannelsEntity(filter, sort)
        val queryRepo = QueryChannelsRepo(queryChannelsEntity, client, this)
        activeQueryMap[queryChannelsEntity] = queryRepo
        return queryRepo
    }

    suspend fun insertConfigs(configs: MutableMap<String, Config>) {
        val configEntities = mutableListOf<ChannelConfigEntity>()
        for ((channelType, config) in configs) {
            val entity = ChannelConfigEntity(channelType)
            entity.config = config
        }
        channelConfigDao.insertMany(configEntities)
    }


    suspend fun selectMessagesForChannel(
        cid: String,
        limit: Int = 100,
        offset: Int = 0
    ): List<MessageEntity> {

        return messageDao.messagesForChannel(cid, limit, offset)
    }


    suspend fun insertUser(user: User) {
            userDao.insert(UserEntity(user))
    }

    suspend fun insertChannel(channel: Channel) {
        channelStateDao.insert(ChannelStateEntity(channel))
    }

    suspend fun insertChannelStateEntity(channelStateEntity: ChannelStateEntity) {

        channelStateDao.insert(channelStateEntity)
    }

    suspend fun insertChannels(channels: List<Channel>) {
        var entities = mutableListOf<ChannelStateEntity>()
        for (channel in channels) {
            entities.add(ChannelStateEntity(channel))
        }

        channelStateDao.insertMany(entities)
    }


    suspend fun insertReactionEntity(reactionEntity: ReactionEntity) {
        reactionDao.insert(reactionEntity)
    }

    suspend fun insertQuery(queryChannelsEntity: QueryChannelsEntity) {
        channelQueryDao.insert(queryChannelsEntity)
    }

    suspend fun insertUsers(users: List<User>) {
        val userEntities = mutableListOf<UserEntity>()
        for (user in users) {
            userEntities.add(UserEntity(user))
        }
        userDao.insertMany(userEntities)
    }

    suspend fun insertMessages(messages: List<Message>) {
        val messageEntities = mutableListOf<MessageEntity>()
        for (message in messages) {
            messageEntities.add(MessageEntity(message))
        }
        messageDao.insertMany(messageEntities)
    }

    suspend fun insertMessage(message: Message) {
        val messageEntity = MessageEntity(message)
        messageDao.insert(messageEntity)
    }

    suspend fun insertMessageEntity(messageEntity: MessageEntity) {
        messageDao.insert(messageEntity)
    }

    suspend fun connectionRecovered() {
        // 1 update the results for queries that are actively being shown right now
        for ((_, queryRepo) in activeQueryMap.entries.toList().take(3)) {
            queryRepo.query(QueryChannelsPaginationRequest())
        }
        // 2 update the data for all channels that are being show right now...
        val cids = activeChannelMap.keys.toList().take(30)
        val filter = `in`("cid", cids)
        val request = QueryChannelsRequest(filter, 0, 30)
        val result = client.queryChannels(request).execute()
        if (result.isSuccess) {
            val channels = result.data()
            for (c in channels) {
                val channelRepo = this.channel(c)
                channelRepo.updateLiveDataFromChannel(c)
            }
            storeStateForChannels(channels)
        }
        // 3 retry any failed requests
        retryFailedEntities()
    }

    suspend fun retryMessages(): List<MessageEntity> {
        val userMap: Map<String, User> = mutableMapOf(currentUser.id to currentUser)

        val messageEntities = messageDao.selectSyncNeeded()
        if (isOnline()) {
            for (messageEntity in messageEntities) {
                val channel = client.channel(messageEntity.cid)
                val result = channel.sendMessage(messageEntity.toMessage(userMap)).execute()

                if (result.isSuccess) {
                    messageEntity.syncStatus = SyncStatus.SYNCED
                    insertMessageEntity(messageEntity)
                }
            }
        }
        return messageEntities
    }

    suspend fun retryReactions(): List<ReactionEntity> {
        val userMap: Map<String, User> = mutableMapOf(currentUser.id to currentUser)

        val reactionEntities = reactionDao.selectSyncNeeded()
        for (reactionEntity in reactionEntities) {
            val reaction = reactionEntity.toReaction(userMap)
            reaction.user = null
            val result = client.sendReaction(reaction).execute()
            if (result.isSuccess) {
                reactionEntity.syncStatus = SyncStatus.SYNCED
                insertReactionEntity(reactionEntity)
            } else {
                addError(result.error())
            }
        }
        return reactionEntities
    }


    suspend fun retryChannels(): List<ChannelStateEntity> {
        val userMap: Map<String, User> = mutableMapOf(currentUser.id to currentUser)
        val channelEntities = channelStateDao.selectSyncNeeded()

        for (channelEntity in channelEntities) {
            val channel = channelEntity.toChannel(userMap)
            val controller = client.channel(channelEntity.type, channelEntity.type)
            val result = controller.watch().execute()
            if (result.isSuccess) {
                channelEntity.syncStatus = SyncStatus.SYNCED
                insertChannelStateEntity(channelEntity)
            }

        }
        return channelEntities
    }

    suspend fun retryFailedEntities() {
        // retry channels, messages and reactions in that order..
        retryChannels()
        retryMessages()
        retryReactions()
    }


    suspend fun selectChannelEntity(cid: String): ChannelStateEntity? {
        return channelStateDao.select(cid)
    }

    suspend fun selectQuery(id: String): QueryChannelsEntity? {
        return channelQueryDao.select(id)
    }

    suspend fun selectChannelEntities(channelCIDs: List<String>): List<ChannelStateEntity> {
        return channelStateDao.select(channelCIDs)
    }

    suspend fun selectUsers(userIds: List<String>): List<UserEntity> {
        return userDao.select(userIds)
    }

    suspend fun storeStateForChannel(channel: Channel) {
        return storeStateForChannels(listOf(channel))
    }


    suspend fun selectUserMap(userIds: List<String>): MutableMap<String, User> {
        val userEntities = selectUsers(userIds.toList())
        val userMap = mutableMapOf<String, User>()
        for (userEntity in userEntities) {
            userMap[userEntity.id] = userEntity.toUser()
        }
        client.getCurrentUser()?.let {
            userMap[it.id] = it
        }

        return userMap
    }

    suspend fun storeStateForChannels(channelsResponse: List<Channel>) {
        val users = mutableSetOf<User>()
        val configs: MutableMap<String, Config> = mutableMapOf()
        // start by gathering all the users
        val messages = mutableListOf<Message>()
        for (channel in channelsResponse) {

            users.add(channel.createdBy)
            configs[channel.type] = channel.config
            for (member in channel.members) {
                users.add(member.user)
            }
            for (read in channel.read) {
                users.add(read.user)
            }
            messages.addAll(channel.messages)

            for (message in channel.messages) {
                users.add(message.user)
                for (reaction in message.latestReactions) {
                    reaction.user?.let { users.add(it) }
                }
            }

        }

        // store the channel configs
        insertConfigs(configs)
        // store the users
        insertUsers(users.toList())
        // store the channel data
        insertChannels(channelsResponse)
        // store the messages
        insertMessages(messages)
    }

    suspend fun selectAndEnrichChannel(channelId: String, messageLimit: Int = 0): Channel? {
        val channelStates = selectAndEnrichChannels(listOf(channelId), messageLimit)
        return channelStates.getOrNull(0)
    }

    suspend fun selectAndEnrichChannels(
        channelIds: List<String>,
        messageLimit: Int = 0
    ): List<Channel> {

        // fetch the channel entities from room
        val channelEntities = selectChannelEntities(channelIds)

        // gather the user ids from channels, members and the last message
        val userIds = mutableSetOf<String>()
        val channelMessagesMap = mutableMapOf<String, List<MessageEntity>>()
        for (channelEntity in channelEntities) {
            channelEntity.createdByUserId?.let { userIds.add(it) }
            channelEntity.members.let {
                userIds.addAll(it.keys)
            }
            channelEntity.reads.let {
                userIds.addAll(it.keys)
            }
            if (messageLimit > 0) {
                val messages = selectMessagesForChannel(channelEntity.cid, messageLimit)
                for (message in messages) {
                    userIds.add(message.userId)
                    for (reaction in message.latestReactions) {
                        userIds.add(reaction.userId)
                    }
                }
                channelMessagesMap[channelEntity.cid] = messages
            }
        }

        // get a map with user id to User
        val userMap = selectUserMap(userIds.toList())

        // convert the channels
        val channels = mutableListOf<Channel>()
        for (channelEntity in channelEntities) {
            val channel = channelEntity.toChannel(userMap)
            // get the config we have stored offline
            channelConfigs.get(channel.type)?.let {
                channel.config = it
            }

            if (messageLimit > 0) {
                val messageEntities = channelMessagesMap[channel.cid] ?: emptyList()
                val messages = messageEntities.map { it.toMessage(userMap) }
                channel.messages = messages
            }

            channels.add(channel)
        }
        return channels.toList()
    }

    suspend fun selectReactionEntity(messageId: String, userId: String, type: String): ReactionEntity? {
        return reactionDao.select(messageId, userId, type)
    }

    data class Builder(
        private var appContext: Context, private var client: ChatClient, private var user: User
    ) {

        private var database: ChatDatabase? = null

        private var userPresence: Boolean = false
        private var offlineEnabled: Boolean = true

        fun database(db: ChatDatabase): Builder {
            this.database = db
            return this
        }

        fun offlineEnabled(): Builder {
            this.offlineEnabled = true
            return this
        }

        fun offlineDisabled(): Builder {
            this.offlineEnabled = false
            return this
        }

        fun userPresenceEnabled(): Builder{
            this.userPresence = true
            return this
        }

        fun userPresenceDisabled(): Builder{
            this.userPresence = false
            return this
        }

        fun build(): ChatRepo {
            val chatRepo = ChatRepo(appContext, client, user, offlineEnabled, userPresence, database)

            ChatRepo.instance = chatRepo

            return chatRepo
        }
    }

    private fun setLogger(logger: ChatLogger) {
        this.baseLogger = logger
        this.logger = TaggedLoggerImpl("ChatRepo", logger)
    }

    companion object {

        private lateinit var instance: ChatRepo

        @JvmStatic
        fun instance(): ChatRepo {
            return instance
        }


    }
}

var gson = Gson()
package io.getstream.chat.android.livedata

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth
import io.getstream.chat.android.client.ChatClient
import io.getstream.chat.android.client.api.models.Pagination
import io.getstream.chat.android.client.logger.ChatLogLevel
import io.getstream.chat.android.client.models.Channel
import io.getstream.chat.android.client.models.Message
import io.getstream.chat.android.client.models.User
import io.getstream.chat.android.client.utils.SyncStatus
import io.getstream.chat.android.livedata.utils.TestDataHelper
import io.getstream.chat.android.livedata.utils.TestLoggerHandler
import io.getstream.chat.android.livedata.utils.getOrAwaitValue
import io.getstream.chat.android.livedata.utils.waitForSetUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper

@RunWith(AndroidJUnit4::class)
class ChannelRepoReadPaginateTest: BaseTest() {

    @Before
    fun setup() {
        client = createClient()
        setupRepo(client, true)
    }

    @After
    fun tearDown() {
        db.close()
        client.disconnect()
    }

    /**
     * test that a message added only to the local storage is picked up
     */
    @Test
    fun watchSetsMessagesAndChannelOffline() = runBlocking(Dispatchers.IO) {
        repo.setOffline()
        // add a message to local storage
        repo.insertUser(data.user1)
        repo.insertChannel(data.channel1)
        channelRepo._sendMessage(data.message1)
        // remove the livedata
        channelRepo = ChannelRepo(data.channel1.type, data.channel1.id, repo.client, repo)

        // run watch while we're offline
        channelRepo._watch()

        // the message should still show up
        val messages = channelRepo.messages.getOrAwaitValue()
        val channel = channelRepo.channel.getOrAwaitValue()

        Truth.assertThat(messages).isNotEmpty()
        Truth.assertThat(channel).isNotNull()
    }

    /**
     * test that a message added only to the local storage is picked up
     */
    @Test
    fun watchSetsMessagesAndChannelOnline() = runBlocking(Dispatchers.IO) {
        repo.setOnline()
        // setup an online message
        val message = Message()
        message.syncStatus = SyncStatus.SYNC_NEEDED
        // write a message
        channelRepo._sendMessage(message)

        val messages = channelRepo.messages.getOrAwaitValue()
        val channel = channelRepo.channel.getOrAwaitValue()

        Truth.assertThat(messages.size).isGreaterThan(0)
        Truth.assertThat(messages.first().id).isEqualTo(message.id)
        Truth.assertThat(channel).isNotNull()
    }

    @Test
    fun recovery() = runBlocking(Dispatchers.IO) {
        // running recover should trigger channels to show up for active queries and channels
        repo.connectionRecovered()

        // verify channel data is loaded
        val channelRepos = queryRepo.channels.getOrAwaitValue()
        Truth.assertThat(channelRepos.size).isGreaterThan(0)

        // verify we have messages as well
        val messages = channelRepo.messages.getOrAwaitValue()
        Truth.assertThat(messages.size).isGreaterThan(0)
    }

    @Test
    fun loadNewerMessages() {
        val channelRepo = repo.channel("messaging", "testabc")
        Truth.assertThat(channelRepo.loading.getOrAwaitValue()).isFalse()
        channelRepo.upsertMessages(listOf(data.message1, data.message2Older))
        // verify we sort correctly
        val messages = channelRepo.sortedMessages()
        Truth.assertThat(messages[0].createdAt!!.before(messages[1].createdAt)).isTrue()
        // verify we generate the right request
        val request = channelRepo.loadMoreMessagesRequest(10, Pagination.GREATER_THAN)
        val filter = request.messages.get("id_gt") ?: ""
        // message 2 is older, we should use message1 for filtering on newer messages
        Truth.assertThat(filter).isEqualTo(data.message1.id)
        // verify that running the query doesn't error
        runBlocking(Dispatchers.IO) {channelRepo.runChannelQuery(request)}
        // TODO: Mock the call to query channel
    }

    @Test
    fun loadOlderMessages() {
        val channelRepo = repo.channel("messaging", "testabc")
        Truth.assertThat(channelRepo.loading.getOrAwaitValue()).isFalse()
        channelRepo.upsertMessages(listOf(data.message1, data.message2Older))
        // verify we sort correctly
        val messages = channelRepo.sortedMessages()
        Truth.assertThat(messages[0].createdAt!!.before(messages[1].createdAt)).isTrue()
        // verify we generate the right request
        val request = channelRepo.loadMoreMessagesRequest(10, Pagination.LESS_THAN)
        val filter = request.messages.get("id_lt") ?: ""
        // message 2 is older, we should use message 2 for getting older messages
        Truth.assertThat(filter).isEqualTo(data.message2Older.id)
        // verify that running the query doesn't error
        runBlocking(Dispatchers.IO) {channelRepo.runChannelQuery(request)}
        // TODO: Mock the call to query channel
    }


}
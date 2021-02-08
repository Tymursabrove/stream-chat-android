package io.getstream.chat.android.livedata.repository.helper

import androidx.annotation.CallSuper
import com.nhaarman.mockitokotlin2.mock
import io.getstream.chat.android.livedata.repository.ChannelConfigRepository
import io.getstream.chat.android.livedata.repository.ChannelRepository
import io.getstream.chat.android.livedata.repository.MessageRepository
import io.getstream.chat.android.livedata.repository.QueryChannelsRepository
import io.getstream.chat.android.livedata.repository.ReactionRepository
import io.getstream.chat.android.livedata.repository.RepositoryFacade
import io.getstream.chat.android.livedata.repository.SyncStateRepository
import io.getstream.chat.android.livedata.repository.UserRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScope
import org.junit.jupiter.api.BeforeEach

@ExperimentalCoroutinesApi
internal open class BaseRepositoryFacadeTest {

    protected lateinit var users: UserRepository
    protected lateinit var configs: ChannelConfigRepository
    protected lateinit var channels: ChannelRepository
    protected lateinit var queryChannels: QueryChannelsRepository
    protected lateinit var messages: MessageRepository
    protected lateinit var reactions: ReactionRepository
    protected lateinit var syncState: SyncStateRepository

    protected val scope = TestCoroutineScope()

    protected lateinit var sut: RepositoryFacade

    @CallSuper
    @BeforeEach
    fun setUp() {
        users = mock()
        configs = mock()
        channels = mock()
        queryChannels = mock()
        messages = mock()
        reactions = mock()
        syncState = mock()

        sut = RepositoryFacade(
            userRepository = users,
            configsRepository = configs,
            channelsRepository = channels,
            queryChannelsRepository = queryChannels,
            messageRepository = messages,
            reactionsRepository = reactions,
            syncStateRepository = syncState,
            scope = scope,
            defaultConfig = mock(),
        )
    }
}

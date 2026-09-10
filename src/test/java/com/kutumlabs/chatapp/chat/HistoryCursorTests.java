package com.kutumlabs.chatapp.chat;

import static org.assertj.core.api.Assertions.*;

import com.github.f4b6a3.ulid.UlidCreator;
import org.junit.jupiter.api.Test;

class HistoryCursorTests {
    @Test
    void cursorsCannotBeReusedForAnotherChatPageSizeOrServerInstance() {
        var cursors = new HistoryCursor();
        var chat = UlidCreator.getMonotonicUlid();
        byte[] state = new byte[] {1, 2, 3, 4};
        String cursor = cursors.encode(chat, 50, state);
        assertThat(cursors.decode(chat, 50, cursor)).isEqualTo(state);
        assertThatThrownBy(() -> cursors.decode(UlidCreator.getMonotonicUlid(), 50, cursor))
                .isInstanceOf(ChatFailure.class);
        assertThatThrownBy(() -> cursors.decode(chat, 100, cursor)).isInstanceOf(ChatFailure.class);
        assertThatThrownBy(() -> new HistoryCursor().decode(chat, 50, cursor)).isInstanceOf(ChatFailure.class);
        assertThatThrownBy(() -> cursors.decode(chat, 50, "tampered")).isInstanceOf(ChatFailure.class);
    }
}

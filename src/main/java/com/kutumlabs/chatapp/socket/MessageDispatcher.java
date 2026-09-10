package com.kutumlabs.chatapp.socket;

import com.kutumlabs.chatapp.chat.ChatModels.StoredMessage;

public interface MessageDispatcher {
    void dispatch(StoredMessage message);
}

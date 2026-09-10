package com.kutumlabs.chatapp.chat;

import com.github.f4b6a3.ulid.Ulid;
import org.springframework.stereotype.Service;

@Service
public class MembershipService {
    private final ChatStore store;

    public MembershipService(ChatStore store) {
        this.store = store;
    }

    public void require(Ulid chatId, Ulid userId) {
        if (!store.isMember(chatId, userId)) throw ChatFailure.forbidden();
    }
}

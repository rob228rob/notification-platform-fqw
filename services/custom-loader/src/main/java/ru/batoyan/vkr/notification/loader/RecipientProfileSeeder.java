package ru.batoyan.vkr.notification.loader;

import java.util.List;

public interface RecipientProfileSeeder {

    void seed();

    List<String> recipientIds();
}

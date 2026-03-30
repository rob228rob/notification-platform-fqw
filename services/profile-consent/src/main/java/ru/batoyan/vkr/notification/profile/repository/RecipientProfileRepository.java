package ru.batoyan.vkr.notification.profile.repository;

import ru.batoyan.vkr.notification.profile.model.RecipientProfile;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface RecipientProfileRepository {

    Optional<RecipientProfile> findByRecipientId(String recipientId);

    Map<String, RecipientProfile> findAllByRecipientIds(Collection<String> recipientIds);
}
